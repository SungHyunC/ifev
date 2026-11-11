package com.ifev.app.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ifev.app.MainViewModel.Mode
import com.ifev.app.MainViewModel.Stage
import com.ifev.app.MainViewModel.Tab
import com.ifev.app.MainViewModel.UiState
import com.ifev.app.R
import com.ifev.app.engine.Confidence
import com.ifev.app.engine.EvCatalog
import com.ifev.app.engine.EvModel
import com.ifev.app.engine.KmSource
import com.ifev.app.engine.Measured
import com.ifev.app.engine.Prices
import com.ifev.app.engine.Verdict
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

class Actions(
    val onMode: (Mode) -> Unit,
    val onTab: (Tab) -> Unit,
    val onModel: (EvModel) -> Unit,
    val onHomeCharging: (Boolean) -> Unit,
    val onMonths: (Int) -> Unit,
    val onDemoDayZero: () -> Unit,
    val onDemoDrive: () -> Unit,
    val onDemoFourWeeks: () -> Unit,
)

@Composable
fun MainScreen(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 40.dp, vertical = 24.dp)) {
        TopBar(ui, actions)
        if (ui.mode == Mode.DEMO) {
            Spacer(Modifier.height(16.dp))
            DemoBar(ui.stage, actions)
        }
        Spacer(Modifier.height(20.dp))
        if (ui.car.driving) DrivingView(ui) else ParkedView(ui, actions)
    }
}

@Composable
private fun TopBar(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_launcher), contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(16.dp))
        Column {
            Text("이프EV", style = PleosType.title, color = c.textPrimary)
            Label("내 차가 전기차였다면", style = PleosType.labelSmall)
        }
        Spacer(Modifier.weight(1f))
        if (!ui.car.driving) {
            Segmented(
                options = EvCatalog.all.map { "${it.name} ${it.trim.substringBefore(" ")} ${it.batteryKwh.roundToInt()}kWh" },
                selected = EvCatalog.all.indexOf(ui.model).coerceAtLeast(0),
                onSelect = { actions.onModel(EvCatalog.all[it]) },
            )
            Spacer(Modifier.width(24.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot("차량 ${ui.car.vehicleStatus}", ui.car.vehicleStatus == "연결됨" || ui.mode == Mode.DEMO)
            StatusDot("내비 ${ui.car.naviStatus}", ui.car.naviStatus == "연결됨" || ui.mode == Mode.DEMO)
        }
        Spacer(Modifier.width(24.dp))
        Segmented(listOf("실시간", "시연"), if (ui.mode == Mode.LIVE) 0 else 1, { actions.onMode(if (it == 0) Mode.LIVE else Mode.DEMO) })
    }
}

@Composable
private fun DemoBar(stage: Stage, actions: Actions) {
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("시연 시나리오 (가상 데이터)", modifier = Modifier.padding(end = 8.dp))
        PleosButton("① 설치 직후", stage == Stage.DAY_ZERO, actions.onDemoDayZero)
        PleosButton("② 출근길 주행", stage == Stage.DRIVING, actions.onDemoDrive)
        PleosButton("③ 4주 뒤", stage == Stage.FOUR_WEEKS, actions.onDemoFourWeeks)
    }
}

// ───────────────────────────── driving ─────────────────────────────

/** While driving: two gauges and nothing to touch. */
@Composable
private fun DrivingView(ui: UiState) {
    val c = LocalPleosColors.current
    val homeWon = ui.tripKwh * Prices().homePerKwh
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Gauge(Modifier.weight(1f), "지금 이 차 · 연료", ui.car.fuelPercent?.div(100.0), c.fuel)
            Gauge(Modifier.weight(1f), "${ui.model.name} ${ui.model.trim.substringBefore(" ")}였다면 · 배터리", ui.ghostSoc, c.ev)
        }
        Card(Modifier.fillMaxWidth()) {
            Text(
                "이번 주행 %.1fkm · 전기차라면 %.1fkWh 사용 (집 충전 약 %,d원)".format(ui.car.tripKm, ui.tripKwh, homeWon.roundToInt()),
                style = PleosType.bodyStrong,
                color = c.textPrimary,
            )
            Spacer(Modifier.height(6.dp))
            Label("주행 중에는 조작할 것이 없어요. 정차(P)하면 결과를 보여 드려요.")
        }
    }
}

