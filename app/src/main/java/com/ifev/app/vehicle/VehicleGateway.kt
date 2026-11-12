package com.ifev.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import android.content.Context
import com.ifev.app.data.GearState
import com.ifev.app.engine.kmPerLiterFromLitersPer100Km
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.coroutines.resume

/** What the app reads from the car. Every value is read-only: the app never controls the vehicle. */
sealed interface CarSignal {
    data class Gear(val parked: Boolean, val state: GearState = if (parked) GearState.PARK else GearState.DRIVE) : CarSignal
    data class Odometer(val km: Double) : CarSignal
    data class FuelPercent(val percent: Int) : CarSignal
    /** Average of the trip in progress, km/L (the SDK's L/100 km converted). */
    data class TripFuelEconomy(val kmPerL: Double) : CarSignal
    /** Long-run average, km/L (the SDK's L/100 km converted). */
    data class AverageFuelEconomy(val kmPerL: Double) : CarSignal
    data class Climate(val on: Boolean) : CarSignal
    /** Only an EV (or plug-in) reports these. The emulator's car is an EV. */
    data class EvSoc(val percent: Int) : CarSignal
    data class EvEfficiency(val kmPerKwh: Double) : CarSignal
}

/** Pleos read permissions: CAR_DRIVE, CAR_ENERGY, CAR_CLIMATE, CAR_INFO, CAR_LIGHTS. */
class VehicleGateway(context: Context, private val log: (String) -> Unit) {

    private val vehicle = Vehicle(context.applicationContext)

    /** Drawing signals share this SDK connection but do not enter the trip engine's event queue. */
    fun motion(): Flow<MotionState> = MotionSignals(vehicle, log).states()

    fun start() {
        runCatching { vehicle.initialize() }.onFailure { log("차량 SDK 초기화 실패: ${it.message}") }
    }

    fun stop() {
        runCatching { vehicle.release() }.onFailure { log("차량 SDK 해제 실패: ${it.message}") }
    }

