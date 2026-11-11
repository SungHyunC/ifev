package com.ifev.app.engine

/** 1회 충전 주행거리(km). 상온은 상온 시험, 저온은 -7℃에서 난방을 켜고 잰 값이다. */
data class CertifiedRange(val city: Double, val highway: Double, val combined: Double)

data class EvModel(
    val id: String,
    val name: String,
    val trim: String,
    val batteryKwh: Double,
    val warm: CertifiedRange,
    val cold: CertifiedRange,
    /** Where the numbers come from, and which of them are estimates rather than certified values. */
    val source: String,
    /** True when some of the range figures are derived, not certified; the screen says so. */
    val estimated: Boolean = false,
) {
    val label get() = "$name $trim"
    val shortLabel get() = "$name ${trim.substringBefore(" ")}"
}

/**
 * 비교 대상 현대 전기차. 환경부(산업부) 인증 1회 충전 주행거리를 보도 자료에서 옮겼다.
 * 공개 자료에 없는 값은 아래 규칙으로 추정하고 [EvModel.estimated]로 표시한다.
 * - 저온은 복합만 공개된 경우: 상온 도심·고속에 (저온 복합 ÷ 상온 복합)을 곱한다.
 * - 저온이 전혀 없는 경우: 같은 플랫폼·배터리 차종의 저온/상온 비율을 쓴다.
 * - 도심·고속이 없는 경우: 공개된 도심·고속·복합 전비의 비율로 나눈다.
 */
object EvCatalog {
    val CASPER = EvModel(
        id = "casper-electric",
        name = "캐스퍼 일렉트릭",
        trim = "인스퍼레이션 49kWh",
        batteryKwh = 49.0,
        warm = CertifiedRange(city = 323.0, highway = 262.0, combined = 295.0),
        cold = coldFromCombined(CertifiedRange(323.0, 262.0, 295.0), 245.0),
        source = "17인치 상온 295km(도심 323·고속 262), 저온 복합 245km. 저온 도심·고속은 추정",
        estimated = true,
    )
    val KONA = EvModel(
        id = "kona-electric",
        name = "코나 일렉트릭",
        trim = "롱레인지 64.8kWh",
        batteryKwh = 64.8,
        warm = CertifiedRange(city = 454.0, highway = 371.0, combined = 417.0),
        cold = coldFromCombined(CertifiedRange(454.0, 371.0, 417.0), 307.0),
        source = "17인치 상온 417km(도심 454·고속 371), 저온 복합 307km. 저온 도심·고속은 추정",
        estimated = true,
    )
    val IONIQ5_LONG = run {
        val warm = CertifiedRange(city = 533.0, highway = 426.0, combined = 485.0)
        EvModel(
            id = "ioniq5-long",
            name = "아이오닉 5",
            trim = "롱레인지 2WD 84kWh",
            batteryKwh = 84.0,
            warm = warm,
            cold = coldFromCombined(warm, warm.combined * IONIQ6_COLD_RATIO),
            source = "19인치 상온 485km(도심 533·고속 426). 저온 인증값 미확보: 같은 84kWh 배터리인 아이오닉 6 롱레인지의 저온/상온 비율(80%)로 추정",
            estimated = true,
        )
    }
    val IONIQ6_LONG = EvModel(
        id = "ioniq6-long",
        name = "아이오닉 6",
        trim = "롱레인지 2WD 84kWh",
        batteryKwh = 84.0,
        warm = CertifiedRange(city = 609.0, highway = 517.0, combined = 568.0),
        cold = CertifiedRange(city = 429.0, highway = 482.0, combined = 453.0),
        source = "18인치 상온 568km(도심 609·고속 517), 저온 453km(도심 429·고속 482). 모두 인증값",
    )
    val IONIQ6_STANDARD = EvModel(
        id = "ioniq6-standard",
        name = "아이오닉 6",
        trim = "스탠다드 2WD 63kWh",
        batteryKwh = 63.0,
        warm = CertifiedRange(city = 476.0, highway = 402.0, combined = 442.0),
        cold = CertifiedRange(city = 375.0, highway = 402.0, combined = 387.0),
        source = "18인치 상온 442km(도심 476·고속 402), 저온 387km(도심 375·고속 402). 모두 인증값",
    )
    val IONIQ9 = run {
        // Only the combined range is published; city and highway follow the published efficiencies
        // (city 4.7, highway 3.9, combined 4.3 km/kWh).
        val warm = CertifiedRange(city = 532.0 * 4.7 / 4.3, highway = 532.0 * 3.9 / 4.3, combined = 532.0)
        EvModel(
            id = "ioniq9-2wd",
            name = "아이오닉 9",
            trim = "항속형 2WD 110.3kWh",
            batteryKwh = 110.3,
            warm = warm,
            cold = coldFromCombined(warm, 400.0),
            source = "19인치 상온 복합 532km, -7℃ 약 400km(보도 근사치). 도심·고속은 공개 전비 비율(4.7·3.9·4.3km/kWh)로 추정",
            estimated = true,
        )
    }

