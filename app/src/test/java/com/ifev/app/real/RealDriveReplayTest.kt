package com.ifev.app.real

import com.ifev.app.engine.BatterySim
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
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Real drives through the same engine as the app. Skipped unless -Pdrives points at a folder with one
 * sub-folder per person:
 *
 *   trips.json       from tools/gpx_to_trips.py (no coordinates)
 *   profile.json     {"label": "팀원 A", "homeCharging": true, "fuelKmPerL": 12.4}
 *   ev_actual.json   only for EV owners, over the same days:
 *                    {"modelId": "ioniq5-long", "chargedKwh": 182.0, "startSoc": 80, "endSoc": 64}
 *
 * Writes report.md next to the folders: per person the verdict and charges for every Hyundai EV, and
 * for EV owners the predicted energy against what they actually charged.
 */
class RealDriveReplayTest {

    @Test
    fun replay() {
        val root = System.getProperty("ifev.drives").orEmpty()
        assumeTrue("no -Pdrives given", root.isNotBlank())
        val dirs = File(root).listFiles { f -> File(f, "trips.json").exists() }.orEmpty().sortedBy { it.name }
        val out = StringBuilder("# 실제 주행 기록 재생 결과\n\n엔진: 앱과 같은 BatterySim·Measured. 4주 기록은 30일 기준으로 환산.\n\n")
        for (dir in dirs) {
            val trips = readTrips(File(dir, "trips.json"))
            if (trips.isEmpty()) continue
            val profile = File(dir, "profile.json").takeIf { it.exists() }?.let { JSONObject(it.readText()) } ?: JSONObject()
            val label = profile.optString("label", dir.name)
            val home = profile.optBoolean("homeCharging", true)
            val fuel = if (profile.has("fuelKmPerL")) profile.getDouble("fuelKmPerL") else null
            val plan = ChargingPlan(homeCharging = home)
            val now = trips.maxOf { it.endAt }
            out.append("## $label\n\n집 충전 ${if (home) "있음" else "없음"} · 주행 ${trips.size}번 · %,.1f km\n\n".format(trips.sumOf { it.distanceKm }))
            out.append("| 차종 | 판정 | 30일 충전 | 20% 미만 | 월 절감 | 신뢰도 |\n|---|---|---|---|---|---|\n")
            for (m in EvCatalog.all) {
                val r = Measured.report(m, trips, plan, fallbackFuelKmPerL = fuel, nowMs = now) ?: continue
                val charge = r.monthly.homeCharges?.let { "집 %.1f · 급속 %.1f".format(it, r.monthly.fastCharges) }
                    ?: "급속 평소 %.1f · 길 위 %.1f".format(r.monthly.fastRoutine, r.monthly.fastEnRoute)
                out.append("| ${m.shortLabel} | ${r.verdict.title} | $charge | %.0f일 | %s | %s (%d일 중 운전 %d일) |\n".format(
                    r.monthly.lowSocDays ?: 0.0, r.monthly.savingWon?.let { Measured.won(it) } ?: "-", mapOf("LOW" to "낮음", "MEDIUM" to "중간", "HIGH" to "높음")[r.confidence.name], r.days, r.drivingDays))
            }
            File(dir, "ev_actual.json").takeIf { it.exists() }?.let { f ->
                val a = JSONObject(f.readText())
                val m = EvCatalog.byId(a.getString("modelId"))
                val used = a.getDouble("chargedKwh") + (a.getDouble("startSoc") - a.getDouble("endSoc")) / 100 * m.batteryKwh
                val predicted = BatterySim.run(m, trips, plan, nowMs = now).drivenKwh
                val err = (predicted - used) / used * 100
                out.append("\n**전기차 차주 검증** (${m.shortLabel}): 실제 사용 %.1f kWh, 이프EV 예측 %.1f kWh, 오차 %+.1f%%\n".format(used, predicted, err))
                println("$label: actual %.1f kWh, predicted %.1f kWh, error %+.1f%%".format(used, predicted, err))
                if (abs(err) > 25) out.append("\n오차가 25%를 넘어요. 공조 비율·기온 가정과 GPS 거리 누락을 먼저 확인하세요.\n")
            }
            out.append("\n")
            println("$label: ${trips.size} trips, verdict (${EvCatalog.IONIQ6_LONG.shortLabel}) " +
                Measured.report(EvCatalog.IONIQ6_LONG, trips, plan, fallbackFuelKmPerL = fuel, nowMs = now)!!.verdict.title)
        }
        File(root, "report.md").writeText(out.toString())
        println("report: ${File(root, "report.md")} (${dirs.size} people)")
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
