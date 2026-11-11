package com.ifev.app.engine

import com.ifev.app.scenario.DemoScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

/** Regressions for the problems found in the 2026-11-11 review. */
class ReviewFixesTest {
    private val plan = ChargingPlan(homeCharging = true)

    private fun at(day: Int, hour: Double): Long =
        LocalDateTime.of(2026, 11, 2, 0, 0).plusDays(day.toLong()).plusMinutes((hour * 60).toLong())
            .atZone(Trip.KST).toInstant().toEpochMilli()

    // ── fuel economy units ──

    @Test
    fun sdkFuelEconomyIsLitersPer100KmAndBecomesKmPerLiter() {
        assertEquals(13.6, kmPerLiterFromLitersPer100Km(100.0 / 13.6)!!, 1e-9)
        assertEquals(20.0, kmPerLiterFromLitersPer100Km(5.0)!!, 1e-9)
        assertNull(kmPerLiterFromLitersPer100Km(0.0))
        assertNull(kmPerLiterFromLitersPer100Km(Double.NaN))
        assertNull(kmPerLiterFromLitersPer100Km(250.0))
    }

    // ── model year to months on the road ──

    @Test
    fun nextYearsModelIsNotOneMonthOld() {
        val today = LocalDate.of(2026, 11, 11)
        // A 2027 model on sale since mid-2026, not "1 month" (which made yearly distance ×12).
        assertEquals(4, monthsOnRoad(2027, today))
        assertEquals(16, monthsOnRoad(2026, today))
        assertEquals(1, monthsOnRoad(2028, today))
    }

    // ── heater off ──

    @Test
    fun switchingTheHeaterOffNeverCostsRange() {
        for (m in EvCatalog.all) for (t in listOf(-7.0, -2.0, 0.0, 7.5)) for (v in listOf(25.0, 45.0, 78.0, 110.0)) {
            val on = EnergyModel.rangeKm(m, t, v, climateOn = true)
            val off = EnergyModel.rangeKm(m, t, v, climateOn = false)
            assertTrue("${m.id} $t℃ $v km/h: off $off < on $on", off >= on - 1e-9)
        }
    }

    // ── savings with partly missing fuel economy ──

    @Test
    fun aTripWithoutFuelEconomyIsNotCountedAsFreeFuel() {
        val m = EvCatalog.IONIQ6_LONG
        val known = Trip(at(0, 8.0), at(0, 9.0), 100.0, Place.HOME, Place.OTHER, 15.0, 1.0, 10.0)
        val unknown = known.copy(startAt = at(1, 8.0), endAt = at(1, 9.0), fuelKmPerL = null)
        val both = BatterySim.run(m, listOf(known, unknown), plan, nowMs = at(2, 9.0))
        val single = BatterySim.run(m, listOf(known), plan, nowMs = at(1, 7.0))
        // Two equal trips save about twice what one saves, not one trip's fuel minus two trips' power.
        assertEquals(single.savingWon * 2, both.savingWon, single.savingWon * 0.05)
    }

    // ── confidence needs driving days ──

    @Test
    fun oneTripAndFourQuietWeeksIsNotHighConfidence() {
        val trip = Trip(at(0, 8.0), at(0, 8.5), 18.0, Place.HOME, Place.OFFICE, 7.5, 1.0, 12.0)
        val r = Measured.report(EvCatalog.IONIQ6_LONG, listOf(trip), plan, nowMs = at(27, 20.0))!!
        assertEquals(28, r.days)
        assertEquals(1, r.drivingDays)
        assertEquals(Confidence.LOW, r.confidence)
    }

    @Test
    fun noVerdictUntilTheDriverSaysWhetherThereIsAHomeCharger() {
        val r = Measured.report(EvCatalog.IONIQ6_LONG, DemoScenario.fourWeeks(), plan, nowMs = DemoScenario.fourWeeksNow, homeChargingAnswered = false)!!
        assertEquals(Verdict.UNKNOWN, r.verdict)
    }