    /** Cheapest first, the way a buyer reads a line-up. */
    val all = listOf(CASPER, KONA, IONIQ5_LONG, IONIQ6_STANDARD, IONIQ6_LONG, IONIQ9)

    fun byId(id: String?): EvModel = all.firstOrNull { it.id == id } ?: IONIQ6_LONG

    /**
     * Splits a cold combined range into city and highway. Cold costs the city cycle much more than the
     * highway (the heater runs for longer per km): the certified Ioniq 6 Long Range keeps 70 % of its
     * warm city range but 93 % of its highway range. The same shape is used here, scaled so that the
     * combined figure (55 % city, 45 % highway, on energy) matches the published cold combined range.
     */
    fun coldFromCombined(warm: CertifiedRange, coldCombined: Double): CertifiedRange {
        val r = coldCombined / warm.combined
        val city0 = warm.city * r * CITY_SHAPE
        val highway0 = minOf(warm.highway, warm.highway * r * HIGHWAY_SHAPE)
        val combined0 = 1 / (0.55 / city0 + 0.45 / highway0)
        val k = coldCombined / combined0
        return CertifiedRange(city = city0 * k, highway = minOf(warm.highway, highway0 * k), combined = coldCombined)
    }

    /** Ioniq 6 Long Range: cold/warm by cycle relative to cold/warm combined (429/609, 482/517 over 453/568). */
    private const val CITY_SHAPE = (429.0 / 609.0) / (453.0 / 568.0)
    private const val HIGHWAY_SHAPE = (482.0 / 517.0) / (453.0 / 568.0)
}

/** 아이오닉 6 롱레인지(84kWh) 인증 저온 453 ÷ 상온 568. */
private const val IONIQ6_COLD_RATIO = 453.0 / 568.0

/**
 * How long the car has probably been on the road, from its model year alone (the VIN says nothing
 * about the purchase date). A model year goes on sale around the middle of the year before: a car
 * bought in the second half of 2026 is usually a 2027 model. Counting from 1 January of the model
 * year would make new cars look younger than they are, down to "1 month" for next year's model, and
 * multiply their yearly distance. The driver can correct it on screen.
 */
fun monthsOnRoad(modelYear: Int, today: java.time.LocalDate): Int =
    java.time.temporal.ChronoUnit.MONTHS.between(java.time.LocalDate.of(modelYear - 1, 7, 1), today).toInt().coerceAtLeast(1)

/** The car being driven now, for its certified fuel economy when the car does not report its own. */
data class FuelCar(val id: String, val label: String, val certifiedKmPerL: Double)

object FuelCarCatalog {
    /** 공인 복합연비. 더 뉴 그랜저 2.5 가솔린, 디 올 뉴 아반떼 1.6 하이브리드(17인치). */
    val all = listOf(
        FuelCar("grandeur-2.5", "그랜저 2.5 가솔린", 11.7),
        FuelCar("avante-1.6-hev", "아반떼 1.6 하이브리드", 21.2),
    )

    fun byId(id: String?): FuelCar? = all.firstOrNull { it.id == id }
}

enum class FuelSource { CAR, CERTIFIED, NONE }

/**
 * The Pleos Vehicle SDK reports fuel economy in L/100 km (Fuel.getCumulativeFuelEconomy and the
 * FuelEconomyListener say so), the AAOS convention; Korean drivers and this app think in km/L.
 * Values outside 1–40 L/100 km (2.5–100 km/L) are not a running car's average and are dropped.
 */
fun kmPerLiterFromLitersPer100Km(litersPer100Km: Double): Double? =
    if (litersPer100Km.isFinite() && litersPer100Km in 1.0..40.0) 100.0 / litersPer100Km else null

/**
 * The fuel economy to compare against: the car's own long-run average when it reports one,
 * otherwise the certified figure of the model the driver picked. Certified figures usually beat
 * real driving, so the saving comes out smaller than with the car's own number.
 */
fun resolveFuelEconomy(carAverage: Double?, myCar: FuelCar?): Pair<Double?, FuelSource> = when {
    carAverage != null && carAverage > 0 -> carAverage to FuelSource.CAR
    myCar != null -> myCar.certifiedKmPerL to FuelSource.CERTIFIED
    else -> null to FuelSource.NONE
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