@Composable
private fun Gauge(modifier: Modifier, title: String, level: Double?, color: androidx.compose.ui.graphics.Color) {
    val c = LocalPleosColors.current
    Card(modifier.fillMaxSize()) {
        Label(title, style = PleosType.bodySmall)
        Spacer(Modifier.weight(1f))
        Text(level?.let { "${(it * 100).roundToInt()}%" } ?: "-", style = PleosType.display, color = color)
        Spacer(Modifier.height(24.dp))
        LevelBar(level ?: 0.0, color, height = 36)
        Spacer(Modifier.weight(1f))
    }
}

// ───────────────────────────── parked ─────────────────────────────

@Composable
private fun ParkedView(ui: UiState, actions: Actions) {
    val days = ui.report?.days ?: 0
    Segmented(
        options = listOf("① 예비 진단", "② 실측 기록 · ${days}일", "③ 확정 리포트"),
        selected = ui.tab.ordinal,
        onSelect = { actions.onTab(Tab.entries[it]) },
    )
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        when (ui.tab) {
            Tab.PRELIM -> PrelimView(ui, actions)
            Tab.MEASURED -> MeasuredView(ui)
            Tab.REPORT -> ReportView(ui)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PrelimView(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    val p = ui.prelim
    Questions(ui, actions)
    Card(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge("예비 진단", c.textSecondary, c.surfaceHigh)
            Badge("신뢰도 낮음 · 차와 내비에 이미 있는 기록", c.textTertiary, c.surfaceHigh)
        }
        Spacer(Modifier.height(16.dp))
        Text(p.verdict.title, style = PleosType.headline, color = verdictColor(p.verdict))
        Spacer(Modifier.height(8.dp))
        Text(prelimChargeLine(ui), style = PleosType.body, color = c.textSecondary)
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Stat(
            "연간 주행",
            p.annualKm?.let { "%,d km".format(it.roundToInt()) } ?: "-",
            when (p.kmSource) {
                KmSource.ODOMETER -> "누적 %,d km · %d개월 기준".format(ui.car.odometerKm?.roundToInt() ?: 0, ui.settings.monthsOwned ?: 0)
                KmSource.COMMUTE -> "출퇴근만 반영"
                KmSource.NONE -> "주행거리를 아직 몰라요"
            },
        )
        Stat(
            "이번 달 충전",
            p.homeChargesPerWeek?.let { "집 주 %.1f회".format(it) } ?: p.fastChargesPerMonth?.let { "급속 월 %.1f회".format(it) } ?: "-",
            "서울 ${java.time.LocalDate.now().monthValue}월 평년 기온 기준",
        )
        val low = p.annualSavingLow
        val high = p.annualSavingHigh
        Stat(
            "연료비 절감",
            if (low != null && high != null) "연 ${manwonRange(low, high)}" else "-",
            ui.car.averageFuelKmPerL?.let { "내 차 평균 %.1fkm/L와 비교".format(it) } ?: "평균 연비를 아직 몰라요",
            color = c.ev,
        )
    }
    if (p.destinations.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Text("최근 목적지, 전기차로 다녀온다면", style = PleosType.bodyStrong, color = c.textPrimary)
            Spacer(Modifier.height(16.dp))
            p.destinations.forEachIndexed { i, d ->
                val routed = ui.car.destinations.firstOrNull { it.first == d.destination }?.second == true
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(d.destination.name, style = PleosType.bodySmallStrong, color = c.textPrimary)
                        Label("왕복 %,d km%s".format(d.roundTripKm.roundToInt(), if (routed) "" else " · 직선거리로 추정"), style = PleosType.labelSmall)
                    }
                    StopsTag("지금", d.stopsNow)
                    Spacer(Modifier.width(12.dp))
                    StopsTag("1월", d.stopsWinter)
                }
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Text("계산 근거", style = PleosType.bodySmallStrong, color = c.textPrimary)
        Spacer(Modifier.height(12.dp))
        Bullets(
            p.assumptions + listOf(
                "${ui.model.label} ${ui.model.batteryKwh.roundToInt()}kWh, 환경부 인증 상온 ${ui.model.warm.combined.roundToInt()}km·저온 ${ui.model.cold.combined.roundToInt()}km",
            ),
            style = PleosType.label,
            color = c.textTertiary,
        )
        if (p.missing.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Label("아직 모르는 것: " + p.missing.joinToString(", "), color = c.negative)
        }
    }
    Label("앞으로 2~4주 실제 주행으로 확인할게요. 출발만 하면 자동으로 기록돼요.", style = PleosType.bodySmall)
}

