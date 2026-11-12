package com.ifev.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.map

/**
 * Vehicle speed (m/s) from the standard AAOS property PERF_VEHICLE_SPEED, which the Pleos Vehicle SDK
 * does not expose. It fills in trip distance between coarse odometer steps. Permission:
 * android.car.permission.CAR_SPEED (runtime). The flow completes when the car refuses, so the
 * caller can retry once the permission is granted.
 */
class SpeedSource(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext

    fun speeds(): Flow<Double> = samples().map { it.speedMs }

    fun samples(): Flow<SpeedSample> = callbackFlow {
        val car = runCatching { Car.createCar(app) }.getOrNull()
        val manager = runCatching { car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager }.getOrNull()
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                val receivedAt = SystemClock.elapsedRealtime()
                // Unavailable values can still carry an old payload; never turn one into motion.
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                speedSample(value.propertyId, value.status, value.value, receivedAt)?.let { trySend(it) }
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                log("차속 오류: $propertyId")
            }
        }
        val config = runCatching { manager?.getCarPropertyConfig(VehiclePropertyIds.PERF_VEHICLE_SPEED) }.getOrNull()
        val rates = listOfNotNull(
            speedSampleRate(config?.minSampleRate, config?.maxSampleRate),
            CarPropertyManager.SENSOR_RATE_FASTEST,
            CarPropertyManager.SENSOR_RATE_NORMAL,
        ).distinct()
        val ok = manager != null && rates.any { rate ->
            runCatching { manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, rate) }
                .onFailure { log("차속 ${rate}Hz 구독 실패: ${it.message}") }.getOrDefault(false)
        }
        if (!ok) {
            runCatching { manager?.unregisterCallback(callback) }
            runCatching { car?.disconnect() }
            close()
            return@callbackFlow
        }
        awaitClose {
            runCatching { manager!!.unregisterCallback(callback) }
            runCatching { car?.disconnect() }
        }
    }
}