    /** Live signals. Listeners are registered on collection and removed on cancellation. */
    fun signals(): Flow<CarSignal> = callbackFlow {
        fun failed(what: String) = { e: Exception -> log("$what 구독 실패: ${e.message}") }
        val gear = vehicle.getGear()
        val odometer = vehicle.getOdometer()
        val fuel = vehicle.getFuel()
        val hvac = vehicle.getHVAC()

        val gearListener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                trySend(CarSignal.Gear(gear == VehicleGear.GEAR_PARK, gearStateFromPleos(gear)))
            }
            override fun onFailed(e: Exception) = failed("기어")(e)
        }
        val odoListener = object : DistanceDrivenListener {
            override fun onDistanceDrivenUpdated(distance: Float?) {
                if (distance != null) trySend(CarSignal.Odometer(distance.toDouble()))
            }
            override fun onFailed(e: Exception) = failed("누적 주행거리")(e)
        }
        val percentListener = object : FuelPercentListener {
            override fun onFuelPercentUpdated(percent: Int?) {
                if (percent != null) trySend(CarSignal.FuelPercent(percent))
            }
            override fun onFailed(e: Exception) = failed("연료량")(e)
        }
        val tripEconomyListener = object : FuelEconomyListener {
            override fun onFuelEconomyUpdated(economy: Float?) {
                economy?.toDouble()?.let(::kmPerLiterFromLitersPer100Km)?.let { trySend(CarSignal.TripFuelEconomy(it)) }
            }
            override fun onFailed(e: Exception) = failed("주행 연비")(e)
        }
        val averageEconomyListener = object : FuelEconomyListener {
            override fun onFuelEconomyUpdated(economy: Float?) {
                economy?.toDouble()?.let(::kmPerLiterFromLitersPer100Km)?.let { trySend(CarSignal.AverageFuelEconomy(it)) }
            }
            override fun onFailed(e: Exception) = failed("평균 연비")(e)
        }
        val climateListener = object : HvacPowerStateListener {
            override fun onHvacPowerStateUpdated(on: Boolean?) {
                if (on != null) trySend(CarSignal.Climate(on))
            }
            override fun onFailed(e: Exception) = failed("공조")(e)
        }

        val ev = vehicle.getEvBattery()
        val socListener = object : EvBatteryStateOfChargeListener {
            override fun onEvBatteryStateOfChargeUpdated(value: Int?) {
                if (value != null && value in 0..100) trySend(CarSignal.EvSoc(value))
            }
            override fun onFailed(e: Exception) = failed("EV 배터리")(e)
        }

        // A call can also throw synchronously (e.g. SecurityException for a permission not granted, or a
        // property the car lacks); one refused signal must not end the flow and take the rest with it.
        fun guard(what: String, call: () -> Unit) {
            runCatching(call).onFailure { log("$what 실패: ${it.message}") }
        }
        guard("기어 구독") { gear.registerGearSelection(gearListener) }
        guard("EV 배터리 구독") { ev.registerEvBatteryStateOfCharge(socListener) }
        guard("누적 주행거리 구독") { odometer.registerDistanceDriven(odoListener) }
        guard("연료량 구독") { fuel.registerFuelPercent(percentListener) }
        guard("주행 연비 구독") { fuel.registerCurrentFuelEconomy(tripEconomyListener) }
        guard("평균 연비 구독") { fuel.registerCumulativeFuelEconomy(averageEconomyListener) }
        // Climate is per seat zone; the first zone the car offers stands for the cabin.
        val climateArea = awaitOrNull<SeatArea?> { done ->
            hvac.checkHvacPowerCapabilities({ areas: List<SeatArea> -> done(areas.firstOrNull()) }, { e -> log("공조 지원 확인 실패: ${e.message}"); done(null) })
        }
        climateArea?.let { guard("공조 구독") { hvac.registerHvacPowerState(it, climateListener) } }

        // Listeners only fire on change; read the current values once so the screen is filled at start.
        guard("기어 조회") { gear.getCurrentGearSelectionState({ g -> trySend(CarSignal.Gear(g == VehicleGear.GEAR_PARK, gearStateFromPleos(g))) }, failed("기어 조회")) }
        guard("누적 주행거리 조회") { odometer.getCurrentDistanceDriven({ km -> if (km != null) trySend(CarSignal.Odometer(km.toDouble())) }, failed("누적 주행거리 조회")) }
        guard("연료량 조회") { fuel.getFuelPercent({ p -> if (p != null) trySend(CarSignal.FuelPercent(p)) }, failed("연료량 조회")) }
        guard("평균 연비 조회") { fuel.getCumulativeFuelEconomy({ v -> v?.toDouble()?.let(::kmPerLiterFromLitersPer100Km)?.let { trySend(CarSignal.AverageFuelEconomy(it)) } }, failed("평균 연비 조회")) }
        climateArea?.let { area -> guard("공조 조회") { hvac.getHvacPowerState(area, { on -> if (on != null) trySend(CarSignal.Climate(on)) }, failed("공조 조회")) } }
        // A fuel car refuses these (not supported); that is expected, not an error to show.
        // Each on its own, so a refused SOC read does not skip the efficiency read.
        guard("EV 배터리 조회") { ev.getEvBatteryStateOfCharge({ v -> if (v != null && v in 0..100) trySend(CarSignal.EvSoc(v)) }, { log("EV 배터리 없음: ${it.message}") }) }
        guard("EV 전비 조회") { ev.getCumulativeEvEfficiency({ v -> if (v != null && v in 1f..15f) trySend(CarSignal.EvEfficiency(v.toDouble())) }, { log("EV 전비 없음: ${it.message}") }) }

        awaitClose {
            runCatching { gear.unregisterGearSelection(gearListener) }
            runCatching { odometer.unregisterDistanceDriven(odoListener) }
            runCatching { fuel.unregisterFuelPercent(percentListener) }
            runCatching { fuel.unregisterCurrentFuelEconomy(tripEconomyListener) }
            runCatching { fuel.unregisterCumulativeFuelEconomy(averageEconomyListener) }
            climateArea?.let { runCatching { hvac.unregisterHvacPowerState(it, climateListener) } }
            runCatching { ev.unregisterEvBatteryStateOfCharge(socListener) }
        }
    }

    /**
     * Model year from the VIN's 10th character, read once and never stored. getVINInfo needs CAR_INFO
     * only; when the car refuses, the driver is asked instead.
     */
    suspend fun modelYear(): Int? {
        val vin = awaitOrNull<String?> { done -> vehicle.getCarInfo().getVINInfo({ done(it) }, { e -> log("차대번호 조회 실패: ${e.message}"); done(null) }) }
        return vin?.let(::modelYearFromVin)
    }

    /** Null on timeout, and also when [call] throws synchronously, so the caller's coroutine survives. */
    private suspend fun <T> awaitOrNull(timeoutMs: Long = 5_000, call: (done: (T) -> Unit) -> Unit): T? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine<T?> { cont ->
                runCatching { call { value -> if (cont.isActive) cont.resume(value) } }
                    .onFailure { e -> log("차량 SDK 호출 실패: ${e.message}"); if (cont.isActive) cont.resume(null) }
            }
        }
}

/** ISO 3779 model-year code (10th VIN character), 2010–2039 cycle. */
fun modelYearFromVin(vin: String, today: LocalDate = LocalDate.now()): Int? {
    if (vin.length != 17) return null
    val codes = "ABCDEFGHJKLMNPRSTVWXY123456789"
    val index = codes.indexOf(vin[9].uppercaseChar())
    if (index < 0) return null
    val year = 2010 + index
    // The same letter repeats every 30 years; a code ahead of next year belongs to the previous cycle.
    return if (year > today.year + 1) year - 30 else year
}
