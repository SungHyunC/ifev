package com.ifev.app.ui.topview

import com.ifev.app.data.TurnState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/*
 * Turns the display state into ONE triangle list (positions, ARGB colours, indices) for a single
 * drawVertices call. Pure Kotlin: no Android, no Compose, so the same buffers can be checked in a unit test
 * or rasterised on the desktop.
 *
 * layout() runs when the size or the palette changes: it projects the fixed-camera geometry, shades the
 * static faces and fixes the index list, which therefore never changes again. update() runs every frame and
 * only rewrites what actually moves (lane markings, wheels, gauge fill) or changes colour (lamps, glows,
 * ribbons). No allocation in update().
 *
 * Draw order is index order: [ground group] road, lane markings, shadow, glows; then [car group] wheels,
 * body, lamps, gauge, overlays. The car group is drawn as a unit so it can be made translucent.
 */

/** Theme colours as ARGB ints, so this file does not need Compose. */
internal data class TopViewPalette(
    val dark: Boolean,
    /** Brand teal: battery gauge, regeneration. */
    val ev: Int,
    /** Brand amber: battery gauge below 30 %. */
    val fuel: Int,
    /** Lane markings; the renderer sets the opacity. */
    val line: Int,
)

internal class TopViewRenderer {

    // ---- result: valid after layout() and update() -------------------------------------------------------
    var positions = FloatArray(0); private set
    var colors = IntArray(0); private set
    var indices = ShortArray(0); private set
    var vertexCount = 0; private set

    /** Indices [0, groundIndexCount) are the ground, the next carIndexCount the car. */
    var groundIndexCount = 0; private set
    var carIndexCount = 0; private set

    /** Screen rectangle holding the car and its glows: left, top, right, bottom. */
    val carBounds = FloatArray(4)

    /** Opacity of the car group, 1 solid ... 0.5 "waiting for signal". */
    var carAlpha = 1f; private set

    // ---- projection --------------------------------------------------------------------------------------
    private var width = 0f
    private var height = 0f
    private var pal: TopViewPalette? = null
    private var focal = 1f
    private var cx = 0f
    private var cy = 0f

    private var nv = 0
    private var ni = 0

    // ---- bookkeeping of the moving parts ---------------------------------------------------------------
    private var roadBase = 0
    private val edgeBase = IntArray(2)
    private val dashBase = IntArray(2)
    private var shadowBase = 0
    private var underBase = 0
    private var brakeBase = 0
    private var coneBase = 0

    private val treadBase = IntArray(4)
    private val lugBase = IntArray(4)
    private val capBase = IntArray(8) { -1 }

    private var decalCount = 0
    private val decalBase = IntArray(CarMesh.decals.size)
    private val decalTag = IntArray(CarMesh.decals.size)

    private var gaugeFillBase = 0
    private var gaugeUsedBase = 0

    private val turnGlowBase = IntArray(4)
    private var tailGlowBase = 0
    private val headGlowBase = IntArray(2)
    private val airBase = IntArray(AIR_BANDS)
    private val regenGlowBase = IntArray(2)
    private val regenCoreBase = IntArray(2)

    /** Whether [update] has anything to draw yet. */
    val ready: Boolean get() = vertexCount > 0

    // ---- where things are in the buffers, for tests --------------------------------------------------------
    internal fun treadStart(wheel: Int) = treadBase[wheel]
    internal fun lugStart(wheel: Int) = lugBase[wheel]
    internal fun lugVertexCount() = 4 * LUGS
    internal fun treadVertexCount() = 4 * TREAD_QUADS
    internal fun gaugeFillStart() = gaugeFillBase
    internal fun gaugeUsedStart() = gaugeUsedBase
    internal fun dashStart(side: Int) = dashBase[side]
    internal fun dashVertexCount() = 4 * DASHES
    internal fun airflowStart(band: Int) = airBase[band]
    internal fun regenCoreStart(side: Int) = regenCoreBase[side]
    internal fun ribbonVertexCount() = 2 * RIBBON_POINTS

    /** First vertex of the lamp with [tag] (a quad), or -1. */
    internal fun lampStart(tag: Int): Int {
        for (i in 0 until decalCount) if (decalTag[i] == tag) return decalBase[i]
        return -1
    }

    fun layout(width: Float, height: Float, palette: TopViewPalette) {
        if (width <= 0f || height <= 0f) return
        if (width == this.width && height == this.height && palette == pal && vertexCount > 0) return
        this.width = width
        this.height = height
        this.pal = palette
        val s = min(height, width * 1.05f)
        focal = s * FOCAL_K
        cx = width / 2f
        cy = height * CENTER_Y
        if (positions.isEmpty()) {
            positions = FloatArray(2 * MAX_VERTS)
            // Canvas.drawVertices takes the number of FLOATS (two per vertex) and may size-check the colour
            // array against it, so the colour array is as long as the position array.
            colors = IntArray(2 * MAX_VERTS)
            indices = ShortArray(MAX_INDICES)
        }
        nv = 0
        ni = 0
        buildGround(palette)
        groundIndexCount = ni
        buildWheels()
        buildBody(palette)
        buildAirflow()
        buildDecals(palette)
        buildGauge(palette)
        buildOverlays(palette)
        carIndexCount = ni - groundIndexCount
        vertexCount = nv
        computeCarBounds()
    }

    // ---- small helpers ---------------------------------------------------------------------------------
    private fun vert(color: Int): Int {
        check(nv < MAX_VERTS) { "vertex buffer full" }
        colors[nv] = color
        return nv++
    }

    private fun tri(a: Int, b: Int, c: Int) {
        check(ni + 3 <= MAX_INDICES) { "index buffer full" }
        indices[ni++] = a.toShort()
        indices[ni++] = b.toShort()
        indices[ni++] = c.toShort()
    }

    /** Quad on four consecutive vertices starting at [a]. */
    private fun quadAt(a: Int) {
        tri(a, a + 1, a + 2)
        tri(a, a + 2, a + 3)
    }

