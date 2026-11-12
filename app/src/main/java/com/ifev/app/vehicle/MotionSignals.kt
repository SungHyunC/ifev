package com.ifev.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.os.SystemClock
import com.ifev.app.data.TurnState
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

data class MotionState(
    val steeringDeg: Double? = null,
    val steeringAtMs: Long? = null,
    val brake: Boolean? = null,
    val turn: TurnState = TurnState.OFF,
    val steeringAvailable: Boolean = false,
    val brakeAvailable: Boolean = false,
    val turnAvailable: Boolean = false,
)

/** Pleos 2.3.2 read-only signals. CAR_DRIVE for steering/brake; CAR_LIGHTS for turn signals. */
internal class MotionSignals(private val vehicle: Vehicle, private val log: (String) -> Unit) {
    fun states(): Flow<MotionState> = callbackFlow {
        // Merge before conflating: a brake event must never discard the latest steering update.
        val lock = Any()
        var closed = false
        var state = MotionState()
        fun update(change: (MotionState) -> MotionState) = synchronized(lock) {
            if (!closed) {
                state = change(state)
                trySend(state)
            }
        }
        fun steeringFailed() = update { it.copy(steeringDeg = null, steeringAtMs = null, steeringAvailable = false) }
        fun brakeFailed() = update { it.copy(brake = null, brakeAvailable = false) }
        fun turnFailed() = update { it.copy(turn = TurnState.OFF, turnAvailable = false) }
        fun failed(what: String, unavailable: () -> Unit): (Exception) -> Unit = { e ->
            log("$what 실패: ${e.message}")
            unavailable()
        }
        fun guard(what: String, unavailable: () -> Unit, call: () -> Unit) {
            runCatching(call).onFailure { log("$what 실패: ${it.message}"); unavailable() }
        }

        val steering = runCatching { vehicle.getSteeringWheel() }.onFailure { log("조향 서비스 없음: ${it.message}") }.getOrNull()
        val brake = runCatching { vehicle.getBrake() }.onFailure { log("브레이크 서비스 없음: ${it.message}") }.getOrNull()
        val turn = runCatching { vehicle.getTurnSignal() }.onFailure { log("방향지시등 서비스 없음: ${it.message}") }.getOrNull()
        val steeringListener = object : SteeringWheelAngleListener {
            override fun onSteeringWheelAngleUpdated(angle: Float?) {
                val at = SystemClock.elapsedRealtime()
                val valid = angle?.takeIf { it.isFinite() }?.toDouble()
                update { it.copy(steeringDeg = valid, steeringAtMs = at.takeIf { valid != null }, steeringAvailable = valid != null) }
            }
            override fun onFailed(e: Exception) = failed("조향", ::steeringFailed)(e)
        }
        val brakeListener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(status: Boolean?) {
                update { it.copy(brake = status, brakeAvailable = status != null) }
            }
            override fun onFailed(e: Exception) = failed("브레이크", ::brakeFailed)(e)
        }
        val turnListener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
                val mapped = when (state) {
                    TurnSignalValue.LEFT -> TurnState.LEFT
                    TurnSignalValue.RIGHT -> TurnState.RIGHT
                    else -> TurnState.OFF
                }
                update { it.copy(turn = mapped, turnAvailable = state != null) }
            }
            override fun onFailed(e: Exception) = failed("방향지시등", ::turnFailed)(e)
        }

        trySend(state)
        launch {
            if (steering != null && readable("조향") { done, fail ->
                    steering.checkSteeringWheelAngleCapabilities({ config, _ -> done(config) }, fail)
                }) synchronized(lock) {
                if (!closed) {
                    guard("조향 구독", ::steeringFailed) { steering.registerSteeringWheelAngle(steeringListener) }
                    guard("조향 조회", ::steeringFailed) {
                        steering.getCurrentSteeringWheelAngle(steeringListener::onSteeringWheelAngleUpdated, failed("조향 조회", ::steeringFailed))
                    }
                }
            }
        }
        launch {
            if (brake != null && readable("브레이크", brake::checkBrakePedalStatusCapabilities)) synchronized(lock) {
                if (!closed) {
                    guard("브레이크 구독", ::brakeFailed) { brake.registerBrakePedalStatus(brakeListener) }
                    guard("브레이크 조회", ::brakeFailed) {
                        brake.getCurrentBrakePedalStatus(brakeListener::onBrakePedalStatusUpdated, failed("브레이크 조회", ::brakeFailed))
                    }
                }
            }
        }
        launch {
            if (turn != null && readable("방향지시등", turn::checkTurnSignalStateCapabilities)) synchronized(lock) {
                if (!closed) {
                    guard("방향지시등 구독", ::turnFailed) { turn.registerTurnSignalState(turnListener) }
                    guard("방향지시등 조회", ::turnFailed) {
                        turn.getCurrentTurnSignalState(turnListener::onTurnSignalStateUpdated, failed("방향지시등 조회", ::turnFailed))
                    }
                }
            }
        }
        awaitClose {
            synchronized(lock) {
                closed = true
                // Also remove partially registered listeners after a synchronous SDK failure.
                runCatching { steering?.unregisterSteeringWheelAngle(steeringListener) }
                runCatching { brake?.unregisterBrakePedalStatus(brakeListener) }
                runCatching { turn?.unregisterTurnSignalState(turnListener) }
            }
        }
    }.conflate()

    private suspend fun readable(
        what: String,
        check: (onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
    ): Boolean = withTimeoutOrNull(5_000) {
        suspendCancellableCoroutine { cont ->
            fun finish(value: Boolean) { if (cont.isActive) cont.resume(value) }
            runCatching {
                check({ finish(it.accessType == AccessType.READ || it.accessType == AccessType.READ_WRITE) }, {
                    log("$what 지원 확인 실패: ${it.message}")
                    finish(false)
                })
            }.onFailure { log("$what 지원 확인 실패: ${it.message}"); finish(false) }
        }
    } ?: false
}
