package com.ifev.app.engine

import kotlin.math.max

/**
 * Virtual regenerative braking: every time this petrol car slows down, the energy an EV of similar
 * size would have put back into its battery instead of turning it into brake heat.
 *
 * Shown as a breakdown only. The certified ranges the trip energy comes from already include
 * regeneration, so this is never subtracted from the trip's kWh (that would count it twice); it says
 * how much of that kWh came back on the way.
 *
 * Assumptions, all shown on screen as an estimate: 2,000 kg with the driver, 60 % from wheel to
 * battery, nothing below 2 m/s (motors hand over to the friction brakes near a stop), and the part of
 * the speed loss that rolling resistance and air drag take anyway is not recoverable.
 */
object Regen {
    const val MASS_KG = 2_000.0
    const val EFFICIENCY = 0.6
    private const val MIN_SPEED_MS = 2.0
    private const val G = 9.81
    private const val ROLLING = 0.010
    private const val RHO = 1.2
    private const val CDA_M2 = 0.6
    private const val J_PER_KWH = 3_600_000.0

    /** kWh recovered while speed falls from [fromMs] to [toMs] (m/s) over [seconds]; 0 when not slowing. */
    fun recoveredKwh(fromMs: Double, toMs: Double, seconds: Double): Double {
        val a = max(fromMs, MIN_SPEED_MS)
        val b = max(toMs, MIN_SPEED_MS)
        if (b >= a || seconds <= 0) return 0.0
        val kinetic = 0.5 * MASS_KG * (a * a - b * b)
        val mean = (a + b) / 2
        val lost = (MASS_KG * G * ROLLING + 0.5 * RHO * CDA_M2 * mean * mean) * mean * seconds
        return max(0.0, kinetic - lost) * EFFICIENCY / J_PER_KWH
    }

    /** One stop from [kmh] to standstill, braking at about 1.5 m/s². */
    fun perStopKwh(kmh: Double): Double {
        val v = kmh / 3.6
        return recoveredKwh(v, 0.0, v / 1.5)
    }
}
