package com.ifev.app.ui.topview

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * The virtual EV as a handful of flat polygons, made in code. Pure Kotlin (no Android, no Compose).
 *
 * World axes in metres: x to the right, y up, z forward (the nose points to +z). The car sits on y = 0
 * with its centre at the origin. The camera never moves, so everything that does not move with the car's
 * signals (body, glass, lamps' places) is built, culled and depth-sorted ONCE here. A frame only moves
 * the wheels, the lane markings and the gauge and changes colours (see TopViewRenderer).
 */

/** The fixed camera: behind and above the car, looking at the origin, pitched about 60° down. */
internal object TopViewCamera {
    const val PITCH_DEG = 60.0f
    const val DIST = 12.0f

    private val phi = (PITCH_DEG * Math.PI / 180.0).toFloat()
    val sinP = sin(phi)
    val cosP = cos(phi)

    /** Camera position. */
    val posY = DIST * sinP
    val posZ = -DIST * cosP

    /** Distance along the viewing axis (smaller = nearer). */
    fun depth(y: Float, z: Float): Float = (y - posY) * -sinP + (z - posZ) * cosP

    /** Height on the view plane (up is positive). */
    fun up(y: Float, z: Float): Float = (y - posY) * cosP + (z - posZ) * sinP
}

internal object Mat {
    const val BODY = 0
    const val GLASS = 1
    const val TRIM = 2
    const val FLAP = 3
}

internal object Tag {
    const val NONE = 0
    const val HEAD_L = 1
    const val HEAD_R = 2
    const val DRL = 3
    const val TAIL_TOP = 4
    const val TAIL_FACE = 5
    const val TURN_FL = 6
    const val TURN_FR = 7
    const val TURN_RL = 8
    const val TURN_RR = 9
    const val PLUG = 10
}

/** A flat convex polygon, vertices as x, y, z triples. */
internal class Face3D(val pts: FloatArray, val material: Int, val tag: Int = Tag.NONE) {
    val count: Int get() = pts.size / 3
    var nx = 0f
    var ny = 1f
    var nz = 0f
    var cx = 0f
    var cy = 0f
    var cz = 0f

    /** Computes centroid and unit normal; the normal is turned to point away from the reference point. */
    fun orient(rx: Float, ry: Float, rz: Float): Face3D {
        val n = count
        var sx = 0f; var sy = 0f; var sz = 0f
        var ax = 0f; var ay = 0f; var az = 0f
        for (i in 0 until n) {
            val j = (i + 1) % n
            val xi = pts[3 * i]; val yi = pts[3 * i + 1]; val zi = pts[3 * i + 2]
            val xj = pts[3 * j]; val yj = pts[3 * j + 1]; val zj = pts[3 * j + 2]
            sx += xi; sy += yi; sz += zi
            // Newell's method: robust for any planar polygon.
            ax += (yi - yj) * (zi + zj)
            ay += (zi - zj) * (xi + xj)
            az += (xi - xj) * (yi + yj)
        }
        cx = sx / n; cy = sy / n; cz = sz / n
        val l = sqrt(ax * ax + ay * ay + az * az).coerceAtLeast(1e-9f)
        nx = ax / l; ny = ay / l; nz = az / l
        if (nx * (cx - rx) + ny * (cy - ry) + nz * (cz - rz) < 0f) {
            nx = -nx; ny = -ny; nz = -nz
        }
        return this
    }

    /** True when the camera sees the front of this face. */
    fun facesCamera(): Boolean {
        val vy = TopViewCamera.posY - cy
        val vz = TopViewCamera.posZ - cz
        return ny * vy + nz * vz + nx * (0f - cx) > 0f
    }
}

internal object CarMesh {

    // ---- fixed dimensions (metres) -----------------------------------------------------------------------
    const val HALF_WIDTH = 0.93f
    const val SILL_Y = 0.28f
    const val WHEEL_X = 0.95f
    const val WHEEL_Z = 1.42f

    /** 2.1 m / 2π: the wheels roll exactly as far as the wheel phase says. */
    const val WHEEL_R = 0.3342f
    const val WHEEL_HALF_W = 0.15f

    const val ROOF_Y = 1.468f
    const val ROOF_Z_FRONT = 0.50f
    const val ROOF_Z_REAR = -0.60f

    /** Battery gauge on the roof: x and z extent of the cells, bottom (rear end) to top (front end). */
    const val GAUGE_HALF_W = 0.20f
    const val GAUGE_Z0 = -0.50f
    const val GAUGE_Z1 = 0.36f
    const val GAUGE_CELLS = 10

    private const val NOSE_START = 1.30f
    private const val NOSE_LEN = 1.05f
    private const val NOSE_P = 2.5f
    private const val TAIL_START = 1.35f
    private const val TAIL_LEN = 1.00f
    private const val TAIL_P = 3.4f
    private val END_T = floatArrayOf(0f, 0.22f, 0.42f, 0.58f, 0.71f, 0.81f, 0.89f, 0.94f, 0.975f)

    /** Where the plan ends (the flat nose and tail faces). */
    val NOSE_Z = NOSE_START + END_T.last() * NOSE_LEN
    val TAIL_Z = -(TAIL_START + END_T.last() * TAIL_LEN)

    /** Half width of the body in plan at [z]: straight flanks, rounded nose, blunter tail. */
    fun halfWidth(z: Float): Float {
        val start = if (z >= 0f) NOSE_START else TAIL_START
        val len = if (z >= 0f) NOSE_LEN else TAIL_LEN
        val p = if (z >= 0f) NOSE_P else TAIL_P
        val a = abs(z)
        if (a <= start) return HALF_WIDTH
        val t = ((a - start) / len).coerceIn(0f, 1f)
        return HALF_WIDTH * (1f - t.pow(p)).pow(1f / p)
    }

    private val DECK_Z = floatArrayOf(-2.35f, -2.20f, -1.95f, -1.60f, -1.00f, 0.00f, 1.05f, 1.40f, 1.75f, 2.05f, 2.25f, 2.35f)
    private val DECK_Y = floatArrayOf(0.80f, 0.86f, 0.92f, 0.97f, 0.97f, 0.97f, 0.96f, 0.92f, 0.84f, 0.74f, 0.66f, 0.60f)

    /** Height of the body's shoulder line at [z]: a short sloping hood, a long deck, a lifted tail. */
    fun deckY(z: Float): Float {
        if (z <= DECK_Z.first()) return DECK_Y.first()
        if (z >= DECK_Z.last()) return DECK_Y.last()
        var i = 0
        while (z > DECK_Z[i + 1]) i++
        val t = (z - DECK_Z[i]) / (DECK_Z[i + 1] - DECK_Z[i])
        return DECK_Y[i] + (DECK_Y[i + 1] - DECK_Y[i]) * t
    }

    // ---- body (the tub) --------------------------------------------------------------------------------
    private const val SECTION_POINTS = 11

    /** Cross-section of the body at [z] as 11 (x, y) pairs from the left sill over the crown to the right sill. */
    private fun section(z: Float, out: FloatArray) {
        val w = halfWidth(z)
        val d = deckY(z)
        val b = minOf(0.16f, 0.30f * w)
        val left = floatArrayOf(
            -w, SILL_Y,
            -w, d - 0.22f,
            -w + 0.4f * b, d - 0.09f,
            -w + b, d - 0.02f,
            -0.55f * w, d + 0.03f,
        )
        for (i in 0 until 5) {
            out[2 * i] = left[2 * i]
            out[2 * i + 1] = left[2 * i + 1]
            out[2 * (10 - i)] = -left[2 * i]
            out[2 * (10 - i) + 1] = left[2 * i + 1]
        }
        out[10] = 0f
        out[11] = d + 0.055f
    }

    /** Height of the body's top surface at (x, z): the crown, shoulders and bevel of the section. */
    fun topY(x: Float, z: Float): Float {
        val s = FloatArray(2 * SECTION_POINTS)
        section(z, s)
        val ax = abs(x)
        // Right half only: points 5..10 run from the centre (index 5) to the right sill (index 10).
        var best = s[2 * 5 + 1]
        for (i in 5 until 9) {
            val x0 = s[2 * i]; val y0 = s[2 * i + 1]
            val x1 = s[2 * (i + 1)]; val y1 = s[2 * (i + 1) + 1]
            if (ax >= x0 && ax <= x1) {
                val t = if (x1 - x0 < 1e-6f) 0f else (ax - x0) / (x1 - x0)
                best = y0 + (y1 - y0) * t
                return best
            }
        }
        return s[2 * 9 + 1]
    }

    private fun stationsZ(): FloatArray {
        val z = ArrayList<Float>()
        for (i in END_T.indices.reversed()) z += -(TAIL_START + END_T[i] * TAIL_LEN)
        z += -0.65f
        z += 0f
        z += 0.65f
        for (t in END_T) z += NOSE_START + t * NOSE_LEN
        return z.toFloatArray()
    }

    private fun quad(vararg v: Float): FloatArray = v

    private fun buildTub(out: MutableList<Face3D>) {
        val zs = stationsZ()
        val rows = Array(zs.size) { FloatArray(2 * SECTION_POINTS).also { r -> section(zs[it], r) } }
        for (i in 0 until zs.size - 1) {
            for (j in 0 until SECTION_POINTS - 1) {
                val a = rows[i]; val b = rows[i + 1]
                out += Face3D(
                    quad(
                        a[2 * j], a[2 * j + 1], zs[i],
                        a[2 * j + 2], a[2 * j + 3], zs[i],
                        b[2 * j + 2], b[2 * j + 3], zs[i + 1],
                        b[2 * j], b[2 * j + 1], zs[i + 1],
                    ),
                    Mat.BODY,
                )
            }
        }
        // The flat tail and nose faces.
        for (end in intArrayOf(0, zs.size - 1)) {
            val r = rows[end]
            val pts = FloatArray(3 * SECTION_POINTS)
            for (k in 0 until SECTION_POINTS) {
                pts[3 * k] = r[2 * k]; pts[3 * k + 1] = r[2 * k + 1]; pts[3 * k + 2] = zs[end]
            }
            out += Face3D(pts, Mat.BODY)
        }
    }

    // ---- glass cabin ---------------------------------------------------------------------------------------
    // Stations: z, shoulder-side half width, roof half width, roof height.
    private val CAB_Z = floatArrayOf(1.05f, 0.78f, 0.50f, 0.20f, -0.20f, -0.60f, -0.95f, -1.30f, -1.60f)
    private val CAB_HW_ROOF = floatArrayOf(0.74f, 0.68f, 0.62f, 0.62f, 0.62f, 0.62f, 0.60f, 0.55f, 0.48f)
    private val CAB_Y_ROOF = floatArrayOf(0.98f, 1.20f, 1.44f, ROOF_Y, ROOF_Y + 0.004f, 1.456f, 1.36f, 1.20f, 1.02f)
    private const val CAB_HW_BASE = 0.80f
    private const val RAIL_W = 0.11f

    private fun buildCabin(out: MutableList<Face3D>) {
        val n = CAB_Z.size
        // Section: left base, left roof edge, left rail inner, right rail inner, right roof edge, right base.
        fun row(i: Int): FloatArray {
            val z = CAB_Z[i]
            val yb = deckY(z) + 0.004f
            val hw = CAB_HW_ROOF[i]
            val yr = CAB_Y_ROOF[i]
            return floatArrayOf(
                -CAB_HW_BASE, yb,
                -hw, yr,
                -hw + RAIL_W, yr + 0.004f,
                hw - RAIL_W, yr + 0.004f,
                hw, yr,
                CAB_HW_BASE, yb,
            )
        }
        for (i in 0 until n - 1) {
            val a = row(i); val b = row(i + 1)
            val roofBand = CAB_Z[i] <= ROOF_Z_FRONT + 1e-4f && CAB_Z[i + 1] >= ROOF_Z_REAR - 1e-4f
            for (j in 0 until 5) {
                val rail = j == 1 || j == 3
                val material = if (roofBand && rail) Mat.BODY else Mat.GLASS
                out += Face3D(
                    quad(
                        a[2 * j], a[2 * j + 1], CAB_Z[i],
                        a[2 * j + 2], a[2 * j + 3], CAB_Z[i],
                        b[2 * j + 2], b[2 * j + 3], CAB_Z[i + 1],
                        b[2 * j], b[2 * j + 1], CAB_Z[i + 1],
                    ),
                    material,
                )
            }
        }
    }

    // ---- small body parts ----------------------------------------------------------------------------------
    private fun buildMirrors(out: MutableList<Face3D>) {
        for (s in floatArrayOf(-1f, 1f)) {
            val y = 1.0f
            // Housing seen from above: a short stalk-and-shell shape reaching out past the flank.
            out += Face3D(
                quad(
                    s * 0.88f, y, 0.80f,
                    s * 1.05f, y, 0.78f,
                    s * 1.05f, y, 0.64f,
                    s * 0.88f, y, 0.62f,
                ),
                Mat.BODY,
            )
            // Its glass faces the rear and shows to the camera.
            out += Face3D(
                quad(
                    s * 0.90f, y - 0.01f, 0.615f,
                    s * 1.04f, y - 0.01f, 0.635f,
                    s * 1.04f, y - 0.09f, 0.635f,
                    s * 0.90f, y - 0.09f, 0.615f,
                ),
                Mat.TRIM,
            )
        }
    }

    // ---- lamps and the charge port -------------------------------------------------------------------------
    /** A decal on the body's top surface at (x, z): lifted a hair so it is never inside the body. */
    private fun onTop(x: Float, z: Float): FloatArray = floatArrayOf(x, topY(x, z) + 0.012f, z)

    /** A point at a fraction f (-1 left ... +1 right) of the body's local half width, so lamps follow the rounded plan. */
    private fun at(f: Float, z: Float): FloatArray = onTop(f * halfWidth(z), z)

    private fun deckQuad(tag: Int, material: Int, vararg p: FloatArray): Face3D {
        val pts = FloatArray(3 * p.size)
        for (i in p.indices) {
            pts[3 * i] = p[i][0]; pts[3 * i + 1] = p[i][1]; pts[3 * i + 2] = p[i][2]
        }
        return Face3D(pts, material, tag)
    }

    private fun buildDecals(out: MutableList<Face3D>) {
        val zt = TAIL_Z
        val zn = NOSE_Z
        // Rear light bar: on the lifted tail and across the vertical rear face.
        out += deckQuad(Tag.TAIL_TOP, Mat.TRIM, at(-0.62f, zt + 0.03f), at(0.62f, zt + 0.03f), at(0.62f, zt + 0.17f), at(-0.62f, zt + 0.17f))
        val ew = halfWidth(zt) - 0.05f
        out += Face3D(
            quad(-ew, 0.60f, zt - 0.004f, ew, 0.60f, zt - 0.004f, ew, 0.76f, zt - 0.004f, -ew, 0.76f, zt - 0.004f),
            Mat.TRIM, Tag.TAIL_FACE,
        )
        // Rear turn signals at the corners of the tail.
        out += deckQuad(Tag.TURN_RL, Mat.TRIM, at(-0.96f, zt + 0.10f), at(-0.72f, zt + 0.05f), at(-0.72f, zt + 0.20f), at(-0.96f, zt + 0.26f))
        out += deckQuad(Tag.TURN_RR, Mat.TRIM, at(0.72f, zt + 0.05f), at(0.96f, zt + 0.10f), at(0.96f, zt + 0.26f), at(0.72f, zt + 0.20f))
        // Front: a smooth nose with a thin light bar and two slim lamp clusters, no grille.
        out += deckQuad(Tag.DRL, Mat.TRIM, at(-0.48f, zn - 0.19f), at(0.48f, zn - 0.19f), at(0.48f, zn - 0.11f), at(-0.48f, zn - 0.11f))
        out += deckQuad(Tag.HEAD_L, Mat.TRIM, at(-0.96f, zn - 0.50f), at(-0.56f, zn - 0.22f), at(-0.56f, zn - 0.07f), at(-0.88f, zn - 0.26f))
        out += deckQuad(Tag.HEAD_R, Mat.TRIM, at(0.56f, zn - 0.22f), at(0.96f, zn - 0.50f), at(0.88f, zn - 0.26f), at(0.56f, zn - 0.07f))
        out += deckQuad(Tag.TURN_FL, Mat.TRIM, at(-0.99f, zn - 0.82f), at(-0.92f, zn - 0.82f), at(-0.97f, zn - 0.58f), at(-0.99f, zn - 0.60f))
        out += deckQuad(Tag.TURN_FR, Mat.TRIM, at(0.92f, zn - 0.82f), at(0.99f, zn - 0.82f), at(0.99f, zn - 0.60f), at(0.97f, zn - 0.58f))
        // Charge port flap on the right rear fender, with its plug mark.
        val fx = 0.74f
        val fz = -1.74f
        out += deckQuad(Tag.NONE, Mat.FLAP, onTop(fx - 0.075f, fz - 0.105f), onTop(fx + 0.075f, fz - 0.105f), onTop(fx + 0.075f, fz + 0.105f), onTop(fx - 0.075f, fz + 0.105f))
        out += deckQuad(Tag.PLUG, Mat.TRIM, onTop(fx - 0.030f, fz - 0.040f), onTop(fx + 0.030f, fz - 0.040f), onTop(fx + 0.030f, fz + 0.040f), onTop(fx - 0.030f, fz + 0.040f))
    }

    // ---- assembled static sets -------------------------------------------------------------------------------
    /** Painted body, cabin and mirrors: only faces the camera can see, farthest first. */
    val body: List<Face3D> by lazy {
        val all = ArrayList<Face3D>(400)
        buildTub(all)
        buildCabin(all)
        buildMirrors(all)
        for (f in all) f.orient(0f, 0.6f, 0f)
        val visible = all.filter { it.facesCamera() }
        visible.sortedByDescending { TopViewCamera.depth(it.cy, it.cz) }
    }

    /** Lamps and the charge flap: decals drawn after the body, in this order. */
    val decals: List<Face3D> by lazy {
        val all = ArrayList<Face3D>(16)
        buildDecals(all)
        for (f in all) f.orient(0f, 0.6f, 0f)
        all
    }
}
