package com.ifev.app.ui.topview

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/*
 * Pure Kotlin (no Android, no Compose): the outlines of the neon car, in pixels, built once per view size.
 * The car is drawn in metres (x to the right, y towards the nose, origin in the middle of the car) and put on the
 * screen by [NeonShapes]. The Android side turns every [PathSpec] into one android.graphics.Path, once.
 */

/** Same-colour constants for the neon look (the app is dark-only, so these do not follow the theme). */
/** The top view's colours. [DARK] is the team's neon design; [LIGHT] keeps it readable on a light screen. */
internal class NeonPalette(
    /** The outline: on dark, the cyan of the app icon. */
    val line: Int,
    val amber: Int,
    /** The bright centre of a lit lamp line. */
    val amberCore: Int,
    val red: Int,
    val redCore: Int,
    /** Body interior: dark glass on dark, frosted glass on light. */
    val bodyFill: Int,
    /** Strength of the wide and tight glow around the outline, and of the lane dashes. */
    val glowWide: Float,
    val glowTight: Float,
    val lane: Float,
    /** Strength of the soft lamp light on the ground. */
    val ground: Float,
) {
    companion object {
        val DARK = NeonPalette(
            line = 0xFF1FD3F0.toInt(), amber = 0xFFFFB020.toInt(), amberCore = 0xFFFFD27A.toInt(),
            red = 0xFFFF2D2D.toInt(), redCore = 0xFFFF8A80.toInt(), bodyFill = 0xB8030405.toInt(),
            glowWide = 0.34f, glowTight = 0.70f, lane = 0.14f, ground = 1f,
        )
        val LIGHT = NeonPalette(
            line = 0xFF0097B5.toInt(), amber = 0xFFF29D00.toInt(), amberCore = 0xFFD98200.toInt(),
            red = 0xFFFF3B45.toInt(), redCore = 0xFFE0202C.toInt(), bodyFill = 0xF2FFFFFF.toInt(),
            glowWide = 0.16f, glowTight = 0.32f, lane = 0.22f, ground = 0.75f,
        )
    }
}

/** A path as plain data: verbs and the coordinates they use, in order. */
internal class PathSpec(val verbs: ByteArray, val coords: FloatArray) {
    val verbCount: Int get() = verbs.size

    /** Box around every point, control points included (they bound the curve). [minX, minY, maxX, maxY]. */
    fun bounds(): FloatArray {
        var x0 = Float.POSITIVE_INFINITY
        var y0 = Float.POSITIVE_INFINITY
        var x1 = Float.NEGATIVE_INFINITY
        var y1 = Float.NEGATIVE_INFINITY
        var i = 0
        while (i < coords.size) {
            x0 = min(x0, coords[i]); x1 = max(x1, coords[i])
            y0 = min(y0, coords[i + 1]); y1 = max(y1, coords[i + 1])
            i += 2
        }
        return floatArrayOf(x0, y0, x1, y1)
    }

    companion object {
        const val MOVE: Byte = 0
        const val LINE: Byte = 1
        const val CUBIC: Byte = 2
        const val QUAD: Byte = 3
        const val CLOSE: Byte = 4
    }
}

/** Collects verbs and points; every call takes METRES and stores pixels. */
internal class PathBuilder(private val cx: Float, private val cy: Float, private val s: Float) {
    private val verbs = ArrayList<Byte>()
    private val pts = ArrayList<Float>()

    private fun px(x: Float) = cx + x * s
    private fun py(y: Float) = cy - y * s
    private fun p(x: Float, y: Float) { pts.add(px(x)); pts.add(py(y)) }

    fun moveTo(x: Float, y: Float): PathBuilder { verbs.add(PathSpec.MOVE); p(x, y); return this }
    fun lineTo(x: Float, y: Float): PathBuilder { verbs.add(PathSpec.LINE); p(x, y); return this }
    fun quadTo(cx1: Float, cy1: Float, x: Float, y: Float): PathBuilder {
        verbs.add(PathSpec.QUAD); p(cx1, cy1); p(x, y); return this
    }
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float): PathBuilder {
        verbs.add(PathSpec.CUBIC); p(x1, y1); p(x2, y2); p(x, y); return this
    }
    fun close(): PathBuilder { verbs.add(PathSpec.CLOSE); return this }

    /** A rounded rectangle (corner radius [r]) as a closed path, going clockwise from the top edge. */
    fun roundRect(l: Float, t: Float, r: Float, b: Float, rad: Float): PathBuilder {
        val k = rad * 0.5523f
        moveTo(l + rad, t)
        lineTo(r - rad, t)
        cubicTo(r - rad + k, t, r, t - rad + k, r, t - rad)
        lineTo(r, b + rad)
        cubicTo(r, b + rad - k, r - rad + k, b, r - rad, b)
        lineTo(l + rad, b)
        cubicTo(l + rad - k, b, l, b + rad - k, l, b + rad)
        lineTo(l, t - rad)
        cubicTo(l, t - rad + k, l + rad - k, t, l + rad, t)
        return close()
    }

    fun build(): PathSpec = PathSpec(verbs.toByteArray(), pts.toFloatArray())
}

