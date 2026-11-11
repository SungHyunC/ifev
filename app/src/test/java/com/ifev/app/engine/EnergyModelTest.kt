package com.ifev.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyModelTest {
    private val m = EvCatalog.IONIQ6_LONG

    @Test
    fun certifiedCornersComeBackExactly() {
        assertEquals(609.0, EnergyModel.rangeKm(m, 20.0, EnergyModel.CITY_SPEED_KMH, climateOn = true), 0.01)
        assertEquals(517.0, EnergyModel.rangeKm(m, 20.0, EnergyModel.HIGHWAY_SPEED_KMH, climateOn = true), 0.01)
        assertEquals(429.0, EnergyModel.rangeKm(m, -7.0, EnergyModel.CITY_SPEED_KMH, climateOn = true), 0.01)
        assertEquals(482.0, EnergyModel.rangeKm(m, -7.0, EnergyModel.HIGHWAY_SPEED_KMH, climateOn = true), 0.01)
    }

    @Test
    fun colderAndFasterNeverGoFartherInTheCity() {
        val mild = EnergyModel.rangeKm(m, 10.0, 40.0, climateOn = true)
        val cold = EnergyModel.rangeKm(m, 0.0, 40.0, climateOn = true)
        assertTrue(cold < mild)
    }

    @Test
    fun heatCostsSeventeenPercentWithAcAndFourWithout() {
        val base = EnergyModel.rangeKm(m, 25.0, EnergyModel.CITY_SPEED_KMH, climateOn = true)
        assertEquals(base * 0.83, EnergyModel.rangeKm(m, 35.0, EnergyModel.CITY_SPEED_KMH, climateOn = true), 0.01)
        assertEquals(base * 0.96, EnergyModel.rangeKm(m, 35.0, EnergyModel.CITY_SPEED_KMH, climateOn = false), 0.01)
    }

    @Test
    fun climateShareBlendsBetweenOnAndOff() {
        val on = EnergyModel.kwhPerKm(m, -7.0, 40.0, 1.0)
        val off = EnergyModel.kwhPerKm(m, -7.0, 40.0, 0.0)
        assertTrue(off < on)
        assertEquals((on + off) / 2, EnergyModel.kwhPerKm(m, -7.0, 40.0, 0.5), 1e-9)
    }
}
