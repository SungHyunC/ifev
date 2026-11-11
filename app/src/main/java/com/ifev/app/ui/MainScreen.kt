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
)

@Composable
fun MainScreen(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Column(Modifier.fillMaxSize().background(c.background).padding(horizontal = 40.dp, vertical = 24.dp)) {
        TopBar(ui, actions)
        // While the car is out of P there is nothing to switch or press.
        if (ui.mode == Mode.DEMO && !ui.car.driving) {
            Spacer(Modifier.height(16.dp))
            DemoBar(ui, actions)
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
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            StatusDot("차량 ${ui.car.vehicleStatus}", ui.car.vehicleStatus == "연결됨" || ui.mode == Mode.DEMO)
            StatusDot("내비 ${ui.car.naviStatus}", ui.car.naviStatus == "연결됨" || ui.mode == Mode.DEMO)
        }
        if (!ui.car.driving) {
            Spacer(Modifier.width(24.dp))
            PleosButton(if (ui.settings.voiceOn) "음성 안내 켜짐" else "음성 안내 꺼짐", ui.settings.voiceOn, actions.onVoice)
            Spacer(Modifier.width(16.dp))
            Segmented(listOf("실시간", "시연"), if (ui.mode == Mode.LIVE) 0 else 1, { actions.onMode(if (it == 0) Mode.LIVE else Mode.DEMO) })
        } else if (ui.mode == Mode.DEMO) {
            Spacer(Modifier.width(24.dp))
            Badge("시연 · 가상 주행", c.textPrimary, c.fuelSoft)
        }
    }
}

@Composable
private fun DemoBar(ui: UiState, actions: Actions) {
    val stage = ui.stage
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Label("시연 시나리오 (가상 데이터)", modifier = Modifier.padding(end = 8.dp))
        PleosButton("① 설치 직후", stage == Stage.DAY_ZERO, actions.onDemoDayZero)
        PleosButton("② 출근길 주행", stage == Stage.DRIVING, actions.onDemoDrive)
        PleosButton("③ 4주 뒤", stage == Stage.FOUR_WEEKS, actions.onDemoFourWeeks)
        ui.realDriveLabels.forEachIndexed { i, label ->
            PleosButton("④ 실제 기록 · $label", ui.realLabel == label, { actions.onReal(i) })
        }
        Label("시연의 2~4주 기록은 가상 데이터예요. 실시간 모드의 기록만 실제 주행이에요.", style = PleosType.labelSmall, modifier = Modifier.padding(start = 8.dp))
    }
}

// ───────────────────────────── driving ─────────────────────────────

/**
 * While driving: the imagined battery is the one big number, the real fuel gauge sits beside it, and
 * there is nothing to touch. The route card appears when the navigation has a destination.
 */
@Composable
private fun DrivingView(ui: UiState) {
    val c = LocalPleosColors.current
    val homeWon = ui.tripKwh * Prices().homePerKwh
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
                    Text("집 충전 약 %,d원".format(homeWon.roundToInt()), style = PleosType.body, color = c.textSecondary)
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
                    "이번 주행 %.1fkm · 전기 %.1fkWh · 집 충전 약 %,d원".format(ui.car.tripKm, ui.tripKwh, homeWon.roundToInt()),
                    style = PleosType.bodyStrong,
                    color = c.textPrimary,
                )
            }
        }
    }
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