/** One cubic Bézier in metres, to sample points of the outline for the lamp arcs. */
private class Cubic(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val x2: Float, val y2: Float, val x3: Float, val y3: Float) {
    fun x(t: Float): Float { val u = 1f - t; return u * u * u * x0 + 3f * u * u * t * x1 + 3f * u * t * t * x2 + t * t * t * x3 }
    fun y(t: Float): Float { val u = 1f - t; return u * u * u * y0 + 3f * u * u * t * y1 + 3f * u * t * t * y2 + t * t * t * y3 }
}

/**
 * Everything static about the picture, for one view size. [s] is pixels per metre, ([cx], [cy]) the middle
 * of the car on the screen.
 */
internal class NeonShapes(val cx: Float, val cy: Float, val s: Float) {

    private fun builder() = PathBuilder(cx, cy, s)

    /** Metres to pixels. */
    fun px(x: Float) = cx + x * s
    fun py(y: Float) = cy - y * s

    // ---- the car, nose up, measured from the design mock-up ------------------------------------------------
    /** Body outline, closed. */
    val body: PathSpec = builder().apply {
        moveTo(-HALF_W, SHOULDER_Y)
        cubicTo(-HALF_W, 2.15f, -0.55f, NOSE_Y, 0f, NOSE_Y)
        cubicTo(0.55f, NOSE_Y, HALF_W, 2.15f, HALF_W, SHOULDER_Y)
        lineTo(REAR_W, REAR_SHOULDER_Y)
        cubicTo(REAR_W, -2.22f, 0.80f, TAIL_Y, 0.55f, TAIL_Y)
        lineTo(-0.55f, TAIL_Y)
        cubicTo(-0.80f, TAIL_Y, -REAR_W, -2.22f, -REAR_W, REAR_SHOULDER_Y)
        close()
    }.build()

    /** The glass roof: a rounded rectangle. */
    val cabin: PathSpec = builder().roundRect(-CABIN_HALF_W, CABIN_TOP, CABIN_HALF_W, CABIN_BOTTOM, 0.22f).build()

    /** The two glass edges just outside the roof: front and rear window curves (open paths, drawn fainter). */
    val windows: PathSpec = builder().apply {
        moveTo(-0.65f, CABIN_TOP + 0.05f); quadTo(0f, CABIN_TOP + 0.25f, 0.65f, CABIN_TOP + 0.05f)
        moveTo(-0.65f, CABIN_BOTTOM - 0.05f); quadTo(0f, CABIN_BOTTOM - 0.25f, 0.65f, CABIN_BOTTOM - 0.05f)
    }.build()

    /** Both side mirrors (closed little quadrilaterals sticking out of the body). */
    val mirrors: PathSpec = builder().apply {
        for (side in intArrayOf(-1, 1)) {
            val sd = side.toFloat()
            moveTo(sd * 0.93f, 0.80f)
            lineTo(sd * 1.11f, 0.77f)
            lineTo(sd * 1.13f, 0.58f)
            lineTo(sd * 0.93f, 0.60f)
            close()
        }
    }.build()

    /** The two rear wheels as closed rounded rectangles. */
    val rearWheels: PathSpec = builder().apply {
        for (side in intArrayOf(-1, 1)) wheelRect(this, side * WHEEL_X, REAR_AXLE_Y)
    }.build()

    /** One front wheel centred on the origin of the PATH's own coordinates (pixels around 0,0): rotate, then move. */
    val frontWheelAtOrigin: PathSpec = PathBuilder(0f, 0f, s).apply { wheelRect(this, 0f, 0f) }.build()

    /** Pixel centre of the front wheels (left, right) and the rear ones. */
    val frontWheelX = floatArrayOf(px(-WHEEL_X), px(WHEEL_X))
    val frontWheelY = py(FRONT_AXLE_Y)
    val rearWheelX = floatArrayOf(px(-WHEEL_X), px(WHEEL_X))
    val rearWheelY = py(REAR_AXLE_Y)

    /** The two brake lamp lines at the tail (open). */
    val brakeLamps: PathSpec = builder().apply {
        moveTo(-0.59f, TAIL_Y + 0.03f); lineTo(-0.11f, TAIL_Y + 0.03f)
        moveTo(0.11f, TAIL_Y + 0.03f); lineTo(0.59f, TAIL_Y + 0.03f)
    }.build()

    /** Turn signal arcs along the nose corner and the tail corner, left side, right side (open). */
    val turnArcLeft: PathSpec = turnArcs(-1)
    val turnArcRight: PathSpec = turnArcs(1)

