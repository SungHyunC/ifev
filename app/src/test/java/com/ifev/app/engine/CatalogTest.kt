package com.ifev.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogTest {

    @Test
    fun derivedColdKeepsTheCertifiedCombinedAndTheWarmSplit() {
        val warm = CertifiedRange(454.0, 371.0, 417.0)
        val cold = EvCatalog.coldFromCombined(warm, 307.0)
        assertEquals(307.0, cold.combined, 1e-9)
        assertEquals(warm.city / warm.highway, cold.city / cold.highway, 1e-9)
        assertTrue(cold.city < warm.city && cold.highway < warm.highway)
    }

    @Test
    fun everyModelIsColderShorterAndCityLongerWhenWarm() {
        for (m in EvCatalog.all) {
            assertTrue(m.id, m.cold.combined < m.warm.combined)
            assertTrue(m.id, m.warm.city > m.warm.highway)
            assertTrue(m.id, m.batteryKwh > 0)
        }
        assertEquals(EvCatalog.all.size, EvCatalog.all.map { it.id }.toSet().size)
    }

    @Test
    fun onlyFullyCertifiedModelsAreUnmarked() {
        assertFalse(EvCatalog.IONIQ6_LONG.estimated)
        assertFalse(EvCatalog.IONIQ6_STANDARD.estimated)
        assertTrue(EvCatalog.IONIQ5_LONG.estimated)
        assertTrue(EvCatalog.IONIQ9.estimated)
    }

    @Test
    fun unknownIdFallsBackToIoniq6Long() {
        assertEquals(EvCatalog.IONIQ6_LONG, EvCatalog.byId(null))
        assertEquals(EvCatalog.KONA, EvCatalog.byId("kona-electric"))
    }

    @Test
    fun theCarsOwnFuelEconomyWinsOverTheCertifiedFigure() {
        val grandeur = FuelCarCatalog.byId("grandeur-2.5")!!
        assertEquals(13.6 to FuelSource.CAR, resolveFuelEconomy(13.6, grandeur))
        assertEquals(11.7 to FuelSource.CERTIFIED, resolveFuelEconomy(null, grandeur))
        assertEquals(11.7 to FuelSource.CERTIFIED, resolveFuelEconomy(0.0, grandeur))
        assertEquals(null to FuelSource.NONE, resolveFuelEconomy(null, null))
    }

    @Test
    fun aCertifiedFallbackStillGivesASavingAndSaysWhereItCameFrom() {
        val input = PrelimInput(17.3, 5, emptyList(), 18_400.0, 14, 11.7, true, 11, fuelIsCertified = true)
        val r = Preliminary.estimate(EvCatalog.IONIQ6_LONG, input, ChargingPlan(homeCharging = true))
        assertTrue(r.annualSavingLow!! > 0)
        assertTrue(r.assumptions.any { "공인 복합연비" in it })
        val noFuel = Preliminary.estimate(EvCatalog.IONIQ6_LONG, input.copy(fuelKmPerL = null), ChargingPlan(homeCharging = true))
        assertNull(noFuel.annualSavingLow)
    }

    @Test
    fun aSmallerBatteryChargesMoreOftenAndStopsMoreOnTheWay() {
        val input = PrelimInput(17.3, 5, listOf(Destination("강릉", 236.0)), 18_400.0, 14, 13.6, true, 11)
        val rows = ModelComparison.rows(input, ChargingPlan(homeCharging = true), emptyList(), nowMs = 0)
        assertEquals(EvCatalog.all.size, rows.size)
        val casper = rows.first { it.model == EvCatalog.CASPER }
        val ioniq6 = rows.first { it.model == EvCatalog.IONIQ6_LONG }
        assertTrue(casper.prelim.homeChargesPerWeek!! > ioniq6.prelim.homeChargesPerWeek!!)
        assertTrue(casper.farthestStopsNow!! > ioniq6.farthestStopsNow!!)
        assertNull(casper.measured)
        assertEquals(casper.prelim.verdict, casper.verdict)
    }
}
