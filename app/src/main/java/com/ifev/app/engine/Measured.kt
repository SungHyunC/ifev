package com.ifev.app.engine

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.roundToInt

enum class Confidence { LOW, MEDIUM, HIGH }

/** Figures normalised to 30 days, so the day-one estimate and the recorded weeks line up. */
data class Monthly(
    val km: Double,
    val homeCharges: Double?,
    /** Every fast charge: [fastRoutine] plus [fastEnRoute]. */
    val fastCharges: Double,
    val lowSocDays: Double?,
    val savingWon: Double?,
    /**
     * Public fast charges of everyday life (no home charger): the number the verdict looks at. From the
     * energy of trips under 100 km, so it does not depend on when the replay happened to charge.
     */
    val fastRoutine: Double = fastCharges,
    /** Fast charges for occasional long trips: on the way and at the destination. */
    val fastEnRoute: Double = 0.0,
    /** Minutes at public fast chargers, waiting included. */
    val fastMinutes: Double = 0.0,
    /** Of [fastMinutes], time the car would have been parked there anyway: not extra waiting. */
    val fastMinutesWhileParked: Double = 0.0,
    /** Slow charges at the office car park; null without one. */
    val workCharges: Double? = null,
    /** Fuel a hybrid would have saved on the same distance, for the "a hybrid fits better" verdict. */
    val hybridSavingWon: Double? = null,
)

data class MeasuredReport(
    val days: Int,
    val confidence: Confidence,
    val sim: SimResult,
    val monthly: Monthly,
    /** Trips of 100 km or more. */
    val longTrips: Int,
    /** Days under 20 % that had no long trip: the everyday routine falling short. */
    val routineLowDays: Int,
    val verdict: Verdict,
    /** How many trips took their distance from each signal. */
    val distanceSources: Map<DistanceSource, Int>,
    /** Days with at least one recorded trip. A month with two driving days says little. */
    val drivingDays: Int = days,
    /**
     * Charges added because the imagined battery ended the period lower than it started (90 %). Without
     * them a short record looks cheaper than an ordinary month: the first days run on a free full battery.
     */
    val steadyStateCharges: Double = 0.0,
    /** Long trips came more than about once a week, so they were counted as everyday driving. */
    val longTripsAreEveryday: Boolean = false,
)

data class CompareRow(val label: String, val predicted: String, val actual: String)

object Measured {
    const val FULL_DAYS = 28
    private const val HALF_DAYS = 14
    /** HIGH needs driving on about three days a week, MEDIUM on about two: calendar days alone do not count. */
    private const val FULL_MIN_DRIVING_DAYS = 12
    private const val HALF_MIN_DRIVING_DAYS = 6
    const val LONG_TRIP_KM = 100.0
    private const val LOW_SOC = 0.2
    /** Fewer recorded days than this say little about monthly distance. */
    const val MIN_COMPARE_DAYS = 7

