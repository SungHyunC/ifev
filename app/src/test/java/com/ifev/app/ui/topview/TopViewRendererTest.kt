package com.ifev.app.ui.topview

import com.ifev.app.data.GearState
import com.ifev.app.data.TurnState
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The neon scene: valid geometry at any size, steady where it must be, moving where it must. */
class TopViewRendererTest {

    private val driving = VisualState(speedMs = 10.0, steeringKnown = true, mode = MotionMode.MOVING)
    private val input = TopViewInput(speedMs = 10.0, speedAtMs = 0, gear = GearState.DRIVE, socFraction = 0.7)

    private fun scene(w: Float = 1682f, h: Float = 1210f) = NeonScene().also { it.layout(w, h) }

    private fun NeonScene.frame(
        st: VisualState = driving, inp: TopViewInput = input, animated: Boolean = true, nowMs: Long = 0,
    ): NeonScene {
        update(st, inp, animated, nowMs)
        return this
    }

    private fun NeonScene.snapshot(): List<Float> =
        laneLines.take(laneCount * 4) + treadLines.toList() + regenPoints.take((regenMid + regenEdge) * 2) +
            airAlpha.toList() + airSway.toList() + listOf(alpha, steerDeg, brake, turnLeft, turnRight, fillTop, usedTop, regenAlpha)

    // ---- geometry ----------------------------------------------------------------------------------------------

    @Test
    fun everyOutlineFitsInsideTheViewAtAnySize() {
        for ((w, h) in listOf(1682f to 1210f, 500f to 800f, 300f to 300f, 2560f to 1440f, 64f to 48f)) {
            val sc = scene(w, h)
            assertTrue(sc.ready)
            val sh = sc.shapes
            for (spec in listOf(sh.body, sh.cabin, sh.mirrors, sh.rearWheels, sh.windows, sh.brakeLamps, sh.turnArcLeft, sh.turnArcRight)) {
                val b = spec.bounds()
                assertTrue("$w x $h: ${b.toList()}", b[0] >= 0f && b[1] >= 0f && b[2] <= w && b[3] <= h)
                assertTrue(b.all { it.isFinite() })
                assertEquals(
                    spec.coords.size,
                    spec.verbs.map { v ->
                        when (v) {
                            PathSpec.MOVE, PathSpec.LINE -> 2
                            PathSpec.QUAD -> 4
                            PathSpec.CUBIC -> 6
                            else -> 0
                        }
                    }.sum(),
                )
            }
        }
    }

    @Test
    fun layoutAgainForTheSameSizeChangesNothingAndANewSizeRebuilds() {
        val sc = scene()
        val v = sc.layoutVersion
        sc.layout(1682f, 1210f)
        assertEquals(v, sc.layoutVersion)
        sc.layout(900f, 700f)
        assertEquals(v + 1, sc.layoutVersion)
        sc.layout(0f, 100f)
        assertFalse(sc.ready)
    }

    @Test
    fun theCarSitsInTheMiddleAndPointsUp() {
        val sc = scene()
        val b = sc.shapes.body.bounds()
        assertEquals(sc.width / 2f, (b[0] + b[2]) / 2f, 1f)
        // Nose (top of the bounds) is above the tail, and the wheels stick out of the body sideways.
        assertTrue(b[3] - b[1] > b[2] - b[0])
        val w = sc.shapes.rearWheels.bounds()
        assertTrue(w[0] < b[0] && w[2] > b[2])
        assertTrue(sc.shapes.frontWheelY < sc.shapes.rearWheelY)
    }

    // ---- lamps ---------------------------------------------------------------------------------------------------

    @Test
    fun turnSignalBlinksAtOnePointFiveHertzWhileAnimated() {
        val sc = scene()
        val left = input.copy(turn = TurnState.LEFT)
        var on = 0
        var off = 0
        for (t in 0 until 2000 step 10) {
            sc.frame(inp = left, nowMs = t.toLong())
            if (sc.turnLeft > 0f) on++ else off++
            assertEquals(0f, sc.turnRight, 0f)
        }
        // 2 s at 1.5 Hz = 3 cycles, lit half of the time.
        assertTrue("on $on off $off", abs(on - off) <= 6)
        var edges = 0
        var prev = -1f
        for (t in 0 until 2000 step 10) {
            sc.frame(inp = left, nowMs = t.toLong())
            if (prev >= 0f && prev != sc.turnLeft) edges++
            prev = sc.turnLeft
        }
        assertTrue("edges $edges", edges in 5..7)
    }

