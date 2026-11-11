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
 * Standard AAOS vehicle properties, read where the Pleos SDK points to them. The Pleos SDK policy is
 * to align with AAOS when the same function exists there, and its Gear API is deprecated with "use the
 * Google GEAR_SELECTION property"; likewise its EV battery capacity getter points to
 * INFO_EV_BATTERY_CAPACITY. Each property is subscribed on its own, so one the car refuses does not
 * stop the others.
 *
 * - GEAR_SELECTION: android.car.permission.CAR_POWERTRAIN
 * - INFO_EV_BATTERY_CAPACITY (Wh): android.car.permission.CAR_INFO
 * - EV_BATTERY_LEVEL (Wh): android.car.permission.CAR_ENERGY (runtime)
 */
class AaosSignals(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext

    sealed interface Signal {
        data class Gear(val parked: Boolean) : Signal
        data class EvCapacityKwh(val kwh: Double) : Signal
        data class EvLevelKwh(val kwh: Double) : Signal
    }

    fun signals(): Flow<Signal> = callbackFlow {
        val car = runCatching { Car.createCar(app) }.getOrNull()
        val manager = car?.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
        if (manager == null) {
            log("AAOS 차량 속성 서비스 없음")
            car?.disconnect()
            close()
            return@callbackFlow
        }
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                if (value.status != CarPropertyValue.STATUS_AVAILABLE) return
                when (value.propertyId) {
                    VehiclePropertyIds.GEAR_SELECTION -> (value.value as? Int)?.let { g ->
                        if (g != GEAR_UNKNOWN) trySend(Signal.Gear(g == GEAR_PARK))
                    }
                    VehiclePropertyIds.EV_BATTERY_LEVEL -> (value.value as? Float)?.let { wh ->
                        if (wh > 0) trySend(Signal.EvLevelKwh(wh / 1000.0))
                    }
                }
            }

            override fun onErrorEvent(propertyId: Int, zone: Int) {
                log("AAOS 속성 오류: $propertyId")
            }
        }
        val subscribed = mutableListOf<Int>()
        for (id in listOf(VehiclePropertyIds.GEAR_SELECTION, VehiclePropertyIds.EV_BATTERY_LEVEL)) {
            val ok = runCatching { manager.registerCallback(callback, id, CarPropertyManager.SENSOR_RATE_ONCHANGE) }
                .onFailure { log("AAOS 속성 $id 구독 실패: ${it.message}") }.getOrDefault(false)
            if (ok) subscribed += id
        }
        // A static property: read once.
        runCatching { manager.getProperty(Float::class.javaObjectType, VehiclePropertyIds.INFO_EV_BATTERY_CAPACITY, 0) }
            .onSuccess { v -> v?.value?.let { wh -> if (wh > 0) trySend(Signal.EvCapacityKwh(wh / 1000.0)) } }
            .onFailure { log("배터리 용량 없음(연료차일 수 있음): ${it.message}") }
        if (subscribed.isEmpty()) log("AAOS 기어·배터리 구독 없음, Pleos SDK 기어로 대신함")
        awaitClose {
            runCatching { manager.unregisterCallback(callback) }
            car.disconnect()
        }
    }

    private companion object {
        /** android.car.VehicleGear values. */
        const val GEAR_UNKNOWN = 0
        const val GEAR_PARK = 4
    }
}
