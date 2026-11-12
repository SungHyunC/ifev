package com.ifev.app.data

import android.content.Context
import android.util.Log
import com.ifev.app.engine.Destination
import com.ifev.app.engine.Place
import com.ifev.app.engine.SeoulClimate
import com.ifev.app.engine.Trip
import com.ifev.app.engine.monthsOnRoad
import com.ifev.app.engine.TripRecorder
import com.ifev.app.vehicle.CarSignal
import com.ifev.app.vehicle.NaviGateway
import com.ifev.app.vehicle.NaviPlace
import com.ifev.app.vehicle.AaosSignals
import com.ifev.app.vehicle.ArrivalUnit
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** A distance and where it came from: the navigation's route, or straight line × road factor. */
data class Km(val km: Double, val routed: Boolean)

/** The navigation's active route: where to and how much is left. */
data class RouteTarget(
    val name: String,
    val remainKm: Double,
    val remainMinutes: Double?,
    /** The navigation's own estimate of the battery (or fuel) left on arrival, %, when it gives one. */
    val naviArrivalPercent: Double? = null,
)

/** Moments the narrator speaks at. */
sealed interface Moment {
    data class RouteSet(val route: RouteTarget) : Moment
    data class Departed(val atMs: Long) : Moment
    data class Parked(val trip: Trip) : Moment
    /** The fuel level jumped up while parked: a fill-up. */
    data class Refueled(val atMs: Long, val previousRefuelAt: Long?) : Moment
}

