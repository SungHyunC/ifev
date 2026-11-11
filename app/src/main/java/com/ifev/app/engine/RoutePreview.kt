package com.ifev.app.engine

import kotlin.math.max

/** One route as the imagined EV would drive it, from the battery it has now. */
data class RoutePreview(
    val distanceKm: Double,
    val kwh: Double,
    /** Battery on arrival after any fast charges on the way, 0..1. */
    val arrivalSoc: Double,
    /** Fast charges on the way (each from the reserve up to [ChargingPlan.fastChargeTo]). */
    val stops: Int,
    val chargeMinutes: Double,
    /** The same route in January, the coldest month. */
    val stopsWinter: Int,
    val arrivalSocWinter: Double,
)

/** Drains a battery the way [BatterySim] does: never below the reserve, a fast charge comes first. */
data class Drain(val soc: Double, val stops: Int)

object RoutePlanner {

    fun drain(startSoc: Double, kwh: Double, batteryKwh: Double, plan: ChargingPlan): Drain {
        var soc = startSoc
        var remaining = kwh / batteryKwh
        var stops = 0
        while (soc - remaining < plan.reserveSoc && stops < MAX_STOPS) {
            remaining -= (soc - plan.reserveSoc).coerceAtLeast(0.0)
            soc = plan.fastChargeTo
            stops++
        }
        return Drain(soc - remaining, stops)
    }

    /**
     * [minutes] is the navigation's remaining time; without it a long route is taken at highway speed
     * and a short one at everyday speed.
     */
    fun preview(
        model: EvModel,
        startSoc: Double,
        distanceKm: Double,
        minutes: Double?,
        month: Int,
        plan: ChargingPlan,
        climateShare: Double = 1.0,
    ): RoutePreview {
        val speed = if (minutes != null && minutes > 0) (distanceKm / (minutes / 60.0)).coerceIn(5.0, 120.0)
        else if (distanceKm >= LONG_ROUTE_KM) EnergyModel.HIGHWAY_SPEED_KMH else Preliminary.EVERYDAY_SPEED_KMH
        fun kwhIn(m: Int) = distanceKm * EnergyModel.kwhPerKm(model, SeoulClimate.meanC(m), speed, climateShare)
        val kwh = kwhIn(month)
        val now = drain(startSoc, kwh, model.batteryKwh, plan)
        val winter = drain(startSoc, kwhIn(SeoulClimate.COLDEST_MONTH), model.batteryKwh, plan)
        val perStopMin = (plan.fastChargeTo - plan.reserveSoc) * model.batteryKwh / plan.fastKw * 60 + plan.fastOverheadMin
        return RoutePreview(
            distanceKm = distanceKm,
            kwh = kwh,
            arrivalSoc = max(now.soc, 0.0),
            stops = now.stops,
            chargeMinutes = now.stops * perStopMin,
            stopsWinter = winter.stops,
            arrivalSocWinter = max(winter.soc, 0.0),
        )
    }

    private const val LONG_ROUTE_KM = 50.0
    private const val MAX_STOPS = 20
}
