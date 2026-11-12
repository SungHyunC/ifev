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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.ifev.app.MainViewModel
import com.ifev.app.MainViewModel.Mode
import com.ifev.app.MainViewModel.Stage
import com.ifev.app.MainViewModel.Tab
import com.ifev.app.MainViewModel.UiState
import com.ifev.app.R
import com.ifev.app.engine.Confidence
import com.ifev.app.engine.EvCatalog
import com.ifev.app.engine.EvModel
import com.ifev.app.engine.FuelCar
import com.ifev.app.engine.FuelCarCatalog
import com.ifev.app.engine.FuelSource
import com.ifev.app.engine.DistanceSource
import com.ifev.app.engine.KmSource
import com.ifev.app.engine.Measured
import com.ifev.app.engine.NaviCheck
import com.ifev.app.engine.Prices
import com.ifev.app.engine.Verdict
import com.ifev.app.engine.ifItWere
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

class Actions(
    val onMode: (Mode) -> Unit,
    val onTab: (Tab) -> Unit,
    val onModel: (EvModel) -> Unit,
    val onHomeCharging: (Boolean) -> Unit,
    val onMyCar: (FuelCar) -> Unit,
    val onMonths: (Int) -> Unit,
    val onDemoDayZero: () -> Unit,
    val onDemoDrive: () -> Unit,
    val onDemoFourWeeks: () -> Unit,
    val onVoice: () -> Unit,
    val onReal: (Int) -> Unit,
    val onDemoRefuel: () -> Unit,
    val onWorkCharging: (Boolean) -> Unit = {},
    val onOpenReport: () -> Unit = {},
    val onCloseReport: () -> Unit = {},
    val onReplay: () -> Unit = {},
)

@Composable
fun MainScreen(ui: UiState, visual: MainViewModel.VisualNow, actions: Actions, canReplay: Boolean = false, replaying: Boolean = false) {
    val c = LocalPleosColors.current
    Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 40.dp, vertical = 24.dp)) {
        TopBar(ui, actions)
        Spacer(Modifier.height(16.dp))
        // Driving always shows the virtual EV, with nothing to press; the report pages are parked-only.
        if (ui.screen == MainViewModel.Screen.REPORT && !ui.car.driving) {
            ReportPages(ui, actions)
        } else {
            HomeScreen(ui, visual, actions, canReplay, replaying)
        }
    }
}

/** The report pages: what the recorded weeks add up to, opened from the virtual EV while parked. */
@Composable
private fun ReportPages(ui: UiState, actions: Actions) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        PleosButton("← 차로 돌아가기", false, actions.onCloseReport)
        if (ui.mode == Mode.DEMO) {
            Spacer(Modifier.width(16.dp))
            DemoBar(ui, actions)
        }
    }
    Spacer(Modifier.height(16.dp))
    ui.lastLine?.let { line -> SpokenLine(line); Spacer(Modifier.height(16.dp)) }
    ParkedView(ui, actions)
}

/** The spoken line, also in writing: the moment matters more than the sound. */
@Composable
internal fun SpokenLine(line: String) {
    Card(Modifier.fillMaxWidth()) {
        Label("차가 방금 말한 것", style = PleosType.labelSmall)
        Text(line, style = PleosType.bodyStrong, color = LocalPleosColors.current.textPrimary)
    }
}

@Composable
private fun TopBar(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.ic_logo), contentDescription = null, modifier = Modifier.size(48.dp))
        Spacer(Modifier.width(16.dp))
        Text("이프EV", style = PleosType.title, color = c.textPrimary)
        Spacer(Modifier.width(24.dp))
        val connected = ui.car.vehicleStatus == "연결됨" && ui.car.naviStatus == "연결됨"
        StatusDot(
            when {
                ui.mode == Mode.DEMO -> "시연"
                connected -> "차·내비 연결됨"
                else -> "차 ${ui.car.vehicleStatus} · 내비 ${ui.car.naviStatus}"
            },
            connected || ui.mode == Mode.DEMO,
        )
        Spacer(Modifier.weight(1f))
        if (!ui.car.driving) {
            PleosButton(if (ui.settings.voiceOn) "음성 켜짐" else "음성 꺼짐", ui.settings.voiceOn, actions.onVoice)
            Spacer(Modifier.width(16.dp))
            Segmented(listOf("실시간", "시연"), if (ui.mode == Mode.LIVE) 0 else 1, { actions.onMode(if (it == 0) Mode.LIVE else Mode.DEMO) })
        } else if (ui.mode == Mode.DEMO) {
            Badge("시연 · 가상 주행", c.textPrimary, c.fuelSoft)
        }
    }
}

