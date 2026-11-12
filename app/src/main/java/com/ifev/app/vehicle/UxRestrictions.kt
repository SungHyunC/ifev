package com.ifev.app.vehicle

import android.car.Car
import android.car.drivingstate.CarUxRestrictions
import android.car.drivingstate.CarUxRestrictionsManager
import android.content.Context
import com.ifev.app.data.GearState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun requiresVisualRestriction(requiresDo: Boolean, activeRestrictions: Int): Boolean =
    requiresDo || activeRestrictions and CarUxRestrictions.UX_RESTRICTIONS_NO_VIDEO != 0

/** Missing service/state, including unknown gear, is restricted until park is positively known. */
internal fun visualRestricted(serviceRestricted: Boolean?, gear: GearState): Boolean =
    serviceRestricted ?: (gear != GearState.PARK)

/**
 * Reading/subscribing to UX restrictions needs no additional permission (AOSP Android 14
 * CarUxRestrictionsManager and CarUxRestrictionsManagerService). CAR_DRIVING_STATE protects the
 * separate driving-state API, and CAR_UX_RESTRICTIONS_CONFIGURATION protects configuration writes;
 * neither is used here. See https://source.android.com/docs/automotive/driver_distraction/consume.
 */
class UxRestrictions(context: Context, private val log: (String) -> Unit) {
    private val app = context.applicationContext

    /** Null means use the gear fallback. A missing service completes so the owner can retry. */
    fun restrictions(): Flow<Boolean?> = callbackFlow {
        val car = runCatching { Car.createCar(app) }.getOrNull()
        val manager = runCatching {
            car?.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager
        }.onFailure { log("UX 제한 서비스 없음: ${it.message}") }.getOrNull()
        if (manager == null) {
            trySend(null)
            runCatching { car?.disconnect() }
            close()
            return@callbackFlow
        }
        fun publish(value: CarUxRestrictions?) {
            trySend(value?.let { requiresVisualRestriction(it.isRequiresDistractionOptimization, it.activeRestrictions) })
        }
        val listener = CarUxRestrictionsManager.OnUxRestrictionsChangedListener { publish(it) }
        val registered = runCatching { manager.registerListener(listener) }
            .onFailure { log("UX 제한 구독 실패: ${it.message}") }.isSuccess
        if (!registered) {
            trySend(null)
            runCatching { manager.unregisterListener() }
            runCatching { car?.disconnect() }
            close()
            return@callbackFlow
        }
        publish(runCatching { manager.currentCarUxRestrictions }.getOrNull())
        launch {
            // Service loss must clear a previous unrestricted value; do not age out change events.
            while (isActive) {
                delay(5_000)
                if (car?.isConnected != true) {
                    trySend(null)
                    close()
                    break
                }
                publish(runCatching { manager.currentCarUxRestrictions }.getOrNull())
            }
        }
        awaitClose {
            runCatching { manager.unregisterListener() }
            runCatching { car?.disconnect() }
        }
    }.conflate()
}
