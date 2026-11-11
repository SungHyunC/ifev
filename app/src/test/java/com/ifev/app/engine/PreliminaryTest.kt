package com.ifev.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PreliminaryTest {
    private val m = EvCatalog.IONIQ6_LONG
    private val gangneung = Destination("강릉", 237.0)

    private fun input(
        odometer: Double? = 18_400.0,
        months: Int? = 14,
        commute: Double? = 17.3,
        home: Boolean? = true,
        fuel: Double? = 13.8,
        destinations: List<Destination> = listOf(gangneung, Destination("수원", 35.0)),
    ) = PrelimInput(commute, 5, destinations, odometer, months, fuel, home, month = 11)

    @Test
    fun odometerWinsOverCommuteForTheYearlyDistance() {
        val r = Preliminary.estimate(m, input(), ChargingPlan(homeCharging = true))
        assertEquals(KmSource.ODOMETER, r.kmSource)
        assertEquals(18_400.0 / 14 * 12, r.annualKm!!, 1e-6)
        assertTrue(r.missing.isEmpty())
    }

    @Test
    fun withoutOwnershipMonthsItFallsBackToCommuteAndSaysSo() {
        val r = Preliminary.estimate(m, input(months = null), ChargingPlan(homeCharging = true))
        assertEquals(KmSource.COMMUTE, r.kmSource)
        assertEquals(17.3 * 2 * 5 * 52, r.annualKm!!, 1e-6)
        assertTrue(r.missing.contains("차를 탄 기간"))
        assertTrue(r.assumptions.any { "출퇴근만" in it })
    }

    @Test
    fun aDestinationReportsStopsNowAndInWinter() {
        val plan = ChargingPlan(homeCharging = true)
        val r = Preliminary.estimate(m, input(), plan)
        val g = r.destinations.first()
        assertEquals("강릉", g.destination.name)
        assertEquals(474.0, g.roundTripKm, 1e-9)
        assertEquals(Preliminary.stopsFor(m, 474.0, SeoulClimate.meanC(11), plan), g.stopsNow)
        assertTrue(g.stopsWinter >= g.stopsNow)
        assertEquals(0, r.destinations.last().stopsNow)
    }

    @Test
    fun stopsCountFullBatteriesAfterTheFirst() {
        val plan = ChargingPlan(homeCharging = true)
        assertEquals(0, Preliminary.stopsFor(m, 100.0, 20.0, plan))
        // 517 km highway range: 0.8 of it is about 414 km, then 0.7 (about 362 km) per stop.
        assertEquals(1, Preliminary.stopsFor(m, 600.0, 20.0, plan))
        assertEquals(2, Preliminary.stopsFor(m, 800.0, 20.0, plan))
    }

    @Test
    fun homeChargingDecidesTheVerdict() {
        assertEquals(Verdict.GOOD, Preliminary.estimate(m, input(home = true), ChargingPlan(homeCharging = true)).verdict)
        val noHome = Preliminary.estimate(m, input(home = false), ChargingPlan(homeCharging = false))
        assertNull(noHome.homeChargesPerWeek)
        assertNotNull(noHome.fastChargesPerMonth)
        assertTrue(noHome.verdict == Verdict.CONDITIONAL || noHome.verdict == Verdict.HYBRID_BETTER)
        val unknown = Preliminary.estimate(m, input(home = null), ChargingPlan(homeCharging = true))
        assertEquals(Verdict.UNKNOWN, unknown.verdict)
        assertTrue(unknown.missing.contains("집 충전 가능 여부"))
    }

    @Test
    fun heavyMileageWithoutHomeChargingPointsToAHybrid() {
        val r = Preliminary.estimate(m, input(odometer = 60_000.0, months = 12, home = false), ChargingPlan(homeCharging = false))
        assertEquals(Verdict.HYBRID_BETTER, r.verdict)
    }

    @Test
    fun savingsRangeIsOrderedAndNeedsFuelEconomy() {
        val r = Preliminary.estimate(m, input(), ChargingPlan(homeCharging = true))
        assertTrue(r.annualSavingLow!! <= r.annualSavingHigh!!)
        val noFuel = Preliminary.estimate(m, input(fuel = null), ChargingPlan(homeCharging = true))
        assertNull(noFuel.annualSavingLow)
        assertTrue(noFuel.missing.contains("평균 연비"))
    }
}
