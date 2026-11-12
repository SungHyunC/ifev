package com.ifev.app.engine

import java.time.DayOfWeek
import java.time.Instant
import kotlin.math.roundToInt

/** Where the car stood still, and for how long, from the P → D → P record. */
data class Parking(
    /** Days the car stood at home for at least [LONG_H] hours, and the mean length of those stays. */
    val homeDays: Int,
    val homeMeanHours: Double,
    /** Weekdays the car stood at the office for at least [LONG_H] hours, and the mean length. */
    val officeDays: Int,
    val officeMeanHours: Double,
    /** Stays of at least [LONG_H] hours anywhere else. */
    val otherLongStays: Int,
) {
    companion object {
        const val LONG_H = 4.0

        fun of(trips: List<Trip>): Parking {
            val sorted = trips.sortedBy { it.startAt }
            val stays = sorted.zipWithNext { a, b -> Triple(a.endPlace, a.endAt, (b.startAt - a.endAt) / 3_600_000.0) }
                .filter { it.third >= LONG_H }
            fun day(ms: Long) = Instant.ofEpochMilli(ms).atZone(Trip.KST)
            val home = stays.filter { it.first == Place.HOME }
            val office = stays.filter { it.first == Place.OFFICE && day(it.second).dayOfWeek !in setOf(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) }
            return Parking(
                homeDays = home.map { day(it.second).toLocalDate() }.toSet().size,
                homeMeanHours = home.map { it.third }.average().takeIf { !it.isNaN() } ?: 0.0,
                officeDays = office.map { day(it.second).toLocalDate() }.toSet().size,
                officeMeanHours = office.map { it.third }.average().takeIf { !it.isNaN() } ?: 0.0,
                otherLongStays = stays.count { it.first == Place.OTHER },
            )
        }
    }
}

/** One change to the owner's situation, replayed on the same recorded weeks. */
data class Lever(
    val change: String,
    /** Why this change is realistic for this car: the parking it already does, the model it would be. */
    val because: String,
    val verdict: Verdict,
    /** Public fast charges of everyday life per 30 days after the change. */
    val routineFast: Double,
    val savingWon: Double?,
)

/**
 * "What would change the answer": instead of stopping at a verdict, replays the same weeks with one
 * thing changed (a charger where the car already stands for hours, a larger battery, the loss of the
 * home charger) and reports the verdict each would give. A good verdict gets the conditions that
 * would break it; a poor one gets the single change that would fix it.
 */
object Levers {
    data class Result(
        val parking: Parking,
        /** True when the levers are what would make it better; false when they are what would break it. */
        val improving: Boolean,
        val levers: List<Lever>,
    )

    fun of(
        model: EvModel,
        trips: List<Trip>,
        plan: ChargingPlan,
        current: MeasuredReport,
        prices: Prices = Prices(),
        fallbackFuelKmPerL: Double? = null,
        nowMs: Long,
        catalog: List<EvModel> = EvCatalog.all,
    ): Result? {
        if (current.verdict == Verdict.UNKNOWN || trips.isEmpty()) return null
        val parking = Parking.of(trips)
        fun replay(m: EvModel, p: ChargingPlan) = Measured.report(m, trips, p, prices, fallbackFuelKmPerL, nowMs)
        fun lever(change: String, because: String, r: MeasuredReport?) = r?.let {
            Lever(change, because, it.verdict, it.monthly.fastRoutine, it.monthly.savingWon)
        }
        val officeBecause = "평일 낮 회사에 평균 %.0f시간 서 있어요 (기록 중 %d일)".format(parking.officeMeanHours, parking.officeDays)
        val hasOfficeStays = parking.officeDays >= 2

        if (current.verdict == Verdict.GOOD) {
            // What would break it: losing the home charger (moving, a new car park), and what saves it then.
            val out = mutableListOf<Lever>()
            if (plan.homeCharging) {
                val noHome = plan.copy(homeCharging = false, workCharging = false)
                lever("집 충전기를 못 쓰게 되면", "이사하거나 아파트 충전기가 늘 차 있으면", replay(model, noHome))?.let(out::add)
                if (hasOfficeStays && !plan.workCharging) {
                    lever("집 충전 없이 회사 주차장 완속만 있으면", officeBecause, replay(model, noHome.copy(workCharging = true)))?.let(out::add)
                }
            }
            return Result(parking, improving = false, levers = out)
        }

        val out = mutableListOf<Lever>()
        if (!plan.homeCharging) {
            if (hasOfficeStays && !plan.workCharging) {
                lever("회사 주차장에 완속 충전기가 있으면", officeBecause, replay(model, plan.copy(workCharging = true)))?.let(out::add)
            }
            val homeBecause = if (parking.homeDays > 0) "밤에 집에 평균 %.0f시간 서 있어요".format(parking.homeMeanHours) else "밤마다 세워 두는 곳에"
            lever("집(아파트)에 완속 충전기가 있으면", homeBecause, replay(model, plan.copy(homeCharging = true)))?.let(out::add)
        }
        // A larger battery: the smallest step up in the line-up that gives a better verdict.
        catalog.filter { it.batteryKwh > model.batteryKwh }.sortedBy { it.batteryKwh }
            .firstNotNullOfOrNull { m -> replay(m, plan)?.takeIf { rank(it.verdict) > rank(current.verdict) }?.let { m to it } }
            ?.let { (m, r) -> lever("${m.shortLabel}(${m.batteryKwh.roundToInt()}kWh)로 바꾸면", "배터리가 더 커서 충전 간격이 길어요", r)?.let(out::add) }
        return Result(parking, improving = true, levers = out.sortedWith(compareByDescending<Lever> { rank(it.verdict) }.thenBy { it.routineFast }))
    }

    fun rank(v: Verdict) = when (v) {
        Verdict.GOOD -> 3
        Verdict.CONDITIONAL -> 2
        Verdict.HYBRID_BETTER -> 1
        Verdict.UNKNOWN -> 0
    }
}

/**
 * The other answer: a hybrid on the same distance, with no charging at all. Its fuel economy is the
 * certified figure of the hybrid in [FuelCarCatalog] (아반떼 1.6 하이브리드), the one sourced number.
 */
object Hybrid {
    val reference: FuelCar get() = FuelCarCatalog.all.first { it.id == "avante-1.6-hev" }

    /** Fuel money saved over [km] by the reference hybrid against [currentKmPerL]; null when not cheaper. */
    fun saving(km: Double, currentKmPerL: Double?, prices: Prices = Prices()): Double? {
        val now = currentKmPerL?.takeIf { it > 0 } ?: return null
        val hybrid = reference.certifiedKmPerL
        if (now >= hybrid) return null
        return km / now * prices.gasolinePerLiter - km / hybrid * prices.gasolinePerLiter
    }
}
