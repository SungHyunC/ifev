package com.ifev.app.ui.topview

import com.ifev.app.data.TurnState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tanh

/**
 * Pure Kotlin (no Android, no Compose): decides what the neon car looks like for one frame.
 * [layout] builds the outlines ([NeonShapes]) when the view size changes; [update] turns a [VisualState] and
 * a [TopViewInput] into the numbers the Android painter needs (angles, intensities, a few reusable arrays).
 * Nothing here allocates per frame.
 *
 * `animated = false` (the car restricts the screen while driving) means nothing in the picture moves: lamps
 * steady on, tread lines, lane dashes, light particles and airflow at rest, regeneration at a steady strength.
 */
internal class NeonScene {
    var width = 0f; private set
    var height = 0f; private set
    var ready = false; private set

    /** Bumped whenever [shapes] was rebuilt, so the painter knows to rebuild its Paths. */
    var layoutVersion = 0; private set
    lateinit var shapes: NeonShapes; private set

    // ---- per-frame values -----------------------------------------------------------------------------------
    /** Overall opacity: 1, lowered while the signal is lost. */
    var alpha = 1f; private set

    /** Front wheel angle on screen, degrees, clockwise positive (left turn negative). */
    var steerDeg = 0f; private set

    var brake = 0f; private set
    /** Blink-aware intensity of the left and right turn signal, 0 or 1. */
    var turnLeft = 0f; private set
    var turnRight = 0f; private set

    /** Lane dashes: [laneCount] segments (x0,y0,x1,y1) in [laneLines]; [laneAlpha] their opacity. */
    val laneLines = FloatArray(MAX_LANE_SEGMENTS * 4)
    var laneCount = 0; private set

    /** Four wheels (FL, FR, RL, RR) × [TREADS] tread segments (x0,y0,x1,y1). Segments of zero length are skipped by the painter. */
    val treadLines = FloatArray(4 * TREADS * 4)

    /** Light particles of regeneration: [regenMid] bright ones then [regenEdge] faded ones, as x,y pairs. */
    val regenPoints = FloatArray(2 * MAX_REGEN * 2)
    var regenMid = 0; private set
    var regenEdge = 0; private set
    var regenAlpha = 0f; private set

    /** Airflow curves: opacity and sideways sway (pixels) of each; opacity 0 when the climate system is off. */
    val airAlpha = FloatArray(3)
    val airSway = FloatArray(3)

    /** Battery gauge: the filled part and the part used on this trip (empty cells above it), pixels. */
    var fillTop = 0f; private set
    var fillVisible = false; private set
    var fillAmber = false; private set
    var usedTop = 0f; private set
    var usedVisible = false; private set

    // ---- integrators (only advance while animated) -------------------------------------------------------------
    private var lastMs = Long.MIN_VALUE
    private var laneM = 0.0
    private var treadM = 0.0
    private var regenCycle = 0.0
    private var airCycle = 0.0

    fun layout(w: Float, h: Float) {
        if (!(w > 0f && h > 0f && w.isFinite() && h.isFinite())) {
            ready = false
            return
        }
        if (ready && w == width && h == height) return
        width = w
        height = h
        val s = minOf(h * 0.68f / NeonShapes.CAR_LENGTH, w * 0.62f / NeonShapes.DRAWN_WIDTH)
        shapes = NeonShapes(w / 2f, h * 0.53f, s)
        layoutVersion++
        ready = true
    }

    fun update(st: VisualState, input: TopViewInput, animated: Boolean, nowMs: Long) {
        if (!ready) return
        val sh = shapes
        val dt = if (animated && lastMs != Long.MIN_VALUE) ((nowMs - lastMs) / 1000.0).coerceIn(0.0, 0.1) else 0.0
        lastMs = nowMs

        alpha = (1.0 - 0.6 * st.ghost.coerceIn(0.0, 1.0)).toFloat()
        steerDeg = st.steeringDeg.coerceIn(-40.0, 40.0).toFloat()
        brake = if (input.brake) 1f else 0f

        // Turn signals: 1.5 Hz blink while animated, steady on otherwise.
        val on = !animated || ((nowMs % BLINK_PERIOD_MS) + BLINK_PERIOD_MS) % BLINK_PERIOD_MS < BLINK_PERIOD_MS / 2
        val lit = if (on) 1f else 0f
        turnLeft = if (input.turn == TurnState.LEFT) lit else 0f
        turnRight = if (input.turn == TurnState.RIGHT) lit else 0f

        // Flow: lane dashes and tread lines follow the shown speed (saturating, so fast driving does not strobe).
        val v = st.speedMs
        if (dt > 0.0) {
            laneM += 12.0 * tanh(v / 12.0) * dt
            treadM += 4.0 * tanh(v / 4.0) * dt
            regenCycle += (0.5 + 0.8 * st.regen) * dt
            airCycle += dt / AIR_PERIOD_S
        }
        buildLanes(sh)
        buildTreads(sh)
        buildRegen(sh, st, animated)
        buildAir(sh, input, animated)
        buildGauge(sh, input)
    }

