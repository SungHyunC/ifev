package com.ifev.app.ui.topview

import com.ifev.app.data.GearState
import com.ifev.app.data.TurnState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The triangle list the renderer hands to drawVertices: valid, steady where it must be, moving where it must. */
class TopViewRendererTest {

    // Sentinel colours that appear nowhere else, so a test can find the gauge by colour.
    private val ev = 0xFF00FF01.toInt()
    private val fuel = 0xFFFF0102.toInt()
    private val night = TopViewPalette(true, ev, fuel, 0xFF686A72.toInt())
    private val day = night.copy(dark = false)

    private val amberOn = 0xFFFFA31A.toInt()
    private val brakeRed = 0xFFFF2B2B.toInt()

    private val driving = VisualState(speedMs = 10.0, steeringKnown = true, mode = MotionMode.MOVING)
    private val input = TopViewInput(speedMs = 10.0, speedAtMs = 0, gear = GearState.DRIVE, socFraction = 0.7)

    private fun renderer(w: Float = 1682f, h: Float = 1210f, pal: TopViewPalette = night) =
        TopViewRenderer().also { it.layout(w, h, pal) }

    private fun TopViewRenderer.frame(st: VisualState = driving, inp: TopViewInput = input, animated: Boolean = true, nowMs: Long = 0): TopViewRenderer {
        update(st, inp, animated, nowMs)
        return this
    }

    private fun TopViewRenderer.snapshot(): Pair<FloatArray, IntArray> =
        positions.copyOf(2 * vertexCount) to colors.copyOf(vertexCount)

    private fun TopViewRenderer.alphaOf(v: Int) = colors[v] ushr 24

    // ---- validity --------------------------------------------------------------------------------------

    @Test
    fun layoutGivesAValidTriangleListAtAnySize() {
        for ((w, h) in listOf(1682f to 1210f, 500f to 800f, 300f to 300f, 2560f to 1440f, 64f to 48f)) {
            val r = renderer(w, h)
            assertTrue(r.ready)
            assertTrue("${r.vertexCount} vertices", r.vertexCount in 1..32767)
            assertEquals(0, (r.groundIndexCount) % 3)
            assertEquals(0, (r.carIndexCount) % 3)
            assertTrue(r.groundIndexCount > 0 && r.carIndexCount > 0)
            val total = r.groundIndexCount + r.carIndexCount
            assertTrue(total <= r.indices.size)
            for (i in 0 until total) {
                val ix = r.indices[i].toInt()
                assertTrue("index $ix of ${r.vertexCount}", ix in 0 until r.vertexCount)
            }
        }
    }

    @Test
    fun theWholePictureIsOneBatchWellUnderTheLimits() {
        val r = renderer()
        // One drawVertices call has to carry everything; keep it small enough for a slow emulator.
        assertTrue("${r.vertexCount} vertices", r.vertexCount < 4500)
        assertTrue("${r.groundIndexCount + r.carIndexCount} indices", r.groundIndexCount + r.carIndexCount < 15000)
    }

    @Test
    fun noInputMakesNaNOrInfiniteGeometry() {
        val r = renderer()
        val steer = listOf(-40.0, -17.5, 0.0, 12.0, 40.0)
        val soc = listOf(0.0, 0.1, 0.3, 0.75, 1.0)
        var n = 0
        for (s in steer) for (c in soc) for (animated in listOf(true, false)) for (turn in TurnState.values()) {
            val st = VisualState(
                steeringDeg = s, steeringKnown = true, speedMs = 3.0 * n, wheelPhaseRad = 1.7 * n, distanceM = 11.3 * n,
                regen = (n % 5) / 4.0, ghost = (n % 3) / 2.0, mode = MotionMode.MOVING,
            )
            val inp = input.copy(socFraction = c, consumedFraction = (n % 7) / 6.0, turn = turn, brake = n % 2 == 0, climateOn = n % 3 == 0, steeringDeg = s)
            r.update(st, inp, animated, 1000L * n)
            for (i in 0 until 2 * r.vertexCount) assertTrue("position $i = ${r.positions[i]} in case $n", r.positions[i].isFinite() && abs(r.positions[i]) < 1e6f)
            n++
        }
        assertTrue(n > 100)
    }

