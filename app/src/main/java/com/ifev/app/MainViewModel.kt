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
import com.ifev.app.data.RouteTarget
import com.ifev.app.engine.DistanceSource
import com.ifev.app.engine.Lines
import com.ifev.app.engine.Levers
import com.ifev.app.engine.Regen
import com.ifev.app.engine.Prices
import com.ifev.app.engine.Moments
import com.ifev.app.engine.NaviCheck
import com.ifev.app.engine.NaviComparison
import com.ifev.app.engine.RoutePlanner
import com.ifev.app.engine.RoutePreview
import com.ifev.app.engine.resolveFuelEconomy
import com.ifev.app.scenario.DemoScenario
import com.ifev.app.scenario.RealDrive
import com.ifev.app.scenario.RealDrives
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
    enum class Stage { DAY_ZERO, DRIVING, FOUR_WEEKS, REAL }
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
        /** Fast charges the imagined EV would already have needed on this trip. */
        val tripFastStops: Int,
        /** Imagined battery when this trip began. */
        val startSoc: Double,
        /** The imagined car charged while parked before this trip (overnight at home, say). */
        val chargedWhileParked: Boolean,
        /** The navigation's active route, driven by the imagined EV from the battery it has now. */
        val route: RoutePreview?,
        /** For a car that already is an EV: the navigation's arrival estimate next to IfEV's. */
        val naviCheck: NaviCheck?,
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
        val liveSignals: List<CarFact>,
        val voiceEngine: String,
        /** Set while showing a real person's recorded weeks. */
        val realLabel: String?,
        val realDriveLabels: List<String>,
        /** Where the real record came from: a participant's phone GPS, or a public dataset. */
        val realSource: String?,
        /** The same recorded weeks at the January normal temperature. */
        val winter: MeasuredReport?,
        /** For a car that already is an EV: IfEV's prediction graded by the car's own battery. */
        val score: Moments.PredictionScore?,
        /** The line the car spoke last, while it is fresh. */
        val lastLine: String?,
        /** What would change the verdict (or break it), replayed on the same weeks. */
        val levers: Levers.Result?,
        /** Virtual regenerative braking on the drive in progress, kWh. */
        val tripRegenKwh: Double,
    )

    data class CarFact(val label: String, val value: String?, val api: String)

    private data class Local(
        val mode: Mode = Mode.LIVE,
        val stage: Stage = Stage.DAY_ZERO,
        val tab: Tab? = null,
        val demoCar: CarState = DemoScenario.dayZero(),
        val demoTrips: List<Trip> = emptyList(),
        val demoSettings: Settings = Settings(homeCharging = true, monthsOwned = DemoScenario.MONTHS_OWNED, monthsFromVin = false),
        val demoNow: Long? = null,
        val realLabel: String? = null,
        val realSource: String? = null,
        /** The demo drive's virtual regeneration (the live drive's comes from the recorder). */
        val demoRegenKwh: Double = 0.0,
    )

    private val recorder = app.recorder
    private val narrator = app.narrator
    /** Real people's recorded weeks bundled for the presentation; empty until collected. */
    val realDrives: List<RealDrive> = RealDrives.load(app)
    private val local = MutableStateFlow(Local())
    private var driveJob: Job? = null

    private val ticker = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(TICK_MS)
        }
    }

    @Volatile private var lastLine: Pair<String, Long>? = null

    init {
        viewModelScope.launch { narrator.lastLine.collect { lastLine = it } }
    }

    val ui: StateFlow<UiState?> = combine(local, recorder.car, recorder.trips, recorder.settings, ticker) { l, car, trips, settings, now ->
        if (l.mode == Mode.DEMO) build(l, l.demoCar, l.demoTrips, l.demoSettings, l.demoNow ?: now)
        else build(l, car, trips, settings, now)
    }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** True while the real car is out of P: nothing on screen may be switched then. */
    private val liveDriving get() = recorder.car.value.parked == false

    fun setMode(mode: Mode) {
        if (liveDriving) return
        narrator.clearLastLine()
        driveJob?.cancel()
        local.update { Local(mode = mode, demoSettings = it.demoSettings) }
    }

    fun selectTab(tab: Tab) = local.update { it.copy(tab = tab) }

    fun selectModel(model: EvModel) = editSettings { it.copy(modelId = model.id) }

    fun answerHomeCharging(available: Boolean) = editSettings { it.copy(homeCharging = available) }

    fun answerWorkCharging(available: Boolean) = editSettings { it.copy(workCharging = available) }

    fun selectMyCar(car: FuelCar) = editSettings { it.copy(myCarId = car.id) }

    fun toggleVoice() = editSettings { it.copy(voiceOn = !it.voiceOn) }

    /** The demo plays its own moments; the same lines the live car would say. */
    private fun demoSay(text: String) {
        if (local.value.demoSettings.voiceOn) narrator.say(text)
    }

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
        if (liveDriving) return
        narrator.clearLastLine()
        driveJob?.cancel()
        local.update { it.copy(mode = Mode.DEMO, stage = Stage.DAY_ZERO, tab = Tab.PRELIM, demoCar = DemoScenario.dayZero(), demoTrips = emptyList(), demoNow = null) }
    }

    /**
     * Demo stage ②: one commute played back in about a minute, with the imagined battery draining
     * alongside. It starts the way a real one would: a destination set in the navigation, spoken.
     */
    fun demoDrive() {
        if (liveDriving) return
        narrator.clearLastLine()
        driveJob?.cancel()
        local.update { it.copy(mode = Mode.DEMO, stage = Stage.DRIVING, tab = Tab.MEASURED, demoTrips = emptyList(), demoNow = null, demoRegenKwh = 0.0) }
        driveJob = viewModelScope.launch {
            val settings = local.value.demoSettings
            val model = EvCatalog.byId(settings.modelId)
            val plan = settings.plan()
            val start = System.currentTimeMillis()
            val realMs = (DemoScenario.DRIVE_REAL_MINUTES * 60_000).toLong()
            val steps = DemoScenario.drive
            val total = steps.last().first
            val base = DemoScenario.dayZero()
            val commute = steps.last().second
            val destination = DemoScenario.office.name
            fun route(kmDone: Double) = RouteTarget(destination, commute - kmDone, DemoScenario.DRIVE_REAL_MINUTES * (1 - kmDone / commute))
            demoSay(Lines.route(model, destination, RoutePlanner.preview(model, plan.startSoc, commute, DemoScenario.DRIVE_REAL_MINUTES, month(start), plan)))
            for ((sec, km) in steps) {
                val simulatedNow = start + realMs * sec / total
                // Stop-and-go to the office: a stop at about every 0.8 km from 50 km/h.
                val regen = kotlin.math.floor(km / DemoScenario.STOP_EVERY_KM) * Regen.perStopKwh(50.0)
                local.update {
                    it.copy(
                        demoRegenKwh = regen,
                        demoNow = simulatedNow,
                        demoCar = base.copy(
                            parked = false,
                            tripStartAt = start,
                            tripKm = km,
                            tripClimateShare = 1.0,
                            odometerKm = base.odometerKm!! + km,
                            fuelPercent = if (sec > total / 2) 53 else 54,
                            route = route(km).takeIf { km < commute },
                        ),
                    )
                }
                delay(1_000)
            }
            val end = start + realMs
            val trip = Trip(
                start, end, commute, Place.HOME, Place.OFFICE, SeoulClimate.meanC(month(end)), 1.0, 11.8, DistanceSource.SPEED,
                regenKwh = kotlin.math.floor(commute / DemoScenario.STOP_EVERY_KM) * Regen.perStopKwh(50.0),
            )
            local.update {
                it.copy(
                    demoNow = end + 60_000,
                    demoTrips = listOf(trip),
                    demoCar = base.copy(parked = true, odometerKm = base.odometerKm!! + trip.distanceKm, fuelPercent = 53),
                )
            }
            val kwh = trip.distanceKm * EnergyModel.kwhPerKm(model, trip.tempC, trip.avgSpeedKmh, trip.climateShare)
            val after = BatterySim.run(model, listOf(trip), plan, nowMs = trip.endAt).endSoc
            demoSay(Lines.parked(model, trip, kwh, plan, after))
        }
    }

    /** A real person's recorded weeks (phone GPS, no coordinates) on the same screens. */
    fun demoReal(index: Int) {
        if (liveDriving) return
        narrator.clearLastLine()
        val d = realDrives.getOrNull(index) ?: return
        driveJob?.cancel()
        local.update {
            it.copy(
                mode = Mode.DEMO, stage = Stage.REAL, tab = Tab.REPORT,
                demoCar = CarState(vehicleStatus = "실제 기록", naviStatus = "실제 기록", parked = true),
                demoTrips = d.trips, demoNow = d.trips.maxOf { t -> t.endAt },
                demoSettings = it.demoSettings.copy(homeCharging = d.homeCharging),
                realLabel = d.label,
                realSource = d.source,
            )
        }
    }

    /**
     * Demo: the fill-up moment, at the end of the four made-up weeks. The last fill-up was nine days
     * before; the trips since then are priced in petrol and in the imagined EV's electricity.
     */
    fun demoRefuel() {
        if (liveDriving) return
        demoFourWeeks()
        val settings = local.value.demoSettings
        val model = EvCatalog.byId(settings.modelId)
        val plan = settings.plan()
        val since = DemoScenario.fourWeeks().filter { it.startAt >= DemoScenario.fourWeeksNow - DemoScenario.REFUEL_EVERY_MS }
        Moments.sinceRefuel(model, since, plan, DemoScenario.dayZero().averageFuelKmPerL)?.let { demoSay(Lines.refueled(model, it, plan)) }
    }

    /** Demo stage ③: four recorded weeks, compared with the day-one estimate. */
    fun demoFourWeeks() {
        if (liveDriving) return
        narrator.clearLastLine()
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
        val plan = settings.plan()
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
        val answered = settings.homeCharging != null
        val report = Measured.report(model, trips, plan, fallbackFuelKmPerL = fuel, nowMs = now, homeChargingAnswered = answered)
        val modelRows = ModelComparison.rows(input, plan, trips, fallbackFuelKmPerL = fuel, nowMs = now, homeChargingAnswered = answered)
        val predicted = Measured.predictedMonthly(prelim)

        // Battery when this trip began, after any charging while parked; then this trip's energy.
        val beforeTrip = if (trips.isEmpty()) plan.startSoc
        else BatterySim.run(model, trips, plan, nowMs = car.tripStartAt ?: now).endSoc
        val tripKwh = if (car.driving && car.tripKm > 0) {
            val hours = ((now - (car.tripStartAt ?: now)) / 3_600_000.0).coerceAtLeast(1.0 / 60)
            val speed = (car.tripKm / hours).coerceIn(5.0, 120.0)
            car.tripKm * EnergyModel.kwhPerKm(model, SeoulClimate.meanC(month(now)), speed, car.tripClimateShare)
        } else 0.0

        // The same rule as the replay: never below the reserve, a fast charge on the way comes first.
        val drained = RoutePlanner.drain(beforeTrip, tripKwh, model.batteryKwh, plan)
        // Did the imagined car charge (at home overnight, say) while parked before this trip?
        val chargedWhileParked = car.driving && trips.isNotEmpty() &&
            beforeTrip > BatterySim.run(model, trips, plan, nowMs = trips.maxOf { it.endAt }).endSoc + 0.01

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
            ghostSoc = drained.soc.coerceIn(0.0, 1.0),
            tripFastStops = drained.stops,
            startSoc = beforeTrip,
            chargedWhileParked = chargedWhileParked,
            naviCheck = run {
                val r = car.route ?: return@run null
                val ev = car.ev ?: return@run null
                val soc = ev.soc ?: return@run null
                val cap = ev.capacityKwh ?: return@run null
                val navi = r.naviArrivalPercent ?: return@run null
                NaviComparison.check(model, r.remainKm, r.remainMinutes, month(now), soc, navi, cap)
            },
            route = car.route?.let { r ->
                RoutePlanner.preview(model, drained.soc, r.remainKm, r.remainMinutes, month(now), plan, climateShare = car.tripClimateShare.takeIf { car.driving } ?: 1.0)
            },
            tripKwh = tripKwh,
            recentDays = report?.sim?.days?.takeLast(7)?.reversed().orEmpty(),
            tripCount = trips.size,
            fuelKmPerL = fuel,
            fuelSource = fuelSource,
            myCar = myCar,
            modelRows = modelRows,
            carFacts = carFacts(car, trips),
            liveSignals = liveSignals(car, trips),
            voiceEngine = narrator.engine,
            realLabel = l.realLabel.takeIf { l.stage == Stage.REAL },
            realDriveLabels = realDrives.map { it.label },
            realSource = l.realSource.takeIf { l.stage == Stage.REAL },
            winter = if (report != null && report.days >= Measured.MIN_COMPARE_DAYS) {
                Measured.report(model, Moments.winterOf(trips), plan, fallbackFuelKmPerL = fuel, nowMs = now, homeChargingAnswered = answered)
            } else null,
            score = Moments.predictionScore(model, trips),
            levers = report?.takeIf { it.days >= Measured.MIN_COMPARE_DAYS && answered }?.let {
                Levers.of(model, trips, plan, it, fallbackFuelKmPerL = fuel, nowMs = now)
            },
            tripRegenKwh = if (l.mode == Mode.DEMO) l.demoRegenKwh else car.tripRegenKwh,
            lastLine = lastLine?.takeIf { (_, at) -> System.currentTimeMillis() - at < LAST_LINE_MS }?.first,
        )
    }

    /** Records that were already in the car and the navigation before the app was installed. */
    private fun carFacts(car: CarState, trips: List<Trip>): List<CarFact> = listOf(
        CarFact("누적 주행거리", car.odometerKm?.takeIf { it > 0 }?.let { "%,d km".format(it.toInt()) }, "Odometer"),
        CarFact("연식", car.modelYear?.let { "${it}년식" }, "CarInfo.getVINInfo"),
        CarFact("내 차 평균 연비", car.averageFuelKmPerL?.let { "%.1f km/L".format(it) }, "Fuel"),
        CarFact(
            "집 · 회사",
            when {
                car.home != null && car.office != null && (car.commute?.km ?: 0.0) < Preliminary.MIN_COMMUTE_KM -> "같은 곳으로 저장됨"
                else -> listOfNotNull(car.home?.let { "집" }, car.office?.let { "회사" }).joinToString(" · ").ifEmpty { null }
            },
            "NaviHelper 즐겨찾기",
        ),
        CarFact("최근 목적지", car.destinations.size.takeIf { it > 0 }?.let { "${it}곳" }, "NaviHelper 최근 목적지"),
    )

    /** Signals read live from now on: what makes the record automatic. */
    private fun liveSignals(car: CarState, trips: List<Trip>): List<CarFact> = listOf(
        CarFact("주행 시작·끝", if (trips.isNotEmpty()) "${trips.size}번 자동 기록" else if (car.parked != null) "기어 신호 받는 중" else null, "Gear P→D→P"),
        CarFact("공조", car.climateOn?.let { if (it) "켜짐" else "꺼짐" }, "HVAC"),
        CarFact("연료", car.fuelPercent?.let { "$it%" }, "Fuel"),
    )

    private fun month(ms: Long) = Instant.ofEpochMilli(ms).atZone(Trip.KST).monthValue

    private companion object {
        const val TICK_MS = 1_000L
        const val LAST_LINE_MS = 3 * 60_000L
    }
}
