package com.ifev.app.engine

import kotlin.math.roundToInt

/**
 * What the car says at each moment, in one or two short sentences. Spoken only when parked or at a
 * moment the driver just acted (setting a destination, leaving P), never in the middle of a drive.
 * Numbers are written out the way the voice should read them.
 */
object Lines {

    fun route(model: EvModel, destination: String, p: RoutePreview): String {
        val head = "${destination}까지 ${km(p.distanceKm)}. ${model.ifItWere} 도착할 때 배터리 ${pct(p.arrivalSoc)}예요."
        val stops = when {
            p.stops > 0 -> " 가는 길에 급속 충전 ${p.stops}번, 약 ${p.chargeMinutes.roundToInt()}분이 필요해요."
            p.stopsWinter > 0 -> " 충전 없이 갈 수 있어요. 1월이라면 가는 길에 급속 충전 ${p.stopsWinter}번이 필요해요."
            else -> " 가는 길에 충전은 필요 없어요."
        }
        return head + stops
    }

    fun parked(model: EvModel, trip: Trip, kwh: Double, plan: ChargingPlan, socAfter: Double, prices: Prices = Prices()): String =
        "방금 ${km(trip.distanceKm)}. ${model.ifItWere} 전기 ${"%.1f".format(kwh)}킬로와트시, " +
            "${plan.everydayCharger}으로 약 ${(kwh * plan.everydayPerKwh(prices)).roundToInt()}원이에요. " +
            regenClause(trip.regenKwh, kwh) +
            "가상 배터리는 ${pct(socAfter)} 남았어요."

    /** "그중 0.4는 브레이크 대신 모터가 되찾은 몫이에요." when it is worth saying. */
    private fun regenClause(regen: Double?, kwh: Double): String =
        if (regen != null && regen >= 0.05 && regen < kwh) "그중 ${"%.1f".format(regen)}은 감속할 때 모터가 되찾은 몫이에요. " else ""

    /**
     * Leaving after a stop away from home (a rest area, the destination) during which the replay put
     * a fast charge: the charge happened while the driver was resting anyway.
     */
    fun restStop(model: EvModel, parkedMinutes: Int, chargeMinutes: Int, soc: Double): String {
        val extra = (chargeMinutes - parkedMinutes).coerceAtLeast(0)
        if (parkedMinutes > 120) {
            return "${model.ifItWere} 세워 둔 사이 급속으로 ${pct(soc)}까지 채웠다고 셌어요. 충전 ${chargeMinutes}분은 원래 세워 두던 시간이에요."
        }
        return "방금 ${parkedMinutes}분 쉬셨죠. ${model.ifItWere} 그 사이 급속으로 ${pct(soc)}까지 채웠다고 셌어요. " +
            if (extra > 0) "충전이 ${extra}분 더 걸려서 그만큼은 기다린 셈이에요." else "따로 기다린 시간은 없어요."
    }

    fun departedAfterCharging(model: EvModel, soc: Double, where: String = "집에서"): String =
        "${model.ifItWere} 세워 둔 사이 $where ${pct(soc)}까지 충전하고 출발해요."

    fun refueled(model: EvModel, c: RefuelCompare, plan: ChargingPlan): String =
        "방금 주유하셨네요. 지난 주유 뒤로 달린 ${km(c.km)}에 기름값이 약 ${won(c.fuelWon)} 들었어요. " +
            "${model.ifItWere} ${plan.everydayCharger}으로 약 ${won(c.evWon)}이에요."

    /** "4만 6천 원", rounded to the thousand, the way it is said. */
    fun won(v: Double): String {
        val thousands = Math.round(v / 1000).toInt()
        if (thousands <= 0) return "${Math.round(v)}원"
        val man = thousands / 10
        val chun = thousands % 10
        return when {
            man == 0 -> "${chun}천 원"
            chun == 0 -> "${man}만 원"
            else -> "${man}만 ${chun}천 원"
        }
    }

    private fun km(v: Double) = if (v < 10) "%.1f킬로미터".format(v) else "${v.roundToInt()}킬로미터"

    private fun pct(soc: Double) = "${(soc * 100).roundToInt()}퍼센트"
}

/** "아이오닉 6 롱레인지" rather than the trim code, for speech. */
val EvModel.spokenName: String get() = shortLabel

/** "롱레인지였다면" / "항속형이었다면": the ending follows the last syllable. */
val EvModel.ifItWere: String get() = shortLabel + if (endsWithConsonant(shortLabel)) "이었다면" else "였다면"

fun endsWithConsonant(word: String): Boolean {
    val ch = word.trimEnd().lastOrNull() ?: return false
    if (ch in '0'..'9') return ch in "013678"  // 영 일 삼 육 칠 팔
    if (ch !in '가'..'힣') return false
    return (ch - '가') % 28 != 0
}
