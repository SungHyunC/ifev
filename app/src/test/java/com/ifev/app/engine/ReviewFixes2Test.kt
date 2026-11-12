package com.ifev.app.engine

import com.ifev.app.scenario.DemoScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Regressions for the second (2026-11-12) review. */
class ReviewFixes2Test {
    private val home = ChargingPlan(homeCharging = true)
    private val noHome = ChargingPlan(homeCharging = false)
    private val m = EvCatalog.IONIQ6_LONG

    private fun at(day: Int, hour: Double): Long =
        LocalDateTime.of(2026, 11, 2, 0, 0).plusDays(day.toLong()).plusMinutes((hour * 60).toLong())
            .atZone(Trip.KST).toInstant().toEpochMilli()

    private fun daily(km: Double, days: Int = 28) = (0 until days).flatMap { d ->
        listOf(
            Trip(at(d, 8.0), at(d, 9.2), km / 2, Place.HOME, Place.OFFICE, 7.5, 1.0, 13.0),
            Trip(at(d, 18.5), at(d, 19.7), km / 2, Place.OFFICE, Place.HOME, 7.5, 1.0, 13.0),
        )
    }

    @Test
    fun aLongDailyDriveWithoutAHomeChargerDoesNotLoseItsCharges() {
        // 110 km a day in one go: every trip is "long", and they happen every day.
        val trips = (0 until 28).map { d -> Trip(at(d, 8.0), at(d, 9.5), 110.0, Place.HOME, Place.OTHER, 7.5, 1.0, 13.0) }
        val r = Measured.report(m, trips, noHome, nowMs = at(27, 20.0))!!
        assertTrue(r.longTripsAreEveryday)
        assertTrue("charges ${r.monthly.fastCharges}", r.monthly.fastCharges > 5)
        assertTrue(r.monthly.fastMinutes > 100)
        assertEquals(Verdict.HYBRID_BETTER, r.verdict)
    }

    @Test
    fun noVerdictFlipAtTheHundredKilometreLineForDailyDriving() {
        val below = (0 until 28).map { d -> Trip(at(d, 8.0), at(d, 9.5), 99.9, Place.HOME, Place.OTHER, 7.5, 1.0, 13.0) }
        val above = below.map { it.copy(distanceKm = 100.1) }
        val a = Measured.report(m, below, noHome, nowMs = at(27, 20.0))!!
        val b = Measured.report(m, above, noHome, nowMs = at(27, 20.0))!!
        assertEquals(a.verdict, b.verdict)
        assertEquals(a.monthly.fastCharges, b.monthly.fastCharges, 0.2)
    }

    @Test
    fun theWeekendTripToGangneungIsChargedForWithoutAHomeCharger() {
        val r = Measured.report(m, DemoScenario.fourWeeks(), noHome, nowMs = DemoScenario.fourWeeksNow)!!
        assertTrue("long-trip charges ${r.monthly.fastEnRoute}", r.monthly.fastEnRoute > 0.5)
    }

    @Test
    fun withAHomeChargerTheCarIsFullBeforeALongTrip() {
        val r = Measured.report(m, DemoScenario.fourWeeks(), home, nowMs = DemoScenario.fourWeeksNow)!!
        val noHomeR = Measured.report(m, DemoScenario.fourWeeks(), noHome, nowMs = DemoScenario.fourWeeksNow)!!
        // Charging at home should never leave less margin than charging only at public fast chargers.
        assertTrue("home min ${r.sim.minSoc} vs public ${noHomeR.sim.minSoc}", r.sim.minSoc >= noHomeR.sim.minSoc - 0.05)
    }

    @Test
    fun coldCostsTheCityMoreThanTheHighway() {
        for (model in listOf(EvCatalog.CASPER, EvCatalog.KONA, EvCatalog.IONIQ9)) {
            val cityKeep = model.cold.city / model.warm.city
            val hwyKeep = model.cold.highway / model.warm.highway
            assertTrue("${model.id}: city $cityKeep hwy $hwyKeep", cityKeep < hwyKeep)
            assertTrue(model.cold.highway <= model.warm.highway)
            val combined = 1 / (0.55 / model.cold.city + 0.45 / model.cold.highway)
            assertEquals(model.cold.combined, combined, 0.5)
        }
    }

    private fun recorder() = TripRecorder(placeOf = { _, _ -> Place.OTHER }, tempAt = { 7.5 })

    @Test
    fun gpsNoiseDoesNotStretchATripTheOdometerMeasured() {
        val r = recorder()
        r.onOdometer(1000.0)
        r.onPosition(37.5000, 127.0000)
        r.onGear(isPark = false, at = 0)
        // A zigzagging fix adds ~2.2 km of track for a 1 km drive.
        listOf(37.5050 to 127.0050, 37.4990 to 127.0010, 37.5060 to 127.0060, 37.5000 to 127.0090).forEach { (la, lo) -> r.onPosition(la, lo) }
        r.onOdometer(1001.0)
        val t = r.onGear(isPark = true, at = 120_000)!!
        assertEquals(1.0, t.distanceKm, 1e-9)
        assertEquals(DistanceSource.ODOMETER, t.distanceSource)
    }

    @Test
    fun aRestoredTripKeepsCountingClimateTime() {
        val before = recorder()
        before.onClimate(true, 0)
        before.onGear(isPark = false, at = 0)
        val snap = before.snapshot(600_000)!!
        val after = recorder()
        after.restore(snap, now = 600_000)
        after.onSpeed(20.0, 600_000); after.onSpeed(20.0, 610_000)
        repeat(30) { i -> after.onSpeed(20.0, 610_000L + (i + 1) * 10_000) }
        val t = after.onGear(isPark = true, at = 910_000)!!
        assertTrue("climate share ${t.climateShare}", t.climateShare > 0.95)
    }

    @Test
    fun theFillUpMomentPricesTheKilometresSinceTheLastOne() {
        val since = DemoScenario.fourWeeks().filter { it.startAt >= DemoScenario.fourWeeksNow - DemoScenario.REFUEL_EVERY_MS }
        val c = Moments.sinceRefuel(m, since, home, 13.6)!!
        assertTrue(c.km > 100)
        assertTrue(c.evWon < c.fuelWon / 2)
        val line = Lines.refueled(m, c, home)
        assertTrue(line.startsWith("방금 주유하셨네요."))
        assertEquals("4만 6천 원", Lines.won(46_200.0))
        assertEquals("3천 원", Lines.won(2_800.0))
        assertNull(Moments.sinceRefuel(m, emptyList(), home, 13.6))
    }

    @Test
    fun winterNeedsMoreChargingThanNovember() {
        val trips = daily(34.0)
        val now = Measured.report(m, trips, home, nowMs = at(27, 20.0))!!
        val jan = Measured.report(m, Moments.winterOf(trips), home, nowMs = at(27, 20.0))!!
        assertTrue(jan.sim.drivenKwh > now.sim.drivenKwh)
    }

    @Test
    fun theCarsOwnBatteryGradesThePrediction() {
        val t = daily(34.0, 3)
        assertNull(Moments.predictionScore(m, t))
        val graded = t.map { it.copy(evKwhUsed = it.distanceKm * 0.16) }
        val s = Moments.predictionScore(m, graded)
        assertNotNull(s)
        assertEquals(6, s!!.trips)
        assertEquals(graded.sumOf { it.evKwhUsed!! }, s.actualKwh, 1e-9)
    }
}
