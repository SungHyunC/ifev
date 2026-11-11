package com.ifev.app.data

import android.content.Context
import android.util.Log
import com.ifev.app.engine.Destination
import com.ifev.app.engine.Place
import com.ifev.app.engine.SeoulClimate
import com.ifev.app.engine.Trip
import com.ifev.app.engine.TripRecorder
import com.ifev.app.vehicle.CarSignal
import com.ifev.app.vehicle.NaviGateway
import com.ifev.app.vehicle.NaviPlace
import com.ifev.app.vehicle.NaviSignal
import com.ifev.app.vehicle.SpeedSource
import com.ifev.app.vehicle.VehicleGateway
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/** A distance and where it came from: the navigation's route, or straight line × road factor. */
data class Km(val km: Double, val routed: Boolean)

data class CarState(
    val vehicleStatus: String = "연결 중",
    val naviStatus: String = "연결 중",
    val parked: Boolean? = null,
    val odometerKm: Double? = null,
    val fuelPercent: Int? = null,
    val averageFuelKmPerL: Double? = null,
    val climateOn: Boolean? = null,
    val tripKm: Double = 0.0,
    val tripStartAt: Long? = null,
    val tripClimateShare: Double = 0.0,
    val home: NaviPlace? = null,
    val office: NaviPlace? = null,
    val commute: Km? = null,
    val destinations: List<Pair<Destination, Boolean>> = emptyList(),
    val modelYear: Int? = null,
) {
    val driving: Boolean get() = parked == false
}

/**
 * The process-wide recorder: reads the car and the navigation, turns them into trips and keeps the
 * day-one facts (saved places, recent destinations, odometer, fuel economy) for the estimate.
 * [RecorderService] keeps it alive while the screen shows other apps.
 */
class LiveRecorder(context: Context) {
    private val app = context.applicationContext
    val store = Store(app)
    private val vehicle = VehicleGateway(app, ::log)
    private val navi = NaviGateway(app, ::log)
    private val speed = SpeedSource(app, ::log)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val events = Channel<Any>(Channel.UNLIMITED)
    private var started = false
    private var routing: Job? = null

    private val routedKm = HashMap<String, Double>()
    private var recent: List<NaviPlace> = emptyList()
    private var position: Pair<Double, Double>? = null

    private val recorder = TripRecorder(
        placeOf = { lat, lon -> placeOf(lat, lon) },
        tempAt = { at -> SeoulClimate.meanC(Instant.ofEpochMilli(at).atZone(Trip.KST).monthValue) },
    )

    private val _car = MutableStateFlow(CarState())
    val car: StateFlow<CarState> = _car.asStateFlow()
    private val _trips = MutableStateFlow(store.trips())
    val trips: StateFlow<List<Trip>> = _trips.asStateFlow()
    private val _settings = MutableStateFlow(store.settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        vehicle.start()
        // One consumer, so the recorder never sees two signals at once.
        scope.launch { for (e in events) handle(e) }
        scope.launch { vehicle.signals().collect { events.send(it) } }
        scope.launch { navi.signals().collect { events.send(it) } }
        scope.launch {
            // Completes when CAR_SPEED is not granted yet; try again so a later grant takes effect.
            while (isActive) {
                speed.speeds().collect { events.send(Speed(it)) }
                delay(SPEED_RETRY_MS)
            }
        }
        scope.launch {
            while (isActive) {
                navi.requestPosition()
                delay(POSITION_POLL_MS)
            }
        }
        scope.launch {
            val year = vehicle.modelYear() ?: return@launch
            _car.update { it.copy(modelYear = year) }
            val s = _settings.value
            if (s.monthsOwned == null) updateSettings(s.copy(monthsOwned = monthsSinceModelYear(year), monthsFromVin = true))
        }
    }

    fun stop() {
        scope.coroutineContext.cancelChildren()
        runCatching { vehicle.stop() }
        started = false
    }

    fun updateSettings(s: Settings) {
        store.save(s)
        _settings.value = s
    }

    fun clearTrips() {
        store.clearTrips()
        _trips.value = emptyList()
    }