    @Test
    fun restrictedTurnSignalIsSteadyOn() {
        val sc = scene()
        val right = input.copy(turn = TurnState.RIGHT)
        for (t in listOf(0L, 100L, 333L, 500L, 1000L, 1234L)) {
            sc.frame(inp = right, animated = false, nowMs = t)
            assertEquals(1f, sc.turnRight, 0f)
            assertEquals(0f, sc.turnLeft, 0f)
        }
    }

    @Test
    fun brakeLampsFollowTheBrakeInput() {
        val sc = scene()
        assertEquals(0f, sc.frame().brake, 0f)
        assertEquals(1f, sc.frame(inp = input.copy(brake = true)).brake, 0f)
    }

    // ---- steering and wheels ------------------------------------------------------------------------------------

    @Test
    fun frontWheelsTurnWithTheSteeringAngleAndLimitsHold() {
        val sc = scene()
        assertEquals(-12f, sc.frame(st = driving.copy(steeringDeg = -12.0)).steerDeg, 1e-4f)
        assertEquals(25f, sc.frame(st = driving.copy(steeringDeg = 25.0)).steerDeg, 1e-4f)
        assertEquals(40f, sc.frame(st = driving.copy(steeringDeg = 400.0)).steerDeg, 1e-4f)
    }

    @Test
    fun steeringMovesOnlyTheFrontTreadLinesSideways() {
        val sc = scene()
        sc.frame(st = driving.copy(steeringDeg = 0.0), animated = false)
        val straight = sc.treadLines.copyOf()
        sc.frame(st = driving.copy(steeringDeg = 30.0), animated = false)
        val turned = sc.treadLines
        val n = NeonScene.TREADS * 4
        // Wheels 0 and 1 (front) differ, wheels 2 and 3 (rear) do not.
        assertTrue((0 until 2 * n).any { abs(straight[it] - turned[it]) > 0.5f })
        for (i in 2 * n until 4 * n) assertEquals(straight[i], turned[i], 1e-4f)
    }

    @Test
    fun treadLinesRunWithTheSpeedAndStandStillAtRest() {
        val sc = scene()
        sc.frame(nowMs = 0)
        val a = sc.treadLines.copyOf()
        sc.frame(nowMs = 50)
        assertFalse(a.contentEquals(sc.treadLines))
        val stopped = driving.copy(speedMs = 0.0, mode = MotionMode.STOPPED)
        sc.frame(st = stopped, nowMs = 100)
        val b = sc.treadLines.copyOf()
        sc.frame(st = stopped, nowMs = 400)
        assertTrue(b.contentEquals(sc.treadLines))
    }

    @Test
    fun forwardAndReverseRunTheTreadsOppositeWays() {
        fun yOf(sc: NeonScene) = sc.treadLines[1]
        val f = scene()
        // Start a few frames in, away from the point where a line wraps from one end of the wheel to the other.
        f.frame(nowMs = 0); f.frame(nowMs = 40); f.frame(nowMs = 80)
        val y0 = yOf(f); f.frame(nowMs = 100); val y1 = yOf(f)
        val r = scene()
        val rev = driving.copy(speedMs = -10.0)
        r.frame(st = rev, nowMs = 0); r.frame(st = rev, nowMs = 40); r.frame(st = rev, nowMs = 80)
        val z0 = yOf(r); r.frame(st = rev, nowMs = 100); val z1 = yOf(r)
        assertTrue((y1 - y0) * (z1 - z0) < 0f)
    }