/** What the car reports when it already is an EV (the emulator's car is one). */
data class EvState(val socPercent: Double?, val capacityKwh: Double?, val kmPerKwh: Double?, val levelKwh: Double? = null) {
    /** SOC from the SDK, or from AAOS level ÷ capacity. */
    val soc: Double? get() = socPercent ?: if (levelKwh != null && capacityKwh != null && capacityKwh > 0) levelKwh / capacityKwh * 100 else null
}

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
    /** Virtual regenerative braking on the drive in progress, kWh. */
    val tripRegenKwh: Double = 0.0,
    val home: NaviPlace? = null,
    val office: NaviPlace? = null,
    val commute: Km? = null,
    val destinations: List<Pair<Destination, Boolean>> = emptyList(),
    val modelYear: Int? = null,
    val route: RouteTarget? = null,
    /** Non-null when the car reports an EV battery. */
    val ev: EvState? = null,
    /** Which API the gear comes from: the AAOS standard property, or the Pleos SDK as fallback. */
    val gearSource: String? = null,
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
    private val aaos = AaosSignals(app, ::log)
    /** AAOS gear first; the deprecated Pleos Gear API again once AAOS has gone quiet. */
    private val gears = GearArbiter()
    /** Set once an unspecified-unit arrival value above 100 shows this navigation sends Wh there. */
    private var arrivalInWh = false
    private var aaosJob: Job? = null
    private var speedJob: Job? = null
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
    private val _moments = MutableSharedFlow<Moment>(extraBufferCapacity = 8)
    val moments: SharedFlow<Moment> = _moments.asSharedFlow()
    private var lastAnnouncedRoute: String? = null
    private var pendingArrival: Double? = null
    private var lastFuelPercent: Int? = null
    /** The car's own battery energy when this trip began (EVs only). */
    private var tripEvStartKwh: Double? = null
    private val _settings = MutableStateFlow(store.settings())
    val settings: StateFlow<Settings> = _settings.asStateFlow()

    @Synchronized
    fun start() {
        if (started) return
        started = true
        // A trip that was open when the process died carries on instead of being lost.
        store.openTrip()?.let { snap ->
            if (System.currentTimeMillis() - snap.savedAt < RESTORE_WITHIN_MS) {
                recorder.restore(snap, System.currentTimeMillis())
                log("진행 중이던 주행을 이어서 기록합니다")
            } else {
                store.clearOpenTrip()
            }
        }
        vehicle.start()
        // One consumer, so the recorder never sees two signals at once.
        scope.launch { for (e in events) handle(e) }
        scope.launch { vehicle.signals().collect { events.send(it) } }
        scope.launch { navi.signals().collect { events.send(it) } }
        launchPermissionBound()
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

    /** AAOS gear/battery and speed: the subscriptions a runtime permission grant can change. */
    private fun launchPermissionBound() {
        aaosJob = scope.launch { aaos.signals().collect { events.send(it) } }
        speedJob = scope.launch {
            // Completes when CAR_SPEED is not granted yet; try again so a later grant takes effect.
            while (isActive) {
                speed.speeds().collect { events.send(Speed(it)) }
                delay(SPEED_RETRY_MS)
            }
        }
    }

    /**
     * The activity calls this after the runtime permission dialog: subscribe again at once instead of
     * waiting for the retries in [AaosSignals] and the speed loop.
     */
    @Synchronized
    fun onPermissionsChanged() {
        if (!started) return
        aaosJob?.cancel()
        speedJob?.cancel()
        launchPermissionBound()
    }

    @Synchronized
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
                    is CarSignal.Gear -> if (gears.acceptPleos(e.parked, _car.value.parked, now)) {
                        _car.update { it.copy(gearSource = "Pleos Gear") }
                        onGear(e.parked, now)
                    }
                    is CarSignal.EvSoc -> {
                        if (_car.value.ev?.socPercent == null) log("EV SOC ${e.percent}%")
                        _car.update { it.copy(ev = (it.ev ?: EvState(null, null, null)).copy(socPercent = e.percent.toDouble())) }
                    }
                    is CarSignal.EvEfficiency -> _car.update { it.copy(ev = (it.ev ?: EvState(null, null, null)).copy(kmPerKwh = e.kmPerKwh)) }
                    is CarSignal.Odometer -> {
                        recorder.onOdometer(e.km)
                        _car.update { it.copy(odometerKm = e.km) }
                    }
                    is CarSignal.FuelPercent -> {
                        val before = lastFuelPercent
                        lastFuelPercent = e.percent
                        if (before != null && e.percent - before >= com.ifev.app.engine.Moments.REFUEL_JUMP_PCT && _car.value.parked != false) {
                            val previous = _settings.value.lastRefuelAt
                            updateSettings(_settings.value.copy(lastRefuelAt = now))
                            log("주유 감지: $before% → ${e.percent}%")
                            _moments.tryEmit(Moment.Refueled(now, previous))
                        }
                        _car.update { it.copy(fuelPercent = e.percent) }
                    }
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
                is NaviSignal.Destination -> {
                    val prev = _car.value.route
                    val route = RouteTarget(
                        name = e.name,
                        remainKm = e.remainMeters / 1000.0,
                        remainMinutes = e.remainSeconds.takeIf { it > 0 }?.div(60.0),
                        // The estimate can arrive before the destination does.
                        naviArrivalPercent = prev?.takeIf { it.name == e.name }?.naviArrivalPercent ?: pendingArrival,
                    )
                    _car.update { it.copy(route = route) }
                    // Speak once per destination, when it is set, not on every distance update.
                    if (lastAnnouncedRoute != e.name) {
                        lastAnnouncedRoute = e.name
                        _moments.tryEmit(Moment.RouteSet(route))
                    }
                }
                is NaviSignal.ArrivalEstimate -> {
                    // A field without a documented unit is read as Wh for the rest of the session once
                    // it has shown a value only Wh can be; until then 0..100 is taken as %.
                    if (e.unit == ArrivalUnit.UNSPECIFIED && e.raw > 100) arrivalInWh = true
                    val unit = if (e.unit == ArrivalUnit.UNSPECIFIED && arrivalInWh) ArrivalUnit.WH else e.unit
                    val pct = arrivalPercent(e.raw, unit, _car.value.ev?.capacityKwh)
                    if (pct != null && _car.value.route?.naviArrivalPercent == null) log("내비 도착 배터리 추정: ${e.raw} → %.1f%%".format(pct))
                    if (pct != null) {
                        pendingArrival = pct
                        _car.update { c -> c.copy(route = c.route?.copy(naviArrivalPercent = pct)) }
                    }
                }
                NaviSignal.RouteCleared -> {
                    lastAnnouncedRoute = null
                    pendingArrival = null
                    _car.update { it.copy(route = null) }
                }
            }
            is AaosSignals.Signal -> when (e) {
                is AaosSignals.Signal.Gear -> {
                    if (!gears.aaosSeen) log("기어: AAOS GEAR_SELECTION 사용")
                    gears.onAaos(now)
                    _car.update { it.copy(gearSource = "AAOS GEAR_SELECTION") }
                    onGear(e.parked, now)
                }
                is AaosSignals.Signal.EvCapacityKwh -> {
                    log("배터리 용량 %.1fkWh".format(e.kwh))
                    _car.update { it.copy(ev = (it.ev ?: EvState(null, null, null)).copy(capacityKwh = e.kwh)) }
                }
                is AaosSignals.Signal.EvLevelKwh -> {
                    if (_car.value.ev?.levelKwh == null) log("배터리 잔량 %.1fkWh".format(e.kwh))
                    _car.update { it.copy(ev = (it.ev ?: EvState(null, null, null)).copy(levelKwh = e.kwh)) }
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
                tripRegenKwh = recorder.currentRegenKwh,
            )
        }
        if (recorder.driving && now - lastSnapshotAt >= SNAPSHOT_EVERY_MS) {
            lastSnapshotAt = now
            recorder.snapshot(now)?.let(store::saveOpenTrip)
        }
    }

    private var lastSnapshotAt = 0L

    private fun onGear(parked: Boolean, now: Long) {
        val wasDriving = recorder.driving
        recorder.onGear(parked, now)?.let { raw ->
            // An EV reports what the trip really took from its battery: the prediction gets graded.
            val used = tripEvStartKwh?.let { start -> evKwhNow()?.let { start - it } }?.takeIf { it >= 0 }
            val trip = if (used != null) raw.copy(evKwhUsed = used) else raw
            tripEvStartKwh = null
            saveTrip(trip)
            _moments.tryEmit(Moment.Parked(trip))
        }
        if (!wasDriving && recorder.driving) {
            tripEvStartKwh = evKwhNow()
            _moments.tryEmit(Moment.Departed(now))
        }
        _car.update { it.copy(parked = parked) }
    }

    private fun evKwhNow(): Double? {
        val ev = _car.value.ev ?: return null
        return ev.levelKwh ?: ev.soc?.let { soc -> ev.capacityKwh?.let { soc / 100 * it } }
    }

    private fun saveTrip(trip: Trip) {
        store.clearOpenTrip()
        lastSnapshotAt = 0L
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
        private const val SNAPSHOT_EVERY_MS = 10_000L
        /** An older open trip is a car that was switched off mid-trip long ago, not a crash. */
        private const val RESTORE_WITHIN_MS = 3 * 3_600_000L
        /** Saved place counts as "here" within this radius. */
        private const val PLACE_RADIUS_KM = 0.3
        private const val NEAR_KM = 1.0
        /** Straight line to road distance, used only until the navigation routes it. */
        const val ROAD_FACTOR = 1.3

        /**
         * The navigation's arrival estimate as % of the battery. A known unit is taken as given; only
         * [ArrivalUnit.UNSPECIFIED] is guessed from the size (0..100 is %, more is Wh). Wh needs the
         * battery capacity; null when it is unknown or the value falls outside the battery.
         */
        fun arrivalPercent(raw: Int, unit: ArrivalUnit, capacityKwh: Double?): Double? {
            fun fromWh(): Double? =
                if (capacityKwh != null && capacityKwh > 0) (raw / 1000.0 / capacityKwh * 100).takeIf { it in 0.0..100.0 } else null
            return when (unit) {
                ArrivalUnit.PERCENT -> raw.takeIf { it in 0..100 }?.toDouble()
                ArrivalUnit.WH -> fromWh()
                ArrivalUnit.UNSPECIFIED -> if (raw in 0..100) raw.toDouble() else fromWh()
            }
        }

        fun monthsSinceModelYear(year: Int, today: LocalDate = LocalDate.now(Trip.KST)): Int = monthsOnRoad(year, today)
    }
}

/**
 * Chooses between the two gear sources. The AAOS GEAR_SELECTION property wins while it is alive; the
 * deprecated Pleos Gear API is used before AAOS has answered, and again when AAOS has been silent for
 * [aaosStaleMs] and Pleos reports a gear different from the current one. GEAR_SELECTION only reports
 * changes, so a silent AAOS while the gear stays the same is normal; a gear change that only Pleos
 * reports is what shows AAOS has stopped. When both report a change, the repeat is harmless: the trip
 * recorder ignores the same gear twice.
 */
class GearArbiter(private val aaosStaleMs: Long = 60_000L) {
    private var lastAaosAt: Long? = null

    val aaosSeen: Boolean get() = lastAaosAt != null

    fun onAaos(now: Long) {
        lastAaosAt = now
    }

    /** Whether a Pleos gear report ([parked]) should be applied, given the gear now shown ([current]). */
    fun acceptPleos(parked: Boolean, current: Boolean?, now: Long): Boolean {
        val last = lastAaosAt ?: return true
        return now - last > aaosStaleMs && parked != current
    }
}
