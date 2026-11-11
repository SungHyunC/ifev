package com.ifev.app.engine

/** One Hyundai EV run through the same day-one facts and the same recorded trips. */
data class ModelRow(
    val model: EvModel,
    val prelim: PrelimResult,
    /** Fast charges for the farthest recent destination, now and in January. */
    val farthestStopsNow: Int?,
    val farthestStopsWinter: Int?,
    val measured: MeasuredReport?,
) {
    /** The recorded weeks win once there are any; until then the day-one verdict. */
    val verdict: Verdict get() = measured?.verdict ?: prelim.verdict
}

object ModelComparison {
    fun rows(
        input: PrelimInput,
        plan: ChargingPlan,
        trips: List<Trip>,
        prices: Prices = Prices(),
        fallbackFuelKmPerL: Double? = null,
        nowMs: Long,
        models: List<EvModel> = EvCatalog.all,
    ): List<ModelRow> = models.map { m ->
        val prelim = Preliminary.estimate(m, input, plan, prices)
        val far = prelim.destinations.firstOrNull()
        ModelRow(
            model = m,
            prelim = prelim,
            farthestStopsNow = far?.stopsNow,
            farthestStopsWinter = far?.stopsWinter,
            measured = Measured.report(m, trips, plan, prices, fallbackFuelKmPerL, nowMs),
        )
    }
}
