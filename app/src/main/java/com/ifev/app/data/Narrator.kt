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
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Turns the recorder's moments into one spoken line each, so the imagined EV is felt even while the
 * navigation is on screen: when a destination is set, when leaving after an overnight charge, and
 * when parking. Nothing is said in the middle of a drive.
 */
class Narrator(private val recorder: LiveRecorder, private val voice: Voice) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var started = false

    @Synchronized
    fun start() {
        if (started) return
        started = true
        voice.start()
        scope.launch {
            recorder.moments.collect { m ->
                val settings = recorder.settings.value
                if (!settings.voiceOn) return@collect
                lineFor(m, settings, recorder.trips.value, recorder.car.value)?.let(voice::say)
            }
        }
    }

    /** For the demo, which plays its own moments. */
    fun say(text: String) {
        voice.start()
        voice.say(text)
    }

    val engine: String get() = voice.engine

    private fun lineFor(m: Moment, settings: Settings, trips: List<Trip>, car: CarState): String? {
        val model = EvCatalog.byId(settings.modelId)
        val plan = ChargingPlan(homeCharging = settings.homeCharging ?: true)
        val now = System.currentTimeMillis()
        return when (m) {
            is Moment.RouteSet -> {
                val soc = BatterySim.run(model, trips, plan, nowMs = car.tripStartAt ?: now).endSoc.takeIf { trips.isNotEmpty() } ?: plan.startSoc
                val p = RoutePlanner.preview(model, soc, m.route.remainKm, m.route.remainMinutes, month(now), plan)
                Lines.route(model, m.route.name, p)
            }
            is Moment.Departed -> {
                if (trips.isEmpty()) return null
                val before = BatterySim.run(model, trips, plan, nowMs = trips.maxOf { it.endAt }).endSoc
                val after = BatterySim.run(model, trips, plan, nowMs = m.atMs).endSoc
                if (after > before + 0.01) Lines.departedAfterCharging(model, after) else null
            }
            is Moment.Parked -> {
                val kwh = m.trip.distanceKm * EnergyModel.kwhPerKm(model, m.trip.tempC, m.trip.avgSpeedKmh, m.trip.climateShare)
                val prices = Prices()
                val won = kwh * if (plan.homeCharging) prices.homePerKwh else prices.fastPerKwh
                val socAfter = BatterySim.run(model, trips, plan, nowMs = m.trip.endAt).endSoc
                Lines.parked(model, m.trip, kwh, won, plan.homeCharging, socAfter)
            }
        }
    }

    private fun month(ms: Long) = Instant.ofEpochMilli(ms).atZone(Trip.KST).monthValue
}
