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

        // Each attempt obtains the service again, so a transient connection failure can recover.
        // Install removal before registration: a throwing SDK call may still have added a listener.
        var removeSteering: () -> Unit = {}
        var removeBrake: () -> Unit = {}
        var removeTurn: () -> Unit = {}
        fun clearSteering() = synchronized(lock) {
            runCatching(removeSteering)
            removeSteering = {}
        }
        fun clearBrake() = synchronized(lock) {
            runCatching(removeBrake)
            removeBrake = {}
        }
        fun clearTurn() = synchronized(lock) {
            runCatching(removeTurn)
            removeTurn = {}
        }
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

        fun subscribe(
            what: String,
            unavailable: () -> Unit,
            cleanup: () -> Unit,
            attempt: suspend () -> MotionCapability,
        ) = launch {
            val result = retryMotionSubscription(
                attempt = attempt,
                cleanup = cleanup,
                onFailure = failed("$what 구독", unavailable),
                onRetry = { retry ->
                    unavailable()
                    log("$what 지원 확인/구독 재시도 $retry/3: 5초 후")
                },
            )
            if (result != MotionCapability.READABLE) unavailable()
        }

        trySend(state)
        subscribe("조향", ::steeringFailed, ::clearSteering) {
            val steering = vehicle.getSteeringWheel()
            val capability = readable("조향") { done, fail ->
                steering.checkSteeringWheelAngleCapabilities({ config, _ -> done(config) }, fail)
            }
            if (capability == MotionCapability.READABLE) synchronized(lock) {
                if (!closed) {
                    removeSteering = { steering.unregisterSteeringWheelAngle(steeringListener) }
                    steering.registerSteeringWheelAngle(steeringListener)
                    guard("조향 조회", ::steeringFailed) {
                        steering.getCurrentSteeringWheelAngle(steeringListener::onSteeringWheelAngleUpdated, failed("조향 조회", ::steeringFailed))
                    }
                }
            }
            capability
        }
        subscribe("브레이크", ::brakeFailed, ::clearBrake) {
            val brake = vehicle.getBrake()
            val capability = readable("브레이크", brake::checkBrakePedalStatusCapabilities)
            if (capability == MotionCapability.READABLE) synchronized(lock) {
                if (!closed) {
                    removeBrake = { brake.unregisterBrakePedalStatus(brakeListener) }
                    brake.registerBrakePedalStatus(brakeListener)
                    guard("브레이크 조회", ::brakeFailed) {
                        brake.getCurrentBrakePedalStatus(brakeListener::onBrakePedalStatusUpdated, failed("브레이크 조회", ::brakeFailed))
                    }
                }
            }
            capability
        }
        subscribe("방향지시등", ::turnFailed, ::clearTurn) {
            val turn = vehicle.getTurnSignal()
            val capability = readable("방향지시등", turn::checkTurnSignalStateCapabilities)
            if (capability == MotionCapability.READABLE) synchronized(lock) {
                if (!closed) {
                    removeTurn = { turn.unregisterTurnSignalState(turnListener) }
                    turn.registerTurnSignalState(turnListener)
                    guard("방향지시등 조회", ::turnFailed) {
                        turn.getCurrentTurnSignalState(turnListener::onTurnSignalStateUpdated, failed("방향지시등 조회", ::turnFailed))
                    }
                }
            }
            capability
        }
        awaitClose {
            synchronized(lock) {
                closed = true
                // Also remove partially registered listeners after a synchronous SDK failure.
                clearSteering()
                clearBrake()
                clearTurn()
            }
        }
    }.conflate()

    private suspend fun readable(
        what: String,
        check: (onComplete: (CapabilityConfig) -> Unit, onFailed: (Exception) -> Unit) -> Unit,
    ): MotionCapability = awaitMotionCapability(
        onFailure = { log("$what 지원 확인 실패: ${it.message}") },
        onTimeout = { log("$what 지원 확인 시간 초과") },
    ) { done, fail ->
        check({ done(it.accessType == AccessType.READ || it.accessType == AccessType.READ_WRITE) }, fail)
    }
}