    /** Convex polygon fan over [n] consecutive vertices starting at [a]. */
    private fun fanAt(a: Int, n: Int) {
        for (k in 1 until n - 1) tri(a, a + k, a + k + 1)
    }

    private fun put(v: Int, x: Float, y: Float, z: Float) {
        val d = TopViewCamera.depth(y, z)
        positions[2 * v] = cx + x * focal / d
        positions[2 * v + 1] = cy - TopViewCamera.up(y, z) * focal / d
    }

    private fun hide(v: Int, n: Int) {
        for (k in 0 until n) {
            colors[v + k] = colors[v + k] and 0x00FFFFFF
        }
    }

    private fun setAll(v: Int, n: Int, color: Int) {
        for (k in 0 until n) colors[v + k] = color
    }

    /** A strip of [rows] pairs of vertices: vertex 2i left, 2i+1 right. */
    private fun addStrip(rows: Int): Int {
        val base = nv
        repeat(rows * 2) { vert(0) }
        for (i in 0 until rows - 1) {
            val v = base + 2 * i
            tri(v, v + 1, v + 3)
            tri(v, v + 3, v + 2)
        }
        return base
    }

    /** Soft ellipse on a horizontal plane: centre, inner ring, outer ring. Colour it with [setSoft]. */
    private fun addSoft(x: Float, y: Float, z: Float, rx: Float, rz: Float, inner: Float): Int {
        val base = nv
        put(vert(0), x, y, z)
        for (ring in 0..1) {
            val k = if (ring == 0) inner else 1f
            for (i in 0 until SOFT_SEGS) {
                val a = 2f * PI.toFloat() * i / SOFT_SEGS
                put(vert(0), x + rx * k * cos(a), y, z + rz * k * sin(a))
            }
        }
        for (i in 0 until SOFT_SEGS) {
            val j = (i + 1) % SOFT_SEGS
            tri(base, base + 1 + i, base + 1 + j)
            val a0 = base + 1 + i; val a1 = base + 1 + j
            val b0 = base + 1 + SOFT_SEGS + i; val b1 = base + 1 + SOFT_SEGS + j
            tri(a0, b0, b1)
            tri(a0, b1, a1)
        }
        return base
    }

    private fun setSoft(base: Int, inner: Int, outer: Int) {
        colors[base] = inner
        for (i in 0 until SOFT_SEGS) {
            colors[base + 1 + i] = inner
            colors[base + 1 + SOFT_SEGS + i] = outer
        }
    }

    /** A ribbon of constant width following 3D points; colour it with [setRibbon]. */
    private fun addRibbon(pts: FloatArray, n: Int, halfWidthM: Float): Int {
        val sx = FloatArray(n)
        val sy = FloatArray(n)
        val hw = FloatArray(n)
        for (i in 0 until n) {
            val x = pts[3 * i]; val y = pts[3 * i + 1]; val z = pts[3 * i + 2]
            val d = TopViewCamera.depth(y, z)
            sx[i] = cx + x * focal / d
            sy[i] = cy - TopViewCamera.up(y, z) * focal / d
            hw[i] = halfWidthM * focal / d
        }
        val base = addStrip(n)
        for (i in 0 until n) {
            val i0 = max(i - 1, 0)
            val i1 = min(i + 1, n - 1)
            var tx = sx[i1] - sx[i0]
            var ty = sy[i1] - sy[i0]
            val l = sqrt(tx * tx + ty * ty).coerceAtLeast(1e-3f)
            tx /= l; ty /= l
            val nx = -ty * hw[i]
            val ny = tx * hw[i]
            positions[2 * (base + 2 * i)] = sx[i] + nx
            positions[2 * (base + 2 * i) + 1] = sy[i] + ny
            positions[2 * (base + 2 * i + 1)] = sx[i] - nx
            positions[2 * (base + 2 * i + 1) + 1] = sy[i] - ny
        }
        return base
    }

    /**
     * Light packets running along a ribbon: opacity = [strength] × envelope × (floor + wave), where the wave
     * travels towards the end of the ribbon. [flow] false gives the same ribbon without the motion.
     */
    private fun setRibbon(base: Int, n: Int, rgb: Int, strength: Float, flow: Boolean, t: Float, cycles: Float, rate: Float, floor: Float) {
        for (i in 0 until n) {
            val s = i / (n - 1f)
            val env = smooth(0f, 0.14f, s) * (1f - smooth(0.86f, 1f, s))
            val level = if (flow) {
                val w = 0.5f + 0.5f * sin(2f * PI.toFloat() * (s * cycles - t * rate))
                floor + (1f - floor) * w * w
            } else {
                floor + (1f - floor) * 0.55f
            }
            val c = withAlpha(rgb, strength * env * level)
            colors[base + 2 * i] = c
            colors[base + 2 * i + 1] = c
        }
    }

    // ---- ground --------------------------------------------------------------------------------------------
    private fun rowZ(i: Int) = ROW_Z[i]

    /** Markings fade out far ahead and far behind, so the end of the road is never seen. */
    private fun farFade(z: Float) = (1f - smooth(1.5f, 9.5f, z)) * smooth(-16f, -10f, z)

