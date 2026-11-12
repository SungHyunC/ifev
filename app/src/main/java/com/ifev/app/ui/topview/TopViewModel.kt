package com.ifev.app.ui.topview

import com.ifev.app.data.GearState
import com.ifev.app.data.TurnState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min

/*
 * Pure Kotlin (no Android, no Compose): the part of the virtual-EV top view that decides WHAT to show.
 * [VisualSmoother] turns raw vehicle samples into display values; the renderer only draws them.
 * Everything here is for the screen. Smoothed values are never fed back into any analysis.
 */

/**
 * One snapshot of what the virtual EV should show.
 *
 * [speedMs] is signed (reverse negative, as AAOS reports it) and [speedAtMs] is when it arrived, on the
 * same clock as the `nowMs` given to [VisualSmoother.step] (SystemClock.elapsedRealtime()).
 * [steeringDeg] is the raw SDK value (left negative); [SteeringConfig] says how to read it.
 */
data class TopViewInput(
    val speedMs: Double? = null,
    val speedAtMs: Long? = null,
    val gear: GearState = GearState.UNKNOWN,
    val steeringDeg: Double? = null,
    val brake: Boolean = false,
    val turn: TurnState = TurnState.OFF,
    val climateOn: Boolean = false,
    /** Battery state of charge, 0..1. */
    val socFraction: Double = 0.0,
    /** How hard the car recovers energy right now, 0..1. */
    val regenLevel: Double = 0.0,
    /**
     * Share of the battery used on this trip, 0..1, in the same unit as [socFraction] (whole battery = 1).
     * The gauge shows that part as empty cells right above the current fill.
     */
    val consumedFraction: Double = 0.0,
    /** The car restricts the screen while driving: no animation faster than 1 frame per second. */
    val restricted: Boolean = false,
) {
    /** Same picture? Ignores [speedAtMs] (a new sample of the same speed changes nothing on screen). */
    fun sameLook(o: TopViewInput): Boolean =
        speedMs == o.speedMs && gear == o.gear && steeringDeg == o.steeringDeg && brake == o.brake &&
            turn == o.turn && climateOn == o.climateOn && socFraction == o.socFraction &&
            regenLevel == o.regenLevel && consumedFraction == o.consumedFraction && restricted == o.restricted
}

/**
 * How to read the raw steering value. The Pleos SDK documents it as the front ROAD-wheel angle, the
 * emulator console takes a HAND-wheel range (±540), so both readings exist and the choice is explicit.
 * Front wheel angle = sign × (raw, or raw / ratio for a hand wheel), limited to ±[MAX_FRONT_WHEEL_DEG].
 */
data class SteeringConfig(
    val kind: Kind = Kind.ROAD_WHEEL,
    /** +1 keeps the signal's convention (left negative); -1 mirrors it if the mesh axis disagrees. */
    val sign: Int = 1,
    /** Steering ratio, hand wheel degrees per road wheel degree. Only used for [Kind.HAND_WHEEL]. */
    val ratio: Double = 15.0,
) {
    enum class Kind { ROAD_WHEEL, HAND_WHEEL }

    init {
        require(sign == 1 || sign == -1) { "sign must be +1 or -1, was $sign" }
        require(ratio.isFinite() && ratio > 0.0) { "ratio must be positive, was $ratio" }
    }

    /** Front road-wheel angle for a raw signal value, degrees, left negative. NaN reads as straight ahead. */
    fun frontWheelDeg(raw: Double): Double {
        if (raw.isNaN()) return 0.0
        val road = if (kind == Kind.HAND_WHEEL) raw / ratio else raw
        return (sign * road).coerceIn(-MAX_FRONT_WHEEL_DEG, MAX_FRONT_WHEEL_DEG)
    }

    companion object {
        const val MAX_FRONT_WHEEL_DEG = 40.0
    }
}

/** What the car is doing, as far as the screen is concerned. */
enum class MotionMode {
    /** Gear unknown, or no usable speed for a gear that moves, or the speed signal dropped: say "신호 대기". */
    WAITING_SIGNAL,

    /** Gear P: standing still by definition. */
    PARKED,

    /** D, R or N but not moving (standstill with hysteresis). */
    STOPPED,

    /** D or R, moving. */
    MOVING,

    /** N, moving: coasting, the wheels turn but nothing drives or recovers. */
    ROLLING,
}

