package com.ifev.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.ifev.app.data.CarState
import com.ifev.app.data.Settings
import com.ifev.app.engine.BatterySim
import com.ifev.app.engine.ChargingPlan
import com.ifev.app.engine.CompareRow
import com.ifev.app.engine.DaySummary
import com.ifev.app.engine.EnergyModel
import com.ifev.app.engine.EvCatalog
import com.ifev.app.engine.EvModel
import com.ifev.app.engine.FuelCar
import com.ifev.app.engine.FuelCarCatalog
import com.ifev.app.engine.FuelSource
import com.ifev.app.engine.ModelComparison
import com.ifev.app.engine.ModelRow
import com.ifev.app.engine.Measured
import com.ifev.app.engine.MeasuredReport
import com.ifev.app.engine.Monthly
import com.ifev.app.engine.Place
import com.ifev.app.engine.PrelimInput
import com.ifev.app.engine.PrelimResult
import com.ifev.app.engine.Preliminary
import com.ifev.app.engine.SeoulClimate
import com.ifev.app.engine.Trip
import com.ifev.app.engine.resolveFuelEconomy
import com.ifev.app.scenario.DemoScenario
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant

class MainViewModel(app: Application) : AndroidViewModel(app) {

    enum class Mode { LIVE, DEMO }
    enum class Stage { DAY_ZERO, DRIVING, FOUR_WEEKS }
    enum class Tab { PRELIM, MEASURED, REPORT }

    data class UiState(
        val mode: Mode,
        val stage: Stage,
        val tab: Tab,
        val car: CarState,
        val settings: Settings,
        val model: EvModel,
        val prelim: PrelimResult,
        val report: MeasuredReport?,
        val predicted: Monthly?,
        val rows: List<CompareRow>,
        val insights: List<String>,
        /** Imagined EV battery right now, 0..1. */
        val ghostSoc: Double,
        val tripKwh: Double,
        val recentDays: List<DaySummary>,
        val tripCount: Int,
        val fuelKmPerL: Double?,
        val fuelSource: FuelSource,
        val myCar: FuelCar?,
        /** Every Hyundai EV on the same facts and trips. */
        val modelRows: List<ModelRow>,
        /** What the app read from the car without asking; null value means the car did not give it. */
        val carFacts: List<CarFact>,
    )

    data class CarFact(val label: String, val value: String?, val api: String)

    private data class Local(
        val mode: Mode = Mode.LIVE,
        val stage: Stage = Stage.DAY_ZERO,
        val tab: Tab? = null,
        val demoCar: CarState = DemoScenario.dayZero(),
        val demoTrips: List<Trip> = emptyList(),
        val demoSettings: Settings = Settings(homeCharging = true, monthsOwned = DemoScenario.MONTHS_OWNED, monthsFromVin = true),
        val demoNow: Long? = null,
    )