@Composable
private fun ParkedView(ui: UiState, actions: Actions) {
    val days = ui.report?.days ?: 0
    ui.naviCheck?.let {
        NaviCheckCard(it, ui.car.route?.name)
        Spacer(Modifier.height(16.dp))
    }
    ModelPicker(ui, actions)
    Spacer(Modifier.height(16.dp))
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
            Tab.REPORT -> ReportView(ui, actions)
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PrelimView(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    val p = ui.prelim
    Card(Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Badge("예비 진단", c.textSecondary, c.surfaceHigh)
            Badge("차와 내비에 이미 있던 기록 · 4주 기록하면 확정", c.textSecondary, c.surfaceHigh)
        }
        Spacer(Modifier.height(16.dp))
        val title = when {
            p.verdict != Verdict.UNKNOWN -> p.verdict.title
            ui.settings.homeCharging == null -> "집에서 충전할 수 있는지 알려 주세요"
            else -> "주행거리를 알면 바로 계산할게요"
        }
        Text(title, style = PleosType.headline, color = verdictColor(p.verdict))
        Spacer(Modifier.height(8.dp))
        Text(prelimChargeLine(ui), style = PleosType.body, color = c.textSecondary)
    }
    Questions(ui, actions)
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
            fuelCaption(ui),
            color = c.ev,
        )
    }
    CarFacts(ui)
    ModelTable(ui, actions, measured = false)
    if (p.destinations.isNotEmpty()) {
        Card(Modifier.fillMaxWidth()) {
            Text("최근 목적지, ${ui.model.shortLabel}로 다녀온다면", style = PleosType.bodyStrong, color = c.textPrimary)
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
        if (ui.fuelSource != FuelSource.CAR) {
            Spacer(Modifier.height(16.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("지금 타는 차는?", style = PleosType.bodySmallStrong, color = c.textPrimary)
                    Label("이 차는 연비를 알려 주지 않아요. 공인 복합연비로 대신 계산해요.", style = PleosType.labelSmall)
                }
                FuelCarCatalog.all.forEach { car ->
                    Spacer(Modifier.width(12.dp))
                    PleosButton("${car.label} ${car.certifiedKmPerL}km/L", ui.myCar == car, { actions.onMyCar(car) })
                }
            }
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
        Spacer(Modifier.height(4.dp))
        Label(distanceSourceLine(r.distanceSources), style = PleosType.labelSmall)
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
private fun ReportView(ui: UiState, actions: Actions) {
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
            if (ui.realLabel != null) Badge("실제 주행 기록 · 폰 GPS로 수집 (${ui.realLabel})", c.ev, c.evSoft)
            else if (ui.mode == Mode.DEMO) Badge("시연용 가상 데이터", c.textPrimary, c.fuelSoft)
        }
        Spacer(Modifier.height(16.dp))
        Text("지난 30일, 이 차가 ${ui.model.ifItWere}", style = PleosType.title, color = c.textSecondary)
        Spacer(Modifier.height(8.dp))
        Text(r.verdict.title, style = PleosType.headline, color = verdictColor(r.verdict))
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat(
                "충전",
                "%.0f번".format((m.homeCharges ?: 0.0) + m.fastCharges),
                if (m.homeCharges != null) "집 %.0f · 급속 %.0f".format(m.homeCharges, m.fastCharges)
                else "평소 급속 %.1f · 장거리 길 위 %.1f".format(m.fastRoutine, m.fastEnRoute),
            )
            Stat(
                "배터리 20% 미만",
                "%.0f일".format(m.lowSocDays ?: 0.0),
                (if (sim.lowSocDays > 0 && r.routineLowDays == 0) "모두 장거리 날 · " else "") + "가장 낮을 때 ${(sim.minSoc * 100).roundToInt()}%",
            )
            Stat("연료비 절감", m.savingWon?.let { "월 ${manwon(it)}" } ?: "-", m.savingWon?.let { "연 약 ${manwon(it * 12)}" }, color = c.ev)
            Stat("급속 충전 시간", "%.0f분".format(m.fastMinutes), "30일 기준, 대기 포함")
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
    ModelTable(ui, actions, measured = true)
    Label(
        "30일 기준은 기록한 ${r.days}일(운전한 날 ${r.drivingDays}일)을 늘린 값이에요. " +
            (if (m.homeCharges != null) "시작(90%%)보다 낮게 끝난 배터리를 채우는 집 충전 %.1f번을 더했어요. ".format(r.steadyStateCharges)
            else "평소 급속은 100km 미만 주행에 쓴 전기를 30%→80% 충전량으로 나눈 값이고, 장거리 가는 길의 급속은 따로 셌어요. ") +
            "충전 요금은 2026년 8월 공공 충전요금, 휘발유는 오피넷 평균 기준. 실제 비용은 요금제와 운전 습관에 따라 달라요.",
        style = PleosType.labelSmall,
    )
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
 * The live check: the car is an EV, so Hyundai's navigation estimates its arrival battery itself.
 * IfEV's energy model, applied to the same route and this car's battery, sits next to it.
 */
@Composable
private fun NaviCheckCard(n: NaviCheck, place: String?) {
    val c = LocalPleosColors.current
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("같은 경로, 두 계산", style = PleosType.bodyStrong, color = c.textPrimary)
            Spacer(Modifier.width(12.dp))
            Badge("실시간 · 이 차는 전기차", c.ev, c.evSoft)
            Spacer(Modifier.weight(1f))
            Label((place ?: "목적지") + " %,d km · 출발 배터리 %d%% · 이 차 배터리 %.1fkWh".format(n.routeKm.roundToInt(), n.startSocPct.roundToInt(), n.capacityKwh))
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Stat("현대차 내비 계산", "도착 %d%%".format(n.naviArrivalPct.roundToInt()), n.naviKmPerKwh?.let { "전비 %.1f km/kWh".format(it) } ?: "-")
            Stat(
                "이프EV 계산",
                "도착 %d%%".format(n.ifevArrivalPct.roundToInt()),
                "전비 %.1f km/kWh".format(n.ifevKmPerKwh) +
                    if (n.ifevArrivalPctNoClimate.roundToInt() != n.ifevArrivalPct.roundToInt()) " · 공조 끄면 %d%%".format(n.ifevArrivalPctNoClimate.roundToInt()) else "",
                color = c.ev,
            )
            Stat(
                "차이",
                "%+d%%p".format(n.gapPct.roundToInt()),
                if (n.gapPct < 0) "이프EV가 더 보수적" else "이프EV가 더 낙관적",
            )
        }
        Spacer(Modifier.height(8.dp))
        Label(
            "이프EV는 ${n.modelName} 전비를 이 차 배터리에 적용하고, 서울 이달 평년 %.1f℃·공조 사용을 넣었어요. 이 차의 인증 주행거리는 SDK로 읽을 수 없어요.".format(n.tempC),
            style = PleosType.labelSmall,
        )
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

private fun prelimChargeLine(ui: UiState): String {
    val p = ui.prelim
    val weekly = p.weeklyKm?.let { "한 주 약 %,d km".format(it.roundToInt()) }
    return when {
        weekly == null && ui.car.home != null && ui.car.office != null ->
            "내비에 집과 회사가 같은 곳으로 저장돼 있고 누적 주행거리도 아직 없어요. 주행이 기록되면 실측 탭에서 계산해요."
        weekly == null -> "누적 주행거리와 집·회사를 아직 몰라요. 주행이 기록되면 실측 탭에서 계산해요."
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



// ───────────────────────────── vehicle-specific pieces ─────────────────────────────

/** The day-one inputs the car gave without asking: the reason this is a car app, not a phone calculator. */
@Composable
private fun CarFacts(ui: UiState) {
    val c = LocalPleosColors.current
    val read = ui.carFacts.count { it.value != null }
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("입력 없이 차와 내비에서 읽은 기록", style = PleosType.bodyStrong, color = c.textPrimary)
            Spacer(Modifier.width(12.dp))
            Badge("${read}/${ui.carFacts.size}", c.ev, c.evSoft)
            Spacer(Modifier.weight(1f))
            Label(if (ui.fuelSource == FuelSource.CAR) "물어보는 것은 집 충전 가능 여부 하나예요" else "물어보는 것: 집 충전 여부, 지금 타는 차")
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            ui.carFacts.forEach { f ->
                Column(Modifier.weight(1f)) {
                    Label(f.label, style = PleosType.labelSmall)
                    Text(f.value ?: "이 차는 주지 않음", style = PleosType.bodySmallStrong, color = if (f.value != null) c.textPrimary else c.textTertiary)
                    Label(f.api, style = PleosType.labelSmall)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Label(
            "앞으로 실시간으로 읽는 신호: " + ui.liveSignals.joinToString(" · ") { "${it.label} ${it.value ?: "없음"}" },
            style = PleosType.labelSmall,
        )
    }
}

@Composable
private fun ModelPicker(ui: UiState, actions: Actions) {
    val c = LocalPleosColors.current
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label("비교할 현대 전기차", modifier = Modifier.padding(end = 6.dp))
        EvCatalog.all.forEach { m ->
            val on = m == ui.model
            Box(
                Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) c.evSoft else c.surface)
                    .clickable { actions.onModel(m) }
                    .padding(horizontal = 20.dp, vertical = 12.dp),
            ) {
                Text(
                    "${m.name} ${m.batteryKwh.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }}kWh",
                    style = if (on) PleosType.labelStrong else PleosType.label,
                    color = if (on) c.ev else c.textSecondary,
                )
            }
        }
    }
}

/** Every Hyundai EV on the same facts (day one) or the same recorded trips (measured). Tap a row to switch. */
@Composable
private fun ModelTable(ui: UiState, actions: Actions, measured: Boolean) {
    val c = LocalPleosColors.current
    Card(Modifier.fillMaxWidth()) {
        Text(if (measured) "같은 30일, 현대 전기차별로 보면" else "현대 전기차별로 보면", style = PleosType.bodyStrong, color = c.textPrimary)
        val verdicts = ui.modelRows.map { if (measured && it.measured != null) it.measured.verdict else it.prelim.verdict }.toSet()
        val sameVerdict = verdicts.size == 1
        if (sameVerdict && verdicts.first() != Verdict.UNKNOWN) {
            Spacer(Modifier.height(4.dp))
            Label("판정은 6종 모두 '${verdicts.first().title}'. 차이는 충전 횟수와 먼 길에서 나요.")
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
        Label("* 공개 인증값이 일부 없어 추정한 차종이에요. 계산 근거에 어떤 값을 추정했는지 적어 두었어요. 행을 누르면 그 차종으로 바뀌어요.", style = PleosType.labelSmall)
    }
}

private fun shortVerdict(v: Verdict) = when (v) {
    Verdict.GOOD -> "집 충전이면 충분"
    Verdict.CONDITIONAL -> "급속 충전 필요"
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