@Composable
internal fun DemoBar(ui: UiState, actions: Actions) {
    val stage = ui.stage
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("시연 (가상)", modifier = Modifier.padding(end = 4.dp))
        PleosButton("① 설치 직후", stage == Stage.DAY_ZERO, actions.onDemoDayZero)
        PleosButton("② 출근길", stage == Stage.DRIVING, actions.onDemoDrive)
        PleosButton("③ 4주 뒤", stage == Stage.FOUR_WEEKS, actions.onDemoFourWeeks)
        PleosButton("주유", false, actions.onDemoRefuel)
        if (ui.realDriveLabels.isNotEmpty()) {
            // Real recorded weeks on the same screens: a public dataset and, once collected, participants.
            Label("실제 기록", modifier = Modifier.padding(start = 16.dp, end = 4.dp))
            ui.realDriveLabels.forEachIndexed { i, label ->
                PleosButton(label.substringBefore(" ·"), ui.realLabel == label, { actions.onReal(i) })
            }
        }
    }
}

// ───────────────────────────── driving ─────────────────────────────

/**
 * While driving: the imagined battery is the one big number, the real fuel gauge sits beside it, and
 * there is nothing to touch. The route card appears when the navigation has a destination.
 */
@Composable
internal fun DrivingView(ui: UiState) {
    val c = LocalPleosColors.current
    // Priced the way the driver charges: at home (slow) or, without a home charger, at a fast charger.
    val plan = ui.settings.plan()
    val homeWon = ui.tripKwh * plan.everydayPerKwh()
    val costLabel = plan.everydayCharger
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Gauge(
                Modifier.weight(1.4f),
                "${ui.model.ifItWere} · 배터리",
                ui.ghostSoc,
                c.ev,
                note = when {
                    ui.tripFastStops > 0 -> "이번 주행에서 급속 충전 ${ui.tripFastStops}번 한 셈이에요"
                    ui.chargedWhileParked -> "세워 둔 사이 집에서 ${(ui.startSoc * 100).roundToInt()}%까지 충전하고 출발"
                    else -> "출발할 때 ${(ui.startSoc * 100).roundToInt()}%"
                },
            )
            if (ui.car.fuelPercent != null) {
                Gauge(Modifier.weight(1f), "지금 이 차 · 연료", ui.car.fuelPercent / 100.0, c.fuel)
            } else {
                Card(Modifier.weight(1f).fillMaxSize()) {
                    Label("이번 주행", style = PleosType.bodySmall)
                    Spacer(Modifier.weight(1f))
                    Text("%.1f km".format(ui.car.tripKm), style = PleosType.headline, color = c.textPrimary)
                    Spacer(Modifier.height(12.dp))
                    Text("전기 %.1fkWh".format(ui.tripKwh), style = PleosType.title, color = c.ev)
                    Text("$costLabel 약 %,d원".format(homeWon.roundToInt()), style = PleosType.body, color = c.textSecondary)
                    RegenLine(ui)
                    Spacer(Modifier.weight(1f))
                }
            }
        }
        ui.route?.let { r ->
            Card(Modifier.fillMaxWidth()) {
                Text(
                    // The place name comes from the navigation: keep it out of the format string.
                    (ui.car.route?.name ?: "목적지") + "까지 %,d km · 도착 배터리 %d%%".format(r.distanceKm.roundToInt(), (r.arrivalSoc * 100).roundToInt()) +
                        if (r.stops > 0) " · 급속 ${r.stops}번" else " · 충전 없이",
                    style = PleosType.bodyStrong,
                    color = c.textPrimary,
                )
                ui.naviCheck?.let { n ->
                    Spacer(Modifier.height(4.dp))
                    Label("이 차(전기차) 도착 배터리: 내비 %d%% · 이프EV %d%%".format(n.naviArrivalPct.roundToInt(), n.ifevArrivalPct.roundToInt()))
                }
            }
        }
        if (ui.car.fuelPercent != null) {
            Card(Modifier.fillMaxWidth()) {
                Text(
                    "이번 주행 %.1fkm · 전기 %.1fkWh · ".format(ui.car.tripKm, ui.tripKwh) + costLabel + " 약 %,d원".format(homeWon.roundToInt()),
                    style = PleosType.bodyStrong,
                    color = c.textPrimary,
                )
                RegenLine(ui)
            }
        }
    }
}

/**
 * Virtual regenerative braking: grows each time the car slows down. Part of the trip's kWh above
 * (certified ranges include it), so it is a breakdown, not a bonus.
 */
