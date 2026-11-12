package com.ifev.app.real

import com.ifev.app.engine.ChargingPlan
import com.ifev.app.engine.DistanceSource
import com.ifev.app.engine.EvCatalog
import com.ifev.app.engine.Measured
import com.ifev.app.engine.Place
import com.ifev.app.engine.Trip
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The bundled public-data weeks (assets/public/<id>/, from the Vehicle Energy Dataset via
 * tools/ved_to_trips.py) through the same engine as the app. Prints only; the figures go into
 * docs/공개데이터_검증.md.
 */
class VedReplayTest {

    @Test
    fun replay() {
        val root = listOf(File("src/main/assets/public"), File("app/src/main/assets/public")).firstOrNull { it.isDirectory }
        assumeTrue("no assets/public", root != null)
        val dirs = root!!.listFiles { f -> File(f, "trips.json").exists() }.orEmpty().sortedBy { it.name }
        for (dir in dirs) {
            val trips = readTrips(File(dir, "trips.json"))
            val profile = File(dir, "profile.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
            val now = trips.maxOf { it.endAt }
            val fuelKm = trips.filter { it.fuelKmPerL != null }
            println("== ${profile.optString("label", dir.name)}")
            println("주행 %d번 · %.1f km · 가장 긴 주행 %.1f km · 평균 기온 %.1f℃ · 연비 %.1f km/L (연비 있는 주행 %d번)".format(
                trips.size, trips.sumOf { it.distanceKm }, trips.maxOf { it.distanceKm }, trips.sumOf { it.tempC * it.distanceKm } / trips.sumOf { it.distanceKm },
                fuelKm.sumOf { it.distanceKm } / fuelKm.sumOf { it.distanceKm / it.fuelKmPerL!! }, fuelKm.size))
            for (home in listOf(true, false)) {
                for (m in listOf(EvCatalog.IONIQ6_LONG, EvCatalog.CASPER)) {
                    val r = Measured.report(model = m, trips = trips, plan = ChargingPlan(homeCharging = home), nowMs = now) ?: continue
                    val mo = r.monthly
                    println(("${m.shortLabel} · 집 충전 ${if (home) "있음" else "없음"}: 판정 ${r.verdict.title} · 기간 %d일(운전 %d일) · 신뢰도 ${r.confidence}" +
                        " · 30일 %.0f km · 사용 %.1f kWh(기간) · 집 충전 %s · 급속 %.1f회(평소 %.1f · 길 위 %.1f) · 20%% 미만 %.0f일 · 월 절감 %s")
                        .format(r.days, r.drivingDays, mo.km, r.sim.drivenKwh, mo.homeCharges?.let { "%.1f회".format(it) } ?: "-",
                            mo.fastCharges, mo.fastRoutine, mo.fastEnRoute, mo.lowSocDays ?: 0.0, mo.savingWon?.let { Measured.won(it) } ?: "-"))
                }
            }
        }
    }

    private fun readTrips(f: File): List<Trip> {
        val arr = JSONArray(f.readText())
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            Trip(
                startAt = o.getLong("s"), endAt = o.getLong("e"), distanceKm = o.getDouble("km"),
                startPlace = Place.valueOf(o.getString("from")), endPlace = Place.valueOf(o.getString("to")),
                tempC = o.getDouble("t"), climateShare = o.getDouble("c"),
                fuelKmPerL = if (o.isNull("f")) null else o.getDouble("f"),
                distanceSource = DistanceSource.entries.firstOrNull { it.name == o.optString("ds") } ?: DistanceSource.TRACK,
            )
        }.sortedBy { it.startAt }
    }
}
