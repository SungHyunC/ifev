package com.ifev.app.engine

import com.ifev.app.scenario.DemoScenario
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

/** Regressions for the independent review of the office-charger, levers and regen changes. */
class CodexVerifyFixesTest {
    private val model = EvCatalog.IONIQ6_LONG

    private fun at(day: Int, hour: Int) = LocalDateTime.of(2026, 10, 1, hour, 0).plusDays(day.toLong()).atZone(Trip.KST).toInstant().toEpochMilli()

    @Test fun officeChargeAlsoCoversLongTrips() {
        // Two days of 100.1 km to the office, parked there all day: the office charger supplies it all.
        val trips = (0 until 2).flatMap { d ->
            listOf(
                Trip(at(d, 7), at(d, 8), 100.1, Place.HOME, Place.OFFICE, 20.0, 1.0, 12.0),
                Trip(at(d, 18), at(d, 19), 5.0, Place.OFFICE, Place.OTHER, 20.0, 1.0, 12.0),
                Trip(at(d, 20), at(d, 21), 5.0, Place.OTHER, Place.HOME, 20.0, 1.0, 12.0),
            )
        }
        val plan = ChargingPlan(homeCharging = false, workCharging = true)
        val r = Measured.report(model, trips, plan, nowMs = at(1, 23))!!
        println("work=${r.sim.workKwh} driven=${r.sim.drivenKwh} routine=${r.monthly.fastRoutine} long=${r.monthly.fastEnRoute}")
        assertEquals(0, r.sim.fastCharges)
        assertTrue("long-trip fast charges should be covered by the office", r.monthly.fastEnRoute < 0.5)
    }

    @Test fun losingTheHomeChargerKeepsTheOfficeCharger() {
        val trips = DemoScenario.fourWeeks()
        val now = DemoScenario.fourWeeksNow
        val plan = ChargingPlan(homeCharging = true, workCharging = true)
        val r = Measured.report(model, trips, plan, fallbackFuelKmPerL = 13.6, nowMs = now)!!
        val l = Levers.of(model, trips, plan, r, fallbackFuelKmPerL = 13.6, nowMs = now)!!
        val noHome = l.levers.first { it.change.contains("집 충전기") }
        val direct = Measured.report(model, trips, plan.copy(homeCharging = false), fallbackFuelKmPerL = 13.6, nowMs = now)!!
        assertEquals(direct.verdict, noHome.verdict)
        assertTrue(l.levers.any { it.change.contains("회사 충전기를 못 쓰게") })
    }

    @Test fun officeAnswerAloneIsNotAGoodVerdict() {
        // One short trip to OTHER: no office stays in the record, so the office answer cannot carry it.
        val trips = listOf(Trip(at(0, 9), at(0, 10), 1.0, Place.HOME, Place.OTHER, 20.0, 1.0, 12.0))
        val r = Measured.report(model, trips, ChargingPlan(homeCharging = false, workCharging = true), nowMs = at(27, 12))!!
        assertTrue(r.verdict != Verdict.GOOD)
    }

    @Test fun regenIgnoresGapsBetweenSamples() {
        val rec = TripRecorder({ _, _ -> Place.OTHER }, { 20.0 })
        rec.onGear(isPark = false, at = 0)
        rec.onSpeed(20.0, 1_000)
        rec.onSpeed(10.0, 121_000) // two minutes without samples
        assertEquals(0.0, rec.currentRegenKwh, 0.0)
        rec.onSpeed(5.0, 124_000)
        assertTrue(rec.currentRegenKwh > 0)
    }

    @Test fun arrivalSocIsBeforeChargingWhileParked() {
        // Without a home charger the replay charges on parking under 30 %; arrival must be read before that.
        val trips = listOf(
            Trip(at(0, 8), at(0, 12), 380.0, Place.HOME, Place.OTHER, 20.0, 1.0, 12.0),
        )
        val sim = BatterySim.run(model, trips, ChargingPlan(homeCharging = false), nowMs = trips[0].endAt)
        println("arrival=${sim.lastArrivalSoc} end=${sim.endSoc}")
        assertTrue(sim.lastArrivalSoc < sim.endSoc)
    }

    @Test fun everydayChargerFollowsThePlan() {
        assertEquals("회사 충전", ChargingPlan(homeCharging = false, workCharging = true).everydayCharger)
        assertEquals(Prices().homePerKwh, ChargingPlan(homeCharging = false, workCharging = true).everydayPerKwh(), 0.0)
        assertEquals(Prices().fastPerKwh, ChargingPlan(homeCharging = false).everydayPerKwh(), 0.0)
    }
}