    @Test
    fun laneDashesFlowWhileDrivingAndStopWhenStoppedOrRestricted() {
        val sc = scene()
        sc.frame(nowMs = 0)
        val a = sc.laneLines.copyOf(sc.laneCount * 4)
        sc.frame(nowMs = 60)
        assertFalse(a.contentEquals(sc.laneLines.copyOf(sc.laneCount * 4)))
        assertTrue(sc.laneCount > 0)
        for (i in 0 until sc.laneCount) {
            assertTrue(sc.laneLines[4 * i + 1] in 0f..sc.height && sc.laneLines[4 * i + 3] in 0f..sc.height)
        }
        val restricted = scene()
        restricted.frame(animated = false, nowMs = 0)
        val r0 = restricted.laneLines.copyOf(restricted.laneCount * 4)
        restricted.frame(animated = false, nowMs = 5000)
        assertTrue(r0.contentEquals(restricted.laneLines.copyOf(restricted.laneCount * 4)))
    }

    // ---- nothing moves when restricted -------------------------------------------------------------------------

    @Test
    fun restrictedPictureHasNothingThatMoves() {
        val sc = scene()
        val inp = input.copy(turn = TurnState.LEFT, climateOn = true, regenLevel = 0.6, brake = true)
        val st = driving.copy(regen = 0.6, distanceM = 5.0, wheelPhaseRad = 3.0)
        sc.frame(st = st, inp = inp, animated = false, nowMs = 0)
        val a = sc.snapshot()
        sc.frame(st = st.copy(distanceM = 500.0, wheelPhaseRad = 300.0), inp = inp, animated = false, nowMs = 7777)
        assertEquals(a, sc.snapshot())
    }

    // ---- regeneration, airflow, gauge --------------------------------------------------------------------------

    @Test
    fun regenerationIsHiddenWithoutAnyAndAirflowWithoutClimate() {
        val sc = scene().frame(inp = input.copy(climateOn = false))
        assertEquals(0, sc.regenMid + sc.regenEdge)
        assertEquals(0f, sc.regenAlpha, 0f)
        assertTrue(sc.airAlpha.all { it == 0f })
    }

    @Test
    fun regenerationStrengthFollowsTheLevelAndParticlesFlowWhenAnimated() {
        val sc = scene()
        sc.frame(st = driving.copy(regen = 0.2), nowMs = 0)
        val weak = sc.regenMid + sc.regenEdge
        val weakAlpha = sc.regenAlpha
        sc.frame(st = driving.copy(regen = 1.0), nowMs = 20)
        assertTrue(sc.regenMid + sc.regenEdge > weak)
        assertTrue(sc.regenAlpha > weakAlpha)
        assertTrue(sc.regenMid + sc.regenEdge <= 2 * NeonScene.MAX_REGEN)

        val p0 = sc.regenPoints.copyOf((sc.regenMid + sc.regenEdge) * 2)
        sc.frame(st = driving.copy(regen = 1.0), nowMs = 100)
        assertFalse(p0.contentEquals(sc.regenPoints.copyOf((sc.regenMid + sc.regenEdge) * 2)))
        // They run from the wheels (outer) towards the middle: every point lies between the wheels' x range.
        for (i in 0 until sc.regenMid + sc.regenEdge) {
            val x = sc.regenPoints[2 * i]
            assertTrue(x in sc.shapes.frontWheelX[0]..sc.shapes.frontWheelX[1])
        }
    }

    @Test
    fun airflowAppearsWithTheClimateSystemAndStaysSteadyWhenRestricted() {
        val sc = scene()
        val on = input.copy(climateOn = true)
        sc.frame(inp = on, nowMs = 0)
        assertTrue(sc.airAlpha.all { it > 0f })
        val a = sc.airAlpha.copyOf()
        sc.frame(inp = on, nowMs = 900)
        assertFalse(a.contentEquals(sc.airAlpha))
        sc.frame(inp = on, animated = false, nowMs = 0)
        val r = sc.airAlpha.copyOf()
        sc.frame(inp = on, animated = false, nowMs = 900)
        assertTrue(r.contentEquals(sc.airAlpha))
        assertTrue(sc.airSway.all { it == 0f })
    }