@Composable
private fun Questions(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("집이나 아파트에서 충전할 수 있나요?", style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
            PleosButton("있어요", ui.settings.homeCharging == true, { actions.onHomeCharging(true) })
            Spacer(Modifier.width(12.dp))
            PleosButton("없어요", ui.settings.homeCharging == false, { actions.onHomeCharging(false) })
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("이 차를 탄 기간", style = PleosType.bodySmallStrong, color = c.textPrimary)
                if (ui.settings.monthsFromVin) Label("차대번호 연식(${ui.car.modelYear ?: "-"}년식) 기준 추정이에요. 다르면 고쳐 주세요.", style = PleosType.labelSmall)
            }
            PleosButton("−", false, { actions.onMonths(-1) })
            Text(
                ui.settings.monthsOwned?.let { "${it}개월" } ?: "-",
                style = PleosType.bodyStrong,
                color = c.textPrimary,
                modifier = Modifier.width(140.dp),
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
            )
            PleosButton("+", false, { actions.onMonths(1) })
        }
    }
}

@Composable
private fun StopsTag(season: String, stops: Int) {
    val c = LocalPleosColors.current
    if (stops == 0) Badge("$season · 충전 없이", c.ev, c.evSoft)
    else Badge("$season · 급속 ${stops}회", c.fuel, c.fuelSoft)
}