    private fun buildGround(p: TopViewPalette) {
        roadBase = addStrip(ROWS)
        edgeBase[0] = addStrip(ROWS)
        edgeBase[1] = addStrip(ROWS)
        for (s in 0..1) {
            dashBase[s] = nv
            repeat(DASHES) {
                val a = nv
                repeat(4) { vert(0) }
                quadAt(a)
            }
        }
        val roadRgb = if (p.dark) 0xFFFFFF else 0x000000
        for (i in 0 until ROWS) {
            val f = farFade(rowZ(i))
            val road = withAlpha(roadRgb, (if (p.dark) 0.05f else 0.045f) * f)
            colors[roadBase + 2 * i] = road
            colors[roadBase + 2 * i + 1] = road
            val edge = withAlpha(p.line, (if (p.dark) 0.60f else 0.50f) * f)
            for (s in 0..1) {
                colors[edgeBase[s] + 2 * i] = edge
                colors[edgeBase[s] + 2 * i + 1] = edge
            }
        }
        // Contact shadow, battery glow, brake glow (behind the car) and the headlight cone.
        underBase = addSoft(0f, 0.002f, 0f, 2.1f, 3.5f, 0f)
        shadowBase = addSoft(0.08f, 0.004f, -0.05f, 1.22f, 2.78f, 0.66f)
        brakeBase = addSoft(0f, 0.003f, -2.9f, 2.0f, 1.7f, 0f)
        coneBase = addSoft(0f, 0.005f, 4.7f, 1.7f, 3.3f, 0.12f)
    }

    /** Lane curvature: the road bends the way the front wheels point, only while the car moves. */
    private fun curvature(st: VisualState, animated: Boolean): Float {
        val move = if (animated) smooth(0.3f, 2.0f, abs(st.speedMs).toFloat()) else if (st.moving) 1f else 0f
        val delta = Math.toRadians(st.steeringDeg).toFloat()
        return tan(delta) / WHEELBASE * CURVE_GAIN * move
    }

    private fun updateGround(st: VisualState, animated: Boolean, p: TopViewPalette) {
        val kappa = curvature(st, animated)
        val period = DASH_PERIOD
        val off = if (animated) {
            val m = st.distanceM % period
            (if (m < 0) m + period else m).toFloat()
        } else {
            0f
        }
        for (i in 0 until ROWS) {
            val z = rowZ(i)
            val bend = 0.5f * kappa * z * z
            put(roadBase + 2 * i, -EDGE_X + bend, 0f, z)
            put(roadBase + 2 * i + 1, EDGE_X + bend, 0f, z)
            for (s in 0..1) {
                val x = (if (s == 0) -EDGE_X else EDGE_X) + bend
                put(edgeBase[s] + 2 * i, x - EDGE_W / 2f, 0f, z)
                put(edgeBase[s] + 2 * i + 1, x + EDGE_W / 2f, 0f, z)
            }
        }
        val a = (if (p.dark) 0.60f else 0.52f)
        for (s in 0..1) {
            val x0 = if (s == 0) -LANE_HALF else LANE_HALF
            for (d in 0 until DASHES) {
                val v = dashBase[s] + 4 * d
                val z0 = (d - 3) * period - off
                val z1 = z0 + DASH_LEN
                val c0 = max(z0, Z_NEAR)
                val c1 = min(z1, Z_FAR)
                if (c1 - c0 < 0.05f) {
                    for (k in 0 until 4) {
                        positions[2 * (v + k)] = 0f
                        positions[2 * (v + k) + 1] = 0f
                    }
                    hide(v, 4)
                    continue
                }
                val b0 = 0.5f * kappa * c0 * c0
                val b1 = 0.5f * kappa * c1 * c1
                put(v, x0 + b0 - DASH_W / 2f, 0f, c0)
                put(v + 1, x0 + b0 + DASH_W / 2f, 0f, c0)
                put(v + 2, x0 + b1 + DASH_W / 2f, 0f, c1)
                put(v + 3, x0 + b1 - DASH_W / 2f, 0f, c1)
                val ca = withAlpha(p.line, a * farFade(c0))
                val cb = withAlpha(p.line, a * farFade(c1))
                colors[v] = ca; colors[v + 1] = ca; colors[v + 2] = cb; colors[v + 3] = cb
            }
        }
    }

    // ---- wheels --------------------------------------------------------------------------------------------
    private fun buildWheels() {
        // Order: rear left, rear right, front left, front right.
        for (w in 0..3) {
            treadBase[w] = nv
            repeat(TREAD_QUADS) { q ->
                val a = nv
                repeat(4) { vert(0) }
                quadAt(a)
            }
            lugBase[w] = nv
            repeat(LUGS) {
                val a = nv
                repeat(4) { vert(0) }
                quadAt(a)
            }
            if (w >= 2) {
                for (side in 0..1) {
                    capBase[w * 2 + side] = nv
                    repeat(3) {
                        val a = nv
                        repeat(CAP_SEGS + 1) { vert(0) }
                        for (i in 0 until CAP_SEGS) tri(a, a + 1 + i, a + 1 + (i + 1) % CAP_SEGS)
                    }
                    repeat(SPOKES) {
                        val a = nv
                        repeat(4) { vert(0) }
                        quadAt(a)
                    }
                }
            }
        }
    }

    private val treadCos = FloatArray(TREAD_QUADS + 1) { cos(ARC_MIN + it * ARC_STEP) }
    private val treadSin = FloatArray(TREAD_QUADS + 1) { sin(ARC_MIN + it * ARC_STEP) }

    private var yawCos = 1f
    private var yawSin = 0f
    private var wheelCx = 0f
    private var wheelCz = 0f

    /** Puts local wheel coordinates (lx along the axle, ly up from the hub, lz along the heading) on screen. */
    private fun putWheel(v: Int, lx: Float, ly: Float, lz: Float) {
        put(v, wheelCx + lx * yawCos + lz * yawSin, CarMesh.WHEEL_R + ly, wheelCz - lx * yawSin + lz * yawCos)
    }