    fun report(
        model: EvModel,
        trips: List<Trip>,
        plan: ChargingPlan,
        prices: Prices = Prices(),
        fallbackFuelKmPerL: Double? = null,
        nowMs: Long,
        /** False while the driver has not said whether there is a charger at home: no verdict yet. */
        homeChargingAnswered: Boolean = true,
    ): MeasuredReport? {
        if (trips.isEmpty()) return null
        val first = trips.minOf { it.startAt }
        val last = maxOf(trips.maxOf { it.endAt }, nowMs)
        val days = (ChronoUnit.DAYS.between(day(first), day(last)) + 1).toInt().coerceAtLeast(1)
        val drivingDays = trips.map { it.date() }.toSet().size
        val sim = BatterySim.run(model, trips, plan, prices, fallbackFuelKmPerL, nowMs)
        val scale = 30.0 / days

        // Long trips are "occasional" up to about one day a week; more often, they are everyday life.
        val longDays = trips.filter { it.distanceKm >= LONG_TRIP_KM }.map { it.date() }.toSet()
        val occasional = longDays.size <= maxOf(2, days / 7)
        val longTrips = if (occasional) trips.filter { it.distanceKm >= LONG_TRIP_KM } else emptyList()
        val everydayTrips = trips - longTrips.toSet()
        fun kwh(ts: List<Trip>) = ts.sumOf { it.distanceKm * EnergyModel.kwhPerKm(model, it.tempC, it.avgSpeedKmh, it.climateShare) }
        val perLongCharge = (plan.fastChargeTo - plan.reserveSoc).coerceAtLeast(0.05) * model.batteryKwh
        var enRoute = sim.enRouteFastCharges * scale
        val home: Double?
        val routine: Double
        val steady: Double
        if (plan.homeCharging) {
            // Steady state: refill what the period took out of the starting battery, at the usual top-up.
            val debtKwh = (plan.startSoc - sim.endSoc).coerceAtLeast(0.0) * model.batteryKwh
            steady = debtKwh / ((plan.homeChargeTo - plan.homeChargeBelow).coerceAtLeast(0.05) * model.batteryKwh)
            home = (sim.homeCharges + steady) * scale
            routine = (sim.fastCharges - sim.enRouteFastCharges).coerceAtLeast(0) * scale
        } else {
            // Everyday public charging, by energy: what the everyday (non-long) trips used, in 30 → 80 %
            // top-ups. Counting charge events instead depends on timing: a charge on the way to a long
            // trip also powers the commutes after it, and the first days run on a free full battery.
            // Long trips the same way, at 10 → 80 % top-ups: on the way and at the destination both count.
            steady = 0.0
            home = null
            // What the office charger put in covers everyday driving first.
            routine = (kwh(everydayTrips) - sim.workKwh).coerceAtLeast(0.0) / ((plan.fastChargeTo - plan.publicChargeBelow).coerceAtLeast(0.05) * model.batteryKwh) * scale
            enRoute = kwh(longTrips) / perLongCharge * scale
        }
        val perRoutineMin = (plan.fastChargeTo - plan.publicChargeBelow) * model.batteryKwh / plan.fastKw * 60 + plan.fastOverheadMin
        val perLongMin = perLongCharge / plan.fastKw * 60 + plan.fastOverheadMin
        val fastMinutes = if (plan.homeCharging) sim.fastMinutes * scale else routine * perRoutineMin + enRoute * perLongMin
        val parkedShare = if (sim.fastMinutes > 0) sim.fastMinutesWhileParked / sim.fastMinutes else if (plan.homeCharging) 0.0 else 1.0
        val monthly = Monthly(
            km = sim.totalKm * scale,
            homeCharges = home,
            fastCharges = routine + enRoute,
            lowSocDays = sim.lowSocDays * scale,
            savingWon = if (sim.fuelUnknownKm < sim.totalKm) sim.savingWon * scale else null,
            fastRoutine = routine,
            fastEnRoute = enRoute,
            fastMinutes = fastMinutes,
            fastMinutesWhileParked = fastMinutes * parkedShare,
            workCharges = if (plan.workCharging) sim.workCharges * scale else null,
            hybridSavingWon = run {
                val knownKm = sim.totalKm - sim.fuelUnknownKm
                if (knownKm <= 0 || sim.fuelCostWon <= 0) return@run null
                Hybrid.saving(sim.totalKm * scale, knownKm / (sim.fuelCostWon / prices.gasolinePerLiter), prices)
            },
        )
        val longDates = longTrips.flatMap { listOf(it.date(), day(it.endAt)) }.toSet()
        val routineLow = sim.days.count { it.minSoc < LOW_SOC && it.date !in longDates }
        val verdict = when {
            !homeChargingAnswered -> Verdict.UNKNOWN
            plan.homeCharging && routineLow == 0 -> Verdict.GOOD
            plan.homeCharging -> Verdict.CONDITIONAL
            // An office charger that covers everyday life works like one at home.
            plan.workCharging && routine < 1.0 && routineLow == 0 -> Verdict.GOOD
            routine <= Preliminary.MAX_FAST_PER_MONTH_FOR_EV -> Verdict.CONDITIONAL
            else -> Verdict.HYBRID_BETTER
        }
        return MeasuredReport(
            days = days,
            confidence = when {
                days >= FULL_DAYS && drivingDays >= FULL_MIN_DRIVING_DAYS -> Confidence.HIGH
                days >= HALF_DAYS && drivingDays >= HALF_MIN_DRIVING_DAYS -> Confidence.MEDIUM
                else -> Confidence.LOW
            },
            sim = sim,
            monthly = monthly,
            longTrips = trips.count { it.distanceKm >= LONG_TRIP_KM },
            routineLowDays = routineLow,
            verdict = verdict,
            distanceSources = trips.groupingBy { it.distanceSource }.eachCount(),
            drivingDays = drivingDays,
            longTripsAreEveryday = !occasional,
            steadyStateCharges = steady,
        )
    }

    /** The day-one estimate on the same 30-day footing. */
    fun predictedMonthly(prelim: PrelimResult): Monthly? {
        val annualKm = prelim.annualKm ?: return null
        val low = prelim.annualSavingLow
        val high = prelim.annualSavingHigh
        val saving = if (low != null && high != null) (low + high) / 2 / 12 else null
        return Monthly(
            km = annualKm / 12,
            homeCharges = prelim.homeChargesPerWeek?.times(30.0 / 7),
            fastCharges = prelim.fastChargesPerMonth ?: 0.0,
            // The day-one estimate does not model single days, so it makes no claim here.
            lowSocDays = null,
            savingWon = saving,
            fastRoutine = prelim.fastChargesPerMonth ?: 0.0,
            // Long trips are checked one destination at a time, not per month.
            fastEnRoute = Double.NaN,
        )
    }