    @Test
    fun layoutAgainForTheSameSizeAndPaletteChangesNothing() {
        val r = renderer().frame(nowMs = 123)
        val before = r.snapshot()
        r.layout(1682f, 1210f, night)
        val after = r.snapshot()
        assertTrue(before.first.contentEquals(after.first))
        assertTrue(before.second.contentEquals(after.second))
        // A different palette does rebuild.
        r.layout(1682f, 1210f, day)
        r.update(driving, input, true, 0)
        assertFalse(before.second.contentEquals(r.snapshot().second))
    }

    // ---- the picture ------------------------------------------------------------------------------------

    @Test
    fun theCarSitsInTheMiddleOfTheView() {
        val w = 1682f
        val h = 1210f
        val r = renderer(w, h).frame()
        var l = Float.MAX_VALUE; var rt = -Float.MAX_VALUE; var t = Float.MAX_VALUE; var b = -Float.MAX_VALUE
        val from = r.groundIndexCount
        for (i in from until from + r.carIndexCount) {
            val v = r.indices[i].toInt()
            if (r.alphaOf(v) == 0) continue          // hidden parts are parked at (0, 0)
            val x = r.positions[2 * v]; val y = r.positions[2 * v + 1]
            l = minOf(l, x); rt = maxOf(rt, x); t = minOf(t, y); b = maxOf(b, y)
        }
        assertEquals(w / 2f, (l + rt) / 2f, w * 0.03f)
        assertEquals(h / 2f, (t + b) / 2f, h * 0.10f)
        assertTrue("car is ${(b - t) / h} of the height", (b - t) / h in 0.45f..0.85f)
    }

    @Test
    fun theCarMeshFacesTheCameraAndIsSortedFarToNear() {
        val faces = CarMesh.body
        assertTrue(faces.size > 100)
        assertTrue(faces.all { it.facesCamera() })
        val depths = faces.map { TopViewCamera.depth(it.cy, it.cz) }
        assertEquals(depths.sortedDescending(), depths)
        // Painted body and glass are both there, and the wheel arches of the plan are symmetric.
        assertTrue(faces.any { it.material == Mat.GLASS })
        assertTrue(faces.any { it.material == Mat.BODY })
        for (z in listOf(-2.2f, -1.0f, 0f, 1.2f, 2.2f)) {
            assertTrue(CarMesh.halfWidth(z) in 0.3f..CarMesh.HALF_WIDTH + 1e-4f)
        }
        // Every lamp has its place, front and back.
        for (tag in listOf(Tag.HEAD_L, Tag.HEAD_R, Tag.DRL, Tag.TAIL_TOP, Tag.TAIL_FACE, Tag.TURN_FL, Tag.TURN_FR, Tag.TURN_RL, Tag.TURN_RR, Tag.PLUG)) {
            assertTrue("lamp $tag", CarMesh.decals.any { it.tag == tag })
        }
    }

    @Test
    fun noGrilleOnTheNose() {
        // An EV: nothing but a smooth hood and a slim light bar between the two lamp clusters.
        val nose = CarMesh.decals.filter { it.cz > CarMesh.NOSE_Z - 0.6f && it.cz < CarMesh.NOSE_Z }
        assertTrue(nose.all { it.tag in listOf(Tag.HEAD_L, Tag.HEAD_R, Tag.DRL, Tag.TURN_FL, Tag.TURN_FR) })
    }

    @Test
    fun dayAndNightPaintTheCarDifferently() {
        val d = renderer(pal = day).frame().snapshot().second
        val n = renderer(pal = night).frame().snapshot().second
        assertFalse(d.contentEquals(n))
    }

    // ---- gauge -------------------------------------------------------------------------------------------