    private val recorder = app.recorder
    private val local = MutableStateFlow(Local())
    private var driveJob: Job? = null

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    val ui: StateFlow<UiState?> = combine(local, recorder.car, recorder.trips, recorder.settings, ticker) { l, car, trips, settings, now ->
        if (l.mode == Mode.DEMO) build(l, l.demoCar, l.demoTrips, l.demoSettings, l.demoNow ?: now)
        else build(l, car, trips, settings, now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    fun setMode(mode: Mode) {
        driveJob?.cancel()
        local.update { Local(mode = mode, demoSettings = it.demoSettings) }
    }

    fun selectTab(tab: Tab) = local.update { it.copy(tab = tab) }

    fun selectModel(model: EvModel) = editSettings { it.copy(modelId = model.id) }

    fun answerHomeCharging(available: Boolean) = editSettings { it.copy(homeCharging = available) }

    fun selectMyCar(car: FuelCar) = editSettings { it.copy(myCarId = car.id) }

    fun adjustMonths(delta: Int) = editSettings {
        val base = it.monthsOwned ?: 12
        it.copy(monthsOwned = (base + delta).coerceIn(1, 240), monthsFromVin = false)
    }

    private fun editSettings(change: (Settings) -> Settings) {
        if (local.value.mode == Mode.DEMO) local.update { it.copy(demoSettings = change(it.demoSettings)) }
        else recorder.updateSettings(change(recorder.settings.value))
    }

    /** Demo stage ①: the day the app is installed. Nothing recorded yet, only what the car already knows. */
    fun demoDayZero() {
        driveJob?.cancel()
        local.update { it.copy(mode = Mode.DEMO, stage = Stage.DAY_ZERO, tab = Tab.PRELIM, demoCar = DemoScenario.dayZero(), demoTrips = emptyList(), demoNow = null) }
    }

    /** Demo stage ②: one commute played back in about a minute, with the imagined battery draining alongside. */
    fun demoDrive() {
        driveJob?.cancel()
        local.update { it.copy(mode = Mode.DEMO, stage = Stage.DRIVING, tab = Tab.MEASURED, demoTrips = emptyList(), demoNow = null) }
        driveJob = viewModelScope.launch {
            val start = System.currentTimeMillis()
            val realMs = (DemoScenario.DRIVE_REAL_MINUTES * 60_000).toLong()
            val steps = DemoScenario.drive
            val total = steps.last().first
            val base = DemoScenario.dayZero()
            for ((sec, km) in steps) {
                val simulatedNow = start + realMs * sec / total
                local.update {
                    it.copy(
                        demoNow = simulatedNow,
                        demoCar = base.copy(
                            parked = false,
                            tripStartAt = start,
                            tripKm = km,
                            tripClimateShare = 1.0,
                            odometerKm = base.odometerKm!! + km,
                            fuelPercent = if (sec > total / 2) 53 else 54,
                        ),
                    )
                }
                delay(1_000)
            }
            val end = start + realMs
            val trip = Trip(start, end, steps.last().second, Place.HOME, Place.OFFICE, SeoulClimate.meanC(month(end)), 1.0, 11.8)
            local.update {
                it.copy(
                    demoNow = end + 60_000,
                    demoTrips = listOf(trip),
                    demoCar = base.copy(parked = true, odometerKm = base.odometerKm!! + trip.distanceKm, fuelPercent = 53),
                )
            }
        }
    }

    /** Demo stage ③: four recorded weeks, compared with the day-one estimate. */
    fun demoFourWeeks() {
        driveJob?.cancel()
        local.update {
            it.copy(
                mode = Mode.DEMO, stage = Stage.FOUR_WEEKS, tab = Tab.REPORT,
                demoCar = DemoScenario.dayZero(), demoTrips = DemoScenario.fourWeeks(), demoNow = DemoScenario.fourWeeksNow,
            )
        }
    }

    private fun build(l: Local, car: CarState, trips: List<Trip>, settings: Settings, now: Long): UiState {
        val model = EvCatalog.byId(settings.modelId)
        val myCar = FuelCarCatalog.byId(settings.myCarId)
        val (fuel, fuelSource) = resolveFuelEconomy(car.averageFuelKmPerL, myCar)
        val plan = ChargingPlan(homeCharging = settings.homeCharging ?: true)
        val input = PrelimInput(
            commuteOneWayKm = car.commute?.km,
            destinations = car.destinations.map { it.first },
            odometerKm = car.odometerKm,
            monthsOwned = settings.monthsOwned,
            fuelKmPerL = fuel,
            fuelIsCertified = fuelSource == FuelSource.CERTIFIED,
            homeCharging = settings.homeCharging,
            month = month(now),
        )
        val prelim = Preliminary.estimate(model, input, plan)
        val report = Measured.report(model, trips, plan, fallbackFuelKmPerL = fuel, nowMs = now)
        val modelRows = ModelComparison.rows(input, plan, trips, fallbackFuelKmPerL = fuel, nowMs = now)
        val predicted = Measured.predictedMonthly(prelim)

        // Battery when this trip began, after any charging while parked; then this trip's energy.
        val beforeTrip = if (trips.isEmpty()) plan.startSoc
        else BatterySim.run(model, trips, plan, nowMs = car.tripStartAt ?: now).endSoc
        val tripKwh = if (car.driving && car.tripKm > 0) {
            val hours = ((now - (car.tripStartAt ?: now)) / 3_600_000.0).coerceAtLeast(1.0 / 60)
            val speed = (car.tripKm / hours).coerceIn(5.0, 120.0)
            car.tripKm * EnergyModel.kwhPerKm(model, SeoulClimate.meanC(month(now)), speed, car.tripClimateShare)
        } else 0.0

        val tab = l.tab ?: when {
            report == null -> Tab.PRELIM
            report.days >= Measured.FULL_DAYS -> Tab.REPORT
            else -> Tab.MEASURED
        }
        return UiState(
            mode = l.mode,
            stage = l.stage,
            tab = tab,
            car = car,
            settings = settings,
            model = model,
            prelim = prelim,
            report = report,
            predicted = predicted,
            rows = report?.let { Measured.compare(predicted, it.monthly) }.orEmpty(),
            insights = report?.let { Measured.insights(prelim, predicted, it) }.orEmpty(),
            ghostSoc = (beforeTrip - tripKwh / model.batteryKwh).coerceIn(0.0, 1.0),
            tripKwh = tripKwh,
            recentDays = report?.sim?.days?.takeLast(7)?.reversed().orEmpty(),
            tripCount = trips.size,
            fuelKmPerL = fuel,
            fuelSource = fuelSource,
            myCar = myCar,
            modelRows = modelRows,
            carFacts = carFacts(car, trips),
        )
    }

    private fun carFacts(car: CarState, trips: List<Trip>): List<CarFact> = listOf(
        CarFact("누적 주행거리", car.odometerKm?.takeIf { it > 0 }?.let { "%,d km".format(it.toInt()) }, "Odometer"),
        CarFact("연식 (타 온 기간)", car.modelYear?.let { "${it}년식" }, "CarInfo.getVINInfo"),
        CarFact("내 차 평균 연비", car.averageFuelKmPerL?.let { "%.1f km/L".format(it) }, "Fuel"),
        CarFact("집 · 회사", listOfNotNull(car.home?.let { "집" }, car.office?.let { "회사" }).joinToString(" · ").ifEmpty { null }, "NaviHelper 즐겨찾기"),
        CarFact("최근 목적지", car.destinations.size.takeIf { it > 0 }?.let { "${it}곳" }, "NaviHelper 최근 목적지"),
        CarFact("공조 사용", car.climateOn?.let { if (it) "켜짐" else "꺼짐" }, "HVAC"),
        CarFact("주행 기록 (P→D→P)", if (trips.isNotEmpty()) "${trips.size}번 자동 기록" else if (car.parked != null) "기어 신호 수신 중" else null, "Gear"),
    )

    private fun month(ms: Long) = Instant.ofEpochMilli(ms).atZone(Trip.KST).monthValue

    private companion object {
        const val TICK_MS = 1_000L
    }
}