/** The display values for one frame. Doubles, SI units, angles in degrees unless the name says rad. */
data class VisualState(
    /** Front wheel angle shown, left negative, within ±40°. */
    val steeringDeg: Double = 0.0,
    /** False until a steering value has been seen (the wheels are drawn straight then). */
    val steeringKnown: Boolean = false,
    /** Speed shown, m/s, signed. Zero while held, stopped or signal is lost. */
    val speedMs: Double = 0.0,
    /** Wheel rotation, rad, cumulative (not wrapped): 2π per 2.1 m of travel, negative when reversing. */
    val wheelPhaseRad: Double = 0.0,
    /** Travel shown, m, cumulative and signed. Lane markings scroll with it. */
    val distanceM: Double = 0.0,
    /** Regeneration shown, 0..1. Only while driving (D/R), never in N or P. */
    val regen: Double = 0.0,
    /** 0 = solid car, 1 = fully "waiting for signal" (the renderer draws the body translucent). */
    val ghost: Double = 0.0,
    val mode: MotionMode = MotionMode.WAITING_SIGNAL,
    /** The speed signal went quiet for longer than allowed (and the picture is being brought to rest). */
    val stale: Boolean = false,
    /** Gear P while a fresh speed says the car moves: contradictory, the car is drawn standing. */
    val conflict: Boolean = false,
) {
    val moving: Boolean get() = mode == MotionMode.MOVING || mode == MotionMode.ROLLING

    /** Same picture (ignores the cumulative phase and distance, which only matter while animating). */
    fun sameLook(o: VisualState): Boolean =
        steeringDeg == o.steeringDeg && steeringKnown == o.steeringKnown && speedMs == o.speedMs &&
            regen == o.regen && ghost == o.ghost && mode == o.mode && stale == o.stale && conflict == o.conflict
}

/** Tuning of [VisualSmoother]. Values from the signal study: all are starting points to tune on the car. */
object TopViewTuning {
    /** Exponential smoothing time constant for steering, speed and regeneration (and the ghost fade). */
    const val TAU_S = 0.15

    /** Rolling circumference used for the wheel phase. */
    const val WHEEL_CIRCUMFERENCE_M = 2.1

    /** Standstill: enter at or below this speed for [STOP_HOLD_S] ... */
    const val STOP_ENTER_MS = 0.15
    const val STOP_HOLD_S = 0.5

    /** ... leave at or above this one. Between the two nothing changes. */
    const val STOP_RELEASE_MS = 0.3

    /** Speed signal is lost after max([STALE_MIN_S], [STALE_PERIODS] × recent sample period) of silence. */
    const val STALE_MIN_S = 0.5
    const val STALE_PERIODS = 3.0

    /** Period assumed before two samples have been seen: AAOS SENSOR_RATE_NORMAL is 1 Hz. */
    const val DEFAULT_PERIOD_MS = 1000.0

    /** After the loss is detected: hold the last speed this long, then bring the picture to rest. */
    const val EXTRAPOLATE_S = 0.2
    const val RAMP_S = 0.6

    /** Longest step the integration trusts (a paused frame loop must not teleport the car). */
    const val MAX_STEP_S = 5.0
}

/**
 * Turns raw samples into display state, one call per frame ([step]). Not thread-safe: call it from the
 * thread that draws.
 *
 * - Steering, speed and regeneration follow their targets with an exponential filter (τ = 0.15 s).
 *   Discrete signals (gear, brake, turn, climate) are not filtered.
 * - Wheel phase and travel integrate the SHOWN speed with the trapezoid rule, per step. Reverse speed is
 *   already negative, so the gear never flips the sign a second time.
 * - A continuous speed signal that is silent for max(0.5 s, 3 × recent period) is lost: the last speed is
 *   held for 0.2 s, then the shown speed goes to zero within 0.6 s and the integration stops. The
 *   car is then "waiting for signal". Nothing is recorded as a real zero; this is the picture only.
 * - Gear machine: UNKNOWN holds (no motion), P stands, D/R move with the measured speed, N rolls.
 *   Standstill needs 0.5 s at or below 0.15 m/s and ends at 0.3 m/s. After a direct D<->R change the
 *   sample received before it is discarded: nothing moves until a new one arrives.
 */
class VisualSmoother(var steering: SteeringConfig = SteeringConfig()) {

