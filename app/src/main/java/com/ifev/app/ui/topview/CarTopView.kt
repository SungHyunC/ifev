package com.ifev.app.ui.topview

import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Constraints
import com.ifev.app.data.GearState
import com.ifev.app.data.TurnState
import com.ifev.app.ui.IfEvTheme
import com.ifev.app.ui.LocalPleosColors
import com.ifev.app.ui.PleosColors
import com.ifev.app.ui.PleosType
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive

/**
 * The virtual EV seen straight from above, drawn as a neon outline (cyan line plus glow, like the app icon)
 * on a transparent background: the caller draws the card. Android Canvas only (paths, strokes, a blur mask
 * filter for the glow, radial gradients for lamp light), no extra library; a few dozen draw calls per frame.
 *
 * What it shows: front wheels steer ([steering] says how to read the raw value), tread lines run in the
 * wheels, lane dashes flow beside the car, red brake lamp lines with a red glow, amber turn signal arcs at
 * the nose and tail corner (1.5 Hz), a restrained battery gauge in the glass roof (cyan, amber below 30 %,
 * the part used on this trip as empty cells above the fill), regeneration as light particles running from
 * the front wheels to the middle of the car, a faint airflow in the cabin while the climate system is on,
 * and a dimmed picture while the car is "waiting for signal". The text for that state is the caller's: pass
 * [onModeChange] to hear about it.
 *
 * [routeLabel], when not null, is a small pill at the top of the view.
 *
 * `input.restricted` (the car restricts the screen while driving): the picture is refreshed at most once a
 * second and nothing in it moves: tread lines and lane dashes at rest, turn signals steady on,
 * regeneration at a steady strength, no airflow motion. Otherwise it runs at the display's frame rate while
 * something moves, and only when something changes while nothing does.
 *
 * No touch handling. The look is the same by day and night (the colours are fixed in [NeonColors]).
 */
@Composable
fun CarTopView(
    input: TopViewInput,
    modifier: Modifier = Modifier,
    steering: SteeringConfig = SteeringConfig(),
    routeLabel: String? = null,
    /** Called when the discrete part of the display state changes (mode, signal lost, P-with-speed conflict). */
    onModeChange: ((VisualState) -> Unit)? = null,
) {
    val colors = LocalPleosColors.current
    val latestState = rememberUpdatedState(input)
    val latest by latestState
    val modeCallback by rememberUpdatedState(onModeChange)
    val smoother = remember { VisualSmoother(steering) }
    SideEffect { smoother.steering = steering }
    val scene = remember { NeonScene() }
    val painter = remember { NeonPainter() }
    val frame = remember { FrameData() }
    val tick = remember { mutableLongStateOf(0L) }
    val measurer = rememberTextMeasurer()
    val label = remember { LabelCache() }
    val preview = LocalInspectionMode.current
    val restricted = input.restricted

    LaunchedEffect(restricted) {
        if (restricted) {
            // At most one picture per second, in step with the frame clock; nothing in it is animated.
            while (isActive) {
                withFrameNanos {
                    val now = SystemClock.elapsedRealtime()
                    val inp = latest
                    val st = smoother.step(inp, now)
                    frame.announce(st, modeCallback)
                    frame.publish(st, inp, false, now)
                    tick.longValue++
                }
                delay(RESTRICTED_FRAME_MS)
            }
        } else {
            while (isActive) {
                val busy = withFrameNanos {
                    val now = SystemClock.elapsedRealtime()
                    val inp = latest
                    val st = smoother.step(inp, now)
                    frame.announce(st, modeCallback)
                    val moving = needsFrames(st, inp)
                    val changed = !frame.sameLook(st, inp, true)
                    frame.publish(st, inp, true, now)
                    if (moving || changed) tick.longValue++
                    moving
                }
                // Nothing moves: look again shortly instead of drawing 60 identical frames a second.
                if (!busy) delay(IDLE_POLL_MS)
            }
        }
    }

    // The draw block is remembered, so a parent that recomposes with every new sample cannot make this view
    // draw again: it draws for a new frame (the tick), a new size or a new theme, and for nothing else.
    val onDraw: DrawScope.() -> Unit = remember(scene, painter, smoother, frame, tick, measurer, label, colors, routeLabel, preview) {
        {
            // Reading the tick makes this block run again for every new frame.
            if (tick.longValue >= 0L) {
                scene.layout(size.width, size.height)
                if (!frame.ready && preview) {
                    val inp = latestState.value
                    frame.publish(smoother.step(inp, 0L), inp, false, 0L)
                }
                if (frame.ready && scene.ready) {
                    scene.update(frame.state, frame.input, frame.animated, frame.nowMs)
                    drawIntoCanvas { painter.draw(it.nativeCanvas, scene) }
                    if (!routeLabel.isNullOrBlank()) drawRouteLabel(routeLabel, measurer, label, colors)
                }
            }
        }
    }
    val description = if (routeLabel.isNullOrBlank()) "가상 전기차 탑뷰" else "가상 전기차 탑뷰, $routeLabel"
    Canvas(
        modifier
            .graphicsLayer { clip = true }
            .semantics { contentDescription = description },
        onDraw = onDraw,
    )
}

