package com.ifev.app.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Place { HOME, OFFICE, OTHER }

/** Where a trip's distance came from, kept so the real car can show which signal it actually gives. */
enum class DistanceSource {
    ODOMETER, SPEED, TRACK,

    /** Recorded before the source was kept. */
    UNKNOWN,
}

/** One drive from leaving park to parking again, as the car recorded it. */
data class Trip(
    val startAt: Long,
    val endAt: Long,
    val distanceKm: Double,
    val startPlace: Place,
    val endPlace: Place,
    /** Outside temperature used for the trip. */
    val tempC: Double,
    /** Share of the trip (0..1) with the climate system on. */
    val climateShare: Double,
    /** The car's own fuel economy for the trip, km/L, when it reported one. */
    val fuelKmPerL: Double?,
    val distanceSource: DistanceSource = DistanceSource.ODOMETER,
    /** Energy the car's own battery lost on this trip, kWh: only when the car already is an EV. */
    val evKwhUsed: Double? = null,
) {
    val hours: Double get() = ((endAt - startAt) / 3_600_000.0).coerceAtLeast(1.0 / 60)
    val avgSpeedKmh: Double get() = distanceKm / hours
    fun date(zone: ZoneId = KST): LocalDate = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
