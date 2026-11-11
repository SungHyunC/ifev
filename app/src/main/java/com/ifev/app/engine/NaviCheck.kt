package com.ifev.app.engine

/**
 * One route, two independent estimates of the battery left on arrival, for a car that already is an
 * EV (the Pleos emulator's car is one): the navigation's own figure, and IfEV's energy model applied
 * to this car's battery. It checks IfEV's energy model against Hyundai's navigation, live, without
 * any made-up data. IfEV's figure uses the efficiency of [model] because the car's own certified
 * ranges are not readable through the SDK; the screen says which model was used.
 */
data class NaviCheck(
    val routeKm: Double,
    val startSocPct: Double,
    val capacityKwh: Double,
    val naviArrivalPct: Double,
    val naviKmPerKwh: Double?,
    val ifevArrivalPct: Double,
    val ifevKmPerKwh: Double,
    /** The same with the climate system off: IfEV's optimistic end. */
    val ifevArrivalPctNoClimate: Double,
    val tempC: Double,
    val modelName: String,
) {
    /** IfEV minus navigation, in percentage points. Negative: IfEV is the more cautious. */
    val gapPct: Double get() = ifevArrivalPct - naviArrivalPct
}

object NaviComparison {
    fun check(
        model: EvModel,
        routeKm: Double,
        minutes: Double?,
        month: Int,
        startSocPct: Double,
        naviArrivalPct: Double,
        capacityKwh: Double,
    ): NaviCheck? {
        if (routeKm <= 0 || capacityKwh <= 0 || startSocPct <= 0) return null
        val speed = if (minutes != null && minutes > 0) (routeKm / (minutes / 60.0)).coerceIn(5.0, 120.0)
        else EnergyModel.HIGHWAY_SPEED_KMH
        val temp = SeoulClimate.meanC(month)
        fun kwh(climate: Double) = routeKm * EnergyModel.kwhPerKm(model, temp, speed, climate)
        val withClimate = kwh(1.0)
        val naviKwh = (startSocPct - naviArrivalPct) / 100 * capacityKwh
        return NaviCheck(
            routeKm = routeKm,
            startSocPct = startSocPct,
            capacityKwh = capacityKwh,
            naviArrivalPct = naviArrivalPct,
            naviKmPerKwh = if (naviKwh > 0.01) routeKm / naviKwh else null,
            ifevArrivalPct = startSocPct - withClimate / capacityKwh * 100,
            ifevKmPerKwh = routeKm / withClimate,
            ifevArrivalPctNoClimate = startSocPct - kwh(0.0) / capacityKwh * 100,
            tempC = temp,
            modelName = model.shortLabel,
        )
    }
}