    private var lastNowMs = NONE
    private var started = false

    private var steerSeen = false
    private var steerTarget = 0.0
    private var steerShown = 0.0

    private var haveSample = false
    private var sampleSpeed = 0.0
    private var sampleAtMs = NONE
    private var seenAtMs = NONE
    private var discardUntilMs = NONE
    private var waitSinceMs = NONE
    private var periodMs = TopViewTuning.DEFAULT_PERIOD_MS
    private var periodMeasured = false
    private var speedInit = false

    private var lastGear = GearState.UNKNOWN
    private var stopped = true
    private var belowSinceMs = NONE

    private var wasStale = false
    private var staleBase = 0.0

    private var speedShown = 0.0
    private var phase = 0.0
    private var distance = 0.0
    private var regenShown = 0.0
    private var ghostShown = 0.0

    /** Forget everything (new trip, new session). */
    fun reset() {
        lastNowMs = NONE; started = false
        steerSeen = false; steerTarget = 0.0; steerShown = 0.0
        haveSample = false; sampleSpeed = 0.0; sampleAtMs = NONE; seenAtMs = NONE
        discardUntilMs = NONE; waitSinceMs = NONE
        periodMs = TopViewTuning.DEFAULT_PERIOD_MS; periodMeasured = false; speedInit = false
        lastGear = GearState.UNKNOWN; stopped = true; belowSinceMs = NONE
        wasStale = false; staleBase = 0.0
        speedShown = 0.0; phase = 0.0; distance = 0.0; regenShown = 0.0; ghostShown = 0.0
    }