    private fun buildLanes(sh: NeonShapes) {
        val pitch = sh.lanePitch
        val frac = frac(laneM / NeonShapes.LANE_PITCH_M).toFloat()
        val first = -1
        val rows = (height / pitch).toInt() + 2
        var n = 0
        for (side in 0..1) {
            for (k in first until first + rows + 1) {
                if (n >= MAX_LANE_SEGMENTS) break
                val y0 = (k + frac) * pitch
                val y1 = y0 + sh.laneDash
                if (y1 < 0f || y0 > height) continue
                laneLines[4 * n] = sh.laneX[side]
                laneLines[4 * n + 1] = y0.coerceIn(0f, height)
                laneLines[4 * n + 2] = sh.laneX[side]
                laneLines[4 * n + 3] = y1.coerceIn(0f, height)
                n++
            }
        }
        laneCount = n
    }

    private fun buildTreads(sh: NeonShapes) {
        val s = sh.s
        val span = 0.60f
        val frac = frac(treadM / (span / TREADS)).toFloat()
        for (w in 0 until 4) {
            val front = w < 2
            val wx = if (front) sh.frontWheelX[w] else sh.rearWheelX[w - 2]
            val wy = if (front) sh.frontWheelY else sh.rearWheelY
            val th = if (front) Math.toRadians(steerDeg.toDouble()) else 0.0
            val c = cos(th).toFloat()
            val sn = sin(th).toFloat()
            for (k in 0 until TREADS) {
                val u = (k + frac) / TREADS
                val env = sin(PI * u).toFloat()
                val half = 0.065f * env * s
                val ly = (span / 2f - u * span) * s * -1f // metres up -> pixels down
                // Local pixel offsets (x right, y down), then turned by the steering angle (clockwise).
                val ax = -half; val bx = half; val y = ly
                val i = (w * TREADS + k) * 4
                treadLines[i] = wx + ax * c - y * sn
                treadLines[i + 1] = wy + ax * sn + y * c
                treadLines[i + 2] = wx + bx * c - y * sn
                treadLines[i + 3] = wy + bx * sn + y * c
            }
        }
    }

    private fun buildRegen(sh: NeonShapes, st: VisualState, animated: Boolean) {
        val r = st.regen.coerceIn(0.0, 1.0)
        if (r < 0.03) {
            regenMid = 0; regenEdge = 0; regenAlpha = 0f
            return
        }
        regenAlpha = (0.35 + 0.65 * r).toFloat()
        val n = 2 + (4.0 * r).roundToInt() // per side, 2..6
        // Two passes so the faded ones end up after the bright ones in the array.
        var cursor = 0
        for (pass in 0..1) {
            for (side in 0..1) {
                val c = sh.regenCurve[side]
                for (i in 0 until n) {
                    val u = if (animated) frac(regenCycle + i.toDouble() / n).toFloat() else (i + 0.5f) / n
                    val isMid = u in 0.14f..0.86f
                    if (isMid != (pass == 0)) continue
                    bezier(c, u, cursor++)
                }
            }
            if (pass == 0) regenMid = cursor
        }
        regenEdge = cursor - regenMid
    }

    private fun bezier(c: FloatArray, t: Float, idx: Int) {
        val u = 1f - t
        val a = u * u * u; val b = 3f * u * u * t; val d = 3f * u * t * t; val e = t * t * t
        regenPoints[2 * idx] = a * c[0] + b * c[2] + d * c[4] + e * c[6]
        regenPoints[2 * idx + 1] = a * c[1] + b * c[3] + d * c[5] + e * c[7]
    }

    private fun buildAir(sh: NeonShapes, input: TopViewInput, animated: Boolean) {
        for (i in 0 until 3) {
            if (!input.climateOn) {
                airAlpha[i] = 0f
                airSway[i] = 0f
            } else if (!animated) {
                airAlpha[i] = 0.24f
                airSway[i] = 0f
            } else {
                val ph = 2.0 * PI * (airCycle + i / 3.0)
                airAlpha[i] = (0.16 + 0.14 * (0.5 + 0.5 * sin(ph))).toFloat()
                airSway[i] = (0.03 * sh.s * sin(ph + 1.3)).toFloat()
            }
        }
    }

    private fun buildGauge(sh: NeonShapes, input: TopViewInput) {
        val soc = if (input.socFraction.isNaN()) 0.0 else input.socFraction.coerceIn(0.0, 1.0)
        val used = (if (input.consumedFraction.isNaN()) 0.0 else input.consumedFraction.coerceIn(0.0, 1.0)).coerceAtMost(1.0 - soc)
        val span = sh.gaugeBottom - sh.gaugeTop
        fillTop = sh.gaugeBottom - (soc * span).toFloat()
        fillVisible = soc * span >= 2.0
        fillAmber = soc < 0.30
        usedTop = fillTop - (used * span).toFloat()
        usedVisible = used * span >= 3.0
    }

    private fun frac(x: Double): Double = x - floor(x)

    companion object {
        const val TREADS = 3
        const val MAX_LANE_SEGMENTS = 48
        const val MAX_REGEN = 6
        /** 1.5 Hz: one blink cycle is 667 ms, lit for the first half. */
        const val BLINK_PERIOD_MS = 667L
        const val AIR_PERIOD_S = 3.5
    }
}