    // ── fast charges: routine vs on the way, and the free starting battery ──

    @Test
    fun theComparisonTableAndTheVerdictCountTheSameFastCharges() {
        val noHome = ChargingPlan(homeCharging = false)
        val r = Measured.report(EvCatalog.IONIQ6_LONG, DemoScenario.fourWeeks(), noHome, nowMs = DemoScenario.fourWeeksNow)!!
        assertEquals(r.monthly.fastCharges, r.monthly.fastRoutine + r.monthly.fastEnRoute, 1e-9)
        val rows = Measured.compare(null, r.monthly)
        assertTrue(rows.any { it.label == "평소 급속 충전" })
        assertTrue(rows.any { it.label == "장거리 가는 길 급속" })
        val expected = if (r.monthly.fastRoutine <= Preliminary.MAX_FAST_PER_MONTH_FOR_EV) Verdict.CONDITIONAL else Verdict.HYBRID_BETTER
        assertEquals(expected, r.verdict)
    }

    @Test
    fun aBatteryThatEndsLowerThanItStartedAddsTheChargesToRefillIt() {
        val r = Measured.report(EvCatalog.IONIQ6_LONG, DemoScenario.fourWeeks(), plan, nowMs = DemoScenario.fourWeeksNow)!!
        val raw = r.sim.homeCharges * 30.0 / r.days
        if (r.sim.endSoc < plan.startSoc) assertTrue(r.monthly.homeCharges!! > raw)
        assertTrue(r.steadyStateCharges >= 0)
    }

    @Test
    fun everydayFastChargingIsCountedByEnergyNotByWhenTheReplayCharged() {
        // Same everyday driving, with or without a long trip in it: the everyday figure stays the same.
        val noHome = ChargingPlan(homeCharging = false)
        val m = EvCatalog.IONIQ6_STANDARD
        val weeks = DemoScenario.fourWeeks()
        val everydayOnly = weeks.filter { it.distanceKm < Measured.LONG_TRIP_KM }
        val a = Measured.report(m, weeks, noHome, nowMs = DemoScenario.fourWeeksNow)!!
        val b = Measured.report(m, everydayOnly, noHome, nowMs = DemoScenario.fourWeeksNow)!!
        assertEquals(b.monthly.fastRoutine, a.monthly.fastRoutine, 1e-9)
        assertTrue(a.monthly.fastEnRoute >= b.monthly.fastEnRoute)
    }

    // ── trip recording ──

    private fun recorder() = TripRecorder(placeOf = { _, _ -> Place.OTHER }, tempAt = { 7.5 })

    @Test
    fun liveAndSavedDistanceAgree() {
        val r = recorder()
        r.onOdometer(1000.0)
        r.onGear(isPark = false, at = 0)
        r.onSpeed(20.0, 0)
        r.onSpeed(20.0, 10_000)         // 0.2 km by speed
        r.onOdometer(1001.0)             // 1 km by odometer, coarse
        r.onSpeed(20.0, 20_000)
        val live = r.currentKm
        val trip = r.onGear(isPark = true, at = 20_000)!!
        assertEquals(live, trip.distanceKm, 1e-9)
    }

    @Test
    fun aZeroOdometerIsAValidStart() {
        val r = recorder()
        r.onOdometer(0.0)
        r.onGear(isPark = false, at = 0)
        r.onOdometer(1.0)
        val trip = r.onGear(isPark = true, at = 120_000)
        assertNotNull(trip)
        assertEquals(1.0, trip!!.distanceKm, 1e-9)
        assertEquals(DistanceSource.ODOMETER, trip.distanceSource)
    }