/** The pictures per second allowed while the car restricts the screen. */
private const val RESTRICTED_FRAME_MS = 1000L

/** How often to look for changes while nothing moves. */
private const val IDLE_POLL_MS = 33L

/** True while something on screen moves by itself: lanes and wheels, a blinking lamp, airflow, light packets. */
private fun needsFrames(st: VisualState, input: TopViewInput): Boolean =
    st.speedMs != 0.0 || input.turn != TurnState.OFF || input.climateOn || st.regen > 0.0

/** The latest published picture, shared by the frame loop and the draw block (both on the main thread). */
private class FrameData {
    var ready = false
    var state = VisualState()
    var input = TopViewInput()
    var animated = false
    var nowMs = 0L

    private var announced: VisualState? = null

    fun publish(st: VisualState, inp: TopViewInput, animated: Boolean, nowMs: Long) {
        state = st
        input = inp
        this.animated = animated
        this.nowMs = nowMs
        ready = true
    }

    fun sameLook(st: VisualState, inp: TopViewInput, animated: Boolean): Boolean =
        ready && this.animated == animated && st.sameLook(state) && inp.sameLook(input)

    /** Tell the caller about the discrete part of the state, and only when it changes. */
    fun announce(st: VisualState, callback: ((VisualState) -> Unit)?) {
        val a = announced
        if (a == null || a.mode != st.mode || a.stale != st.stale || a.conflict != st.conflict) {
            announced = st
            callback?.invoke(st)
        }
    }
}

private class LabelCache {
    var text: String? = null
    var maxWidth = -1
    var layout: TextLayoutResult? = null
}

private fun DrawScope.drawRouteLabel(text: String, measurer: TextMeasurer, cache: LabelCache, colors: PleosColors) {
    val maxWidth = (size.width * 0.8f).toInt().coerceAtLeast(1)
    if (cache.text != text || cache.maxWidth != maxWidth || cache.layout == null) {
        cache.layout = measurer.measure(
            text = text,
            style = PleosType.label,
            overflow = TextOverflow.Ellipsis,
            maxLines = 1,
            constraints = Constraints(maxWidth = maxWidth),
        )
        cache.text = text
        cache.maxWidth = maxWidth
    }
    val layout = cache.layout ?: return
    val padX = 24f
    val padY = 10f
    val w = layout.size.width + 2f * padX
    val h = layout.size.height + 2f * padY
    val left = (size.width - w) / 2f
    val top = 28f
    drawRoundRect(colors.surfaceHigh.copy(alpha = 0.92f), Offset(left, top), Size(w, h), CornerRadius(h / 2f))
    drawText(layout, color = colors.textSecondary, topLeft = Offset(left + padX, top + padY))
}

// ---- previews (the layout renderer may not show the blur glow; the emulator does) -----------------------------
private val PREVIEW_INPUT = TopViewInput(
    speedMs = 12.0,
    speedAtMs = 0L,
    gear = GearState.DRIVE,
    steeringDeg = -12.0,
    brake = true,
    turn = TurnState.LEFT,
    climateOn = true,
    socFraction = 0.72,
    regenLevel = 0.5,
    consumedFraction = 0.08,
    restricted = true,
)

@Preview(name = "Top view, neon", widthDp = 841, heightDp = 605)
@Composable
private fun CarTopViewPreview() {
    IfEvTheme {
        Box(Modifier.fillMaxSize().background(LocalPleosColors.current.surface)) {
            CarTopView(PREVIEW_INPUT, Modifier.fillMaxSize(), routeLabel = "집 → 회사")
        }
    }
}
