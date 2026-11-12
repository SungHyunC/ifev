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
    /** With a home charger, a trip at least this long next means plugging in the night before (to 90 %). */
    val topUpBeforeKm: Double = 100.0,
    /** Parked at least this long away from a home charger, the driver charges if the next leg needs it. */
    val minStopHoursToCharge: Double = 0.5,
    /** A slow charger where the car stands on workdays (the office car park). */
    val workCharging: Boolean = false,
    /** A 7 kW wall box or public slow charger. */
    val slowKw: Double = 7.0,
) {
    /** Where everyday driving is charged, as said aloud: "집 충전", "회사 충전" or "급속 충전". */
    val everydayCharger: String get() = when {
        homeCharging -> "집 충전"
        workCharging -> "회사 충전"
        else -> "급속 충전"
    }

    /** The price of that everyday charging: slow at home or the office, fast otherwise. */
    fun everydayPerKwh(prices: Prices = Prices()): Double = if (homeCharging || workCharging) prices.homePerKwh else prices.fastPerKwh
}

data class DaySummary(
    val date: LocalDate,
    val km: Double,
    val minSoc: Double,
    val endSoc: Double,
    val homeCharges: Int,
    val fastCharges: Int,
    val workCharges: Int = 0,
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
    /** Of [fastMinutes], minutes the car was parked there anyway (a stop it made regardless). */
    val fastMinutesWhileParked: Double = 0.0,
    val homeKwh: Double,
    val fastKwh: Double,
    /** Slow charges at the office car park, with [ChargingPlan.workCharging]. */
    val workCharges: Int = 0,
    val workKwh: Double = 0.0,
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
    /** Battery on arriving from the last trip, before any charging while parked after it. */
    val lastArrivalSoc: Double = endSoc,
) {
    /**
     * Fuel minus electricity over the same distance. Trips without a fuel economy reading are left out
     * of the fuel bill, so the fuel bill is scaled up to the whole distance at the economy of the trips
     * that had one; with no reading at all there is nothing to compare and the saving is 0.
     */
    val savingWon: Double get() {
        val knownKm = totalKm - fuelUnknownKm
        if (knownKm <= 0) return 0.0
        return fuelCostWon * totalKm / knownKm - evCostWon
    }
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
        var workKwh = 0.0
        var workCharges = 0
        var fastCharges = 0
        var enRoute = 0
        var fastMinutes = 0.0
        var parkedOverlap = 0.0
        var fuelCost = 0.0
        var fuelUnknownKm = 0.0
        var arrivalSoc = soc
        val days = LinkedHashMap<LocalDate, DayAcc>()

        /** Returns the minutes the charge takes. */
        fun fastCharge(to: Double): Double {
            val added = (to - soc).coerceAtLeast(0.0)
            val minutes = added * battery / plan.fastKw * 60 + plan.fastOverheadMin
            fastKwh += added * battery
            fastMinutes += minutes
            fastCharges++
            soc = to
            return minutes
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
            arrivalSoc = soc
            day.minSoc = min(day.minSoc, soc)
            day.km += trip.distanceKm
            minSoc = min(minSoc, day.minSoc)

            val economy = trip.fuelKmPerL ?: fallbackFuelKmPerL
            if (economy != null && economy > 0) fuelCost += trip.distanceKm / economy * prices.gasolinePerLiter
            else fuelUnknownKm += trip.distanceKm

            val parkedUntil = sorted.getOrNull(i + 1)?.startAt ?: nowMs ?: trip.endAt
            val parkedHours = (parkedUntil - trip.endAt) / 3_600_000.0
            val next = sorted.getOrNull(i + 1)
            val nextNeed = next?.let { it.distanceKm * EnergyModel.kwhPerKm(model, it.tempC, it.avgSpeedKmh, it.climateShare) / battery } ?: 0.0
            val atHomeWithCharger = plan.homeCharging && trip.endPlace == Place.HOME
            val atWorkCharger = plan.workCharging && trip.endPlace == Place.OFFICE && parkedHours >= plan.minHomeParkHours
            if (atWorkCharger) {
                // Plugged in for the working day whenever it is not nearly full, at the charger's pace.
                if (soc < plan.homeChargeTo - 0.1) {
                    val added = min((plan.homeChargeTo - soc) * battery, plan.slowKw * parkedHours)
                    workKwh += added
                    soc += added / battery
                    workCharges++
                    day.work++
                }
            } else if (!atHomeWithCharger && parkedHours >= plan.minStopHoursToCharge && soc - nextNeed < LOW_SOC && soc < plan.fastChargeTo) {
                // Parked anyway (a meal, a night at the destination) and the next leg would run low:
                // charge here rather than stop on the way. Counted with the long-trip charges when long.
                parkedOverlap += min(fastCharge(plan.fastChargeTo), parkedHours * 60)
                if (trip.distanceKm >= plan.topUpBeforeKm || (next?.distanceKm ?: 0.0) >= plan.topUpBeforeKm) enRoute++
                day.fast++
            } else if (plan.homeCharging) {
                val longNext = next != null && next.distanceKm >= plan.topUpBeforeKm && soc < plan.homeChargeTo - 0.01
                if (trip.endPlace == Place.HOME && parkedHours >= plan.minHomeParkHours && (soc < plan.homeChargeBelow || longNext)) {
                    homeKwh += (plan.homeChargeTo - soc) * battery
                    soc = plan.homeChargeTo
                    homeCharges++
                    day.home++
                }
            } else if (soc < plan.publicChargeBelow) {
                // Away from home (a mart, the destination) the charge overlaps time parked there anyway.
                // Parked at home without a charger, it means driving to a public charger and waiting.
                val minutes = fastCharge(plan.fastChargeTo)
                if (trip.endPlace != Place.HOME) parkedOverlap += min(minutes, parkedHours * 60)
                day.fast++
            }
            day.endSoc = soc
        }

        // The office charger is a slow charger: priced like the one at home.
        val slowKwh = homeKwh + workKwh
        val charged = slowKwh + fastKwh
        fun cost(home: Double, fast: Double): Double {
            val perKwh = if (charged > 0) (slowKwh * home + fastKwh * fast) / charged
            else if (plan.homeCharging || plan.workCharging) home else fast
            return drivenKwh * perKwh
        }
        val summaries = days.map { (date, d) -> DaySummary(date, d.km, d.minSoc, d.endSoc, d.home, d.fast, d.work) }
        return SimResult(
            trips = sorted.size,
            totalKm = sorted.sumOf { it.distanceKm },
            drivenKwh = drivenKwh,
            homeCharges = homeCharges,
            fastCharges = fastCharges,
            enRouteFastCharges = enRoute,
            fastMinutes = fastMinutes,
            fastMinutesWhileParked = parkedOverlap,
            homeKwh = homeKwh,
            fastKwh = fastKwh,
            workCharges = workCharges,
            workKwh = workKwh,
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
            lastArrivalSoc = arrivalSoc,
        )
    }

    private class DayAcc(start: Double) {
        var km = 0.0
        var minSoc = start
        var endSoc = start
        var home = 0
        var fast = 0
        var work = 0
    }

    private const val MAX_STOPS = 20
}