    fun compare(predicted: Monthly?, actual: Monthly): List<CompareRow> = listOf(
        CompareRow("월 주행거리", predicted?.km?.let { "%,d km".format(it.roundToInt()) } ?: "-", "%,d km".format(actual.km.roundToInt())),
        CompareRow("집 충전", predicted?.homeCharges?.let { times(it) } ?: "-", actual.homeCharges?.let { times(it) } ?: "-"),
        CompareRow("평소 급속 충전", predicted?.let { times(it.fastRoutine) } ?: "-", times(actual.fastRoutine)),
        CompareRow("장거리 날 급속", predicted?.fastEnRoute?.takeIf { !it.isNaN() }?.let { times(it) } ?: "-", times(actual.fastEnRoute)),
        CompareRow("배터리 20% 미만인 날", predicted?.lowSocDays?.let { "%d일".format(it.roundToInt()) } ?: "-", actual.lowSocDays?.let { "%d일".format(it.roundToInt()) } ?: "-"),
        CompareRow("연료비 절감", predicted?.savingWon?.let { won(it) } ?: "-", actual.savingWon?.let { won(it) } ?: "-"),
    )

    /** Plain sentences on where the recorded weeks differ from the day-one estimate. */
    fun insights(prelim: PrelimResult?, predicted: Monthly?, report: MeasuredReport): List<String> {
        val out = mutableListOf<String>()
        val actual = report.monthly
        if (report.days < MIN_COMPARE_DAYS) {
            out += "아직 ${report.days}일 기록이라 주행거리 비교는 ${MIN_COMPARE_DAYS}일부터 보여 드려요."
        } else if (predicted != null && predicted.km > 0) {
            val diff = (actual.km - predicted.km) / predicted.km
            if (abs(diff) >= 0.2) {
                out += "예상보다 %d%% %s 달렸어요 (월 %,d → %,d km).".format(
                    abs(diff * 100).roundToInt(), if (diff > 0) "더" else "덜", predicted.km.roundToInt(), actual.km.roundToInt(),
                )
            } else {
                out += "주행거리는 예상과 비슷했어요 (월 %,d → %,d km).".format(predicted.km.roundToInt(), actual.km.roundToInt())
            }
        }
        if (report.longTrips > 0) {
            val enRoute = report.sim.enRouteFastCharges
            val stops = if (enRoute > 0) " 장거리 날에 급속 충전이 ${enRoute}번 필요했어요(가는 길이나 머무는 곳에서)." else " 모두 충전 없이 다녀올 수 있었어요."
            out += if (prelim?.kmSource == KmSource.COMMUTE) {
                "예비 진단은 출퇴근만 반영했는데, 100km 넘는 장거리가 ${report.longTrips}번 있었어요.$stops"
            } else {
                "100km 넘는 장거리 ${report.longTrips}번.$stops"
            }
        }
        val low = report.sim.lowSocDays
        out += when {
            low == 0 -> "배터리가 20% 아래로 떨어진 날은 없었어요."
            report.routineLowDays == 0 -> "배터리가 20% 아래로 떨어진 ${low}일은 모두 장거리 날이었어요. 평소 생활에서는 없었어요."
            else -> "장거리가 아닌 날에도 배터리가 20% 아래로 떨어진 날이 ${report.routineLowDays}일 있었어요."
        }
        if (actual.homeCharges == null && report.days >= MIN_COMPARE_DAYS) {
            out += if (report.longTripsAreEveryday) "100km 넘는 주행이 주 1회보다 잦아서 평소 주행으로 셌어요. 공공 급속 월 %.1f회 분량이에요.".format(actual.fastRoutine)
            else "평소 주행에 쓴 전기는 공공 급속 월 %.1f회 분량이에요. 장거리 날은 따로 월 %.1f회예요.".format(actual.fastRoutine, actual.fastEnRoute)
        }
        if (report.verdict == Verdict.HYBRID_BETTER) {
            out += "집 충전 없이 급속 충전이 잦아요. 지금 생활에는 하이브리드가 더 맞을 수 있어요."
        }
        val before = prelim?.verdict
        if (before != null && before != Verdict.UNKNOWN && before != report.verdict && report.days >= MIN_COMPARE_DAYS) {
            out += "예비 진단은 '${before.title}'였지만, 실제 기록으로는 '${report.verdict.title}'예요."
        }
        return out
    }

    private fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(Trip.KST).toLocalDate()

    private fun times(v: Double) = if (v < 10) "%.1f회".format(v) else "%d회".format(v.roundToInt())

    /** Money in 만 원 for large amounts; a negative amount carries a minus sign. */
    fun won(v: Double): String {
        val sign = if (v < 0) "−" else ""
        val abs = abs(v)
        return if (abs >= 10_000) "%s%,d만 원".format(sign, (abs / 10_000).roundToInt()) else "%s%,d원".format(sign, abs.roundToInt())
    }
}
