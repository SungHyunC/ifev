package com.ifev.app.engine

/**
 * How far a model goes on a full battery in given conditions, built only from published numbers:
 *
 * - Speed: interpolates between the certified city and highway ranges by average speed. The Korean
 *   city and highway cycles average about 31 and 78 km/h.
 * - Cold: interpolates from the warm range at 20 ℃ to the certified cold range at -7 ℃. The cold test
 *   runs the heater, so with the climate system off the AAA (2019) figure is used instead: -12 % at
 *   -6.7 ℃ (20 ℉) without HVAC.
 * - Heat: AAA (2019) measured -17 % at 35 ℃ (95 ℉) with the air conditioner on and -4 % with it off,
 *   growing from 25 ℃.
 */
object EnergyModel {
    const val CITY_SPEED_KMH = 31.0
    const val HIGHWAY_SPEED_KMH = 78.0
    private const val WARM_C = 20.0
    private const val COLD_TEST_C = -7.0
    private const val HOT_FROM_C = 25.0
    private const val HOT_AAA_C = 35.0
    private const val COLD_LOSS_NO_HVAC = 0.12
    private const val HOT_LOSS_AC = 0.17
    private const val HOT_LOSS_NO_AC = 0.04

    fun rangeKm(model: EvModel, tempC: Double, avgSpeedKmh: Double, climateOn: Boolean): Double {
        val hwy = ((avgSpeedKmh - CITY_SPEED_KMH) / (HIGHWAY_SPEED_KMH - CITY_SPEED_KMH)).coerceIn(0.0, 1.0)
        val warm = lerp(model.warm.city, model.warm.highway, hwy)
        val cold = lerp(model.cold.city, model.cold.highway, hwy)
        val coldness = ((WARM_C - tempC) / (WARM_C - COLD_TEST_C)).coerceIn(0.0, 1.0)
        val withHeater = lerp(warm, cold, coldness)
        // AAA's -12 % is one figure for every car; some models lose less than that even with the heater
        // on (their certified cold range). Switching the heater off can never cost range.
        val base = if (climateOn) withHeater else maxOf(withHeater, warm * (1 - COLD_LOSS_NO_HVAC * coldness))
        val heat = ((tempC - HOT_FROM_C) / (HOT_AAA_C - HOT_FROM_C)).coerceIn(0.0, 1.0)
        return base * (1 - (if (climateOn) HOT_LOSS_AC else HOT_LOSS_NO_AC) * heat)
    }

    /** kWh per km. [climateShare] is the share of the trip (0..1) with the climate system running. */
    fun kwhPerKm(model: EvModel, tempC: Double, avgSpeedKmh: Double, climateShare: Double): Double {
        val share = climateShare.coerceIn(0.0, 1.0)
        val on = model.batteryKwh / rangeKm(model, tempC, avgSpeedKmh, climateOn = true)
        val off = model.batteryKwh / rangeKm(model, tempC, avgSpeedKmh, climateOn = false)
        return on * share + off * (1 - share)
    }

    private fun lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
}