    @Test
    fun gaugeFillRisesWithTheChargeLevel() {
        val r = renderer()
        val top = { soc: Double -> r.frame(inp = input.copy(socFraction = soc)).positions[2 * (r.gaugeFillStart() + 2) + 1] }
        val low = top(0.2)
        val mid = top(0.5)
        val full = top(1.0)
        assertTrue("screen y falls as the fill rises: $low $mid $full", low > mid && mid > full)
    }

    @Test
    fun gaugeIsTealAndTurnsAmberBelowThirtyPercent() {
        val r = renderer()
        fun fill(soc: Double) = r.frame(inp = input.copy(socFraction = soc)).colors[r.gaugeFillStart()]
        assertEquals(ev, fill(0.85))
        assertEquals(ev, fill(0.30))
        assertEquals(fuel, fill(0.2999))
        assertEquals(fuel, fill(0.05))
        r.frame(inp = input.copy(socFraction = 0.0))
        assertEquals(0, r.alphaOf(r.gaugeFillStart()))
    }

    @Test
    fun theUsedPartIsEmptyCellsRightAboveTheFill() {
        val r = renderer()
        val u = r.gaugeUsedStart()
        r.frame(inp = input.copy(socFraction = 0.5, consumedFraction = 0.0))
        assertEquals(0, r.alphaOf(u))
        r.frame(inp = input.copy(socFraction = 0.5, consumedFraction = 0.2))
        assertTrue(r.alphaOf(u) > 0)
        val fillTop = r.positions[2 * (r.gaugeFillStart() + 2) + 1]
        val usedTop = r.positions[2 * (u + 2) + 1]
        val usedBottom = r.positions[2 * (u + 1) + 1]
        assertEquals(fillTop, usedBottom, 0.6f)       // starts where the fill ends
        assertTrue(usedTop < fillTop)                  // and goes further up the gauge
        // The part used can never run past the top of the gauge.
        r.frame(inp = input.copy(socFraction = 0.9, consumedFraction = 0.9))
        val cap = r.positions[2 * (u + 2) + 1]
        assertTrue(cap >= r.frame(inp = input.copy(socFraction = 1.0)).positions[2 * (r.gaugeFillStart() + 2) + 1] - 0.6f)
    }

    // ---- lamps -----------------------------------------------------------------------------------------------

    private fun TopViewRenderer.lamp(tag: Int) = colors[lampStart(tag)]

    @Test
    fun turnSignalBlinksAtOnePointFiveHertzWhileAnimated() {
        val r = renderer()
        val left = input.copy(turn = TurnState.LEFT)
        // 667 ms period: on for the first 333 ms, off for the next 334.
        assertEquals(amberOn, r.frame(inp = left, nowMs = 0).lamp(Tag.TURN_FL))
        assertEquals(amberOn, r.frame(inp = left, nowMs = 300).lamp(Tag.TURN_RL))
        assertNotEquals(amberOn, r.frame(inp = left, nowMs = 400).lamp(Tag.TURN_FL))
        assertNotEquals(amberOn, r.frame(inp = left, nowMs = 660).lamp(Tag.TURN_RL))
        assertEquals(amberOn, r.frame(inp = left, nowMs = 670).lamp(Tag.TURN_FL))
        // Only the signalled side lights.
        assertNotEquals(amberOn, r.frame(inp = left, nowMs = 0).lamp(Tag.TURN_FR))
        assertNotEquals(amberOn, r.frame(inp = left, nowMs = 0).lamp(Tag.TURN_RR))
        val right = input.copy(turn = TurnState.RIGHT)
        assertEquals(amberOn, r.frame(inp = right, nowMs = 0).lamp(Tag.TURN_RR))
        assertNotEquals(amberOn, r.frame(inp = right, nowMs = 0).lamp(Tag.TURN_RL))
        assertNotEquals(amberOn, r.frame(inp = input, nowMs = 0).lamp(Tag.TURN_FL))
    }

