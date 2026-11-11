package com.ifev.app.engine

import kotlin.math.ceil
import kotlin.math.max

/** A place the navigation knows, with its road distance from home. */
data class Destination(val name: String, val oneWayKm: Double)

/** What the car and navigation already know on the first day, before any trip is recorded. */
data class PrelimInput(
    /** Home to office, one way, km. From the navigation's "집" and "회사" bookmarks. */
    val commuteOneWayKm: Double?,
    val workdaysPerWeek: Int = 5,
    /** Recent destinations from the navigation, farthest first is not required. */
    val destinations: List<Destination>,
    val odometerKm: Double?,
    /** How long the driver has had the car. */
    val monthsOwned: Int?,
    /** The car's long-run average, km/L. */
    val fuelKmPerL: Double?,
    /** null until the driver answers. */
    val homeCharging: Boolean?,
    /** Current month, 1..12. */
    val month: Int,
    /** True when [fuelKmPerL] is the certified figure of the picked model, not the car's own average. */
    val fuelIsCertified: Boolean = false,
)

enum class Verdict(val title: String) {
    GOOD("집 충전이면 충분해요"),
    CONDITIONAL("급속 충전을 생활에 넣으면 가능해요"),
    HYBRID_BETTER("지금 생활엔 하이브리드가 더 맞아요"),
    UNKNOWN("아직 판단할 수 없어요"),
}

enum class KmSource { ODOMETER, COMMUTE, NONE }

data class DestinationCheck(
    val destination: Destination,
    val roundTripKm: Double,
    /** Fast charges needed for the round trip, starting full at 90 %, in the current month. */
    val stopsNow: Int,
    /** The same in January, the coldest month. */
    val stopsWinter: Int,
)

data class PrelimResult(
    val annualKm: Double?,
    val kmSource: KmSource,
    val weeklyKm: Double?,
    val weeklyKwhNow: Double?,
    /** Home charges per week this month (with a home charger). */
    val homeChargesPerWeek: Double?,
    /** Public fast charges per month this month (without a home charger). */
    val fastChargesPerMonth: Double?,
    val annualFuelWon: Double?,
    val annualEvWonLow: Double?,
    val annualEvWonHigh: Double?,
    val destinations: List<DestinationCheck>,
    val verdict: Verdict,
    /** Things the estimate assumed, shown next to it. */
    val assumptions: List<String>,
    /** Answers or data still missing; the screen asks for these. */
    val missing: List<String>,
) {
    val annualSavingLow: Double? get() = if (annualFuelWon != null && annualEvWonHigh != null) annualFuelWon - annualEvWonHigh else null
    val annualSavingHigh: Double? get() = if (annualFuelWon != null && annualEvWonLow != null) annualFuelWon - annualEvWonLow else null
}

/**
 * The day-one estimate. It only knows totals and places, not when or how often the driver goes
 * where, so it says so in [PrelimResult.assumptions]; the recorded weeks then confirm or correct it.
 */
object Preliminary {
    /** Mixed everyday driving between the city and highway cycles. */
    const val EVERYDAY_SPEED_KMH = 45.0
    private const val WEEKS_PER_YEAR = 52.0

