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
    val fastCharges: Double,
    val lowSocDays: Double?,
    val savingWon: Double?,
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
)

data class CompareRow(val label: String, val predicted: String, val actual: String)

object Measured {
    const val FULL_DAYS = 28
    private const val HALF_DAYS = 14
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
    ): MeasuredReport? {
        if (trips.isEmpty()) return null
        val first = trips.minOf { it.startAt }
        val last = maxOf(trips.maxOf { it.endAt }, nowMs)
        val days = (ChronoUnit.DAYS.between(day(first), day(last)) + 1).toInt().coerceAtLeast(1)
        val sim = BatterySim.run(model, trips, plan, prices, fallbackFuelKmPerL, nowMs)
        val scale = 30.0 / days
        val monthly = Monthly(
            km = sim.totalKm * scale,
            homeCharges = if (plan.homeCharging) sim.homeCharges * scale else null,
            fastCharges = sim.fastCharges * scale,
            lowSocDays = sim.lowSocDays * scale,
            savingWon = if (sim.fuelUnknownKm < sim.totalKm) sim.savingWon * scale else null,
        )
        val fastRoutine = (sim.fastCharges - sim.enRouteFastCharges) * scale
        val longDates = trips.filter { it.distanceKm >= LONG_TRIP_KM }
            .flatMap { listOf(it.date(), day(it.endAt)) }.toSet()
        val routineLow = sim.days.count { it.minSoc < LOW_SOC && it.date !in longDates }
        val verdict = when {
            plan.homeCharging && routineLow == 0 -> Verdict.GOOD
            plan.homeCharging -> Verdict.CONDITIONAL
            fastRoutine <= Preliminary.MAX_FAST_PER_MONTH_FOR_EV -> Verdict.CONDITIONAL
            else -> Verdict.HYBRID_BETTER
        }
        return MeasuredReport(
            days = days,
            confidence = when {
                days < HALF_DAYS -> Confidence.LOW
                days < FULL_DAYS -> Confidence.MEDIUM
                else -> Confidence.HIGH
            },
            sim = sim,
            monthly = monthly,
            longTrips = trips.count { it.distanceKm >= LONG_TRIP_KM },
            routineLowDays = routineLow,
            verdict = verdict,
            distanceSources = trips.groupingBy { it.distanceSource }.eachCount(),
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
            lowSocDays = 0.0,
            savingWon = saving,
        )
    }

    fun compare(predicted: Monthly?, actual: Monthly): List<CompareRow> = listOf(
        CompareRow("월 주행거리", predicted?.km?.let { "%,d km".format(it.roundToInt()) } ?: "-", "%,d km".format(actual.km.roundToInt())),
        CompareRow("집 충전", predicted?.homeCharges?.let { times(it) } ?: "-", actual.homeCharges?.let { times(it) } ?: "-"),
        CompareRow("급속 충전", predicted?.let { times(it.fastCharges) } ?: "-", times(actual.fastCharges)),
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
            val stops = if (enRoute > 0) " 그중 가는 길에 급속 충전이 ${enRoute}번 필요했어요." else " 모두 충전 없이 다녀올 수 있었어요."
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
