package com.ifev.app.engine

/** 환경부 인증 1회 충전 주행거리(km). 상온은 상온 시험, 저온은 -7℃에서 난방을 켜고 잰 값이다. */
data class CertifiedRange(val city: Double, val highway: Double, val combined: Double)

data class EvModel(
    val id: String,
    val name: String,
    val trim: String,
    val batteryKwh: Double,
    val warm: CertifiedRange,
    val cold: CertifiedRange,
) {
    val label get() = "$name $trim"
}

/**
 * 비교 대상 전기차. 수치는 더 뉴 아이오닉 6 환경부 인증 결과(2025년 6월 보도) 그대로다.
 * 상온·저온, 도심·고속 값이 모두 공개된 트림만 넣는다.
 */
object EvCatalog {
    val IONIQ6_LONG = EvModel(
        id = "ioniq6-long",
        name = "아이오닉 6",
        trim = "롱레인지 2WD",
        batteryKwh = 84.0,
        warm = CertifiedRange(city = 609.0, highway = 517.0, combined = 568.0),
        cold = CertifiedRange(city = 429.0, highway = 482.0, combined = 453.0),
    )
    val IONIQ6_STANDARD = EvModel(
        id = "ioniq6-standard",
        name = "아이오닉 6",
        trim = "스탠다드 2WD",
        batteryKwh = 63.0,
        warm = CertifiedRange(city = 476.0, highway = 402.0, combined = 442.0),
        cold = CertifiedRange(city = 375.0, highway = 402.0, combined = 387.0),
    )
    val all = listOf(IONIQ6_LONG, IONIQ6_STANDARD)

    fun byId(id: String?): EvModel = all.firstOrNull { it.id == id } ?: IONIQ6_LONG
}

/**
 * 원/단위 가격. 휘발유는 오피넷 2026-11-11 전국 평균, 충전은 2026-08-01부터 적용된 공공 충전요금이다.
 * 집·아파트 완속은 공공 완속(30kW 미만) 295원을 기본으로 쓰고, 민간 아파트 요금 상단 350원을 범위의 끝으로 쓴다.
 */
data class Prices(
    val gasolinePerLiter: Double = 1855.0,
    val homePerKwh: Double = 295.0,
    val homePerKwhHigh: Double = 350.0,
    val fastPerKwh: Double = 348.4,      // 100kW 이상 200kW 미만
    val fastPerKwhLow: Double = 325.6,   // 50kW 이상 100kW 미만
    val fastPerKwhHigh: Double = 393.1,  // 200kW 이상
)

/** 기상청 서울 월평균기온 평년값(1991~2020, ℃). 외기온도를 SDK로 받을 수 없을 때 쓴다. */
object SeoulClimate {
    private val monthlyMeanC = doubleArrayOf(-2.0, 0.7, 6.1, 12.6, 18.2, 22.7, 25.3, 26.1, 21.6, 15.0, 7.5, 0.2)

    /** [month] is 1..12. */
    fun meanC(month: Int): Double = monthlyMeanC[(month - 1).coerceIn(0, 11)]

    const val COLDEST_MONTH = 1
}