    /** Centres (pixels) and radii of the soft lamp glows: [0]=nose-left, [1]=nose-right, [2]=tail-left, [3]=tail-right. */
    val turnGlowX = floatArrayOf(px(-0.80f), px(0.80f), px(-0.80f), px(0.80f))
    val turnGlowY = floatArrayOf(py(2.05f), py(2.05f), py(-2.15f), py(-2.15f))
    val turnGlowR = floatArrayOf(1.5f * s, 1.5f * s, 1.3f * s, 1.3f * s)

    /** Brake glow: centre and radius in pixels (spreads down past the tail). */
    val brakeGlowX = px(0f)
    val brakeGlowY = py(TAIL_Y - 0.05f)
    val brakeGlowR = 1.8f * s

    /** The gentle airflow curves inside the cabin, three of them (open). */
    val airflow: Array<PathSpec> = Array(3) { i ->
        val x = (i - 1) * 0.36f
        builder().apply {
            moveTo(x, -1.05f)
            cubicTo(x + 0.26f, -0.50f, x - 0.26f, 0.20f, x, 0.78f)
        }.build()
    }

    // ---- regeneration: from the front wheels' inner edge towards the battery in the middle --------------------
    /** Cubic control points in pixels, left then right: x0,y0,x1,y1,x2,y2,x3,y3. */
    val regenCurve: Array<FloatArray> = Array(2) { i ->
        val sd = if (i == 0) -1f else 1f
        floatArrayOf(
            px(sd * 0.93f), py(FRONT_AXLE_Y),
            px(sd * 0.55f), py(1.35f),
            px(sd * 0.22f), py(0.75f),
            px(sd * 0.05f), py(0.05f),
        )
    }

    // ---- battery gauge inside the cabin (pixels) ----------------------------------------------------------------
    val gaugeLeft = px(-GAUGE_HALF_W)
    val gaugeRight = px(GAUGE_HALF_W)
    val gaugeBottom = py(GAUGE_BOTTOM)
    val gaugeTop = py(GAUGE_TOP)
    val gaugeRadius = 0.12f * s

    // ---- lanes ---------------------------------------------------------------------------------------------------
    val laneX = floatArrayOf(px(-LANE_X), px(LANE_X))
    val lanePitch = LANE_PITCH_M * s
    val laneDash = LANE_DASH_M * s

    private fun wheelRect(b: PathBuilder, x: Float, y: Float) {
        b.roundRect(x - WHEEL_W / 2f, y + WHEEL_LEN / 2f, x + WHEEL_W / 2f, y - WHEEL_LEN / 2f, 0.07f)
    }

    /** Samples along the corner outlines, nudged a little to the inside of the car. */
    private fun turnArcs(side: Int): PathSpec {
        val sd = side.toFloat()
        val nose = Cubic(sd * HALF_W, SHOULDER_Y, sd * HALF_W, 2.15f, sd * 0.55f, NOSE_Y, 0f, NOSE_Y)
        val tail = Cubic(sd * REAR_W, REAR_SHOULDER_Y, sd * REAR_W, -2.22f, sd * 0.80f, TAIL_Y, sd * 0.55f, TAIL_Y)
        val b = builder()
        fun arc(c: Cubic, t0: Float, t1: Float, inset: Float) {
            val n = 10
            for (i in 0..n) {
                val t = t0 + (t1 - t0) * i / n
                var x = c.x(t)
                var y = c.y(t)
                // Toward the middle of the car by [inset] metres.
                val dx = -x
                val dy = -y * 0.4f
                val len = sqrt(dx * dx + dy * dy).coerceAtLeast(1e-4f)
                x += dx / len * inset
                y += dy / len * inset
                if (i == 0) b.moveTo(x, y) else b.lineTo(x, y)
            }
        }
        arc(nose, 0.35f, 0.82f, 0.06f)
        arc(tail, 0.10f, 0.62f, 0.06f)
        return b.build()
    }

    companion object {
        const val CAR_LENGTH = 4.7f
        /** Width of what is drawn: wheels and mirrors included, a little more than the body. */
        const val DRAWN_WIDTH = 2.5f

        const val HALF_W = 0.93f
        const val REAR_W = 0.95f
        const val NOSE_Y = 2.35f
        const val SHOULDER_Y = 1.70f
        const val TAIL_Y = -2.35f
        const val REAR_SHOULDER_Y = -1.95f

        const val CABIN_HALF_W = 0.70f
        const val CABIN_TOP = 1.12f
        const val CABIN_BOTTOM = -1.40f

        const val WHEEL_X = 1.03f
        const val WHEEL_W = 0.20f
        const val WHEEL_LEN = 0.70f
        const val FRONT_AXLE_Y = 1.40f
        const val REAR_AXLE_Y = -1.55f

        const val GAUGE_HALF_W = 0.58f
        const val GAUGE_TOP = 1.02f
        const val GAUGE_BOTTOM = -1.30f

        const val LANE_X = 1.95f
        const val LANE_PITCH_M = 2.4f
        const val LANE_DASH_M = 1.2f
    }
}