    private fun updateWheels(st: VisualState, animated: Boolean, p: TopViewPalette) {
        val delta = Math.toRadians(st.steeringDeg).toFloat()
        val spin = if (animated) {
            val m = st.wheelPhaseRad % (2.0 * PI)
            (if (m < 0) m + 2.0 * PI else m).toFloat()
        } else {
            0f
        }
        // At high spin rates a discrete pattern would only alias on screen: let it blur out.
        val rev = abs(st.speedMs).toFloat() / 2.1f
        val contrast = if (animated) 1f - 0.8f * smooth(4f, 9f, rev) else 1f
        val tire = if (p.dark) TIRE_DARK else TIRE_LIGHT
        val lug = if (p.dark) LUG_DARK else LUG_LIGHT
        val hw = CarMesh.WHEEL_HALF_W
        val r = CarMesh.WHEEL_R
        for (w in 0..3) {
            val left = w % 2 == 0
            wheelCx = if (left) -CarMesh.WHEEL_X else CarMesh.WHEEL_X
            wheelCz = if (w < 2) -CarMesh.WHEEL_Z else CarMesh.WHEEL_Z
            val front = w >= 2
            val yaw = if (front) delta else 0f
            yawCos = cos(yaw)
            yawSin = sin(yaw)

            // Tread seen from above: a fixed half-cylinder, lit by its own normals.
            for (q in 0 until TREAD_QUADS) {
                val v = treadBase[w] + 4 * q
                val c0 = treadCos[q]; val s0 = treadSin[q]
                val c1 = treadCos[q + 1]; val s1 = treadSin[q + 1]
                putWheel(v, -hw, r * c0, r * s0)
                putWheel(v + 1, hw, r * c0, r * s0)
                putWheel(v + 2, hw, r * c1, r * s1)
                putWheel(v + 3, -hw, r * c1, r * s1)
                val mid = ARC_MIN + (q + 0.5f) * ARC_STEP
                val sm = sin(mid)
                val k = lightFactor(sm * yawSin, cos(mid), sm * yawCos)
                setAll(v, 4, mul(tire, k))
            }

            // Lugs: they carry the rotation. Angle measured from the top towards the front.
            for (l in 0 until LUGS) {
                val v = lugBase[w] + 4 * l
                var a = spin + 2f * PI.toFloat() * l / LUGS
                a %= 2f * PI.toFloat()
                if (a > PI.toFloat()) a -= 2f * PI.toFloat()
                val edge = min(a - (ARC_MIN + LUG_HALF), (ARC_MAX - LUG_HALF) - a)
                val fade = (edge / 0.45f).coerceIn(0f, 1f) * contrast
                if (fade <= 0.01f) {
                    for (k in 0 until 4) {
                        positions[2 * (v + k)] = 0f
                        positions[2 * (v + k) + 1] = 0f
                    }
                    hide(v, 4)
                    continue
                }
                val rr = r + 0.004f
                val a0 = a - LUG_HALF
                val a1 = a + LUG_HALF
                val inner = hw - 0.025f
                putWheel(v, -inner, rr * cos(a0), rr * sin(a0))
                putWheel(v + 1, inner, rr * cos(a0), rr * sin(a0))
                putWheel(v + 2, inner, rr * cos(a1), rr * sin(a1))
                putWheel(v + 3, -inner, rr * cos(a1), rr * sin(a1))
                val sa = sin(a)
                val k = lightFactor(sa * yawSin, cos(a), sa * yawCos)
                setAll(v, 4, withAlpha(mul(lug, k), fade))
            }

            if (front) updateCaps(w, left, spin, contrast, p)
        }
    }

    /** Wheel faces: only drawn while they face the camera, which happens for a steered front wheel. */
    private fun updateCaps(w: Int, left: Boolean, spin: Float, contrast: Float, p: TopViewPalette) {
        val hw = CarMesh.WHEEL_HALF_W
        val r = CarMesh.WHEEL_R
        for (side in 0..1) {
            val base = capBase[w * 2 + side]
            val sgn = if (side == 0) -1f else 1f
            // Normal of this face in the world, and its centre.
            val nx = sgn * yawCos
            val nz = -sgn * yawSin
            val fx = wheelCx + sgn * hw * yawCos
            val fz = wheelCz - sgn * hw * yawSin
            val toCamX = 0f - fx
            val toCamY = TopViewCamera.posY - r
            val toCamZ = TopViewCamera.posZ - fz
            val facing = nx * toCamX + nz * toCamZ
            if (facing <= 0.02f) {
                hide(base, 3 * (CAP_SEGS + 1) + 4 * SPOKES)
                continue
            }
            val k = lightFactor(nx, 0f, nz)
            val side0 = base
            val rim0 = base + CAP_SEGS + 1
            val hub0 = base + 2 * (CAP_SEGS + 1)
            val spoke0 = base + 3 * (CAP_SEGS + 1)
            val lx = sgn * hw
            for (d in 0..2) {
                val v = base + d * (CAP_SEGS + 1)
                val rad = r * DISC_SCALE[d]
                val lift = lx + sgn * 0.003f * d
                putWheel(v, lift, 0f, 0f)
                for (i in 0 until CAP_SEGS) {
                    val a = 2f * PI.toFloat() * i / CAP_SEGS
                    putWheel(v + 1 + i, lift, rad * cos(a), rad * sin(a))
                }
            }
            val sidewall = mul(if (p.dark) TIRE_DARK else TIRE_LIGHT, k * 1.25f)
            val rim = mul(RIM, k)
            val hub = mul(HUB, k)
            setAll(side0, CAP_SEGS + 1, sidewall)
            setAll(rim0, CAP_SEGS + 1, rim)
            setAll(hub0, CAP_SEGS + 1, hub)
            for (s in 0 until SPOKES) {
                val v = spoke0 + 4 * s
                val a = spin + 2f * PI.toFloat() * s / SPOKES
                val da = 0.13f
                val liftS = lx + sgn * 0.007f
                putWheel(v, liftS, r * 0.18f * cos(a - da * 0.4f), r * 0.18f * sin(a - da * 0.4f))
                putWheel(v + 1, liftS, r * 0.62f * cos(a - da), r * 0.62f * sin(a - da))
                putWheel(v + 2, liftS, r * 0.62f * cos(a + da), r * 0.62f * sin(a + da))
                putWheel(v + 3, liftS, r * 0.18f * cos(a + da * 0.4f), r * 0.18f * sin(a + da * 0.4f))
                setAll(v, 4, withAlpha(mul(SPOKE, k), 0.55f + 0.45f * contrast))
            }
        }
    }