    /** Advance to [nowMs] (elapsed-realtime milliseconds) with the latest [input]. */
    fun step(input: TopViewInput, nowMs: Long): VisualState {
        val dtRaw = if (lastNowMs == NONE) 0.0 else max(0L, nowMs - lastNowMs) / 1000.0
        lastNowMs = nowMs
        val dt = min(dtRaw, TopViewTuning.MAX_STEP_S)
        val k = if (dtRaw > 0.0) 1.0 - exp(-dtRaw / TopViewTuning.TAU_S) else 0.0

        val gear = input.gear
        val movable = gear == GearState.DRIVE || gear == GearState.REVERSE || gear == GearState.NEUTRAL

        // A direct D<->R change: what was received before it describes the other direction.
        if (gear != lastGear) {
            val flipped = (lastGear == GearState.DRIVE && gear == GearState.REVERSE) ||
                (lastGear == GearState.REVERSE && gear == GearState.DRIVE)
            if (flipped) {
                haveSample = false
                waitSinceMs = nowMs
                discardUntilMs = nowMs
            }
            lastGear = gear
        }

        // A new speed sample.
        var accepted = false
        val at = input.speedAtMs
        val v = input.speedMs
        if (at != null && v != null && v.isFinite() && at > seenAtMs) {
            seenAtMs = at
            if (at > discardUntilMs) {
                if (sampleAtMs != NONE) {
                    val gapMs = (at - sampleAtMs).toDouble()
                    if (!periodMeasured) {
                        periodMs = gapMs.coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
                        periodMeasured = true
                    } else if (gapMs <= staleAfterMs()) {
                        // A longer gap was an outage, not the rate of the signal.
                        periodMs += PERIOD_BLEND * (gapMs.coerceAtLeast(MIN_PERIOD_MS) - periodMs)
                    }
                }
                sampleAtMs = at
                sampleSpeed = v
                haveSample = true
                waitSinceMs = NONE
                accepted = true
            }
        }

        // Is the speed signal lost?
        val refMs = if (haveSample) sampleAtMs else waitSinceMs
        val ageS = if (refMs == NONE) 0.0 else max(0L, nowMs - refMs) / 1000.0
        val staleAfterS = staleAfterMs() / 1000.0
        val stale = refMs != NONE && ageS > staleAfterS
        if (stale && !wasStale) staleBase = speedShown
        wasStale = stale

        // Standstill with hysteresis, on the measured speed.
        val fresh = movable && haveSample && !stale
        if (fresh) {
            updateStandstill(abs(sampleSpeed), nowMs)
        } else if (gear == GearState.PARK) {
            stopped = true
            belowSinceMs = NONE
        }
        val conflict = gear == GearState.PARK && haveSample && !stale && abs(sampleSpeed) >= TopViewTuning.STOP_RELEASE_MS

        val mode = when {
            gear == GearState.UNKNOWN -> MotionMode.WAITING_SIGNAL
            gear == GearState.PARK -> MotionMode.PARKED
            refMs == NONE -> MotionMode.WAITING_SIGNAL
            stale -> MotionMode.WAITING_SIGNAL
            !haveSample || stopped -> MotionMode.STOPPED
            gear == GearState.NEUTRAL -> MotionMode.ROLLING
            else -> MotionMode.MOVING
        }

        // Speed shown.
        val before = speedShown
        when {
            !movable || !haveSample -> speedShown += (0.0 - speedShown) * k
            stale -> {
                val into = ageS - staleAfterS - TopViewTuning.EXTRAPOLATE_S
                val keep = if (into <= 0.0) 1.0 else (1.0 - into / TopViewTuning.RAMP_S).coerceIn(0.0, 1.0)
                speedShown = staleBase * keep
            }
            else -> {
                val target = if (stopped) 0.0 else sampleSpeed
                // The first sample starts the filter where the car is, not at zero.
                speedShown = if (accepted && !speedInit) target else speedShown + (target - speedShown) * k
            }
        }
        if (accepted) speedInit = true
        if (abs(speedShown) < SPEED_EPS) speedShown = 0.0

        // Travel and wheel rotation: trapezoid over this step.
        val d = 0.5 * (before + speedShown) * dt
        distance += d
        phase += 2.0 * PI * d / TopViewTuning.WHEEL_CIRCUMFERENCE_M

        // Regeneration only means something while the car is driven.
        val regenTarget = if (mode == MotionMode.MOVING && input.regenLevel.isFinite()) input.regenLevel.coerceIn(0.0, 1.0) else 0.0
        regenShown += (regenTarget - regenShown) * k
        if (regenShown < VISUAL_EPS && regenTarget == 0.0) regenShown = 0.0

        // The very first frame starts at its target: no solid-to-ghost flash when the view appears.
        val ghostTarget = if (mode == MotionMode.WAITING_SIGNAL) 1.0 else 0.0
        ghostShown = if (!started) ghostTarget else ghostShown + (ghostTarget - ghostShown) * k
        started = true
        if (ghostShown < VISUAL_EPS) ghostShown = 0.0
        if (ghostShown > 1.0 - VISUAL_EPS) ghostShown = 1.0

        // Steering: hold the last pose when the value goes missing.
        val raw = input.steeringDeg
        val steeringNow = raw != null && raw.isFinite()
        if (steeringNow) {
            steerTarget = steering.frontWheelDeg(raw!!)
            if (!steerSeen) {
                steerSeen = true
                steerShown = steerTarget
            }
        }
        steerShown += (steerTarget - steerShown) * k
        if (abs(steerTarget - steerShown) < 1e-3) steerShown = steerTarget

        return VisualState(
            steeringDeg = steerShown,
            steeringKnown = steeringNow,
            speedMs = speedShown,
            wheelPhaseRad = phase,
            distanceM = distance,
            regen = regenShown,
            ghost = ghostShown,
            mode = mode,
            stale = stale,
            conflict = conflict,
        )
    }

    private fun staleAfterMs(): Double =
        max(TopViewTuning.STALE_MIN_S * 1000.0, TopViewTuning.STALE_PERIODS * periodMs)

    private fun updateStandstill(speedAbs: Double, nowMs: Long) {
        when {
            speedAbs >= TopViewTuning.STOP_RELEASE_MS -> {
                stopped = false
                belowSinceMs = NONE
            }
            speedAbs <= TopViewTuning.STOP_ENTER_MS -> {
                if (belowSinceMs == NONE) belowSinceMs = nowMs
                if (nowMs - belowSinceMs >= (TopViewTuning.STOP_HOLD_S * 1000.0).toLong()) stopped = true
            }
            else -> belowSinceMs = NONE
        }
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
        const val MIN_PERIOD_MS = 10.0
        const val MAX_PERIOD_MS = 5000.0
        const val PERIOD_BLEND = 0.4
        /** Below this the picture cannot tell: 1 cm/s of speed, 1 % of an opacity or of a flow strength. */
        const val SPEED_EPS = 0.01
        const val VISUAL_EPS = 0.01
    }
}