    @Test
    fun restrictedTurnSignalIsSteadyOn() {
        val r = renderer()
        val left = input.copy(turn = TurnState.LEFT)
        for (t in listOf(0L, 400L, 660L, 5000L)) {
            assertEquals("at $t", amberOn, r.frame(inp = left, animated = false, nowMs = t).lamp(Tag.TURN_FL))
        }
    }

    @Test
    fun brakeLampsGoBrightRed() {
        val r = renderer()
        assertNotEquals(brakeRed, r.frame(inp = input.copy(brake = false)).lamp(Tag.TAIL_TOP))
        assertEquals(brakeRed, r.frame(inp = input.copy(brake = true)).lamp(Tag.TAIL_TOP))
        assertEquals(brakeRed, r.frame(inp = input.copy(brake = true)).lamp(Tag.TAIL_FACE))
        assertNotEquals(brakeRed, r.frame(inp = input.copy(brake = false)).lamp(Tag.TAIL_FACE))
    }

    // ---- movement ----------------------------------------------------------------------------------------------

    @Test
    fun restrictedPictureHasNothingThatMoves() {
        val r = renderer()
        val a = run {
            r.update(driving.copy(wheelPhaseRad = 0.0, distanceM = 0.0), input.copy(climateOn = true, turn = TurnState.RIGHT, regenLevel = 0.6), false, 0)
            r.snapshot()
        }
        // Later, further along the road, wheels turned: the picture is the same, pixel for pixel.
        r.update(driving.copy(wheelPhaseRad = 37.3, distanceM = 123.4), input.copy(climateOn = true, turn = TurnState.RIGHT, regenLevel = 0.6), false, 98_765)
        val b = r.snapshot()
        assertTrue(a.first.contentEquals(b.first))
        assertTrue(a.second.contentEquals(b.second))
    }

    @Test
    fun restrictedRegenerationAndAirflowAreShownAtSteadyStrength() {
        val r = renderer()
        val st = driving.copy(regen = 0.7)
        val inp = input.copy(climateOn = true)
        r.update(st, inp, false, 0)
        val regen = r.regenCoreStart(0) + 2 * 6
        val air = r.airflowStart(1) + 2 * 6
        val a = r.alphaOf(regen) to r.alphaOf(air)
        assertTrue(a.first > 0 && a.second > 0)
        r.update(st, inp, false, 4321)
        assertEquals(a, r.alphaOf(regen) to r.alphaOf(air))
    }

    @Test
    fun animatedRegenerationAndAirflowFlow() {
        val r = renderer()
        val st = driving.copy(regen = 0.7)
        val inp = input.copy(climateOn = true)
        val snaps = (0 until 8).map { r.frame(st, inp, true, 137L * it).snapshot().second }
        assertTrue(snaps.drop(1).any { !it.contentEquals(snaps[0]) })
        val regenAlphas = (0 until 8).map { r.frame(st, inp, true, 137L * it).alphaOf(r.regenCoreStart(1) + 2 * 8) }
        assertTrue("light packets run: $regenAlphas", regenAlphas.toSet().size > 2)
    }

    @Test
    fun regenerationIsHiddenWithoutAnyAndAirflowWithoutClimate() {
        val r = renderer()
        r.frame(driving.copy(regen = 0.0), input.copy(climateOn = false))
        for (v in 0 until r.ribbonVertexCount()) {
            assertEquals(0, r.alphaOf(r.regenCoreStart(0) + v))
            assertEquals(0, r.alphaOf(r.regenCoreStart(1) + v))
            for (band in 0..2) assertEquals(0, r.alphaOf(r.airflowStart(band) + v))
        }
        r.frame(driving.copy(regen = 0.5), input.copy(climateOn = true))
        var lit = 0
        for (v in 0 until r.ribbonVertexCount()) if (r.alphaOf(r.regenCoreStart(0) + v) > 0) lit++
        assertTrue(lit > 0)
    }

    @Test
    fun regenerationStrengthFollowsTheLevel() {
        val r = renderer()
        fun strongest(level: Double): Int {
            r.frame(driving.copy(regen = level), input, animated = false)
            var m = 0
            for (v in 0 until r.ribbonVertexCount()) m = maxOf(m, r.alphaOf(r.regenCoreStart(0) + v))
            return m
        }
        assertTrue(strongest(0.9) > strongest(0.3))
    }