    private fun handle(e: Any) {
        val now = System.currentTimeMillis()
        when (e) {
            is CarSignal -> {
                if (_car.value.vehicleStatus != "연결됨") _car.update { it.copy(vehicleStatus = "연결됨") }
                when (e) {
                    is CarSignal.Gear -> {
                        recorder.onGear(e.parked, now)?.let(::saveTrip)
                        _car.update { it.copy(parked = e.parked) }
                    }
                    is CarSignal.Odometer -> {
                        recorder.onOdometer(e.km)
                        _car.update { it.copy(odometerKm = e.km) }
                    }
                    is CarSignal.FuelPercent -> _car.update { it.copy(fuelPercent = e.percent) }
                    is CarSignal.TripFuelEconomy -> recorder.onTripFuelEconomy(e.kmPerL)
                    is CarSignal.AverageFuelEconomy -> _car.update { it.copy(averageFuelKmPerL = e.kmPerL) }
                    is CarSignal.Climate -> {
                        recorder.onClimate(e.on, now)
                        _car.update { it.copy(climateOn = e.on) }
                    }
                }
            }
            is NaviSignal -> when (e) {
                is NaviSignal.Status -> _car.update { it.copy(naviStatus = e.text) }
                is NaviSignal.Position -> {
                    position = e.lat to e.lon
                    recorder.onPosition(e.lat, e.lon)
                    routeFromHomeIfThere()
                }
                is NaviSignal.Places -> {
                    _car.update { it.copy(home = e.home, office = e.office) }
                    refreshDistances()
                }
                is NaviSignal.Recent -> {
                    recent = e.places
                    refreshDistances()
                }
            }
            is Routed -> {
                routedKm[e.key] = e.km
                refreshDistances()
            }
            is Speed -> recorder.onSpeed(e.metersPerSecond, now)
        }
        _car.update {
            it.copy(
                tripKm = recorder.currentKm,
                tripStartAt = recorder.currentStartAt,
                tripClimateShare = recorder.currentClimateShare(now),
            )
        }
    }

    private fun saveTrip(trip: Trip) {
        store.append(trip)
        _trips.value = _trips.value + trip
        log("주행 기록: %.1fkm".format(trip.distanceKm))
    }

    /** Recomputes home→office and home→recent distances from what is known so far. */
    private fun refreshDistances() {
        val home = _car.value.home
        val office = _car.value.office
        fun km(to: NaviPlace): Km? {
            if (home == null) return null
            routedKm[to.key()]?.let { return Km(it, routed = true) }
            return Km(TripRecorder.haversineKm(home.lat, home.lon, to.lat, to.lon) * ROAD_FACTOR, routed = false)
        }
        val destinations = recent
            .filter { home == null || TripRecorder.haversineKm(home.lat, home.lon, it.lat, it.lon) > NEAR_KM }
            .mapNotNull { p -> km(p)?.let { Destination(p.name, it.km) to it.routed } }
        _car.update { it.copy(commute = office?.let(::km), destinations = destinations) }
    }

    /**
     * The navigation routes from where the car is. Parked at home, that is exactly "from home", so
     * the straight-line estimates are replaced by real road distances.
     */
    private fun routeFromHomeIfThere() {
        val home = _car.value.home ?: return
        val here = position ?: return
        if (_car.value.driving || routing?.isActive == true) return
        if (TripRecorder.haversineKm(here.first, here.second, home.lat, home.lon) > NEAR_KM) return
        val targets = (listOfNotNull(_car.value.office) + recent).filter { it.key() !in routedKm }
        if (targets.isEmpty()) return
        routing = scope.launch {
            for (t in targets) {
                val km = navi.roadKmFromHere(t) ?: continue
                events.send(Routed(t.key(), km))
            }
        }
    }

    private data class Routed(val key: String, val km: Double)

    private data class Speed(val metersPerSecond: Double)

    private fun placeOf(lat: Double, lon: Double): Place {
        val car = _car.value
        car.home?.let { if (TripRecorder.haversineKm(lat, lon, it.lat, it.lon) <= PLACE_RADIUS_KM) return Place.HOME }
        car.office?.let { if (TripRecorder.haversineKm(lat, lon, it.lat, it.lon) <= PLACE_RADIUS_KM) return Place.OFFICE }
        return Place.OTHER
    }

    private fun NaviPlace.key() = "%.5f,%.5f".format(lat, lon)

    private fun log(msg: String) = Log.d(TAG, msg)

    companion object {
        private const val TAG = "IfEV"
        private const val POSITION_POLL_MS = 3_000L
        private const val SPEED_RETRY_MS = 10_000L
        /** Saved place counts as "here" within this radius. */
        private const val PLACE_RADIUS_KM = 0.3
        private const val NEAR_KM = 1.0
        /** Straight line to road distance, used only until the navigation routes it. */
        const val ROAD_FACTOR = 1.3

        fun monthsSinceModelYear(year: Int, today: LocalDate = LocalDate.now(Trip.KST)): Int =
            ChronoUnit.MONTHS.between(LocalDate.of(year, 1, 1), today).toInt().coerceAtLeast(1)
    }
}