    fun estimate(model: EvModel, input: PrelimInput, plan: ChargingPlan, prices: Prices = Prices()): PrelimResult {
        val assumptions = mutableListOf<String>()
        val missing = mutableListOf<String>()

        val months = input.monthsOwned?.takeIf { it > 0 }
        val (annualKm, source) = when {
            input.odometerKm != null && input.odometerKm > 0 && months != null ->
                input.odometerKm / months * 12 to KmSource.ODOMETER
            // Home and office saved at the same spot (or not saved) say nothing about the commute.
            input.commuteOneWayKm != null && input.commuteOneWayKm >= MIN_COMMUTE_KM ->
                input.commuteOneWayKm * 2 * input.workdaysPerWeek * WEEKS_PER_YEAR to KmSource.COMMUTE
            else -> null to KmSource.NONE
        }
        when (source) {
            KmSource.ODOMETER -> assumptions += "연간 주행거리: 누적 %,d km ÷ %d개월 × 12".format(input.odometerKm!!.toInt(), months)
            KmSource.COMMUTE -> assumptions += "연간 주행거리: 출퇴근만 반영 (주말·여행은 실제 기록으로 확인)"
            KmSource.NONE -> missing += "주행거리"
        }
        if (months == null) missing += "차를 탄 기간"
        if (input.homeCharging == null) missing += "집 충전 가능 여부"

        val weeklyKm = annualKm?.div(WEEKS_PER_YEAR)
        val tempNow = SeoulClimate.meanC(input.month)
        val kwhPerKmNow = EnergyModel.kwhPerKm(model, tempNow, EVERYDAY_SPEED_KMH, climateShare = 1.0)
        val weeklyKwh = weeklyKm?.times(kwhPerKmNow)
        assumptions += "기온: 서울 %d월 평년 %.1f℃, 공조 사용, 평균 시속 %.0fkm".format(input.month, tempNow, EVERYDAY_SPEED_KMH)

        val battery = model.batteryKwh
        val homePerWeek = if (input.homeCharging == true && weeklyKwh != null) {
            weeklyKwh / ((plan.homeChargeTo - plan.homeChargeBelow) * battery)
        } else null
        val fastPerMonth = if (input.homeCharging == false && weeklyKwh != null) {
            weeklyKwh * WEEKS_PER_YEAR / 12 / ((plan.fastChargeTo - plan.publicChargeBelow) * battery)
        } else null

        val annualKwh = annualKm?.let { km ->
            (1..12).sumOf { m ->
                km / 12 * EnergyModel.kwhPerKm(model, SeoulClimate.meanC(m), EVERYDAY_SPEED_KMH, climateShare = 1.0)
            }
        }
        val annualFuel = if (annualKm != null && input.fuelKmPerL != null && input.fuelKmPerL > 0) {
            annualKm / input.fuelKmPerL * prices.gasolinePerLiter
        } else null
        if (input.fuelKmPerL == null) missing += "평균 연비"
        else assumptions += "휘발유 %,.0f원/L, %s %.1fkm/L".format(
            prices.gasolinePerLiter,
            if (input.fuelIsCertified) "차가 연비를 주지 않아 공인 복합연비(실제보다 높게 나오는 편이라 절감액은 작게 계산됨)" else "차가 기록한 평균 연비",
            input.fuelKmPerL,
        )
        val (evLow, evHigh) = when (input.homeCharging) {
            true -> annualKwh?.times(prices.homePerKwh) to annualKwh?.times(prices.homePerKwhHigh)
            false -> annualKwh?.times(prices.fastPerKwhLow) to annualKwh?.times(prices.fastPerKwhHigh)
            null -> annualKwh?.times(prices.homePerKwh) to annualKwh?.times(prices.fastPerKwhHigh)
        }
        assumptions += when (input.homeCharging) {
            true -> "충전 요금: 완속 %.0f~%.0f원/kWh".format(prices.homePerKwh, prices.homePerKwhHigh)
            false -> "충전 요금: 공공 급속 %.1f~%.1f원/kWh".format(prices.fastPerKwhLow, prices.fastPerKwhHigh)
            null -> "충전 요금: 완속 %.0f ~ 급속 %.1f원/kWh".format(prices.homePerKwh, prices.fastPerKwhHigh)
        }
        val checks = input.destinations
            .filter { it.oneWayKm > 0 }
            .sortedByDescending { it.oneWayKm }
            .map { d ->
                val round = d.oneWayKm * 2
                DestinationCheck(d, round, stopsFor(model, round, tempNow, plan), stopsFor(model, round, SeoulClimate.meanC(SeoulClimate.COLDEST_MONTH), plan))
            }
        if (checks.isNotEmpty()) assumptions += "최근 목적지: 집에서 출발해 왕복, 고속 주행, 90%에서 출발"

        val verdict = when {
            input.homeCharging == null || weeklyKwh == null -> Verdict.UNKNOWN
            input.homeCharging -> Verdict.GOOD
            fastPerMonth != null && fastPerMonth <= MAX_FAST_PER_MONTH_FOR_EV -> Verdict.CONDITIONAL
            else -> Verdict.HYBRID_BETTER
        }
        if (input.homeCharging == false) {
            assumptions += "판정 기준: 집 충전 없이 급속 월 %.1f회(주 1회 정도) 이하면 조건부 적합".format(MAX_FAST_PER_MONTH_FOR_EV)
        }

        return PrelimResult(
            annualKm = annualKm,
            kmSource = source,
            weeklyKm = weeklyKm,
            weeklyKwhNow = weeklyKwh,
            homeChargesPerWeek = homePerWeek,
            fastChargesPerMonth = fastPerMonth,
            annualFuelWon = annualFuel,
            annualEvWonLow = evLow,
            annualEvWonHigh = evHigh,
            destinations = checks,
            verdict = verdict,
            assumptions = assumptions,
            missing = missing,
        )
    }

    /** Fast charges (each up to [ChargingPlan.fastChargeTo]) a highway trip of [km] needs from [ChargingPlan.startSoc]. */
    fun stopsFor(model: EvModel, km: Double, tempC: Double, plan: ChargingPlan): Int {
        val need = km * EnergyModel.kwhPerKm(model, tempC, EnergyModel.HIGHWAY_SPEED_KMH, climateShare = 1.0) / model.batteryKwh
        val first = plan.startSoc - plan.reserveSoc
        if (need <= first) return 0
        return ceil((need - first) / max(plan.fastChargeTo - plan.reserveSoc, 0.01)).toInt()
    }

    /** Product threshold, not a study result: about one public fast charge a week. Shown to the driver as an assumption. */
    const val MAX_FAST_PER_MONTH_FOR_EV = 4.5

    /** Home and office saved closer than this are the same place, not a commute. */
    const val MIN_COMMUTE_KM = 0.5
}
