package com.ifev.app.engine

/** What the drives since the last fill-up cost in fuel, and what the imagined EV would have paid. */
data class RefuelCompare(val km: Double, val fuelWon: Double, val evWon: Double, val trips: Int)

/**
 * Moments only the car can catch.
 *
 * - Refuel: the fuel level jumping up while parked is a fill-up. That is when the cost of petrol is
 *   most felt, so that is when the imagined EV's bill for the same kilometres is spoken.
 * - Winter preview: the recorded weeks replayed at the January normal temperature, so a driver who
 *   installs in autumn sees the season they worry about before it comes.
 * - Prediction score: once the car is an EV, its own battery says how much energy each trip took;
 *   IfEV's prediction for the same trips is graded against it.
 */
object Moments {

    /** A rise of at least this many percentage points while parked counts as a fill-up. */
    const val REFUEL_JUMP_PCT = 8

    fun sinceRefuel(
        model: EvModel,
        trips: List<Trip>,
        plan: ChargingPlan,
        fallbackFuelKmPerL: Double?,
        prices: Prices = Prices(),
    ): RefuelCompare? {
        if (trips.isEmpty()) return null
        var fuel = 0.0
        var km = 0.0
        var kwh = 0.0
        for (t in trips) {
            val economy = t.fuelKmPerL ?: fallbackFuelKmPerL ?: continue
            if (economy <= 0) continue
            km += t.distanceKm
            fuel += t.distanceKm / economy * prices.gasolinePerLiter
            kwh += t.distanceKm * EnergyModel.kwhPerKm(model, t.tempC, t.avgSpeedKmh, t.climateShare)
        }
        if (km <= 0) return null
        val perKwh = plan.everydayPerKwh(prices)
        return RefuelCompare(km, fuel, kwh * perKwh, trips.size)
    }

    /** The same trips, at the coldest month's normal temperature. */
    fun winterOf(trips: List<Trip>): List<Trip> =
        trips.map { it.copy(tempC = SeoulClimate.meanC(SeoulClimate.COLDEST_MONTH)) }

    data class PredictionScore(val trips: Int, val actualKwh: Double, val predictedKwh: Double, val modelName: String) {
        /** Prediction minus actual, % of actual. */
        val errorPct: Double get() = (predictedKwh - actualKwh) / actualKwh * 100
    }

    /** Trips where the car reported the energy it used (an EV) against IfEV's prediction for [model]. */
    fun predictionScore(model: EvModel, trips: List<Trip>): PredictionScore? {
        val measured = trips.filter { (it.evKwhUsed ?: 0.0) > 0.05 }
        if (measured.isEmpty()) return null
        val predicted = measured.sumOf { it.distanceKm * EnergyModel.kwhPerKm(model, it.tempC, it.avgSpeedKmh, it.climateShare) }
        return PredictionScore(measured.size, measured.sumOf { it.evKwhUsed!! }, predicted, model.shortLabel)
    }
}