    @Test
    fun laneMarkingsFlowWithTheDistanceAndStandStillAtRest() {
        val r = renderer()
        val start = r.dashStart(0)
        // Screen y of every dash's first vertex, NaN where the dash is hidden.
        fun dashes(distance: Double, nowMs: Long = 0): List<Float> {
            r.frame(driving.copy(distanceM = distance), nowMs = nowMs)
            return (0 until r.dashVertexCount() / 4).map { d ->
                if (r.alphaOf(start + 4 * d) > 0) r.positions[2 * (start + 4 * d) + 1] else Float.NaN
            }
        }
        val d0 = dashes(3.0)
        val ahead = dashes(3.5)
        val behind = dashes(2.5)
        var compared = 0
        for (i in d0.indices) {
            if (d0[i].isNaN() || ahead[i].isNaN() || behind[i].isNaN()) continue
            // Driving forward: the markings come towards the camera, down the screen. Reversing: the other way.
            assertTrue("dash $i: ${d0[i]} -> ${ahead[i]}", ahead[i] > d0[i])
            assertTrue("dash $i: ${d0[i]} -> ${behind[i]}", behind[i] < d0[i])
            compared++
        }
        assertTrue("compared $compared dashes", compared >= 3)
        // One whole dash period later the pattern repeats; at rest (same distance) nothing changes.
        assertEquals(d0.map { if (it.isNaN()) -1f else it }, dashes(9.0).map { if (it.isNaN()) -1f else it })
        assertEquals(dashes(2.5), dashes(2.5, nowMs = 9_999))
    }

    @Test
    fun lanesBendTowardsTheSteeringWhileDriving() {
        val r = renderer()
        fun edgeX(steer: Double, mode: MotionMode, speed: Double): Float {
            r.frame(driving.copy(steeringDeg = steer, mode = mode, speedMs = speed), animated = true)
            val s = r.dashStart(1)
            // The dash nearest the top of the screen (farthest ahead).
            var best = 0
            for (v in 0 until r.dashVertexCount()) if (r.alphaOf(s + v) > 0 && r.positions[2 * (s + v) + 1] < r.positions[2 * (s + best) + 1]) best = v
            return r.positions[2 * (s + best)]
        }
        val straight = edgeX(0.0, MotionMode.MOVING, 10.0)
        assertTrue(edgeX(-25.0, MotionMode.MOVING, 10.0) < straight - 5f)     // left: the road bends left
        assertTrue(edgeX(25.0, MotionMode.MOVING, 10.0) > straight + 5f)
        // Standing still the road stays straight, whatever the wheels do.
        assertEquals(straight, edgeX(-25.0, MotionMode.STOPPED, 0.0), 0.01f)
    }

    @Test
    fun wheelPatternTurnsWithThePhaseAndBlursAtSpeed() {
        val r = renderer()
        fun lugs(phase: Double, speed: Double): List<Int> {
            r.frame(driving.copy(wheelPhaseRad = phase, speedMs = speed))
            return (0 until r.lugVertexCount()).map { r.colors[r.lugStart(0) + it] }
        }
        assertFalse(lugs(0.0, 4.0) == lugs(0.5, 4.0))
        assertEquals(lugs(1.0, 4.0), lugs(1.0 + 2 * Math.PI, 4.0))            // a full turn is the same picture
        fun contrast(speed: Double) = lugs(0.3, speed).maxOf { it ushr 24 }
        assertTrue(contrast(25.0) < contrast(4.0))                             // no aliasing mush at high speed
    }

