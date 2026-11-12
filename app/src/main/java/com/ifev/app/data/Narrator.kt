package com.ifev.app.data

import com.ifev.app.engine.BatterySim
import com.ifev.app.engine.ChargingPlan
import com.ifev.app.engine.EnergyModel
import com.ifev.app.engine.EvCatalog
import com.ifev.app.engine.Lines
import com.ifev.app.engine.Prices
import com.ifev.app.engine.RoutePlanner
import com.ifev.app.engine.Trip
import com.ifev.app.vehicle.Voice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.ifev.app.engine.Moments
import com.ifev.app.engine.resolveFuelEconomy
import com.ifev.app.engine.FuelCarCatalog
import java.time.Instant
import com.ifev.app.engine.Place
import com.ifev.app.engine.SeoulClimate
import kotlin.math.roundToInt

/**
 * Turns the recorder's moments into one spoken line each, so the imagined EV is felt even while the
 * navigation is on screen: when a destination is set, when leaving after an overnight charge, and
 * when parking. Nothing is said in the middle of a drive.
 */
class Narrator(private val recorder: LiveRecorder, private val voice: Voice) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    /** The last line spoken and when: shown on screen too, since the moment matters more than the sound. */
    private val _lastLine = MutableStateFlow<Pair<String, Long>?>(null)
    val lastLine: StateFlow<Pair<String, Long>?> = _lastLine

    @Synchronized
    fun start() {
        if (started) return
        started = true
        voice.start()
        scope.launch {
            recorder.moments.collect { m ->
                val settings = recorder.settings.value
                if (!settings.voiceOn) return@collect
                lineFor(m, settings, recorder.trips.value, recorder.car.value)?.let(::say)
            }
        }
    }

    /** For the demo, which plays its own moments. */
    fun say(text: String) {
        _lastLine.value = text to System.currentTimeMillis()
        voice.start()
        voice.say(text)
    }

    val engine: String get() = voice.engine

    /** A new demo stage or mode starts with a clean slate: an older line would belong to another story. */
    fun clearLastLine() {
        _lastLine.value = null
    }

    private fun lineFor(m: Moment, settings: Settings, trips: List<Trip>, car: CarState): String? {
        val model = EvCatalog.byId(settings.modelId)
        val plan = settings.plan()
        val now = System.currentTimeMillis()
        return when (m) {
            is Moment.RouteSet -> {
                val start = BatterySim.run(model, trips, plan, nowMs = car.tripStartAt ?: now).endSoc.takeIf { trips.isNotEmpty() } ?: plan.startSoc
                // A destination set mid-drive starts from what this drive has used so far, as the screen does.
                val soc = if (car.driving && car.tripKm > 0) {
                    val hours = ((now - (car.tripStartAt ?: now)) / 3_600_000.0).coerceAtLeast(1.0 / 60)
                    val speed = (car.tripKm / hours).coerceIn(5.0, 120.0)
                    val used = car.tripKm * EnergyModel.kwhPerKm(model, SeoulClimate.meanC(month(now)), speed, car.tripClimateShare)
                    RoutePlanner.drain(start, used, model.batteryKwh, plan).soc
                } else start
                val p = RoutePlanner.preview(model, soc, m.route.remainKm, m.route.remainMinutes, month(now), plan)
                Lines.route(model, m.route.name, p)
            }
            is Moment.Departed -> {
                if (trips.isEmpty()) return null
                val last = trips.maxBy { it.endAt }
                // On arrival, before anything the replay charged while parked (without a home charger it
                // charges below 30 % the moment the car parks, so the end of the last trip is too late).
                val before = BatterySim.run(model, trips, plan, nowMs = last.endAt).lastArrivalSoc
                val after = BatterySim.run(model, trips, plan, nowMs = m.atMs).endSoc
                when {
                    after <= before + 0.01 -> null
                    last.endPlace == Place.HOME && plan.homeCharging -> Lines.departedAfterCharging(model, after)
                    else -> {
                        val parked = ((m.atMs - last.endAt) / 60_000.0).roundToInt()
                        val charge = ((after - before) * model.batteryKwh / plan.fastKw * 60 + plan.fastOverheadMin).roundToInt()
                        if (last.endPlace == Place.OFFICE && plan.workCharging) Lines.departedAfterCharging(model, after, "회사 주차장에서")
                        else Lines.restStop(model, parked, charge, after)
                    }
                }
            }
            is Moment.Refueled -> {
                val since = trips.filter { t -> m.previousRefuelAt == null || t.startAt >= m.previousRefuelAt }
                val fallback = resolveFuelEconomy(car.averageFuelKmPerL, FuelCarCatalog.byId(settings.myCarId)).first
                Moments.sinceRefuel(model, since, plan, fallback)?.let { Lines.refueled(model, it, plan) }
            }
            is Moment.Parked -> {
                val kwh = m.trip.distanceKm * EnergyModel.kwhPerKm(model, m.trip.tempC, m.trip.avgSpeedKmh, m.trip.climateShare)
                val socAfter = BatterySim.run(model, trips, plan, nowMs = m.trip.endAt).endSoc
                Lines.parked(model, m.trip, kwh, plan, socAfter)
            }
        }
    }

    private fun month(ms: Long) = Instant.ofEpochMilli(ms).atZone(Trip.KST).monthValue
}
