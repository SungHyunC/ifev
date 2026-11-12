package com.ifev.app.engine

import com.ifev.app.scenario.DemoScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The "what would change the verdict" replay, the office charger, virtual regen and the hybrid answer. */
class LeversTest {
    private val trips = DemoScenario.fourWeeks()
    private val now = DemoScenario.fourWeeksNow
    private val model = EvCatalog.IONIQ6_LONG

    private fun report(m: EvModel, plan: ChargingPlan) = Measured.report(m, trips, plan, fallbackFuelKmPerL = 13.6, nowMs = now)!!

    @Test fun parkingComesFromTheRecord() {
        val p = Parking.of(trips)
        println("parking $p")
        assertTrue("weekday office stays", p.officeDays >= 15)
        assertTrue(p.officeMeanHours in 6.0..12.0)
        assertTrue(p.homeDays >= 20)
    }

    @Test fun officeChargerReducesPublicFastCharging() {
        val noHome = ChargingPlan(homeCharging = false)
        val without = report(model, noHome)
        val with = report(model, noHome.copy(workCharging = true))
        println("no home: ${without.verdict} routine=${without.monthly.fastRoutine}; +office: ${with.verdict} routine=${with.monthly.fastRoutine} work=${with.monthly.workCharges}")
        assertTrue(with.monthly.fastRoutine < without.monthly.fastRoutine)
        assertTrue(with.sim.workKwh > 0)
        assertNotNull(with.monthly.workCharges)
        assertNull(without.monthly.workCharges)
        // Every kWh driven is still paid for: office charging only moves it to the slow price.
        assertEquals(without.sim.drivenKwh, with.sim.drivenKwh, 1e-9)
        assertTrue(with.sim.evCostWon <= without.sim.evCostWon)
    }

    @Test fun leversForAGoodVerdictAreWhatWouldBreakIt() {
        val plan = ChargingPlan(homeCharging = true)
        val r = report(model, plan)
        val l = Levers.of(model, trips, plan, r, fallbackFuelKmPerL = 13.6, nowMs = now)!!
        l.levers.forEach { println("break: ${it.change} → ${it.verdict} routine=${it.routineFast}") }
        assertTrue(!l.improving)
        assertTrue(l.levers.first().change.contains("집 충전기"))
    }

    @Test fun leversForAPoorVerdictAreWhatWouldFixIt() {
        val plan = ChargingPlan(homeCharging = false)
        val small = EvCatalog.CASPER
        val r = report(small, plan)
        val l = Levers.of(small, trips, plan, r, fallbackFuelKmPerL = 13.6, nowMs = now)!!
        println("casper no home: ${r.verdict} routine=${r.monthly.fastRoutine}")
        l.levers.forEach { println("fix: ${it.change} → ${it.verdict} routine=${it.routineFast} (${it.because})") }
        assertTrue(l.improving)
        assertTrue(l.levers.isNotEmpty())
        // Sorted best verdict first, then fewest fast charges.
        val ranks = l.levers.map { Levers.rank(it.verdict) }
        assertEquals(ranks.sortedDescending(), ranks)
        assertTrue(l.levers.any { it.change.contains("회사 주차장") })
    }

    @Test fun regenIsABreakdownOfBraking() {
        val stop = Regen.perStopKwh(50.0)
        println("one stop from 50 km/h: $stop kWh")
        assertTrue(stop in 0.02..0.04)
        assertEquals(0.0, Regen.recoveredKwh(10.0, 12.0, 1.0), 0.0)
        assertEquals(0.0, Regen.recoveredKwh(1.5, 0.0, 1.0), 0.0)
        // A commute's worth stays well under the trip's energy.
        val commute = 21 * stop
        val kwh = 16.8 * EnergyModel.kwhPerKm(model, 7.5, 22.0, 1.0)
        assertTrue(commute < kwh * 0.4)
    }

    @Test fun hybridAnswerSavesOnlyWhenItIsMoreEfficient() {
        val s = Hybrid.saving(1_000.0, 11.7)!!
        assertEquals(1_000 / 11.7 * 1855 - 1_000 / 21.2 * 1855, s, 1e-6)
        assertNull(Hybrid.saving(1_000.0, 25.0))
        assertNull(Hybrid.saving(1_000.0, null))
    }

    @Test fun restStopLineSaysWhatWaitingWasExtra() {
        val line = Lines.restStop(model, 24, 30, 0.8)
        assertTrue(line, line.contains("24분") && line.contains("6분"))
        assertTrue(Lines.restStop(model, 40, 30, 0.8).contains("따로 기다린 시간은 없어요"))
    }

    @Test fun officeVerdictNamesTheOfficeCharger() {
        assertEquals("회사 주차장 충전이면 충분해요", Verdict.GOOD.titleFor(ChargingPlan(homeCharging = false, workCharging = true)))
        assertEquals(Verdict.GOOD.title, Verdict.GOOD.titleFor(ChargingPlan(homeCharging = true, workCharging = true)))
    }
}