    // ---- body, lamps -----------------------------------------------------------------------------------------
    private fun buildBody(p: TopViewPalette) {
        val bodyC = if (p.dark) BODY_DARK else BODY_LIGHT
        for (f in CarMesh.body) {
            val a = nv
            for (i in 0 until f.count) put(vert(0), f.pts[3 * i], f.pts[3 * i + 1], f.pts[3 * i + 2])
            fanAt(a, f.count)
            setAll(a, f.count, shadeStatic(f, bodyC, p))
        }
    }

    private fun shadeStatic(f: Face3D, bodyC: Int, p: TopViewPalette): Int {
        var vx = 0f - f.cx
        var vy = TopViewCamera.posY - f.cy
        var vz = TopViewCamera.posZ - f.cz
        val vl = sqrt(vx * vx + vy * vy + vz * vz)
        vx /= vl; vy /= vl; vz /= vl
        val k = lightFactor(f.nx, f.ny, f.nz)
        val gloss = if (f.material == Mat.GLASS || f.material == Mat.TRIM) 1f else 0.18f
        // Half vector for a highlight.
        var hx = LX + vx
        var hy = LY + vy
        var hz = LZ + vz
        val hl = sqrt(hx * hx + hy * hy + hz * hz).coerceAtLeast(1e-6f)
        hx /= hl; hy /= hl; hz /= hl
        val nh = max(0f, f.nx * hx + f.ny * hy + f.nz * hz)
        val spec = pow(nh, if (f.material == Mat.GLASS) 30f else 14f) * (if (f.material == Mat.GLASS) 0.40f else 0.16f)
        val base = when (f.material) {
            Mat.BODY -> bodyC
            Mat.GLASS -> if (p.dark) GLASS_DARK else GLASS_LIGHT
            Mat.TRIM -> TRIM
            else -> mul(bodyC, 1.12f)
        }
        // Glass also mirrors the sky: faces turned up pick up a little blue.
        var c = mul(base, if (f.material == Mat.GLASS) 0.85f + 0.3f * k else k)
        if (f.material == Mat.GLASS) c = mix(c, SKY, 0.03f + 0.07f * max(0f, f.ny))
        return addLight(c, spec * gloss)
    }

    private fun buildDecals(p: TopViewPalette) {
        decalCount = 0
        for (f in CarMesh.decals) {
            val a = nv
            for (i in 0 until f.count) put(vert(0), f.pts[3 * i], f.pts[3 * i + 1], f.pts[3 * i + 2])
            fanAt(a, f.count)
            decalBase[decalCount] = a
            decalTag[decalCount] = f.tag
            decalCount++
            when (f.tag) {
                Tag.NONE -> setAll(a, f.count, shadeStatic(f, if (p.dark) BODY_DARK else BODY_LIGHT, p))
                Tag.PLUG -> setAll(a, f.count, p.ev)
            }
        }
    }

    private fun updateLamps(input: TopViewInput, animated: Boolean, nowMs: Long, p: TopViewPalette) {
        val blinkOn = !animated || (nowMs % BLINK_PERIOD_MS) < BLINK_PERIOD_MS / 2
        val left = input.turn == TurnState.LEFT && blinkOn
        val right = input.turn == TurnState.RIGHT && blinkOn
        val head = if (p.dark) HEAD_ON else HEAD_DAY
        val tail = if (input.brake) TAIL_BRAKE else TAIL_ON
        val amberOff = if (p.dark) AMBER_OFF_DARK else AMBER_OFF_LIGHT
        for (i in 0 until decalCount) {
            val v = decalBase[i]
            val c = when (decalTag[i]) {
                Tag.HEAD_L, Tag.HEAD_R -> head
                Tag.DRL -> withAlpha(head, 0.85f)
                Tag.TAIL_TOP, Tag.TAIL_FACE -> tail
                Tag.TURN_FL, Tag.TURN_RL -> if (left) AMBER_ON else amberOff
                Tag.TURN_FR, Tag.TURN_RR -> if (right) AMBER_ON else amberOff
                else -> continue
            }
            setAll(v, 4, c)
        }
    }

    // ---- gauge on the roof -----------------------------------------------------------------------------------
    private fun buildGauge(p: TopViewPalette) {
        val y = CarMesh.ROOF_Y
        val hw = CarMesh.GAUGE_HALF_W
        val z0 = CarMesh.GAUGE_Z0
        val z1 = CarMesh.GAUGE_Z1
        fun flat(x0: Float, x1: Float, za: Float, zb: Float, lift: Float, color: Int): Int {
            val a = nv
            repeat(4) { vert(color) }
            quadAt(a)
            put(a, x0, y + lift, za)
            put(a + 1, x1, y + lift, za)
            put(a + 2, x1, y + lift, zb)
            put(a + 3, x0, y + lift, zb)
            return a
        }
        flat(-hw - 0.035f, hw + 0.035f, z0 - 0.045f, z1 + 0.045f, 0.010f, GAUGE_FRAME)
        flat(-0.075f, 0.075f, z1 + 0.04f, z1 + 0.095f, 0.010f, GAUGE_FRAME)
        flat(-hw, hw, z0, z1, 0.016f, GAUGE_TRACK)
        // The part used on this trip (empty cells with an outline) and the charge level: positions move.
        gaugeUsedBase = nv
        repeat(5) {
            val a = nv
            repeat(4) { vert(0) }
            quadAt(a)
        }
        gaugeFillBase = nv
        repeat(4) { vert(0) }
        quadAt(gaugeFillBase)
        // Cell dividers over everything.
        for (i in 1 until CarMesh.GAUGE_CELLS) {
            val z = z0 + (z1 - z0) * i / CarMesh.GAUGE_CELLS
            flat(-hw, hw, z - 0.007f, z + 0.007f, 0.034f, withAlpha(GAUGE_FRAME, 0.92f))
        }
    }