@Composable
private fun RegenLine(ui: UiState) {
    val c = LocalPleosColors.current
    if (ui.tripRegenKwh < 0.01) return
    Spacer(Modifier.height(8.dp))
    Text(
        "감속할 때 모터가 되찾은 전기 %.2fkWh (배터리 +%.1f%%p)".format(ui.tripRegenKwh, ui.tripRegenKwh / ui.model.batteryKwh * 100),
        style = PleosType.body,
        color = c.ev,
    )
    Label("브레이크 열로 버려지던 몫 · 추정, 위 전기 사용량에 이미 포함", style = PleosType.labelSmall)
}

@Composable
private fun Gauge(modifier: Modifier, title: String, level: Double?, color: androidx.compose.ui.graphics.Color, note: String? = null) {
    Card(modifier.fillMaxSize()) {
        Label(title, style = PleosType.bodySmall)
        Spacer(Modifier.weight(1f))
        Text(level?.let { "${(it * 100).roundToInt()}%" } ?: "-", style = PleosType.display, color = color)
        Spacer(Modifier.height(24.dp))
        LevelBar(level ?: 0.0, color, height = 36)
        if (note != null) {
            Spacer(Modifier.height(16.dp))
            Label(note, style = PleosType.bodySmall)
        }
        Spacer(Modifier.weight(1f))
    }
}

// ───────────────────────────── parked ─────────────────────────────

/**
 * Parked: one row of tabs with the compared model beside it, then the answer. Each tab leads with a
 * verdict and three numbers; tables, sources and the reasoning sit behind "자세히".
 */
