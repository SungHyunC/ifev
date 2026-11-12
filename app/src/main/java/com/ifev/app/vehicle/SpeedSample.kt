package com.ifev.app.vehicle

import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue

/** Signed m/s and callback receipt time, not sensor time or wall-clock time. */
data class SpeedSample(val speedMs: Double, val receivedAtMs: Long)

internal fun speedSample(propertyId: Int, status: Int, value: Any?, receivedAtMs: Long): SpeedSample? {
    if (propertyId != VehiclePropertyIds.PERF_VEHICLE_SPEED || status != CarPropertyValue.STATUS_AVAILABLE) return null
    val speed = (value as? Float)?.takeIf { it.isFinite() } ?: return null
    return SpeedSample(speed.toDouble(), receivedAtMs)
}

/** Null means the config is missing/invalid and the caller should use AAOS standard rates. */
internal fun speedSampleRate(minHz: Float?, maxHz: Float?): Float? {
    if (minHz == null || maxHz == null || !minHz.isFinite() || !maxHz.isFinite() ||
        minHz < 0f || maxHz <= 0f || minHz > maxHz) return null
    return 10f.coerceIn(minHz, maxHz)
}