    private fun updateGauge(input: TopViewInput, p: TopViewPalette) {
        val y = CarMesh.ROOF_Y
        val hw = CarMesh.GAUGE_HALF_W
        val z0 = CarMesh.GAUGE_Z0
        val len = CarMesh.GAUGE_Z1 - z0
        val soc = clamp01(input.socFraction)
        val used = clamp01(input.consumedFraction)
        val color = if (soc < LOW_SOC) p.fuel else p.ev
        val top = z0 + len * soc
        // Fill: opaque, the colour of the charge level.
        val f = gaugeFillBase
        if (soc <= 0.004f) {
            hide(f, 4)
        } else {
            put(f, -hw, y + 0.024f, z0)
            put(f + 1, hw, y + 0.024f, z0)
            put(f + 2, hw, y + 0.024f, top)
            put(f + 3, -hw, y + 0.024f, top)
            setAll(f, 4, color)
        }
        // Used on this trip: empty cells (track colour) with an outline in the gauge colour.
        val u = gaugeUsedBase
        val usedTop = min(CarMesh.GAUGE_Z1, top + len * used)
        if (usedTop - top < 0.012f) {
            hide(u, 20)
        } else {
            val lw = 0.016f
            fun seg(i: Int, xa: Float, xb: Float, za: Float, zb: Float, c: Int, lift: Float) {
                val v = u + 4 * i
                put(v, xa, y + lift, za)
                put(v + 1, xb, y + lift, za)
                put(v + 2, xb, y + lift, zb)
                put(v + 3, xa, y + lift, zb)
                setAll(v, 4, c)
            }
            seg(0, -hw, hw, top, usedTop, GAUGE_EMPTY, 0.022f)
            val edge = withAlpha(color, 0.9f)
            seg(1, -hw, hw, usedTop - lw, usedTop, edge, 0.026f)
            seg(2, -hw, hw, top, top + lw, edge, 0.026f)
            seg(3, -hw, -hw + lw, top, usedTop, edge, 0.026f)
            seg(4, hw - lw, hw, top, usedTop, edge, 0.026f)
        }
    }

    // ---- glows, ribbons, airflow ------------------------------------------------------------------------------
    private fun buildOverlays(p: TopViewPalette) {
        val zt = CarMesh.TAIL_Z
        val zn = CarMesh.NOSE_Z
        // Lamp glows: turn signals at the four corners, the tail, the headlamps.
        val zf = zn - 0.58f
        val zr = zt + 0.20f
        val xf = 0.95f * CarMesh.halfWidth(zf)
        val xr = 0.82f * CarMesh.halfWidth(zr)
        turnGlowBase[0] = addSoft(-xf, CarMesh.topY(xf, zf) + 0.05f, zf, 0.46f, 0.46f, 0f)
        turnGlowBase[1] = addSoft(xf, CarMesh.topY(xf, zf) + 0.05f, zf, 0.46f, 0.46f, 0f)
        turnGlowBase[2] = addSoft(-xr, CarMesh.topY(xr, zr) + 0.05f, zr, 0.5f, 0.5f, 0f)
        turnGlowBase[3] = addSoft(xr, CarMesh.topY(xr, zr) + 0.05f, zr, 0.5f, 0.5f, 0f)
        tailGlowBase = addSoft(0f, CarMesh.topY(0f, zt + 0.1f) + 0.05f, zt + 0.1f, 1.2f, 0.7f, 0.1f)
        val xh = 0.76f * CarMesh.halfWidth(zn - 0.2f)
        headGlowBase[0] = addSoft(-xh, CarMesh.topY(xh, zn - 0.2f) + 0.05f, zn - 0.2f, 0.62f, 0.62f, 0f)
        headGlowBase[1] = addSoft(xh, CarMesh.topY(xh, zn - 0.2f) + 0.05f, zn - 0.2f, 0.62f, 0.62f, 0f)

        val n = RIBBON_POINTS
        val pts = FloatArray(3 * n)
        // Regeneration: a wide soft band and a bright core from each front wheel to the battery gauge.
        for (side in 0..1) {
            val sx = if (side == 0) -1f else 1f
            for (i in 0 until n) {
                val u = i / (n - 1f)
                val p0x = sx * 0.97f; val p0y = 0.70f; val p0z = CarMesh.WHEEL_Z
                val p1x = sx * 0.80f; val p1y = 1.55f; val p1z = 0.95f
                val p2x = sx * 0.12f; val p2y = CarMesh.ROOF_Y + 0.05f; val p2z = CarMesh.GAUGE_Z1 - 0.05f
                val a = (1f - u) * (1f - u)
                val b = 2f * (1f - u) * u
                val c = u * u
                pts[3 * i] = a * p0x + b * p1x + c * p2x
                pts[3 * i + 1] = a * p0y + b * p1y + c * p2y
                pts[3 * i + 2] = a * p0z + b * p1z + c * p2z
            }
            regenGlowBase[side] = addRibbon(pts, n, 0.17f)
            regenCoreBase[side] = addRibbon(pts, n, 0.055f)
        }
    }

    /** Airflow over the glass roof: three streamlines from the front of the cabin to the rear, under the gauge. */
    private fun buildAirflow() {
        val n = RIBBON_POINTS
        val pts = FloatArray(3 * n)
        for (b in 0 until AIR_BANDS) {
            val x0 = (b - 1) * 0.30f
            for (i in 0 until n) {
                val s = i / (n - 1f)
                val z = 0.92f - s * 2.3f
                val x = x0 + 0.11f * sin(2f * PI.toFloat() * (s * 1.4f) + b * 2.1f)
                pts[3 * i] = x
                pts[3 * i + 1] = cabinTopY(z) + 0.02f
                pts[3 * i + 2] = z
            }
            airBase[b] = addRibbon(pts, n, 0.055f)
        }
    }

