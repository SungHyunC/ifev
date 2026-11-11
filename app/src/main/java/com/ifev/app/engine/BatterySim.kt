package com.ifev.app.engine

import java.time.LocalDate
import kotlin.math.min

/** How the imagined EV owner charges. Defaults describe an ordinary routine, not a best case. */
data class ChargingPlan(
    /** A charger at home (or the apartment car park) the driver can use overnight. */
    val homeCharging: Boolean,
    val startSoc: Double = 0.9,
    /** Plugs in at home when parked long enough and below this level... */
    val homeChargeBelow: Double = 0.5,
    /** ...and charges up to this level. */
    val homeChargeTo: Double = 0.9,
    val minHomeParkHours: Double = 4.0,
    /** Never plans to drive below this level; a fast charge comes first. */
    val reserveSoc: Double = 0.1,
    val fastChargeTo: Double = 0.8,
    /** Without a home charger, visits a public fast charger once parked below this level. */
    val publicChargeBelow: Double = 0.3,
    /** Effective average power on a 100–200 kW public charger. */
    val fastKw: Double = 120.0,
    val fastOverheadMin: Double = 5.0,
)

data class DaySummary(
    val date: LocalDate,
    val km: Double,
    val minSoc: Double,
    val endSoc: Double,
    val homeCharges: Int,
    val fastCharges: Int,
)

data class SimResult(
    val trips: Int,
    val totalKm: Double,
    val drivenKwh: Double,
    val homeCharges: Int,
    /** Fast charges in total: on the way during long trips plus routine public charging. */
    val fastCharges: Int,
    val enRouteFastCharges: Int,
    val fastMinutes: Double,
    val homeKwh: Double,
    val fastKwh: Double,
    val evCostWon: Double,
    val evCostLowWon: Double,
    val evCostHighWon: Double,
    /** Fuel actually burned on the same trips, at the given petrol price. */
    val fuelCostWon: Double,
    /** Distance with no fuel economy reading, excluded from [fuelCostWon]. */
    val fuelUnknownKm: Double,
    val minSoc: Double,
    /** Days the battery fell below 20 %. */
    val lowSocDays: Int,
    val longestTripKm: Double,
    val endSoc: Double,
    val days: List<DaySummary>,
) {
    val savingWon: Double get() = fuelCostWon - evCostWon
}

/**
 * Replays recorded trips on an imagined EV, trip by trip. Each trip drains the battery by the energy
 * [EnergyModel] gives for its temperature, speed and climate use; a fast charge is inserted whenever
 * the trip would otherwise dip under the reserve, and parking at home (or near-empty parking anywhere
 * without a home charger) refills it per [ChargingPlan].
 */
object BatterySim {
    private const val LOW_SOC = 0.2

    /**
     * [nowMs] closes the last parking period; without it the last trip ends the replay with no
     * charging after it.
     */
    fun run(
        model: EvModel,
        trips: List<Trip>,
        plan: ChargingPlan,
        prices: Prices = Prices(),
        fallbackFuelKmPerL: Double? = null,
        nowMs: Long? = null,
    ): SimResult {
        val sorted = trips.sortedBy { it.startAt }
        val battery = model.batteryKwh
        var soc = plan.startSoc
        var minSoc = soc
        var drivenKwh = 0.0
        var homeKwh = 0.0
        var fastKwh = 0.0
        var homeCharges = 0
        var fastCharges = 0
        var enRoute = 0
        var fastMinutes = 0.0
        var fuelCost = 0.0
        var fuelUnknownKm = 0.0
        val days = LinkedHashMap<LocalDate, DayAcc>()

        fun fastCharge(to: Double): Double {
            val added = (to - soc).coerceAtLeast(0.0)
            fastKwh += added * battery
            fastMinutes += added * battery / plan.fastKw * 60 + plan.fastOverheadMin
            fastCharges++
            soc = to
            return added
        }

        sorted.forEachIndexed { i, trip ->
            val day = days.getOrPut(trip.date()) { DayAcc(soc) }
            val energy = trip.distanceKm * EnergyModel.kwhPerKm(model, trip.tempC, trip.avgSpeedKmh, trip.climateShare)
            drivenKwh += energy
            var remaining = energy / battery
            var stops = 0
            while (soc - remaining < plan.reserveSoc && stops < MAX_STOPS) {
                remaining -= (soc - plan.reserveSoc).coerceAtLeast(0.0)
                soc = plan.reserveSoc
                day.minSoc = min(day.minSoc, soc)
                fastCharge(plan.fastChargeTo)
                enRoute++
                day.fast++
                stops++
            }
            soc -= remaining
            day.minSoc = min(day.minSoc, soc)
            day.km += trip.distanceKm
            minSoc = min(minSoc, day.minSoc)

            val economy = trip.fuelKmPerL ?: fallbackFuelKmPerL
            if (economy != null && economy > 0) fuelCost += trip.distanceKm / economy * prices.gasolinePerLiter
            else fuelUnknownKm += trip.distanceKm

            val parkedUntil = sorted.getOrNull(i + 1)?.startAt ?: nowMs ?: trip.endAt
            val parkedHours = (parkedUntil - trip.endAt) / 3_600_000.0
            if (plan.homeCharging) {
                if (trip.endPlace == Place.HOME && parkedHours >= plan.minHomeParkHours && soc < plan.homeChargeBelow) {
                    homeKwh += (plan.homeChargeTo - soc) * battery
                    soc = plan.homeChargeTo
                    homeCharges++
                    day.home++
                }
            } else if (soc < plan.publicChargeBelow) {
                fastCharge(plan.fastChargeTo)
                day.fast++
            }
            day.endSoc = soc
        }

        val charged = homeKwh + fastKwh
        fun cost(home: Double, fast: Double): Double {
            val perKwh = if (charged > 0) (homeKwh * home + fastKwh * fast) / charged
            else if (plan.homeCharging) home else fast
            return drivenKwh * perKwh
        }
        val summaries = days.map { (date, d) -> DaySummary(date, d.km, d.minSoc, d.endSoc, d.home, d.fast) }
        return SimResult(
            trips = sorted.size,
            totalKm = sorted.sumOf { it.distanceKm },
            drivenKwh = drivenKwh,
            homeCharges = homeCharges,
            fastCharges = fastCharges,
            enRouteFastCharges = enRoute,
            fastMinutes = fastMinutes,
            homeKwh = homeKwh,
            fastKwh = fastKwh,
            evCostWon = cost(prices.homePerKwh, prices.fastPerKwh),
            evCostLowWon = cost(prices.homePerKwh, prices.fastPerKwhLow),
            evCostHighWon = cost(prices.homePerKwhHigh, prices.fastPerKwhHigh),
            fuelCostWon = fuelCost,
            fuelUnknownKm = fuelUnknownKm,
            minSoc = minSoc,
            lowSocDays = summaries.count { it.minSoc < LOW_SOC },
            longestTripKm = sorted.maxOfOrNull { it.distanceKm } ?: 0.0,
            endSoc = soc,
            days = summaries,
        )
    }

    private class DayAcc(start: Double) {
        var km = 0.0
        var minSoc = start
        var endSoc = start
        var home = 0
        var fast = 0
    }

    private const val MAX_STOPS = 20
}