@Composable
private fun ParkedView(ui: UiState, actions: Actions) {
    val days = ui.report?.days ?: 0
    ui.naviCheck?.let {
        NaviCheckCard(it, ui.car.route?.name)
        Spacer(Modifier.height(16.dp))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Segmented(
            options = listOf("예비 진단", if (days > 0) "실측 ${days}일" else "실측", "4주 리포트"),
            selected = ui.tab.ordinal,
            onSelect = { actions.onTab(Tab.entries[it]) },
        )
        Spacer(Modifier.weight(1f))
        ModelDropdown(ui, actions)
    }
    Spacer(Modifier.height(20.dp))
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        when (ui.tab) {
            Tab.PRELIM -> PrelimView(ui, actions)
            Tab.MEASURED -> MeasuredView(ui)
            Tab.REPORT -> ReportView(ui, actions)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** The compared model as one button; the line-up opens on tap. */
@Composable
private fun ModelDropdown(ui: UiState, actions: Actions) {
    var open by remember { mutableStateOf(false) }
    Box {
        PleosButton("비교 차  ${ui.model.shortLabel} ▾", false, { open = true })
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            EvCatalog.all.forEach { m ->
                DropdownMenuItem(
                    text = { Text("${m.name} ${m.trim}", style = PleosType.bodySmall) },
                    onClick = { actions.onModel(m); open = false },
                )
            }
        }
    }
}

@Composable
private fun PrelimView(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    val p = ui.prelim
    val hybrid = p.verdict == Verdict.HYBRID_BETTER
    Card(Modifier.fillMaxWidth()) {
        Label("설치 직후 · 차와 내비에 이미 있던 기록으로")
        Spacer(Modifier.height(8.dp))
        val title = when {
            p.verdict != Verdict.UNKNOWN -> p.verdict.title
            ui.settings.homeCharging == null -> "집에서 충전할 수 있는지 알려 주세요"
            else -> "주행거리를 알면 바로 계산할게요"
        }
        Text(title, style = PleosType.headline, color = verdictColor(p.verdict))
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            BigNumber(p.annualKm?.let { "%,d km".format(it.roundToInt()) } ?: "-", "연간 주행")
            BigNumber(
                p.homeChargesPerWeek?.let { "주 %.1f회".format(it) } ?: p.fastChargesPerMonth?.let { "월 %.1f회".format(it) } ?: "-",
                if (p.homeChargesPerWeek != null) "집 충전" else "공공 급속 충전",
            )
            val low = p.annualSavingLow
            val high = p.annualSavingHigh
            BigNumber(
                if (low != null && high != null) "연 ${manwonRange(low, high)}" else "-",
                if (hybrid) "전기차로 바꾸면 아끼는 기름값" else "아끼는 기름값",
                color = if (hybrid) c.textPrimary else c.ev,
            )
        }
        if (hybrid && p.annualHybridSavingWon != null) {
            Spacer(Modifier.height(16.dp))
            Text("하이브리드라면 충전 없이 연 ${manwon(p.annualHybridSavingWon)}을 아껴요.", style = PleosType.bodySmallStrong, color = c.textPrimary)
        }
    }
    HomeQuestion(ui, actions)
    p.destinations.firstOrNull()?.let { far ->
        Expandable(
            "가장 먼 목적지",
            "${far.destination.name} 왕복 %,d km · 지금 ${stopsShort(far.stopsNow)} · 1월 ${stopsShort(far.stopsWinter)}".format(far.roundTripKm.roundToInt()),
        ) {
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
    Expandable("현대 전기차 6종", modelSummary(ui, measured = false)) { ModelTable(ui, actions, measured = false) }
    Expandable("차가 알려 준 것", carFactsSummary(ui)) { CarFacts(ui, actions) }
    Expandable("계산 근거", "공개 인증 주행거리 · 서울 평년 기온 · 공공 충전요금") {
        Bullets(
            p.assumptions + listOf(
                "${ui.model.label}: ${ui.model.source}",
                "충전 습관(가정): 집에 4시간 이상 세울 때 50% 아래면 90%까지, 집 충전이 없으면 30% 아래에서 급속 80%까지, 10% 아래로는 계획하지 않음",
            ),
            style = PleosType.label,
            color = c.textTertiary,
        )
        if (p.missing.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            Label("아직 모르는 것: " + p.missing.joinToString(", "), color = c.negative)
        }
    }
}

/** The one question the car cannot answer. Asked first, always changeable. */
@Composable
private fun HomeQuestion(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("집이나 아파트에서 충전할 수 있나요?", style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
            PleosButton("있어요", ui.settings.homeCharging == true, { actions.onHomeCharging(true) })
            Spacer(Modifier.width(12.dp))
            PleosButton("없어요", ui.settings.homeCharging == false, { actions.onHomeCharging(false) })
        }
        if (ui.fuelSource == FuelSource.NONE) {
            // Without the car's own economy there is nothing to compare: the second (and last) question.
            Spacer(Modifier.height(16.dp))
            FuelCarRow(ui, actions)
        }
    }
}

@Composable
private fun FuelCarRow(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("지금 타는 차는?", style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
        FuelCarCatalog.all.forEach { car ->
            Spacer(Modifier.width(12.dp))
            PleosButton("${car.label} ${car.certifiedKmPerL}km/L", ui.myCar == car, { actions.onMyCar(car) })
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
        Spacer(Modifier.height(20.dp))
        Row(Modifier.fillMaxWidth()) {
            BigNumber("${ui.tripCount}번", "기록된 주행")
            BigNumber("%,.0f km".format(r.sim.totalKm), "달린 거리")
            BigNumber("${(r.sim.endSoc * 100).roundToInt()}%", "${ui.model.shortLabel} 가상 배터리", color = c.ev)
        }
        ui.insights.firstOrNull()?.let {
            Spacer(Modifier.height(16.dp))
            Label(it, style = PleosType.bodySmall)
        }
    }
    ui.score?.let { ScoreCard(it) }
    if (ui.recentDays.isNotEmpty()) {
        val fast = ui.recentDays.sumOf { it.fastCharges }
        val home = ui.recentDays.sumOf { it.homeCharges }
        Expandable("최근 ${ui.recentDays.size}일", "급속 ${fast}번 · 집 충전 ${home}번 · 가장 낮을 때 ${(ui.recentDays.minOf { it.minSoc } * 100).roundToInt()}%") {
            ui.recentDays.forEachIndexed { i, d ->
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
                Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${d.date.monthValue}/${d.date.dayOfMonth} (${d.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN)})",
                        style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.width(200.dp),
                    )
                    Text("%.1f km".format(d.km), style = PleosType.bodySmall, color = c.textSecondary, modifier = Modifier.width(180.dp))
                    Text("최저 ${(d.minSoc * 100).roundToInt()}%", style = PleosType.bodySmall, color = if (d.minSoc < 0.2) c.negative else c.textSecondary, modifier = Modifier.weight(1f))
                    when {
                        d.fastCharges > 0 -> Badge("급속 ${d.fastCharges}회", c.fuel, c.fuelSoft)
                        d.homeCharges > 0 -> Badge("집 충전", c.ev, c.evSoft)
                        d.workCharges > 0 -> Badge("회사 충전", c.ev, c.evSoft)
                        else -> Label("충전 없음", style = PleosType.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Label(distanceSourceLine(r.distanceSources), style = PleosType.labelSmall)
        }
    }
    if (ui.insights.size > 1) Expandable("예비 진단과 비교", "${ui.insights.size}가지") { Bullets(ui.insights) }
}

@Composable
private fun ReportView(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    val r = ui.report
    if (r == null) {
        Empty()
        return
    }
    val sim = r.sim
    val m = r.monthly
    val hybrid = r.verdict == Verdict.HYBRID_BETTER
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Made-up weeks get no confidence rating: "가상" says what they are.
            if (ui.mode == Mode.DEMO && ui.realLabel == null) Badge("시연용 가상 기록 ${r.days}일", c.textPrimary, c.fuelSoft)
            else Badge("실제 기록 ${r.days}일 · 신뢰도 ${confidenceLabel(r.confidence)}", c.ev, c.evSoft)
            if (ui.realSource != null) {
                Spacer(Modifier.width(12.dp))
                Label(ui.realSource, style = PleosType.labelSmall)
            }
        }
        Spacer(Modifier.height(16.dp))
        Text("한 달 생활 그대로, ${ui.model.ifItWere}", style = PleosType.body, color = c.textSecondary)
        Text(r.verdict.titleFor(ui.settings.plan()), style = PleosType.headline, color = verdictColor(r.verdict))
        if (ui.realSource?.contains("VED") == true) {
            // The public dataset trims trip ends and keeps only Ann Arbor: it understates charging.
            Label("공개 데이터는 장거리가 빠져 있어 충전 부담이 가볍게 나와요.", style = PleosType.labelSmall)
        }
        Spacer(Modifier.height(24.dp))
        Row(Modifier.fillMaxWidth()) {
            BigNumber("%.0f번".format((m.homeCharges ?: 0.0) + (m.workCharges ?: 0.0) + m.fastCharges), "한 달 충전")
            BigNumber(m.savingWon?.let { "월 ${manwon(it)}" } ?: "-", if (hybrid) "전기차로 바꾸면 아끼는 기름값" else "아끼는 기름값", color = if (hybrid) c.textPrimary else c.ev)
            BigNumber("%.0f일".format(m.lowSocDays ?: 0.0), "배터리 20% 미만")
        }
        Spacer(Modifier.height(16.dp))
        Label(chargeBreakdown(m), style = PleosType.bodySmall)
        if (hybrid && m.hybridSavingWon != null) {
            Spacer(Modifier.height(8.dp))
            Text("하이브리드라면 충전 없이 월 ${manwon(m.hybridSavingWon)}을 아껴요.", style = PleosType.bodySmallStrong, color = c.textPrimary)
        }
    }
    ui.levers?.let { LeversCard(ui, it, actions) }
    ui.winter?.let { w ->
        fun charges(x: com.ifev.app.engine.Monthly) = (x.homeCharges ?: 0.0) + (x.workCharges ?: 0.0) + x.fastCharges
        Expandable(
            "1월이라면",
            "충전 %.0f번 · 판정 %s".format(charges(w.monthly), if (w.verdict == r.verdict) "그대로" else "'${w.verdict.titleFor(ui.settings.plan())}'"),
        ) { WinterCard(r, w, ui.settings.plan()) }
    }
    Expandable("예비 진단과 비교", ui.insights.firstOrNull()) {
        CompareTable(ui, r)
        Spacer(Modifier.height(16.dp))
        Bullets(ui.insights)
    }
    Expandable("현대 전기차 6종", modelSummary(ui, measured = true)) { ModelTable(ui, actions, measured = true) }
    Expandable("계산 기준", "기록한 ${r.days}일을 30일로 환산 · 2026년 8월 요금") {
        Label(
            "30일 기준은 기록한 ${r.days}일(운전한 날 ${r.drivingDays}일)을 늘린 값이에요. " +
                (if (m.homeCharges != null && r.steadyStateCharges < 0.05) ""
                else if (m.homeCharges != null) "시작(90%%)보다 낮게 끝난 배터리를 채우는 집 충전 %.1f번을 더했어요. ".format(r.steadyStateCharges)
                else "평소 급속은 100km 미만 주행에 쓴 전기를 30%→80% 충전량으로 나눈 값이고, 장거리 가는 길의 급속은 따로 셌어요. ") +
                "충전 요금은 2026년 8월 공공 충전요금, 휘발유는 오피넷 평균 기준. 실제 비용은 요금제와 운전 습관에 따라 달라요. " +
                "가장 낮을 때 배터리 ${(sim.minSoc * 100).roundToInt()}%.",
            style = PleosType.labelSmall,
        )
    }
}

/** "집 5 · 급속 1 · 급속 21분은 모두 원래 세워 두던 시간": what the charges are, in one line. */
private fun chargeBreakdown(m: com.ifev.app.engine.Monthly): String {
    val parts = mutableListOf<String>()
    m.homeCharges?.let { parts += "집 %.0f".format(it) }
    m.workCharges?.let { parts += "회사 %.0f".format(it) }
    if (m.homeCharges == null && m.fastEnRoute >= 0.05) {
        if (m.fastRoutine >= 0.05) parts += "평소 급속 %.1f".format(m.fastRoutine)
        parts += "장거리 날 급속 %.1f".format(m.fastEnRoute)
    } else if (m.fastCharges >= 0.5) parts += "급속 %.0f".format(m.fastCharges)
    val time = when {
        m.fastMinutes < 1 -> ""
        m.fastMinutesWhileParked >= m.fastMinutes - 0.5 -> " · 급속 %.0f분은 모두 원래 세워 두던 시간".format(m.fastMinutes)
        m.fastMinutesWhileParked >= 1 -> " · 급속 %.0f분 중 %.0f분은 원래 세워 두던 시간".format(m.fastMinutes, m.fastMinutesWhileParked)
        else -> " · 급속 충전 %.0f분".format(m.fastMinutes)
    }
    return "충전 " + parts.joinToString(" · ") + time
}

@Composable
private fun CompareTable(ui: UiState, r: com.ifev.app.engine.MeasuredReport) {
    val c = LocalPleosColors.current
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Label("30일 기준", modifier = Modifier.weight(1.2f))
        Label("설치 직후 예상", modifier = Modifier.weight(1f))
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

@Composable
private fun Empty() {
    val c = LocalPleosColors.current
    Card(Modifier.fillMaxWidth()) {
        Text("아직 기록된 주행이 없어요", style = PleosType.title, color = c.textPrimary)
        Spacer(Modifier.height(8.dp))
        Label("P단에서 출발하면 자동으로 기록돼요. 다른 앱을 보고 있어도 기록은 이어져요.", style = PleosType.bodySmall)
    }
}

/**
 * "What would change the answer": the same weeks replayed with one thing changed. Shows the two
 * changes that matter most, and asks about the office charger when the car stands there for hours.
 */
@Composable
private fun LeversCard(ui: UiState, l: com.ifev.app.engine.Levers.Result, actions: Actions) {
    val c = LocalPleosColors.current
    val p = l.parking
    Card(Modifier.fillMaxWidth()) {
        Text(if (l.improving) "판정을 바꾸는 한 가지" else "이 판정이 바뀌는 조건", style = PleosType.bodyStrong, color = c.textPrimary)
        val stays = listOfNotNull(
            p.homeDays.takeIf { it > 0 }?.let { "밤에 집 %.0f시간".format(p.homeMeanHours) },
            p.officeDays.takeIf { it > 0 }?.let { "평일 회사 %.0f시간".format(p.officeMeanHours) },
        )
        if (stays.isNotEmpty()) Label("이 차가 서 있던 시간(기어 P 기록): " + stays.joinToString(" · "), style = PleosType.labelSmall)
        Spacer(Modifier.height(8.dp))
        l.levers.take(2).forEachIndexed { i, lever ->
            if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(lever.change, style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
                Text("→ ", style = PleosType.bodySmall, color = c.textTertiary)
                Badge(shortVerdict(lever.verdict), verdictColor(lever.verdict), c.surfaceHigh)
            }
        }
        if (l.levers.isEmpty()) Label("기록 안에서는 판정을 바꿀 조건을 찾지 못했어요.")
        // Always answerable, and changeable later (a new job, a full charger).
        if (p.officeDays >= 2 || ui.settings.workCharging != null) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("회사 주차장에 완속 충전기가 있나요?", style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.weight(1f))
                PleosButton("있어요", ui.settings.workCharging == true, { actions.onWorkCharging(true) })
                Spacer(Modifier.width(12.dp))
                PleosButton("없어요", ui.settings.workCharging == false, { actions.onWorkCharging(false) })
            }
        }
    }
}

