package com.ifev.app.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.DistanceDrivenListener
import ai.pleos.playground.vehicle.listener.FuelEconomyListener
import ai.pleos.playground.vehicle.listener.FuelPercentListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import android.content.Context
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.time.LocalDate
import kotlin.coroutines.resume

/** What the app reads from the car. Every value is read-only: the app never controls the vehicle. */
sealed interface CarSignal {
    data class Gear(val parked: Boolean) : CarSignal
    data class Odometer(val km: Double) : CarSignal
    data class FuelPercent(val percent: Int) : CarSignal
    /** Average of the trip in progress, km/L. */
    data class TripFuelEconomy(val kmPerL: Double) : CarSignal
    /** Long-run average, km/L. */
    data class AverageFuelEconomy(val kmPerL: Double) : CarSignal
    data class Climate(val on: Boolean) : CarSignal
}

/** Everything the app does through the Pleos Vehicle SDK. Permissions: CAR_DRIVE, CAR_ENERGY, CAR_CLIMATE, CAR_INFO. */
class VehicleGateway(context: Context, private val log: (String) -> Unit) {

    private val vehicle = Vehicle(context.applicationContext)

    fun start() = vehicle.initialize()

    fun stop() = vehicle.release()

    /** Live signals. Listeners are registered on collection and removed on cancellation. */
    fun signals(): Flow<CarSignal> = callbackFlow {
        fun failed(what: String) = { e: Exception -> log("$what 구독 실패: ${e.message}") }
        val gear = vehicle.getGear()
        val odometer = vehicle.getOdometer()
        val fuel = vehicle.getFuel()
        val hvac = vehicle.getHVAC()

        val gearListener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) {
                if (gear != null && gear != VehicleGear.GEAR_UNKNOWN) trySend(CarSignal.Gear(gear == VehicleGear.GEAR_PARK))
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
                if (economy != null && economy > 0f) trySend(CarSignal.TripFuelEconomy(economy.toDouble()))
            }
            override fun onFailed(e: Exception) = failed("주행 연비")(e)
        }
        val averageEconomyListener = object : FuelEconomyListener {
            override fun onFuelEconomyUpdated(economy: Float?) {
                if (economy != null && economy > 0f) trySend(CarSignal.AverageFuelEconomy(economy.toDouble()))
            }
            override fun onFailed(e: Exception) = failed("평균 연비")(e)
        }
        val climateListener = object : HvacPowerStateListener {
            override fun onHvacPowerStateUpdated(on: Boolean?) {
                if (on != null) trySend(CarSignal.Climate(on))
            }
            override fun onFailed(e: Exception) = failed("공조")(e)
        }

        gear.registerGearSelection(gearListener)
        odometer.registerDistanceDriven(odoListener)
        fuel.registerFuelPercent(percentListener)
        fuel.registerCurrentFuelEconomy(tripEconomyListener)
        fuel.registerCumulativeFuelEconomy(averageEconomyListener)
        // Climate is per seat zone; the first zone the car offers stands for the cabin.
        val climateArea = awaitOrNull<SeatArea?> { done ->
            hvac.checkHvacPowerCapabilities({ areas: List<SeatArea> -> done(areas.firstOrNull()) }, { e -> log("공조 지원 확인 실패: ${e.message}"); done(null) })
        }
        climateArea?.let { hvac.registerHvacPowerState(it, climateListener) }

        // Listeners only fire on change; read the current values once so the screen is filled at start.
        gear.getCurrentGearSelectionState({ g -> if (g != null && g != VehicleGear.GEAR_UNKNOWN) trySend(CarSignal.Gear(g == VehicleGear.GEAR_PARK)) }, failed("기어 조회"))
        odometer.getCurrentDistanceDriven({ km -> if (km != null) trySend(CarSignal.Odometer(km.toDouble())) }, failed("누적 주행거리 조회"))
        fuel.getFuelPercent({ p -> if (p != null) trySend(CarSignal.FuelPercent(p)) }, failed("연료량 조회"))
        fuel.getCumulativeFuelEconomy({ v -> if (v != null && v > 0f) trySend(CarSignal.AverageFuelEconomy(v.toDouble())) }, failed("평균 연비 조회"))
        climateArea?.let { area -> hvac.getHvacPowerState(area, { on -> if (on != null) trySend(CarSignal.Climate(on)) }, failed("공조 조회")) }

        awaitClose {
            runCatching { gear.unregisterGearSelection(gearListener) }
            runCatching { odometer.unregisterDistanceDriven(odoListener) }
            runCatching { fuel.unregisterFuelPercent(percentListener) }
            runCatching { fuel.unregisterCurrentFuelEconomy(tripEconomyListener) }
            runCatching { fuel.unregisterCumulativeFuelEconomy(averageEconomyListener) }
            climateArea?.let { runCatching { hvac.unregisterHvacPowerState(it, climateListener) } }
        }
    }

    /**
     * Model year from the VIN's 10th character, read once and never stored. Needs PRIVATE_CAR_INFO;
     * when the car refuses, the driver is asked instead.
     */
    suspend fun modelYear(): Int? {
        val vin = awaitOrNull<String?> { done -> vehicle.getCarInfo().getVINInfo({ done(it) }, { e -> log("차대번호 조회 실패: ${e.message}"); done(null) }) }
        return vin?.let(::modelYearFromVin)
    }

    private suspend fun <T> awaitOrNull(timeoutMs: Long = 5_000, call: (done: (T) -> Unit) -> Unit): T? =
        withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont -> call { value -> if (cont.isActive) cont.resume(value) } }
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
