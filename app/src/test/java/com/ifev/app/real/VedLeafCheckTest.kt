package com.ifev.app.real

import com.ifev.app.engine.EnergyModel
import com.ifev.app.engine.EvCatalog
import com.ifev.app.engine.EvModel
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Checks the temperature shape of [EnergyModel] against real winter driving: three 2013 Nissan Leafs
 * (VehId 10, 455, 541) of the public Vehicle Energy Dataset (VED, Apache-2.0), one row per trip with the
 * battery energy that left the pack (regeneration included). Rows come from
 * `tools/ved_to_trips.py --ev-rows` and hold no coordinates or times.
 *
 * The Leaf is not one of the modelled cars, so absolute kWh are not compared. What is compared is the
 * shape: how much more energy per km each temperature bin takes than the 15–25 ℃ bin,
 *
 *   measured: (Σ kWh / Σ km in the bin) ÷ (Σ kWh / Σ km in 15–25 ℃)
 *   model:    (Σ km × kwhPerKm(T, v, 1.0) / Σ km in the bin) ÷ (the same over 15–25 ℃)
 *
 * with each trip's own OAT and average speed and the climate system counted as on (climateShare 1.0).
 *
 * Then a calibrated total: the base rate is the Leaf's own measured rate on its 15–25 ℃ trips, scaled
 * per trip by the model's shape. This calibrates the base on the Leaf's warm trips and so tests only the
 * temperature and speed shape, not the absolute efficiency of any Hyundai car.
 *
 * Asserts only what should stay true; the table is for docs/공개데이터_검증.md.
 */
class VedLeafCheckTest {

    private data class Row(val veh: Int, val km: Double, val sec: Double, val oat: Double, val kwh: Double, val heatW: Double?, val acW: Double?) {
        val kmh get() = km / (sec / 3600.0)
    }

    private data class Bin(val label: String, val lo: Double, val hi: Double) {
        operator fun contains(t: Double) = t >= lo && t < hi
    }

    private val bins = listOf(
        Bin("< -5℃", -100.0, -5.0),
        Bin("-5~0℃", -5.0, 0.0),
        Bin("0~5℃", 0.0, 5.0),
        Bin("5~10℃", 5.0, 10.0),
        Bin("10~15℃", 10.0, 15.0),
        Bin("15~25℃", 15.0, 25.0),
        Bin("25℃ 이상", 25.0, 100.0),
    )
    private val ref = bins.first { it.lo == 15.0 }
    private val models = listOf(EvCatalog.IONIQ6_LONG, EvCatalog.KONA, EvCatalog.CASPER)