@Composable
private fun NaviCheckCard(n: NaviCheck, place: String?) {
    val c = LocalPleosColors.current
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("같은 경로, 두 계산", style = PleosType.bodyStrong, color = c.textPrimary)
            Spacer(Modifier.width(12.dp))
            Badge("실시간 · 이 차는 전기차", c.ev, c.evSoft)
            Spacer(Modifier.weight(1f))
            Label((place ?: "목적지") + " %,d km".format(n.routeKm.roundToInt()))
        }
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth()) {
            BigNumber("도착 %d%%".format(n.naviArrivalPct.roundToInt()), "현대차 내비 계산")
            BigNumber("도착 %d%%".format(n.ifevArrivalPct.roundToInt()), "이프EV 계산 (${n.modelName} 전비)", color = c.ev)
            BigNumber("%+d%%p".format(n.gapPct.roundToInt()), if (n.gapPct < 0) "이프EV가 더 보수적" else "이프EV가 더 낙관적")
        }
    }
}

@Composable
private fun WinterCard(now: com.ifev.app.engine.MeasuredReport, w: com.ifev.app.engine.MeasuredReport, plan: com.ifev.app.engine.ChargingPlan) {
    val c = LocalPleosColors.current
    val n = now.monthly
    val m = w.monthly
    fun charges(x: com.ifev.app.engine.Monthly) = (x.homeCharges ?: 0.0) + (x.workCharges ?: 0.0) + x.fastCharges
    Label("같은 기록을 서울 1월 평년 %.1f℃로 다시 계산했어요.".format(com.ifev.app.engine.SeoulClimate.meanC(1)))
    Spacer(Modifier.height(16.dp))
    Row(Modifier.fillMaxWidth()) {
        val nowN = charges(n).roundToInt()
        val janN = charges(m).roundToInt()
        BigNumber("${janN}번", "한 달 충전 (지금 ${nowN}번)")
        BigNumber("%.0f일".format(m.lowSocDays ?: 0.0), "배터리 20%% 미만 (지금 %.0f일)".format(n.lowSocDays ?: 0.0))
        BigNumber(m.savingWon?.let { "월 ${manwon(it)}" } ?: "-", "아끼는 기름값", color = c.ev)
        BigNumber(if (w.verdict == now.verdict) "그대로" else "바뀜", w.verdict.titleFor(plan), color = verdictColor(w.verdict))
    }
}