    /** Height of the glass cabin's centre line at [z]. */
    private fun cabinTopY(z: Float): Float {
        val zs = floatArrayOf(1.05f, 0.78f, 0.50f, 0.20f, -0.20f, -0.60f, -0.95f, -1.30f, -1.60f)
        val ys = floatArrayOf(0.98f, 1.20f, 1.44f, CarMesh.ROOF_Y, CarMesh.ROOF_Y + 0.004f, 1.456f, 1.36f, 1.20f, 1.02f)
        if (z >= zs.first()) return ys.first()
        if (z <= zs.last()) return ys.last()
        var i = 0
        while (z < zs[i + 1]) i++
        val t = (z - zs[i]) / (zs[i + 1] - zs[i])
        return ys[i] + (ys[i + 1] - ys[i]) * t
    }

    private fun updateOverlays(st: VisualState, input: TopViewInput, animated: Boolean, nowMs: Long, t: Float, p: TopViewPalette) {
        val ghostKeep = 1f - 0.5f * st.ghost.toFloat()
        // Ground: shadow, battery glow, brake glow, headlight cone.
        val shadowA = (if (p.dark) 0.50f else 0.26f) * ghostKeep
        setSoft(shadowBase, withAlpha(0x000000, shadowA), withAlpha(0x000000, 0f))
        val glowColor = if (clamp01(input.socFraction) < LOW_SOC) p.fuel else p.ev
        setSoft(underBase, withAlpha(glowColor, (if (p.dark) 0.26f else 0.13f) * ghostKeep), withAlpha(glowColor, 0f))
        if (input.brake) {
            setSoft(brakeBase, withAlpha(TAIL_BRAKE, 0.34f), withAlpha(TAIL_BRAKE, 0f))
        } else {
            setSoft(brakeBase, 0, 0)
        }
        if (p.dark) {
            setSoft(coneBase, withAlpha(HEAD_ON, 0.17f * ghostKeep), withAlpha(HEAD_ON, 0f))
        } else {
            setSoft(coneBase, 0, 0)
        }

        // Lamp glows on the car.
        val blinkOn = !animated || nowMs % BLINK_PERIOD_MS < BLINK_PERIOD_MS / 2
        val left = input.turn == TurnState.LEFT && blinkOn
        val right = input.turn == TurnState.RIGHT && blinkOn
        val amber = withAlpha(AMBER_ON, 0.55f)
        val none = withAlpha(AMBER_ON, 0f)
        setSoft(turnGlowBase[0], if (left) amber else none, none)
        setSoft(turnGlowBase[1], if (right) amber else none, none)
        setSoft(turnGlowBase[2], if (left) amber else none, none)
        setSoft(turnGlowBase[3], if (right) amber else none, none)
        if (input.brake) {
            setSoft(tailGlowBase, withAlpha(TAIL_BRAKE, 0.45f), withAlpha(TAIL_BRAKE, 0f))
        } else {
            setSoft(tailGlowBase, 0, 0)
        }
        for (i in 0..1) {
            if (p.dark) setSoft(headGlowBase[i], withAlpha(HEAD_ON, 0.30f), withAlpha(HEAD_ON, 0f)) else setSoft(headGlowBase[i], 0, 0)
        }

        // Airflow: faint, cool, only while the climate system is on.
        val n = RIBBON_POINTS
        for (b in 0 until AIR_BANDS) {
            if (input.climateOn) {
                setRibbon(airBase[b], n, AIR, 0.30f, animated, t + b * 0.37f, 1.5f, 0.45f, 0.30f)
            } else {
                for (i in 0 until 2 * n) colors[airBase[b] + i] = 0
            }
        }

        // Regeneration: light packets from the front wheels into the battery; static strength when not animated.
        val regen = st.regen.toFloat()
        for (side in 0..1) {
            if (regen < 0.02f) {
                for (i in 0 until 2 * n) {
                    colors[regenGlowBase[side] + i] = 0
                    colors[regenCoreBase[side] + i] = 0
                }
            } else {
                setRibbon(regenGlowBase[side], n, p.ev, 0.10f + 0.30f * regen, animated, t, 2.2f, 1.1f, 0.45f)
                setRibbon(regenCoreBase[side], n, p.ev, 0.35f + 0.60f * regen, animated, t, 2.2f, 1.1f, 0.30f)
            }
        }
    }

    // ---- per frame -------------------------------------------------------------------------------------------
    /**
     * Writes the frame. [animated] false = the restricted mode: the same picture without anything that moves
     * (no wheel pattern turning, lanes at rest, lamps steady, ribbons steady).
     */
    fun update(st: VisualState, input: TopViewInput, animated: Boolean, nowMs: Long) {
        val p = pal ?: return
        if (vertexCount == 0) return
        // Wave clock wraps every 100 s (a whole number of periods of every wave), so a long uptime keeps float precision.
        val t = if (animated) (nowMs % 100_000L) / 1000f else 0f
        updateGround(st, animated, p)
        updateWheels(st, animated, p)
        updateLamps(input, animated, nowMs, p)
        updateGauge(input, p)
        updateOverlays(st, input, animated, nowMs, t, p)
        carAlpha = 1f - 0.5f * st.ghost.toFloat()
    }

    private fun computeCarBounds() {
        var l = Float.MAX_VALUE
        var tp = Float.MAX_VALUE
        var r = -Float.MAX_VALUE
        var b = -Float.MAX_VALUE
        for (xs in floatArrayOf(-1.9f, 1.9f)) {
            for (zs in floatArrayOf(-3.4f, 3.4f)) {
                for (ys in floatArrayOf(0f, 1.7f)) {
                    val d = TopViewCamera.depth(ys, zs)
                    val px = cx + xs * focal / d
                    val py = cy - TopViewCamera.up(ys, zs) * focal / d
                    l = min(l, px); r = max(r, px); tp = min(tp, py); b = max(b, py)
                }
            }
        }
        carBounds[0] = max(0f, l)
        carBounds[1] = max(0f, tp)
        carBounds[2] = min(width, r)
        carBounds[3] = min(height, b)
    }

