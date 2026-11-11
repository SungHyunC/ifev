package com.ifev.app.vehicle

import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Vehicle speed (m/s) from the standard AAOS property PERF_VEHICLE_SPEED, which the Pleos Vehicle SDK
 * does not expose. It fills in trip distance between coarse odometer steps. Permission:
 * android.car.permission.CAR_SPEED (runtime). The flow completes when the car refuses, so the
 * caller can retry once the permission is granted.
 */
class SpeedSource(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext

    fun speeds(): Flow<Double> = callbackFlow {
        val car = runCatching { Car.createCar(app) }.getOrNull()
        val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                (value.value as? Float)?.let { trySend(it.toDouble()) }
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                log("차속 오류: $propertyId")
            }
        }
        val ok = manager != null && runCatching {
            manager.registerCallback(callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL)
        }.onFailure { log("차속 구독 실패: ${it.message}") }.getOrDefault(false)
        if (!ok) {
            car?.disconnect()
            close()
            return@callbackFlow
        }
        awaitClose {
            runCatching { manager!!.unregisterCallback(callback) }
            car.disconnect()
        }
    }
}