@Composable
private fun ScoreCard(s: com.ifev.app.engine.Moments.PredictionScore) {
    val c = LocalPleosColors.current
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("예측 성적표", style = PleosType.bodyStrong, color = c.textPrimary)
            Spacer(Modifier.width(12.dp))
            Badge("이 차는 전기차 · 차 배터리로 채점", c.ev, c.evSoft)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("실제로 쓴 전기", "%.1f kWh".format(s.actualKwh), "주행 ${s.trips}번 · 차 배터리 기준")
            Stat("이프EV 예측", "%.1f kWh".format(s.predictedKwh), "${s.modelName} 기준", color = c.ev)
            Stat("오차", "%+.0f%%".format(s.errorPct), if (s.errorPct > 0) "이프EV가 더 보수적" else "이프EV가 더 낙관적")
        }
    }
}

// ───────────────────────────── text ─────────────────────────────

@Composable
private fun verdictColor(v: Verdict) = LocalPleosColors.current.let {
    when (v) {
        Verdict.GOOD -> it.ev
        Verdict.CONDITIONAL -> it.fuel
        // An honest answer, not an error: no alarm red.
        Verdict.HYBRID_BETTER -> it.textPrimary
        Verdict.UNKNOWN -> it.textPrimary
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



// ───────────────────────────── vehicle-specific pieces ─────────────────────────────

/** The day-one inputs the car gave without asking: the reason this is a car app, not a phone calculator. */
@Composable
private fun CarFacts(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        ui.carFacts.forEach { f ->
            Column(Modifier.weight(1f)) {
                Label(f.label, style = PleosType.labelSmall)
                Text(f.value ?: "이 차는 주지 않음", style = PleosType.bodySmallStrong, color = if (f.value != null) c.textPrimary else c.textTertiary)
                Label(f.api, style = PleosType.labelSmall)
            }
        }
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
    if (ui.fuelSource == FuelSource.CERTIFIED) {
        Spacer(Modifier.height(16.dp))
        FuelCarRow(ui, actions)
    }
    Spacer(Modifier.height(12.dp))
    Label(fuelCaption(ui) + " · 앞으로 읽는 신호: " + ui.liveSignals.joinToString(" · ") { "${it.label} ${it.value ?: "없음"}" }, style = PleosType.labelSmall)
}

private fun carFactsSummary(ui: UiState): String {
    val read = ui.carFacts.count { it.value != null }
    val shown = ui.carFacts.filter { it.value != null }.take(2).joinToString(" · ") { it.value!! }
    return "입력 없이 ${read}/${ui.carFacts.size}개" + if (shown.isNotEmpty()) " · $shown" else ""
}

/** "6종 모두 '충분해요'" or how many models fit, for the folded table. */
private fun modelSummary(ui: UiState, measured: Boolean): String {
    val verdicts = ui.modelRows.map { if (measured && it.measured != null) it.measured.verdict else it.prelim.verdict }
    val distinct = verdicts.toSet()
    if (distinct.size == 1) return "6종 모두 '${shortVerdict(distinct.first())}'"
    return distinct.sortedByDescending { com.ifev.app.engine.Levers.rank(it) }
        .joinToString(" · ") { v -> "${shortVerdict(v)} ${verdicts.count { it == v }}종" }
}

/** Every Hyundai EV on the same facts (day one) or the same recorded trips (measured). Tap a row to switch. */
@Composable
private fun ModelTable(ui: UiState, actions: Actions, measured: Boolean) {
    val c = LocalPleosColors.current
    Column(Modifier.fillMaxWidth()) {
        val verdicts = ui.modelRows.map { if (measured && it.measured != null) it.measured.verdict else it.prelim.verdict }.toSet()
        val sameVerdict = verdicts.size == 1
        if (sameVerdict && verdicts.first() != Verdict.UNKNOWN) {
            Spacer(Modifier.height(4.dp))
            Label("판정은 같고, 차이는 충전 횟수와 먼 길에서 나요.")
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Label("차종", modifier = Modifier.weight(1.5f))
            if (!sameVerdict) Label("판정", modifier = Modifier.weight(1.3f))
            Label(if (measured) "30일 충전" else "충전", modifier = Modifier.weight(1f))
            Label(if (measured) "20% 미만" else "가장 먼 목적지 (지금/1월)", modifier = Modifier.weight(1.2f))
            Label(if (measured) "월 절감" else "연 절감", modifier = Modifier.weight(1f))
        }
        ui.modelRows.forEach { row ->
            val on = row.model == ui.model
            val p = row.prelim
            val m = row.measured
            Box(Modifier.fillMaxWidth().height(1.dp).background(c.divider))
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(if (on) c.evSoft else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { actions.onModel(row.model) }
                    .padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "${row.model.name} ${row.model.trim.substringBefore(" ")}" + if (row.model.estimated) " *" else "",
                    style = PleosType.bodySmallStrong, color = c.textPrimary, modifier = Modifier.weight(1.5f),
                )
                val verdict = if (measured && m != null) m.verdict else p.verdict
                if (!sameVerdict) Text(shortVerdict(verdict), style = PleosType.bodySmall, color = verdictColor(verdict), modifier = Modifier.weight(1.3f))
                val charge = if (measured && m != null) {
                    if (m.monthly.homeCharges != null) "집 %.0f · 급속 %.0f".format(m.monthly.homeCharges, m.monthly.fastCharges)
                    else "급속 평소 %.1f · 길 위 %.0f".format(m.monthly.fastRoutine, m.monthly.fastEnRoute)
                } else {
                    p.homeChargesPerWeek?.let { "집 주 %.1f회".format(it) } ?: p.fastChargesPerMonth?.let { "급속 월 %.1f회".format(it) } ?: "-"
                }
                Text(charge, style = PleosType.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1f))
                val far = if (measured && m != null) "%.0f일".format(m.monthly.lowSocDays ?: 0.0)
                else if (row.farthestStopsNow != null) "${stopsShort(row.farthestStopsNow)} / ${stopsShort(row.farthestStopsWinter ?: 0)}" else "-"
                Text(far, style = PleosType.bodySmall, color = c.textSecondary, modifier = Modifier.weight(1.2f))
                val saving = if (measured && m != null) m.monthly.savingWon?.let { manwon(it) }
                else p.annualSavingLow?.let { low -> p.annualSavingHigh?.let { high -> manwonRange(low, high) } }
                Text(saving ?: "-", style = PleosType.bodySmall, color = c.ev, modifier = Modifier.weight(1f))
            }
        }
        Spacer(Modifier.height(8.dp))
        Label("* 인증값 일부를 추정한 차종 · 행을 누르면 그 차종으로 바뀌어요", style = PleosType.labelSmall)
    }
}