    companion object {
        const val MAX_VERTS = 6000
        const val MAX_INDICES = 24000

        /** Field of view: focal length in units of the viewport's short side. */
        const val FOCAL_K = 1.62f
        const val CENTER_Y = 0.52f

        const val WHEELBASE = 2.84f
        private const val CURVE_GAIN = 0.35f

        /** Rows of the road strips, close together near the car where the bend matters. */
        private val ROW_Z = floatArrayOf(-16f, -10f, -6f, -3.5f, -1.5f, 0f, 1.5f, 3f, 4.5f, 6f, 8f, 10.5f, 14f, 19f, 26f, 36f)
        private val ROWS = ROW_Z.size
        private const val Z_NEAR = -16f
        private const val Z_FAR = 36f
        private const val LANE_HALF = 1.6f
        private const val EDGE_X = 4.8f
        private const val EDGE_W = 0.14f
        private const val DASHES = 10
        private const val DASH_LEN = 2.0f
        private const val DASH_PERIOD = 6.0f
        private const val DASH_W = 0.11f

        private const val SOFT_SEGS = 28
        private const val RIBBON_POINTS = 16
        private const val AIR_BANDS = 3

        private const val TREAD_QUADS = 8
        private const val ARC_MIN = -2.0944f
        private const val ARC_MAX = 1.0472f
        private const val ARC_STEP = (ARC_MAX - ARC_MIN) / TREAD_QUADS
        private const val LUGS = 6
        private const val LUG_HALF = 0.14f
        private const val CAP_SEGS = 12
        private const val SPOKES = 5
        private val DISC_SCALE = floatArrayOf(1f, 0.66f, 0.2f)

        const val BLINK_PERIOD_MS = 667L
        private const val LOW_SOC = 0.30f

        private const val LX = -0.4466f
        private const val LY = 0.8437f
        private const val LZ = -0.2978f
        private const val FX = 0.5547f
        private const val FY = 0.3698f
        private const val FZ = 0.7396f

        private val BODY_LIGHT = argb(255, 0xC2, 0xC9, 0xD4)
        private val BODY_DARK = argb(255, 0xD0, 0xD6, 0xE0)
        private val GLASS_LIGHT = argb(255, 0x1B, 0x28, 0x37)
        private val GLASS_DARK = argb(255, 0x22, 0x31, 0x44)
        private val TRIM = argb(255, 0x2A, 0x32, 0x3D)
        private val SKY = argb(255, 0x7F, 0xB4, 0xE6)
        private val TIRE_LIGHT = argb(255, 0x1E, 0x22, 0x28)
        private val TIRE_DARK = argb(255, 0x2A, 0x2F, 0x38)
        private val LUG_LIGHT = argb(255, 0x4A, 0x52, 0x5E)
        private val LUG_DARK = argb(255, 0x5C, 0x65, 0x73)
        private val RIM = argb(255, 0xC9, 0xD1, 0xDB)
        private val HUB = argb(255, 0x4A, 0x52, 0x5E)
        private val SPOKE = argb(255, 0x5B, 0x65, 0x72)
        private val HEAD_ON = argb(255, 0xEA, 0xF6, 0xFF)
        private val HEAD_DAY = argb(255, 0xF4, 0xF9, 0xFF)
        private val TAIL_ON = argb(255, 0xB0, 0x14, 0x1C)
        private val TAIL_BRAKE = argb(255, 0xFF, 0x2B, 0x2B)
        private val AMBER_ON = argb(255, 0xFF, 0xA3, 0x1A)
        private val AMBER_OFF_LIGHT = argb(255, 0xDB, 0xB2, 0x6A)
        private val AMBER_OFF_DARK = argb(255, 0x6B, 0x4A, 0x12)
        private val GAUGE_FRAME = argb(255, 0x12, 0x18, 0x20)
        private val GAUGE_TRACK = argb(255, 0x3C, 0x46, 0x54)
        private val GAUGE_EMPTY = argb(255, 0x20, 0x27, 0x31)
        private val AIR = argb(255, 0xB8, 0xE4, 0xFF)

        private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b

        private fun clamp01(x: Double): Float = if (x.isNaN()) 0f else x.coerceIn(0.0, 1.0).toFloat()

        private fun smooth(e0: Float, e1: Float, x: Float): Float {
            val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        private fun pow(x: Float, p: Float): Float = Math.pow(x.toDouble(), p.toDouble()).toFloat()

        /** [rgb]'s colour with opacity [a] (0..1). */
        fun withAlpha(rgb: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255f + 0.5f).toInt() shl 24) or (rgb and 0x00FFFFFF)

        private fun mul(c: Int, k: Float): Int {
            val r = (((c shr 16) and 255) * k).toInt().coerceIn(0, 255)
            val g = (((c shr 8) and 255) * k).toInt().coerceIn(0, 255)
            val b = ((c and 255) * k).toInt().coerceIn(0, 255)
            return (c and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
        }

        private fun addLight(c: Int, s: Float): Int {
            val add = (s * 255f).toInt()
            val r = (((c shr 16) and 255) + add).coerceIn(0, 255)
            val g = (((c shr 8) and 255) + add).coerceIn(0, 255)
            val b = ((c and 255) + add).coerceIn(0, 255)
            return (c and (0xFF shl 24)) or (r shl 16) or (g shl 8) or b
        }

        private fun mix(c0: Int, c1: Int, t: Float): Int {
            fun ch(sh: Int) = (((c0 shr sh) and 255) * (1f - t) + ((c1 shr sh) and 255) * t).toInt().coerceIn(0, 255)
            return (c0 and (0xFF shl 24)) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }

        /** Key light from the upper left and behind, a weak fill from the front right, some ambient. */
        private fun lightFactor(nx: Float, ny: Float, nz: Float): Float {
            val d = max(0f, nx * LX + ny * LY + nz * LZ)
            val f = max(0f, nx * FX + ny * FY + nz * FZ)
            return 0.42f + 0.58f * d + 0.14f * f
        }
    }
}
