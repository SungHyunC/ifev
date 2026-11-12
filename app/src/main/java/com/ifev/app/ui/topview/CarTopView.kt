package com.ifev.app.ui.topview

import android.graphics.Paint
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
import androidx.compose.ui.graphics.toArgb
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
 * The virtual EV seen from above and behind: a fixed camera pitched about 60° down, the car fixed in the
 * middle, the lane markings flowing the way the car drives. Drawn with Compose Canvas only: one
 * drawVertices call for every polygon (plus one more while the car is translucent), no extra library.
 *
 * What it shows: front wheels steer ([steering] says how to read the raw value), wheels turn (tread
 * pattern), brake lamps, turn signals (1.5 Hz), a battery gauge on the glass roof (charge level, teal,
 * amber below 30 %, the part used on this trip as empty cells), regeneration as teal light running from the
 * front wheels into the gauge, a faint airflow over the cabin while the climate system is on, and a
 * translucent body while the car is "waiting for signal". The text for that state is the caller's: pass
 * [onModeChange] to hear about it.
 *
 * [routeLabel], when not null, is a small pill at the top of the view.
 *
 * `input.restricted` (the car restricts the screen while driving): the picture is refreshed at most once a
 * second and nothing in it moves: wheel pattern and lane markings at rest, turn signals steady on,
 * regeneration at a steady strength, no airflow motion. Otherwise it runs at the display's frame rate while
 * something moves, and only when something changes while nothing does.
 *
 * No touch handling. Day or night comes from the app theme ([LocalPleosColors], which IfEvTheme sets from
 * isSystemInDarkTheme()).
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
    val palette = remember(colors) {
        TopViewPalette(
            dark = colors.isDark,
            ev = colors.ev.toArgb(),
            fuel = colors.fuel.toArgb(),
            line = colors.textQuaternary.toArgb(),
        )
    }
    val latestState = rememberUpdatedState(input)
    val latest by latestState
    val modeCallback by rememberUpdatedState(onModeChange)
    val smoother = remember { VisualSmoother(steering) }
    SideEffect { smoother.steering = steering }
    val renderer = remember { TopViewRenderer() }
    val frame = remember { FrameData() }
    val tick = remember { mutableLongStateOf(0L) }
    val paint = remember { Paint().apply { color = android.graphics.Color.WHITE; isAntiAlias = true } }
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
    val onDraw: DrawScope.() -> Unit = remember(renderer, smoother, frame, tick, paint, measurer, label, palette, colors, routeLabel, preview) {
        {
            // Reading the tick makes this block run again for every new frame.
            if (tick.longValue >= 0L) {
                renderer.layout(size.width, size.height, palette)
                if (!frame.ready && preview) {
                    val inp = latestState.value
                    frame.publish(smoother.step(inp, 0L), inp, false, 0L)
                }
                if (frame.ready && renderer.ready) {
                    renderer.update(frame.state, frame.input, frame.animated, frame.nowMs)
                    drawIntoCanvas { submit(it.nativeCanvas, renderer, paint) }
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

/** One drawVertices call for everything, or two (ground, then the car in a translucent layer). */
private fun submit(canvas: android.graphics.Canvas, r: TopViewRenderer, paint: Paint) {
    if (!r.ready) return
    val floats = r.vertexCount * 2
    val mode = android.graphics.Canvas.VertexMode.TRIANGLES
    if (r.carAlpha >= 0.995f) {
        canvas.drawVertices(mode, floats, r.positions, 0, null, 0, r.colors, 0, r.indices, 0, r.groundIndexCount + r.carIndexCount, paint)
        return
    }
    canvas.drawVertices(mode, floats, r.positions, 0, null, 0, r.colors, 0, r.indices, 0, r.groundIndexCount, paint)
    val b = r.carBounds
    val layer = canvas.saveLayerAlpha(b[0], b[1], b[2], b[3], (r.carAlpha * 255f + 0.5f).toInt().coerceIn(0, 255))
    canvas.drawVertices(mode, floats, r.positions, 0, null, 0, r.colors, 0, r.indices, r.groundIndexCount, r.carIndexCount, paint)
    canvas.restoreToCount(layer)
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
    drawRoundRect(colors.surface.copy(alpha = 0.88f), Offset(left, top), Size(w, h), CornerRadius(h / 2f))
    drawText(layout, color = colors.textSecondary, topLeft = Offset(left + padX, top + padY))
}

// ---- previews (Android Studio's layout renderer may not draw drawVertices; the emulator does) ---------------
private val PREVIEW_INPUT = TopViewInput(
    speedMs = 12.0,
    speedAtMs = 0L,
    gear = GearState.DRIVE,
    steeringDeg = -8.0,
    brake = false,
    turn = TurnState.LEFT,
    climateOn = true,
    socFraction = 0.72,
    regenLevel = 0.5,
    consumedFraction = 0.08,
    restricted = true,
)

@Preview(name = "Top view, day", widthDp = 841, heightDp = 605)
@Composable
private fun CarTopViewDayPreview() {
    IfEvTheme(darkTheme = false) {
        Box(Modifier.fillMaxSize().background(LocalPleosColors.current.background)) {
            CarTopView(PREVIEW_INPUT, Modifier.fillMaxSize(), routeLabel = "집 → 회사")
        }
    }
}

@Preview(name = "Top view, night", widthDp = 841, heightDp = 605)
@Composable
private fun CarTopViewNightPreview() {
    IfEvTheme(darkTheme = true) {
        Box(Modifier.fillMaxSize().background(LocalPleosColors.current.background)) {
            CarTopView(PREVIEW_INPUT, Modifier.fillMaxSize())
        }
    }
}