    @Test
    fun frontWheelsSteerAndRearWheelsDoNot() {
        val r = renderer()
        fun treadAt(wheel: Int, steer: Double): List<Float> {
            r.frame(driving.copy(steeringDeg = steer))
            return (0 until 2 * r.treadVertexCount()).map { r.positions[2 * r.treadStart(wheel) + it] }
        }
        for (rear in 0..1) assertEquals(treadAt(rear, 0.0), treadAt(rear, 30.0))
        for (front in 2..3) assertNotEquals(treadAt(front, 0.0), treadAt(front, 30.0))
        // Left is negative: the right front wheel's nose swings left for -30 degrees.
        val straight = treadAt(3, 0.0)
        val left = treadAt(3, -30.0)
        val rightWards = treadAt(3, 30.0)
        assertTrue(left.indices.any { abs(left[it] - straight[it]) > 1f })
        assertTrue(rightWards.indices.any { abs(rightWards[it] - straight[it]) > 1f })
    }

    @Test
    fun waitingForSignalMakesTheBodyTranslucent() {
        val r = renderer()
        assertEquals(1f, r.frame(driving.copy(ghost = 0.0)).carAlpha, 1e-6f)
        assertEquals(0.5f, r.frame(driving.copy(ghost = 1.0, mode = MotionMode.WAITING_SIGNAL)).carAlpha, 1e-6f)
        val b = r.carBounds
        assertTrue(b[2] > b[0] && b[3] > b[1])
        assertTrue(b[0] >= 0f && b[1] >= 0f && b[2] <= 1682f && b[3] <= 1210f)
    }

    @Test
    fun aScriptedDriveThroughSmootherAndRendererStaysSane() {
        val smoother = VisualSmoother(SteeringConfig(SteeringConfig.Kind.HAND_WHEEL, ratio = 15.0))
        val r = renderer()
        var now = 0L
        var speed = 0.0
        var input = TopViewInput(gear = GearState.PARK, socFraction = 0.8)
        var lastSample = -1000L
        var sawMoving = false
        var sawStale = false
        var sawGhost = false
        var last = VisualState()
        for (step in 0 until 60_000 / 16) {
            now += 16
            val t = now / 1000.0
            // 0-5 s parked, 5-25 s forward with turns, 25-30 s braking to a stop, 30-35 s in reverse,
            // 35-42 s the speed signal is gone, then forward again.
            input = when {
                t < 5 -> input
                t < 30 -> input.copy(gear = GearState.DRIVE, turn = if (t in 10.0..14.0) TurnState.LEFT else TurnState.OFF, brake = t in 25.0..30.0,
                    steeringDeg = 540.0 * Math.sin(t / 2.0), climateOn = true, regenLevel = if (t in 25.0..30.0) 0.8 else 0.0, restricted = t > 15)
                t < 35 -> input.copy(gear = GearState.REVERSE, brake = false, steeringDeg = null, regenLevel = 0.0)
                t < 42 -> input.copy(gear = GearState.DRIVE)
                else -> input.copy(gear = GearState.DRIVE)
            }
            speed = when {
                t < 5 -> 0.0
                t < 25 -> minOf(14.0, (t - 5) * 2.0)
                t < 30 -> maxOf(0.0, 14.0 - (t - 25) * 3.0)
                t < 35 -> -1.5
                else -> 8.0
            }
            val signalGone = t in 35.0..42.0
            if (!signalGone && now - lastSample >= 1000) {
                input = input.copy(speedMs = speed, speedAtMs = now, socFraction = 0.8 - t / 400.0, consumedFraction = t / 400.0)
                lastSample = now
            }
            val st = smoother.step(input, now)
            last = st
            r.update(st, input, animated = !input.restricted, nowMs = now)
            for (i in 0 until 2 * r.vertexCount) assertTrue(r.positions[i].isFinite())
            if (st.moving) sawMoving = true
            if (st.stale) sawStale = true
            if (st.ghost > 0.5) sawGhost = true
        }
        // It drove, lost the speed signal (car goes translucent), and drives again when it returns.
        assertTrue(sawMoving && sawStale && sawGhost)
        assertEquals(MotionMode.MOVING, last.mode)
        assertFalse(last.stale)
        assertEquals(0.0, last.ghost, 1e-9)
        assertTrue(last.distanceM > 100.0)
    }
}