private fun shortVerdict(v: Verdict) = when (v) {
    Verdict.GOOD -> "충분해요"
    Verdict.CONDITIONAL -> "급속 생활 가능"
    Verdict.HYBRID_BETTER -> "하이브리드 권장"
    Verdict.UNKNOWN -> "-"
}

private fun stopsShort(n: Int) = if (n == 0) "충전 없이" else "급속 ${n}회"

private fun fuelCaption(ui: UiState): String = when (ui.fuelSource) {
    FuelSource.CAR -> "차가 기록한 평균 %.1fkm/L와 비교".format(ui.fuelKmPerL)
    FuelSource.CERTIFIED -> "${ui.myCar?.label} 공인 %.1fkm/L와 비교 (차가 연비를 주지 않음)".format(ui.fuelKmPerL)
    FuelSource.NONE -> "이 차는 연비를 주지 않아요. 지금 타는 차를 골라 주세요"
}

/** Which signal each recorded trip took its distance from; on a real car this shows whether the odometer works. */
private fun distanceSourceLine(sources: Map<DistanceSource, Int>): String {
    val parts = listOfNotNull(
        sources[DistanceSource.ODOMETER]?.let { "누적 주행거리 $it" },
        sources[DistanceSource.SPEED]?.let { "차속 적분 $it" },
        sources[DistanceSource.TRACK]?.let { "내비 위치 $it" },
        sources[DistanceSource.UNKNOWN]?.let { "출처 미기록 $it" },
    )
    return "거리 출처: " + parts.joinToString(" · ") + "번"
}
