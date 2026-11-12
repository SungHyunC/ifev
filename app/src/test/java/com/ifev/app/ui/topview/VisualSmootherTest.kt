package com.ifev.app.ui.topview

import com.ifev.app.data.GearState
import com.ifev.app.data.TurnState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VisualSmootherTest {

    private val twoPi = 2.0 * PI

    /** Drives a smoother with a simulated clock so a test reads like a timeline. */
    private class Rig(steering: SteeringConfig = SteeringConfig()) {
        val s = VisualSmoother(steering)
        var now = 0L
        var input = TopViewInput(gear = GearState.DRIVE)
        var last = VisualState()
        var lastSampleAt = 0L

        /** A speed sample arriving right now. */
        fun sample(speed: Double) {
            input = input.copy(speedMs = speed, speedAtMs = now)
            lastSampleAt = now
        }

        fun set(f: TopViewInput.() -> TopViewInput) {
            input = input.f()
        }

        fun step(): VisualState {
            last = s.step(input, now)
            return last
        }

        /** Advances [ms] in steps of [stepMs], keeping the input as it is. */
        fun run(ms: Long, stepMs: Long = 16): VisualState {
            val end = now + ms
            while (now < end) {
                now = minOf(now + stepMs, end)
                step()
            }
            return last
        }

        /** Advances [ms] while a fresh sample of [speed] arrives every [periodMs]. */
        fun runWithSamples(ms: Long, speed: Double, periodMs: Long = 1000, stepMs: Long = 16): VisualState {
            val end = now + ms
            var nextSample = now
            while (now < end) {
                if (now >= nextSample) {
                    sample(speed)
                    nextSample += periodMs
                }
                step()
                if (now >= end) break
                now = minOf(now + stepMs, end)
            }
            return last
        }
    }

    // ---- steering --------------------------------------------------------------------------------------

    @Test
    fun roadWheelValueIsTakenAsIs() {
        val c = SteeringConfig()
        assertEquals(-12.5, c.frontWheelDeg(-12.5), 1e-12)
        assertEquals(30.0, c.frontWheelDeg(30.0), 1e-12)
        assertEquals(0.0, c.frontWheelDeg(0.0), 1e-12)
    }

    @Test
    fun handWheelValueIsDividedByTheRatio() {
        val c = SteeringConfig(SteeringConfig.Kind.HAND_WHEEL)
        assertEquals(-10.0, c.frontWheelDeg(-150.0), 1e-12)
        assertEquals(36.0, c.frontWheelDeg(540.0), 1e-12)
        val ratio16 = SteeringConfig(SteeringConfig.Kind.HAND_WHEEL, ratio = 16.0)
        assertEquals(-5.0, ratio16.frontWheelDeg(-80.0), 1e-12)
    }

    @Test
    fun signMirrorsTheAngle() {
        assertEquals(10.0, SteeringConfig(SteeringConfig.Kind.HAND_WHEEL, sign = -1).frontWheelDeg(-150.0), 1e-12)
        assertEquals(-7.0, SteeringConfig(sign = -1).frontWheelDeg(7.0), 1e-12)
    }

    @Test
    fun frontWheelAngleIsLimitedToFortyDegrees() {
        assertEquals(40.0, SteeringConfig().frontWheelDeg(75.0), 1e-12)
        assertEquals(-40.0, SteeringConfig().frontWheelDeg(-540.0), 1e-12)
        // A hand-wheel range read as road wheel would be clipped, a hand wheel at full lock is not.
        assertEquals(-36.0, SteeringConfig(SteeringConfig.Kind.HAND_WHEEL).frontWheelDeg(-540.0), 1e-12)
        assertEquals(40.0, SteeringConfig(SteeringConfig.Kind.HAND_WHEEL, ratio = 10.0).frontWheelDeg(1000.0), 1e-12)
        assertEquals(40.0, SteeringConfig().frontWheelDeg(Double.POSITIVE_INFINITY), 1e-12)
        assertEquals(0.0, SteeringConfig().frontWheelDeg(Double.NaN), 1e-12)
    }

    @Test
    fun badConfigIsRefused() {
        try {
            SteeringConfig(ratio = 0.0)
            fail("ratio 0 must be refused")
        } catch (_: IllegalArgumentException) {
        }
        try {
            SteeringConfig(sign = 0)
            fail("sign 0 must be refused")
        } catch (_: IllegalArgumentException) {
        }
    }

    @Test
    fun smootherUsesTheConfigAndShowsTheFirstValueAtOnce() {
        val r = Rig(SteeringConfig(SteeringConfig.Kind.HAND_WHEEL, sign = -1))
        r.set { copy(steeringDeg = -150.0) }
        val v = r.step()
        assertEquals(10.0, v.steeringDeg, 1e-9)
        assertTrue(v.steeringKnown)
    }

    @Test
    fun steeringFollowsTheTargetWithTheTimeConstant() {
        val r = Rig()
        r.set { copy(steeringDeg = 0.0) }
        r.step()
        r.set { copy(steeringDeg = 20.0) }
        // After one time constant the filter has covered 1 - 1/e of the step.
        val v = r.run((TopViewTuning.TAU_S * 1000).toLong(), stepMs = 5)
        assertEquals(20.0 * (1.0 - exp(-1.0)), v.steeringDeg, 0.2)
        // After five time constants it is there.
        val done = r.run((TopViewTuning.TAU_S * 4000).toLong(), stepMs = 5)
        assertEquals(20.0, done.steeringDeg, 0.2)
        assertTrue(done.steeringDeg < 20.0 + 1e-9)
    }

    @Test
    fun smoothingDoesNotDependOnTheFrameRate() {
        fun after(stepMs: Long): Double {
            val r = Rig()
            r.set { copy(steeringDeg = 0.0) }
            r.step()
            r.set { copy(steeringDeg = 30.0) }
            return r.run(300, stepMs).steeringDeg
        }
        val at200hz = after(5)
        assertEquals(at200hz, after(16), 0.05)
        assertEquals(at200hz, after(100), 0.05)
        assertEquals(at200hz, after(300), 0.05)
    }

    @Test
    fun missingSteeringKeepsTheLastPoseAndSaysUnknown() {
        val r = Rig()
        r.set { copy(steeringDeg = -18.0) }
        r.step()
        r.run(1000)
        r.set { copy(steeringDeg = null) }
        val v = r.run(2000)
        assertEquals(-18.0, v.steeringDeg, 1e-6)
        assertFalse(v.steeringKnown)
        // Garbage is treated like missing, not drawn.
        r.set { copy(steeringDeg = Double.NaN) }
        val g = r.run(500)
        assertEquals(-18.0, g.steeringDeg, 1e-6)
        assertFalse(g.steeringKnown)
    }

    @Test
    fun neverSeenSteeringIsDrawnStraight() {
        val v = Rig().run(200)
        assertEquals(0.0, v.steeringDeg, 0.0)
        assertFalse(v.steeringKnown)
    }

    // ---- speed smoothing and wheel phase ---------------------------------------------------------------

    @Test
    fun speedFollowsAStepWithTheTimeConstant() {
        val r = Rig()
        r.sample(0.0)
        r.step()
        r.run(600)                      // standing: the car is stopped
        r.sample(10.0)
        val v = r.run((TopViewTuning.TAU_S * 1000).toLong(), stepMs = 5)
        assertEquals(10.0 * (1.0 - exp(-1.0)), v.speedMs, 0.25)
        val later = r.run(1500, stepMs = 5)
        assertEquals(10.0, later.speedMs, 0.01)
    }

    @Test
    fun tenMetersPerSecondForTwoPointOneSecondsIsTenWheelTurns() {
        val r = Rig()
        r.sample(10.0)
        r.step()
        val v = r.run(2100, stepMs = 16)
        assertEquals(10.0, v.wheelPhaseRad / twoPi, 1e-6)
        assertEquals(21.0, v.distanceM, 1e-6)
        assertEquals(10.0, v.speedMs, 1e-9)
    }

    @Test
    fun wheelPhaseDoesNotDependOnTheStepSize() {
        fun turns(stepMs: Long): Double {
            val r = Rig()
            r.sample(10.0)
            r.step()
            return r.run(2100, stepMs).wheelPhaseRad / twoPi
        }
        assertEquals(10.0, turns(1), 1e-6)
        assertEquals(10.0, turns(100), 1e-6)
        assertEquals(10.0, turns(1000), 1e-6)   // the 1 Hz steps of the restricted mode
        assertEquals(10.0, turns(2100), 1e-6)
    }

    @Test
    fun trapezoidIntegrationOfARamp() {
        // The shown speed climbs from 0 to 6 m/s; the travel is the area under the shown curve.
        val r = Rig()
        r.sample(0.0)
        r.step()
        r.run(700)
        r.sample(6.0)
        val shown = ArrayList<Pair<Long, Double>>()
        val start = r.now
        var travelled = r.last.distanceM
        var prev = r.last.speedMs
        var area = 0.0
        while (r.now < start + 1200) {
            r.now += 10
            val v = r.step()
            area += 0.5 * (prev + v.speedMs) * 0.010
            prev = v.speedMs
            shown += r.now to v.speedMs
        }
        assertEquals(area, r.last.distanceM - travelled, 1e-9)
        assertTrue(shown.last().second > 5.9)
    }

    @Test
    fun reverseSpeedIsNegativeAndTurnsTheWheelsTheOtherWay() {
        val r = Rig()
        r.set { copy(gear = GearState.REVERSE) }
        r.sample(-2.1)
        r.step()
        val v = r.run(2000)
        // 2.1 m/s for 2 s = 4.2 m = 2 turns backwards. The gear does not flip the sign a second time.
        assertEquals(-2.0, v.wheelPhaseRad / twoPi, 1e-6)
        assertEquals(-4.2, v.distanceM, 1e-6)
        assertEquals(MotionMode.MOVING, v.mode)
    }

    // ---- loss of the speed signal ----------------------------------------------------------------------

    @Test
    fun freshSamplesAtOneHertzAreNeverStale() {
        val r = Rig()
        val v = r.runWithSamples(20_000, 8.0)
        assertFalse(v.stale)
        assertEquals(MotionMode.MOVING, v.mode)
        assertEquals(8.0, v.speedMs, 1e-6)
    }

    @Test
    fun silenceLongerThanThreePeriodsIsStaleAndTheIntegrationStops() {
        val r = Rig()
        r.runWithSamples(5_000, 10.0)                 // samples at 0 s ... 4 s (on the 16 ms grid)
        val lastSampleAt = r.lastSampleAt
        assertFalse(r.last.stale)

        // Just inside 3 periods: still fine and still moving.
        r.run(lastSampleAt + 2_900 - r.now)
        assertFalse(r.last.stale)
        assertEquals(10.0, r.last.speedMs, 1e-6)
        assertEquals(MotionMode.MOVING, r.last.mode)

        // Past 3 periods: stale. The speed is held for 0.2 s ...
        r.run(lastSampleAt + 3_100 - r.now)
        assertTrue(r.last.stale)
        assertEquals(MotionMode.WAITING_SIGNAL, r.last.mode)
        assertEquals(10.0, r.last.speedMs, 1e-6)
        val phaseAtStale = r.last.wheelPhaseRad

        // ... then goes to zero within 0.6 s; the car travelled at most a little further.
        r.run(lastSampleAt + 3_000 + 200 + 600 - r.now + 20)
        assertEquals(0.0, r.last.speedMs, 1e-9)
        val settled = r.last.wheelPhaseRad
        val extra = (settled - phaseAtStale) / twoPi * TopViewTuning.WHEEL_CIRCUMFERENCE_M
        assertTrue("moved $extra m after the loss", extra in 0.0..(10.0 * (0.2 + 0.6)))

        // Frozen from here on, however long the silence lasts.
        r.run(30_000)
        assertEquals(settled, r.last.wheelPhaseRad, 0.0)
        assertTrue(r.last.stale)
    }

    @Test
    fun theStaleLimitFollowsTheRecentPeriod() {
        // A 10 Hz source: lost after the 0.5 s minimum, not after 3 periods = 0.3 s.
        val r = Rig()
        r.runWithSamples(3_000, 5.0, periodMs = 100)
        assertFalse(r.last.stale)
        val lastAt = r.lastSampleAt
        r.run(lastAt + 450 - r.now)
        assertFalse(r.last.stale)
        r.run(lastAt + 560 - r.now)
        assertTrue(r.last.stale)

        // A 2 s source: 3 periods = 6 s.
        val slow = Rig()
        slow.runWithSamples(10_000, 5.0, periodMs = 2000)
        val slowLast = slow.lastSampleAt
        slow.run(slowLast + 5_900 - slow.now)
        assertFalse(slow.last.stale)
        slow.run(slowLast + 6_100 - slow.now)
        assertTrue(slow.last.stale)
    }

    @Test
    fun aNewSampleEndsTheLossAndTheCarMovesAgain() {
        val r = Rig()
        r.runWithSamples(3_000, 10.0)
        r.run(6_000)
        assertTrue(r.last.stale)
        assertEquals(0.0, r.last.speedMs, 1e-9)
        val frozen = r.last.wheelPhaseRad
        r.sample(4.0)
        val v = r.run(1_000)
        assertFalse(v.stale)
        assertEquals(MotionMode.MOVING, v.mode)
        assertEquals(4.0, v.speedMs, 0.01)
        assertTrue(v.wheelPhaseRad > frozen)
    }

    @Test
    fun anOutageDoesNotTeachTheSmootherASlowPeriod() {
        val r = Rig()
        r.runWithSamples(4_000, 10.0)
        r.run(30_000)                    // 30 s of silence
        assertTrue(r.last.stale)
        r.runWithSamples(4_000, 10.0)    // back at 1 Hz
        assertFalse(r.last.stale)
        val lastAt = r.lastSampleAt
        r.run(lastAt + 3_200 - r.now)    // would still be fine if the 30 s gap had been learned as the rate
        assertTrue(r.last.stale)
    }

    @Test
    fun aDuplicateOrOlderSampleIsNotANewSample() {
        val r = Rig()
        r.sample(10.0)
        r.step()
        // The same sample shown again and again does not keep the signal alive.
        r.run(5_000)
        assertTrue(r.last.stale)
    }

    @Test
    fun noSpeedEverReceivedMeansWaitingForSignalNotStale() {
        val r = Rig()
        val v = r.run(5_000)
        assertEquals(MotionMode.WAITING_SIGNAL, v.mode)
        assertFalse(v.stale)
        assertEquals(0.0, v.speedMs, 0.0)
        assertEquals(0.0, v.wheelPhaseRad, 0.0)
    }

    @Test
    fun theWaitingCarFadesToGhostAndBack() {
        val r = Rig()
        r.run(3_000)
        assertEquals(1.0, r.last.ghost, 1e-9)
        r.sample(10.0)
        r.run(1_000)
        assertEquals(0.0, r.last.ghost, 1e-9)
        assertEquals(MotionMode.MOVING, r.last.mode)
    }

    @Test
    fun theFirstFrameDoesNotFlashFromSolidToGhost() {
        val v = Rig().step()
        assertEquals(1.0, v.ghost, 0.0)
    }

    // ---- gears -----------------------------------------------------------------------------------------

    @Test
    fun unknownGearHoldsEvenWithASpeedSample() {
        val r = Rig()
        r.set { copy(gear = GearState.UNKNOWN) }
        val v = r.runWithSamples(3_000, 10.0)
        assertEquals(MotionMode.WAITING_SIGNAL, v.mode)
        assertEquals(0.0, v.speedMs, 1e-9)
        assertEquals(0.0, v.wheelPhaseRad, 1e-9)
        assertEquals(1.0, v.ghost, 1e-9)
    }

    @Test
    fun parkStandsStillAndFlagsAContradiction() {
        val r = Rig()
        r.set { copy(gear = GearState.PARK) }
        r.sample(0.0)
        val v = r.runWithSamples(2_000, 0.0)
        assertEquals(MotionMode.PARKED, v.mode)
        assertFalse(v.conflict)
        assertEquals(0.0, v.ghost, 1e-9)

        val c = r.runWithSamples(2_000, 6.0)
        assertEquals(MotionMode.PARKED, c.mode)
        assertTrue(c.conflict)
        assertEquals(0.0, c.speedMs, 1e-9)
        assertEquals(0.0, c.wheelPhaseRad, 1e-9)
    }

    @Test
    fun parkWithoutAnySpeedIsStillParkedNotWaiting() {
        val r = Rig()
        r.set { copy(gear = GearState.PARK) }
        val v = r.run(5_000)
        assertEquals(MotionMode.PARKED, v.mode)
        assertEquals(0.0, v.ghost, 0.0)
    }

    @Test
    fun driveMovesWithTheMeasuredSpeed() {
        val r = Rig()
        val v = r.runWithSamples(3_000, 7.0)
        assertEquals(MotionMode.MOVING, v.mode)
        assertTrue(v.moving)
        assertEquals(7.0, v.speedMs, 1e-6)
    }

    @Test
    fun neutralRollsAndShowsNoRegeneration() {
        val r = Rig()
        r.set { copy(gear = GearState.NEUTRAL, regenLevel = 0.8) }
        val v = r.runWithSamples(3_000, 7.0)
        assertEquals(MotionMode.ROLLING, v.mode)
        assertTrue(v.moving)
        assertEquals(7.0, v.speedMs, 1e-6)
        assertEquals(0.0, v.regen, 0.0)
    }

    @Test
    fun regenerationShowsOnlyWhileDriven() {
        val r = Rig()
        r.set { copy(regenLevel = 0.6) }
        val v = r.runWithSamples(3_000, 12.0)
        assertEquals(0.6, v.regen, 1e-3)
        r.set { copy(regenLevel = 1.7) }                 // out of range is clamped
        assertEquals(1.0, r.runWithSamples(2_000, 12.0).regen, 1e-3)
        r.set { copy(gear = GearState.PARK, regenLevel = 0.6) }
        assertEquals(0.0, r.runWithSamples(2_000, 0.0).regen, 1e-3)
    }

    @Test
    fun aDirectChangeBetweenDriveAndReverseWaitsForANewSample() {
        val r = Rig()
        r.sample(0.5)
        r.runWithSamples(2_000, 0.5)
        assertEquals(MotionMode.MOVING, r.last.mode)

        // R is selected while the last sample still says +0.5 m/s: that sample is not used for R.
        r.set { copy(gear = GearState.REVERSE) }
        r.now += 40
        r.step()
        assertEquals(MotionMode.STOPPED, r.last.mode)
        val waiting = r.run(1_200)
        assertEquals(0.0, waiting.speedMs, 1e-9)       // came to rest, never turned the other way
        assertEquals(MotionMode.STOPPED, waiting.mode)
        assertFalse(waiting.stale)
        val phaseBefore = waiting.wheelPhaseRad

        // The new sample is reverse speed and the car backs up.
        r.sample(-1.0)
        val back = r.run(1_000)
        assertEquals(-1.0, back.speedMs, 0.01)
        assertEquals(MotionMode.MOVING, back.mode)
        assertTrue(back.wheelPhaseRad < phaseBefore)
    }

    @Test
    fun noNewSampleAfterADriveReverseChangeEndsAsWaitingForSignal() {
        val r = Rig()
        r.runWithSamples(2_000, 1.0)
        r.set { copy(gear = GearState.REVERSE) }
        r.run(1_000)
        assertEquals(MotionMode.STOPPED, r.last.mode)
        r.run(3_000)
        assertEquals(MotionMode.WAITING_SIGNAL, r.last.mode)
        assertTrue(r.last.stale)
    }

    @Test
    fun parkToDriveKeepsTheSample() {
        val r = Rig()
        r.set { copy(gear = GearState.PARK) }
        r.runWithSamples(2_000, 0.0)
        r.set { copy(gear = GearState.DRIVE) }
        val v = r.runWithSamples(2_000, 3.0)
        assertEquals(MotionMode.MOVING, v.mode)
        assertEquals(3.0, v.speedMs, 0.01)
    }

    // ---- standstill hysteresis -------------------------------------------------------------------------

    @Test
    fun standstillNeedsHalfASecondBelowTheEnterSpeed() {
        val r = Rig()
        r.runWithSamples(2_000, 5.0)
        assertEquals(MotionMode.MOVING, r.last.mode)

        r.sample(0.1)                                    // slower than 0.15 m/s ...
        val early = r.run(400)
        assertEquals(MotionMode.MOVING, early.mode)     // ... not yet for 0.5 s
        val late = r.run(200)
        assertEquals(MotionMode.STOPPED, late.mode)
        r.run(800)
        assertEquals(0.0, r.last.speedMs, 1e-9)         // the noise is cut to a real zero
    }

    @Test
    fun standstillEndsAtTheReleaseSpeed() {
        val r = Rig()
        r.sample(0.0)
        r.run(2_000)
        assertEquals(MotionMode.STOPPED, r.last.mode)

        r.sample(0.2)                                    // between 0.15 and 0.3: nothing changes
        assertEquals(MotionMode.STOPPED, r.run(1_000).mode)
        assertEquals(0.0, r.last.speedMs, 1e-9)

        r.sample(0.35)                                   // 0.3 m/s or more: moving
        val v = r.run(200)
        assertEquals(MotionMode.MOVING, v.mode)
    }

    @Test
    fun speedInTheDeadBandDoesNotStartTheStandstillClock() {
        val r = Rig()
        r.runWithSamples(2_000, 5.0)
        r.sample(0.1)
        r.run(300)                                       // 0.3 s below the enter speed
        r.sample(0.2)                                    // back into the dead band: the clock restarts
        r.run(300)
        r.sample(0.1)
        val v = r.run(300)                               // only 0.3 s since the restart
        assertEquals(MotionMode.MOVING, v.mode)
        assertEquals(MotionMode.STOPPED, r.run(400).mode)
    }

    @Test
    fun aMovingCarIsNotStoppedByANoisyBriefDip() {
        val r = Rig()
        r.runWithSamples(2_000, 5.0)
        r.sample(0.05)
        r.run(200)
        r.sample(5.0)
        val v = r.run(2_000)
        assertEquals(MotionMode.MOVING, v.mode)
    }

    // ---- misc ------------------------------------------------------------------------------------------

    @Test
    fun theClockGoingBackwardsChangesNothing() {
        val r = Rig()
        r.runWithSamples(2_000, 10.0)
        val before = r.last
        r.now -= 500
        val v = r.step()
        assertEquals(before.wheelPhaseRad, v.wheelPhaseRad, 1e-9)
        assertEquals(before.distanceM, v.distanceM, 1e-9)
    }

    @Test
    fun aLongPauseDoesNotTeleportTheCar() {
        val r = Rig()
        r.sample(10.0)
        r.step()
        r.now += 600_000                                 // the frame loop slept for ten minutes
        r.sample(10.0)
        val v = r.step()
        assertTrue("moved ${v.distanceM} m", v.distanceM <= 10.0 * TopViewTuning.MAX_STEP_S + 1e-9)
    }

    @Test
    fun nonFiniteSpeedIsIgnored() {
        val r = Rig()
        r.sample(Double.NaN)
        val v = r.run(2_000)
        assertEquals(MotionMode.WAITING_SIGNAL, v.mode)
        assertTrue(abs(v.speedMs) == 0.0)
    }

    @Test
    fun resetForgetsEverything() {
        val r = Rig()
        r.set { copy(steeringDeg = 12.0) }
        r.runWithSamples(2_000, 9.0)
        r.s.reset()
        val v = r.s.step(TopViewInput(gear = GearState.PARK), 10_000)
        assertEquals(0.0, v.wheelPhaseRad, 0.0)
        assertEquals(0.0, v.steeringDeg, 0.0)
        assertFalse(v.steeringKnown)
        assertNotEquals(MotionMode.MOVING, v.mode)
    }

    @Test
    fun sameLookIgnoresOnlyTheSampleTime() {
        val a = TopViewInput(speedMs = 3.0, speedAtMs = 100, gear = GearState.DRIVE)
        assertTrue(a.sameLook(a.copy(speedAtMs = 200)))
        assertFalse(a.sameLook(a.copy(brake = true)))
        assertFalse(a.sameLook(a.copy(speedMs = 3.1)))
        assertFalse(a.sameLook(a.copy(turn = TurnState.LEFT)))
    }
}