    @Test
    fun aTripSurvivesTheProcessBeingKilled() {
        val before = recorder()
        before.onGear(isPark = false, at = 0)
        before.onSpeed(25.0, 0)
        before.onSpeed(25.0, 10_000)
        before.onSpeed(25.0, 20_000)
        val snap = before.snapshot(20_000)!!
        // New process: restored, then the car parks.
        val after = recorder()
        after.restore(snap, now = 60_000)
        after.onSpeed(25.0, 60_000)
        after.onSpeed(25.0, 70_000)
        val trip = after.onGear(isPark = true, at = 70_000)!!
        assertEquals(0L, trip.startAt)
        assertTrue(trip.distanceKm >= 0.5 + 0.25 - 1e-9)
    }

    // ── route preview ──

    @Test
    fun aShortRouteNeedsNoChargeAndALongOneInWinterMayNeedOne() {
        val m = EvCatalog.CASPER
        val short = RoutePlanner.preview(m, 0.9, 16.8, 45.0, 11, plan)
        assertEquals(0, short.stops)
        assertTrue(short.arrivalSoc < 0.9)
        val long = RoutePlanner.preview(m, 0.9, 236.0, 190.0, 11, plan)
        assertTrue(long.stopsWinter >= long.stops)
        assertTrue(long.arrivalSoc >= plan.reserveSoc - 1e-9)
    }

    @Test
    fun drainingMatchesTheReplay() {
        val m = EvCatalog.IONIQ6_LONG
        val trip = Trip(at(0, 9.0), at(0, 12.0), 400.0, Place.HOME, Place.OTHER, -2.0, 1.0, 15.0)
        val sim = BatterySim.run(m, listOf(trip), plan, nowMs = trip.endAt)
        val kwh = trip.distanceKm * EnergyModel.kwhPerKm(m, trip.tempC, trip.avgSpeedKmh, trip.climateShare)
        val d = RoutePlanner.drain(plan.startSoc, kwh, m.batteryKwh, plan)
        assertEquals(sim.enRouteFastCharges, d.stops)
        assertEquals(sim.endSoc, d.soc, 1e-9)
    }

    @Test
    fun spokenLinesCarryTheNumbers() {
        val m = EvCatalog.IONIQ6_LONG
        val p = RoutePlanner.preview(m, 0.9, 236.0, 190.0, 1, plan)
        val line = Lines.route(m, "강릉 경포해변", p)
        assertTrue(line.startsWith("강릉 경포해변까지 236킬로미터."))
        assertTrue("퍼센트" in line)
    }

    @Test
    fun theEndingFollowsTheLastSyllable() {
        assertEquals("아이오닉 6 롱레인지였다면", EvCatalog.IONIQ6_LONG.ifItWere)
        assertEquals("아이오닉 9 항속형이었다면", EvCatalog.IONIQ9.ifItWere)
        assertEquals("캐스퍼 일렉트릭 인스퍼레이션이었다면", EvCatalog.CASPER.ifItWere)
    }

    @Test
    fun theNavigationCheckPutsBothEstimatesOnTheSameBattery() {
        // The emulator case: 194 km, 2 h 10 min, 100 % on a 77.4 kWh car, navigation says 63 %.
        val n = NaviComparison.check(EvCatalog.IONIQ6_LONG, 194.0, 130.0, 11, 100.0, 63.0, 77.4)!!
        assertEquals(194.0 / (0.37 * 77.4), n.naviKmPerKwh!!, 1e-9)
        val kwh = 194.0 * EnergyModel.kwhPerKm(EvCatalog.IONIQ6_LONG, SeoulClimate.meanC(11), 194.0 / (130.0 / 60), 1.0)
        assertEquals(100.0 - kwh / 77.4 * 100, n.ifevArrivalPct, 1e-9)
        assertTrue(n.ifevArrivalPctNoClimate >= n.ifevArrivalPct)
        assertNull(NaviComparison.check(EvCatalog.IONIQ6_LONG, 0.0, null, 11, 100.0, 63.0, 77.4))
    }
}
