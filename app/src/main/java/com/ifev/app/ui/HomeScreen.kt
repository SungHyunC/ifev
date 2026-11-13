package com.ifev.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.ifev.app.MainViewModel.Mode
import com.ifev.app.MainViewModel.UiState
import com.ifev.app.data.GearState
import com.ifev.app.data.TurnState
import androidx.compose.ui.graphics.Color
import com.ifev.app.engine.EnergyModel
import com.ifev.app.engine.SeoulClimate
import com.ifev.app.engine.ifItWere
import com.ifev.app.ui.topview.CarTopView
import com.ifev.app.ui.topview.SteeringConfig
import com.ifev.app.ui.topview.TopViewInput
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Main screen: the imagined EV seen from above, moving with this car's signals, and the same story in
 * numbers beside it. While the car is out of P there is nothing to press and the view redraws at most
 * once a second (CarUxRestrictions NO_VIDEO); parked, it animates and the report is one tap away.
 */
@Composable
fun HomeScreen(ui: UiState, visual: com.ifev.app.MainViewModel.VisualNow, actions: Actions, canReplay: Boolean = false, replaying: Boolean = false) {
    val c = LocalPleosColors.current
    val driving = ui.car.driving
    // Touch targets only when the real car is safely parked (and no demo commute is playing).
    val locked = ui.locked
    if (ui.mode == Mode.DEMO && !locked) {
        DemoBar(ui, actions)
        Spacer(Modifier.height(16.dp))
    }
    ui.lastLine?.let { SpokenLine(it); Spacer(Modifier.height(16.dp)) }
    val v = visual.state
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        // ── the virtual EV ──
        val steering = if (ui.mode == Mode.DEMO) SteeringConfig() else liveSteering
        Box(
            Modifier.weight(1.45f).fillMaxHeight()
                .clip(RoundedCornerShape(24.dp))
                .background(c.background)
                .border(1.dp, c.divider, RoundedCornerShape(24.dp)),
        ) {
            CarTopView(
                input = TopViewInput(
                    speedMs = v.speedMs,
                    speedAtMs = v.speedAtMs,
                    gear = v.gear,
                    steeringDeg = v.steeringDeg,
                    brake = v.brake == true,
                    turn = v.turn,
                    climateOn = v.climateOn == true,
                    socFraction = ui.ghostSoc,
                    regenLevel = visual.regenLevel,
                    consumedFraction = (ui.startSoc - ui.ghostSoc).coerceAtLeast(0.0),
                    restricted = v.restricted,
                ),
                modifier = Modifier.fillMaxSize(),
                steering = steering,
            )
            Column(Modifier.padding(32.dp)) {
                Text(ui.model.ifItWere, style = PleosType.title, color = c.textPrimary)
                Spacer(Modifier.height(14.dp))
                // What the car is doing, in words: only the states that are on.
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Chip(
                        when {
                            ui.mode == Mode.DEMO && driving -> "시연 · 가상 주행"
                            replaying -> "다시 보기 · 2배속"
                            v.restricted -> "주행 중 · 1초에 한 번 갱신"
                            v.gear == GearState.PARK -> "정차 중"
                            else -> gearLabel(v.gear)
                        },
                        c.textTertiary, c.surfaceHigh,
                    )
                    if (v.brake == true) Chip("브레이크", c.negative, Color(0xFF2A0F16))
                    when (v.turn) {
                        TurnState.LEFT -> Chip("왼쪽 방향지시등", c.fuel, c.fuelSoft)
                        TurnState.RIGHT -> Chip("오른쪽 방향지시등", c.fuel, c.fuelSoft)
                        TurnState.OFF -> {}
                    }
                    v.steeringDeg?.let { steering.frontWheelDeg(it) }?.takeIf { abs(it) >= 2 }?.let { deg ->
                        Chip("조향 ${if (deg < 0) "왼쪽" else "오른쪽"} ${abs(deg).roundToInt()}°", c.textTertiary, c.surfaceHigh)
                    }
                }
            }
            if (!locked) {
                // The report, one tap away while parked (hidden whenever the car is not safely in P).
                Box(
                    Modifier.align(Alignment.CenterStart).padding(start = 20.dp).size(72.dp)
                        .clip(CircleShape).background(c.surface).border(1.dp, c.divider, CircleShape)
                        .clickable(onClick = actions.onOpenReport),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("‹", style = PleosType.titleLarge, color = c.textSecondary)
                }
            }
        }
        // ── the same in numbers ──
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Label("가상 배터리", style = PleosType.body)
                Text("${(ui.ghostSoc * 100).roundToInt()}%", style = PleosType.headline, color = if (ui.ghostSoc < 0.3) c.fuel else c.ev)
                Spacer(Modifier.height(10.dp))
                LevelBar(ui.ghostSoc, if (ui.ghostSoc < 0.3) c.fuel else c.ev, height = 20)
                Spacer(Modifier.height(10.dp))
                Label(if (ui.chargedWhileParked) "세워 둔 사이 충전하고 ${(ui.startSoc * 100).roundToInt()}%에서 출발" else "출발할 때 ${(ui.startSoc * 100).roundToInt()}%")
            }
            NumberRow("이번 주행", if (ui.car.tripKm > 0) "%.1f km · %.1f kWh".format(ui.car.tripKm, ui.tripKwh) else "-",
                if (ui.car.tripKm > 0) "${ui.settings.plan().everydayCharger} 약 %,d원".format((ui.tripKwh * ui.settings.plan().everydayPerKwh()).roundToInt()) else "출발하면 셉니다")
            NumberRow(
                "감속 때 되찾은 전기",
                if (ui.tripRegenKwh >= 0.01) "%.2f kWh".format(ui.tripRegenKwh) else "-",
                if (visual.regenLevel > 0.05) "지금 회생 중" else "모터가 되찾은 몫 · 추정",
                highlight = visual.regenLevel > 0.05,
            )
            NumberRow("공조", climateLine(v), climateCaption(ui))
            if (driving || replaying || (v.speedMs ?: 0.0) > 0.3) {
                NumberRow("속도", v.speedMs?.let { "%d km/h".format(abs(it * 3.6).roundToInt()) } ?: "-", gearLabel(v.gear))
            }
            ui.route?.let { r ->
                NumberRow((ui.car.route?.name ?: "목적지") + " 도착", "${(r.arrivalSoc * 100).roundToInt()}%",
                    "%,d km · ".format(r.distanceKm.roundToInt()) + if (r.stops > 0) "급속 ${r.stops}번" else "충전 없이")
            }
            Spacer(Modifier.weight(1f))
            if (!locked) {
                val verdict = ui.report?.verdict ?: ui.prelim.verdict
                Card(Modifier.fillMaxWidth()) {
                    Label(if (ui.report != null) "기록한 ${ui.report.days}일 판정" else "설치 직후 예비 판정", style = PleosType.body)
                    Text(verdict.titleFor(ui.settings.plan()), style = PleosType.bodyStrong, color = c.textPrimary)
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        if (canReplay && ui.mode != Mode.DEMO) PleosButton(if (replaying) "다시 보는 중" else "방금 주행 다시 보기", false, actions.onReplay, Modifier.weight(1f))
                        PleosButton("리포트 보기", true, actions.onOpenReport, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color, background: Color) {
    Text(
        text,
        style = PleosType.labelStrong,
        color = color,
        modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(background).padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

@Composable
private fun NumberRow(title: String, value: String, caption: String?, highlight: Boolean = false) {
    val c = LocalPleosColors.current
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(24.dp)).background(c.surface)
            .padding(horizontal = 32.dp, vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Label(title, style = PleosType.body, modifier = Modifier.width(250.dp))
        Column(Modifier.weight(1f)) {
            Text(value, style = PleosType.title, color = if (highlight) c.ev else c.textPrimary, maxLines = 1)
            if (caption != null) Text(caption, style = PleosType.label, color = c.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

private fun gearLabel(g: GearState) = when (g) {
    GearState.PARK -> "P"
    GearState.REVERSE -> "R"
    GearState.NEUTRAL -> "N"
    GearState.DRIVE -> "D"
    GearState.UNKNOWN -> "기어 신호 대기"
}

/** "켜짐 · 5.6 km/kWh (끄면 6.4)": the climate's cost at this month's normal temperature. */
private fun climateLine(v: com.ifev.app.data.VehicleVisualState): String {
    val on = v.climateOn ?: return "-"
    return if (on) "켜짐" else "꺼짐"
}

private fun climateCaption(ui: UiState): String? {
    val month = LocalDate.now().monthValue
    val temp = SeoulClimate.meanC(month)
    val speed = ui.car.tripKm.takeIf { it > 0 }?.let { 30.0 } ?: 30.0
    val onKm = 1 / EnergyModel.kwhPerKm(ui.model, temp, speed, 1.0)
    val offKm = 1 / EnergyModel.kwhPerKm(ui.model, temp, speed, 0.0)
    return "전비 %.1f km/kWh · 끄면 %.1f".format(onKm, offKm)
}

/**
 * How this car's steering value is read. The Pleos SDK documents a front road-wheel angle, left
 * negative, and real cars get exactly that. The emulator does not: its Virtual Console's −150° arrived
 * through the SDK as +150 (2026-11-12), a hand-wheel range with the sign reversed. So on the emulator
 * only: hand wheel, 15:1, mirrored (−150 → −10° left).
 */
private val isEmulator: Boolean = android.os.Build.HARDWARE == "ranchu" || android.os.Build.HARDWARE == "goldfish" ||
    android.os.Build.PRODUCT.contains("avd") || android.os.Build.FINGERPRINT.contains("emulator")

private val liveSteering: SteeringConfig =
    if (isEmulator) SteeringConfig(kind = SteeringConfig.Kind.HAND_WHEEL, sign = -1, ratio = 15.0) else SteeringConfig()
