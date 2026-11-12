package com.ifev.app.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import com.ifev.app.data.GearState

/** AAOS VehicleGear values are individual bit values, not ordinal positions. */
fun gearStateFromAaos(value: Int): GearState = when (value) {
    4 -> GearState.PARK
    2 -> GearState.REVERSE
    1 -> GearState.NEUTRAL
    8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096 -> GearState.DRIVE
    else -> GearState.UNKNOWN
}

fun gearStateFromPleos(value: VehicleGear?): GearState = when (value) {
    null, VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
    VehicleGear.GEAR_PARK -> GearState.PARK
    VehicleGear.GEAR_REVERSE -> GearState.REVERSE
    VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
    else -> GearState.DRIVE
}

/** Full-gear arbitration for drawing only; the trip engine's P/non-P arbiter is unchanged. */
internal class VisualGearArbiter(private val aaosStaleMs: Long = 60_000L) {
    private var lastAaosAt: Long? = null

    fun onAaos(now: Long) { lastAaosAt = now }

    fun acceptPleos(gear: GearState, current: GearState, now: Long): Boolean {
        val last = lastAaosAt ?: return true
        return now - last > aaosStaleMs && gear != current
    }
}