    @Test
    fun gaugeFillRisesWithTheChargeAndTurnsAmberBelowThirtyPercent() {
        val sc = scene()
        sc.frame(inp = input.copy(socFraction = 0.2))
        val low = sc.fillTop
        assertTrue(sc.fillAmber)
        sc.frame(inp = input.copy(socFraction = 0.9))
        assertTrue(sc.fillTop < low) // pixels grow downwards, a fuller gauge reaches higher
        assertFalse(sc.fillAmber)
        sc.frame(inp = input.copy(socFraction = 0.3))
        assertFalse(sc.fillAmber)
        sc.frame(inp = input.copy(socFraction = 0.0))
        assertFalse(sc.fillVisible)
    }

    @Test
    fun theUsedPartIsEmptyCellsRightAboveTheFillAndCannotExceedTheGauge() {
        val sc = scene()
        sc.frame(inp = input.copy(socFraction = 0.5, consumedFraction = 0.1))
        assertTrue(sc.usedVisible)
        assertTrue(sc.usedTop < sc.fillTop)
        val span = sc.shapes.gaugeBottom - sc.shapes.gaugeTop
        assertEquals(0.1f * span, sc.fillTop - sc.usedTop, 1f)
        sc.frame(inp = input.copy(socFraction = 0.9, consumedFraction = 0.9))
        assertTrue(sc.usedTop >= sc.shapes.gaugeTop - 0.5f)
        sc.frame(inp = input.copy(socFraction = 0.5, consumedFraction = 0.0))
        assertFalse(sc.usedVisible)
    }

    // ---- signal lost ---------------------------------------------------------------------------------------------

    @Test
    fun waitingForSignalDimsTheWholePicture() {
        val sc = scene()
        assertEquals(1f, sc.frame().alpha, 0f)
        val ghost = sc.frame(st = VisualState(ghost = 1.0, mode = MotionMode.WAITING_SIGNAL)).alpha
        assertTrue(ghost < 0.6f && ghost > 0.1f)
    }

    // ---- robustness ---------------------------------------------------------------------------------------------

    @Test
    fun noInputMakesNaNOrInfiniteNumbers() {
        val sc = scene()
        var n = 0
        for (s in listOf(-40.0, -17.5, 0.0, 12.0, 40.0)) for (c in listOf(0.0, 0.1, 0.3, 0.75, 1.0)) {
            for (animated in listOf(true, false)) for (turn in TurnState.values()) {
                val st = VisualState(
                    steeringDeg = s, steeringKnown = true, speedMs = 3.0 * n - 20.0, wheelPhaseRad = 1.7 * n, distanceM = 11.3 * n,
                    regen = (n % 5) / 4.0, ghost = (n % 3) / 2.0, mode = MotionMode.MOVING,
                )
                val inp = input.copy(
                    socFraction = c, consumedFraction = (n % 7) / 6.0, turn = turn, brake = n % 2 == 0,
                    climateOn = n % 3 == 0, steeringDeg = s,
                )
                sc.update(st, inp, animated, 1000L * n)
                assertTrue(sc.snapshot().all { it.isFinite() })
                assertTrue(sc.laneCount <= NeonScene.MAX_LANE_SEGMENTS)
                n++
            }
        }
        sc.frame(inp = input.copy(socFraction = Double.NaN, consumedFraction = Double.NaN, regenLevel = Double.NaN))
        assertTrue(sc.snapshot().all { it.isFinite() })
    }

    @Test
    fun aScriptedDriveThroughSmootherAndSceneStaysSane() {
        val sm = VisualSmoother()
        val sc = scene()
        var seen = 0
        for (i in 0..600) {
            val now = i * 16L
            val inp = TopViewInput(
                speedMs = if (i < 300) i / 20.0 else 15.0 - (i - 300) / 20.0,
                speedAtMs = now - now % 100,
                gear = GearState.DRIVE,
                steeringDeg = 20.0 * kotlin.math.sin(i / 60.0),
                brake = i in 350..420,
                turn = if (i in 100..250) TurnState.LEFT else TurnState.OFF,
                climateOn = true,
                socFraction = 0.8,
                regenLevel = if (i in 300..400) 0.8 else 0.0,
                consumedFraction = 0.05,
            )
            val st = sm.step(inp, now)
            sc.update(st, inp, true, now)
            assertTrue(sc.snapshot().all { it.isFinite() })
            if (sc.turnLeft > 0f) seen++
        }
        assertNotEquals(0, seen)
    }
}