@Composable
private fun MeasuredView(ui: UiState) {
    val c = LocalPleosColors.current
    val r = ui.report
    if (r == null) {
        Empty()
        return
    }
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("실측 ${r.days}일째", style = PleosType.headline, color = c.textPrimary)
            Spacer(Modifier.width(16.dp))
            Badge("신뢰도 ${confidenceLabel(r.confidence)}", c.textSecondary, c.surfaceHigh)
            Spacer(Modifier.weight(1f))
            Label("목표 ${Measured.FULL_DAYS}일")
        }
        Spacer(Modifier.height(16.dp))
        LevelBar(r.days / Measured.FULL_DAYS.toDouble(), c.ev)
        Spacer(Modifier.height(12.dp))
        Label("기록된 주행 ${ui.tripCount}번 · 총 %,.1f km · 가상 배터리 지금 ${(r.sim.endSoc * 100).roundToInt()}%%".format(r.sim.totalKm))
    }
    Card(Modifier.fillMaxWidth()) {
        Text("예비 진단과 비교하면", style = PleosType.bodyStrong, color = c.textPrimary)
        Spacer(Modifier.height(12.dp))
        Bullets(ui.insights)
    }
    Card(Modifier.fillMaxWidth()) {
        Text("최근 기록", style = PleosType.bodyStrong, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        ui.recentDays.forEachIndexed { i, d ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${d.date.monthValue}/${d.date.dayOfMonth} (${d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})",
                    style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.width(200.dp),
                )
                Text("%.1f km".format(d.km), style = PleosType.bodySmall, color = c.textSecondary, modifier = Modifier.width(180.dp))
                Text("가상 배터리 최저 ${(d.minSoc * 100).roundToInt()}%", style = PleosType.bodySmall, color = if (d.minSoc < 0.2) c.negative else c.textSecondary, modifier = Modifier.weight(1f))
                when {
                    d.fastCharges > 0 -> Badge("급속 ${d.fastCharges}회", c.fuel, c.fuelSoft)
                    d.homeCharges > 0 -> Badge("집 충전", c.ev, c.evSoft)
                    else -> Label("충전 없음", style = PleosType.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ReportView(ui: UiState) {
    val c = LocalPleosColors.current
    val r = ui.report
    if (r == null) {
        Empty()
        return
    }
    val sim = r.sim
    val m = r.monthly
    Card(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge("확정 진단", c.ev, c.evSoft)
            Badge("실측 ${r.days}일 · 신뢰도 ${confidenceLabel(r.confidence)}", c.textTertiary, c.surfaceHigh)
        }
        Spacer(Modifier.height(16.dp))
        Text("지난 30일, 이 차가 ${ui.model.name} ${ui.model.trim.substringBefore(" ")}였다면", style = PleosType.title, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Text(r.verdict.title, style = PleosType.headline, color = verdictColor(r.verdict))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("충전", "%.0f번".format((m.homeCharges ?: 0.0) + m.fastCharges), "집 %.0f · 급속 %.0f".format(m.homeCharges ?: 0.0, m.fastCharges))
            Stat(
                "배터리 20% 미만",
                "%.0f일".format(m.lowSocDays ?: 0.0),
                (if (sim.lowSocDays > 0 && r.routineLowDays == 0) "모두 장거리 날 · " else "") + "가장 낮을 때 ${(sim.minSoc * 100).roundToInt()}%",
            )
            Stat("연료비 절감", m.savingWon?.let { "월 ${manwon(it)}" } ?: "-", m.savingWon?.let { "연 약 ${manwon(it * 12)}" }, color = c.ev)
            Stat("급속 대기", "%.0f분".format(sim.fastMinutes * 30.0 / r.days), "30일 기준, 장거리 포함")
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Text("예비 진단 vs 실제 주행", style = PleosType.bodyStrong, color = c.textPrimary)
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
            Label("30일 기준", modifier = Modifier.weight(1.2f))
            Label("예비 진단 (설치 직후)", modifier = Modifier.weight(1f))
            Label("실제 ${r.days}일", modifier = Modifier.weight(1f))
        }
        ui.rows.forEach { row ->
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
                Text(row.label, style = PleosType.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1.2f))
                Text(row.predicted, style = PleosType.bodySmall, color = c.textTertiary, modifier = Modifier.weight(1f))
                Text(row.actual, style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
            }
        }
    }
    Card(Modifier.fillMaxWidth()) {
        Text("이렇게 달랐어요", style = PleosType.bodyStrong, color = c.textPrimary)
        Spacer(Modifier.height(12.dp))
        Bullets(ui.insights)
    }
    Label("충전 요금은 2026년 8월 공공 충전요금, 휘발유는 오피넷 평균 기준. 실제 비용은 요금제와 운전 습관에 따라 달라요.", style = PleosType.labelSmall)
}

@Composable
private fun Empty() {
    val c = LocalPleosColors.current
    Card(Modifier.fillMaxWidth()) {
        Text("아직 기록된 주행이 없어요", style = PleosType.title, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Label("P단에서 출발하면 자동으로 기록돼요. 다른 앱을 보고 있어도 기록은 이어져요.", style = PleosType.bodySmall)
    }
}

// ───────────────────────────── text ─────────────────────────────

@Composable
private fun verdictColor(v: Verdict) = LocalPleosColors.current.let {
    when (v) {
        Verdict.GOOD -> it.ev
        Verdict.CONDITIONAL -> it.fuel
        Verdict.HYBRID_BETTER -> it.negative
        Verdict.UNKNOWN -> it.textPrimary
    }
}

private fun prelimChargeLine(ui: UiState): String {
    val p = ui.prelim
    val weekly = p.weeklyKm?.let { "한 주 약 %,d km".format(it.roundToInt()) }
    return when {
        weekly == null -> "주행거리를 알면 계산할 수 있어요."
        p.homeChargesPerWeek != null -> "$weekly → 집에서 주 %.1f회 충전 (50%%에서 90%%까지)".format(p.homeChargesPerWeek)
        p.fastChargesPerMonth != null -> "$weekly → 공공 급속 월 %.1f회 (30%%에서 80%%까지)".format(p.fastChargesPerMonth)
        else -> "$weekly · 집 충전 여부를 알려 주시면 충전 횟수를 계산해요."
    }
}

/** Whole 만 원, for the big numbers. */
private fun manwon(v: Double): String = Measured.won(v)

/** "14~19만 원" when both ends are in 만 원. */
private fun manwonRange(low: Double, high: Double): String =
    if (low >= 10_000 && high >= 10_000) "%d~%d만 원".format((low / 10_000).roundToInt(), (high / 10_000).roundToInt())
    else "${manwon(low)}~${manwon(high)}"

private fun confidenceLabel(c: Confidence) = when (c) {
    Confidence.LOW -> "낮음"
    Confidence.MEDIUM -> "중간"
    Confidence.HIGH -> "높음"
}