    private fun rows(): List<Row> {
        val text = javaClass.classLoader!!.getResource("ved_leaf_trips.json")!!.readText()
        val arr = JSONArray(text)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Row(
                veh = o.getInt("veh"), km = o.getDouble("km"), sec = o.getDouble("sec"), oat = o.getDouble("oat"), kwh = o.getDouble("kwh"),
                heatW = if (o.isNull("heatW")) null else o.getDouble("heatW"),
                acW = if (o.isNull("acW")) null else o.getDouble("acW"),
            )
        }.filter { it.km >= 1.0 && it.sec > 0 }
    }

    private fun measuredRate(rs: List<Row>) = rs.sumOf { it.kwh } / rs.sumOf { it.km }
    private fun modelRate(m: EvModel, rs: List<Row>) =
        rs.sumOf { it.km * EnergyModel.kwhPerKm(m, it.oat, it.kmh, 1.0) } / rs.sumOf { it.km }

    @Test
    fun temperatureShape() {
        val all = rows()
        val refRows = all.filter { it.oat in ref }
        val refMeasured = measuredRate(refRows)
        println("VED Nissan Leaf (VehId 10, 455, 541): ${all.size} trips, %.1f km, %.1f kWh".format(all.sumOf { it.km }, all.sumOf { it.kwh }))
        println("| 기온 | 주행 | km | 평균 시속 | 리프 kWh/100km | 히터 kWh/100km | 에어컨 kWh/100km | 리프 배율 | " + models.joinToString(" | ") { "${it.shortLabel} 배율" } + " |")
        println("|---|---|---|---|---|---|---|---|" + models.joinToString("") { "---|" })
        val measuredRatio = mutableMapOf<Bin, Double>()
        val modelRatio = mutableMapOf<Pair<Bin, EvModel>, Double>()
        for (b in bins) {
            val rs = all.filter { it.oat in b }
            if (rs.isEmpty()) continue
            val km = rs.sumOf { it.km }
            val speed = km / (rs.sumOf { it.sec } / 3600.0)
            measuredRatio[b] = measuredRate(rs) / refMeasured
            models.forEach { modelRatio[b to it] = modelRate(it, rs) / modelRate(it, refRows) }
            // Mean heater and A/C power over each trip × its duration: energy those signals report, per 100 km.
            val heat = rs.sumOf { (it.heatW ?: 0.0) * it.sec } / 3.6e6 / km * 100
            val ac = rs.sumOf { (it.acW ?: 0.0) * it.sec } / 3.6e6 / km * 100
            println("| ${b.label} | ${rs.size} | %.0f | %.1f | %.1f | %.1f | %.1f | %.2f | ".format(km, speed, measuredRate(rs) * 100, heat, ac, measuredRatio[b]) +
                models.joinToString(" | ") { "%.2f".format(modelRatio[b to it]) } + " |")
        }

        // Stable facts, loosely: the reference bin is 1 by construction, the Leaf needs clearly more per km
        // below freezing than in mild weather, and the model, too, never makes the cold cheaper.
        assertEquals(1.0, measuredRatio.getValue(ref), 1e-9)
        val cold = bins.filter { it.hi <= 0.0 && it in measuredRatio }
        assertTrue(cold.isNotEmpty())
        cold.forEach { b ->
            assertTrue("${b.label} measured", measuredRatio.getValue(b) > 1.2)
            models.forEach { m -> assertTrue("${b.label} ${m.shortLabel}", modelRatio.getValue(b to m) > 1.0) }
        }
    }

    @Test
    fun calibratedTotals() {
        val all = rows()
        val refRows = all.filter { it.oat in ref }
        val base = measuredRate(refRows)
        println("기준: 리프의 15~25℃ 주행 실측 %.2f kWh/100km (이 값으로 맞춘 뒤 기온·속도 모양만 비교)".format(base * 100))
        println("| 기온 | 실측 kWh | " + models.joinToString(" | ") { "${it.shortLabel} 모양 예측 kWh (오차)" } + " |")
        println("|---|---|" + models.joinToString("") { "---|" })
        val totals = models.associateWith { 0.0 }.toMutableMap()
        for (b in bins + Bin("전체", -100.0, 100.0)) {
            val rs = all.filter { it.oat in b }
            if (rs.isEmpty()) continue
            val actual = rs.sumOf { it.kwh }
            val cells = models.map { m ->
                val refRate = modelRate(m, refRows)
                val predicted = rs.sumOf { it.km * base * EnergyModel.kwhPerKm(m, it.oat, it.kmh, 1.0) / refRate }
                if (b.label == "전체") totals[m] = predicted
                if (b == ref) assertEquals("calibrated on this bin", actual, predicted, 1e-6 * actual)
                "%.1f (%+.0f%%)".format(predicted, (predicted - actual) / actual * 100)
            }
            println("| ${b.label} | %.1f | ".format(actual) + cells.joinToString(" | ") + " |")
        }
        val actualAll = all.sumOf { it.kwh }
        // Below freezing the Leaf used more than the model's shape predicts; overall the shape cannot be
        // wildly off in both directions at once, so the total stays within ±50 %.
        models.forEach { m -> assertTrue(m.shortLabel, abs(totals.getValue(m) - actualAll) / actualAll < 0.5) }
    }
}
