package com.ifev.app.engine

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns the car's raw signals into [Trip]s: leaving P starts one, returning to P ends it. Distance
 * comes from the odometer; when it has not moved (coarse steps, or a car that does not report it),
 * from the integrated vehicle speed or the navigation's position track, whichever is longer.
 * Clock-free (time arrives with every signal) and not thread-safe: feed it from one coroutine.
 */
class TripRecorder(
    private val placeOf: (lat: Double, lon: Double) -> Place,
    private val tempAt: (atMs: Long) -> Double,
) {
    private class Open(
        val startAt: Long,
        val startOdometer: Double?,
        val startPlace: Place,
        var pathKm: Double = 0.0,
        var speedKm: Double = 0.0,
        var climateMs: Long = 0,
        var climateSince: Long,
    )

    private var parked: Boolean? = null
    private var odometer: Double? = null
    private var position: Pair<Double, Double>? = null
    private var climateOn = false
    private var tripFuelKmPerL: Double? = null
    private var lastSpeed: Pair<Double, Long>? = null
    private var open: Open? = null

    val driving: Boolean get() = open != null

    /** Distance of the trip in progress, 0 when parked. */
    val currentKm: Double get() = open?.let { o -> maxOf(odometerKm(o) ?: 0.0, o.speedKm, o.pathKm) } ?: 0.0

    /** Share of the trip in progress with the climate system on, up to [now]. */
    fun currentClimateShare(now: Long): Double {
        val o = open ?: return 0.0
        val onMs = o.climateMs + if (climateOn) now - o.climateSince else 0
        return (onMs.toDouble() / (now - o.startAt).coerceAtLeast(1)).coerceIn(0.0, 1.0)
    }

    val currentStartAt: Long? get() = open?.startAt

    /** Returns the finished trip when this gear change ends one. */
    fun onGear(isPark: Boolean, at: Long): Trip? {
        val was = parked
        parked = isPark
        if (!isPark && open == null) {
            open = Open(at, odometer, here(), climateSince = at)
            tripFuelKmPerL = null
            return null
        }
        if (isPark && was != true) return close(at)
        return null
    }

    fun onOdometer(km: Double) {
        if (km > 0) odometer = km
    }

    /** Vehicle speed in m/s. Integrated between samples while a trip is open. */
    fun onSpeed(metersPerSecond: Double, at: Long) {
        val prev = lastSpeed
        lastSpeed = metersPerSecond.coerceAtLeast(0.0) to at
        val o = open ?: return
        if (prev == null) return
        val dtMs = (at - maxOf(prev.second, o.startAt)).coerceIn(0, MAX_SPEED_GAP_MS)
        o.speedKm += prev.first * dtMs / 1_000_000.0
    }

    fun onPosition(lat: Double, lon: Double) {
        val prev = position
        position = lat to lon
        val o = open ?: return
        if (prev != null) {
            val step = haversineKm(prev.first, prev.second, lat, lon)
            // A jump this large is a location fix catching up, not driving.
            if (step < MAX_STEP_KM) o.pathKm += step
        }
    }

    fun onClimate(on: Boolean, at: Long) {
        open?.let { o ->
            if (climateOn) o.climateMs += at - o.climateSince
            o.climateSince = at
        }
        climateOn = on
    }

    /** The car's average for the current trip, km/L. */
    fun onTripFuelEconomy(kmPerL: Double) {
        if (kmPerL > 0) tripFuelKmPerL = kmPerL
    }

    private fun close(at: Long): Trip? {
        val o = open ?: return null
        open = null
        if (climateOn) o.climateMs += at - o.climateSince
        lastSpeed?.let { (v, t) -> o.speedKm += v * (at - maxOf(t, o.startAt)).coerceIn(0, MAX_SPEED_GAP_MS) / 1_000_000.0 }
        val km = currentKmOf(o)
        if (km < MIN_TRIP_KM) return null
        val durationMs = (at - o.startAt).coerceAtLeast(1)
        return Trip(
            startAt = o.startAt,
            endAt = at,
            distanceKm = km,
            startPlace = o.startPlace,
            endPlace = here(),
            tempC = tempAt(o.startAt),
            climateShare = (o.climateMs.toDouble() / durationMs).coerceIn(0.0, 1.0),
            fuelKmPerL = tripFuelKmPerL,
            distanceSource = sourceOf(o),
        )
    }

    private fun odometerKm(o: Open): Double? {
        val odo = odometer
        return if (o.startOdometer != null && odo != null && odo > o.startOdometer) odo - o.startOdometer else null
    }

    private fun currentKmOf(o: Open): Double = odometerKm(o) ?: maxOf(o.speedKm, o.pathKm)

    private fun sourceOf(o: Open): DistanceSource = when {
        odometerKm(o) != null -> DistanceSource.ODOMETER
        o.speedKm >= o.pathKm -> DistanceSource.SPEED
        else -> DistanceSource.TRACK
    }

    private fun here(): Place = position?.let { (lat, lon) -> placeOf(lat, lon) } ?: Place.OTHER

    companion object {
        /** Shuffling between P and D while parking is not a trip. */
        const val MIN_TRIP_KM = 0.3
        private const val MAX_STEP_KM = 5.0
        /** A longer silence between speed samples is a gap in data, not constant speed. */
        private const val MAX_SPEED_GAP_MS = 10_000L
        private const val EARTH_KM = 6371.0

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return 2 * EARTH_KM * asin(sqrt(a))
        }
    }
}
