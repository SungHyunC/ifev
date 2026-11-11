package com.ifev.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BatterySimTest {
    private val m = EvCatalog.IONIQ6_LONG

    private fun at(day: Int, hour: Double): Long =
        LocalDateTime.of(2026, 11, 2, 0, 0).plusDays(day.toLong()).plusMinutes((hour * 60).toLong())
            .atZone(Trip.KST).toInstant().toEpochMilli()

    private fun trip(day: Int, hour: Double, km: Double, speed: Double = 40.0, to: Place, from: Place = Place.OTHER) =
        Trip(at(day, hour), at(day, hour + km / speed), km, from, to, tempC = 20.0, climateShare = 0.0, fuelKmPerL = 12.0)

    @Test
    fun parkingOvernightAtHomeRefillsOnlyBelowHalf() {
        // 20 km a day barely dents 84 kWh: the first nights stay above 50 % and need no charge.
        val trips = (0 until 3).map { trip(it, 18.0, 20.0, to = Place.HOME) }
        val r = BatterySim.run(m, trips, ChargingPlan(homeCharging = true), nowMs = at(3, 8.0))
        assertEquals(0, r.homeCharges)

        // 300 km in a day ends below half; parked at home overnight, it charges back to 90 %.
        val long = listOf(trip(0, 8.0, 300.0, speed = 70.0, to = Place.HOME), trip(1, 8.0, 10.0, to = Place.OTHER))
        val r2 = BatterySim.run(m, long, ChargingPlan(homeCharging = true))
        assertEquals(1, r2.homeCharges)
        assertTrue(r2.days[1].minSoc > 0.85)
    }

    @Test
    fun aTripBeyondRangeStopsForFastChargesOnTheWay() {
        val r = BatterySim.run(m, listOf(trip(0, 8.0, 900.0, speed = 78.0, to = Place.OTHER)), ChargingPlan(homeCharging = true))
        // 900 km at 517 km per full battery: 0.8 of a battery first, then 0.7 per stop.
        val need = 900.0 / 517.0
        val expected = kotlin.math.ceil((need - 0.8) / 0.7).toInt()
        assertEquals(expected, r.enRouteFastCharges)
        assertEquals(expected, r.fastCharges)
        assertEquals(0.1, r.minSoc, 1e-9)
        assertTrue(r.endSoc in 0.1..0.8)
    }

    @Test
    fun withoutHomeChargingTheDriverVisitsAFastChargerWhenLow() {
        val trips = (0 until 10).map { trip(it, 8.0, 60.0, to = Place.OTHER) }
        val r = BatterySim.run(m, trips, ChargingPlan(homeCharging = false))
        assertEquals(0, r.homeCharges)
        assertTrue(r.fastCharges >= 1)
        assertEquals(0, r.enRouteFastCharges)
        assertTrue(r.minSoc >= 0.1)
    }

    @Test
    fun costUsesTheMixOfHomeAndFastEnergy() {
        val trips = listOf(trip(0, 8.0, 100.0, to = Place.OTHER))
        val prices = Prices()
        val home = BatterySim.run(m, trips, ChargingPlan(homeCharging = true), prices)
        val kwh = home.drivenKwh
        // No charge happened, so a home charger owner pays the home price for what was driven.
        assertEquals(kwh * prices.homePerKwh, home.evCostWon, 1e-6)
        assertEquals(100.0 / 12.0 * prices.gasolinePerLiter, home.fuelCostWon, 1e-6)
        assertEquals(home.fuelCostWon - home.evCostWon, home.savingWon, 1e-6)
        val noHome = BatterySim.run(m, trips, ChargingPlan(homeCharging = false), prices)
        assertEquals(kwh * prices.fastPerKwh, noHome.evCostWon, 1e-6)
    }

    @Test
    fun lowDaysCountDaysUnderTwentyPercent() {
        val trips = listOf(trip(0, 8.0, 420.0, speed = 60.0, to = Place.OTHER), trip(1, 8.0, 5.0, to = Place.OTHER))
        val r = BatterySim.run(m, trips, ChargingPlan(homeCharging = true))
        assertEquals(2, r.days.size)
        assertEquals(r.days.count { it.minSoc < 0.2 }, r.lowSocDays)
        assertTrue(r.lowSocDays >= 1)
    }

    @Test
    fun missingFuelEconomyIsReportedNotGuessed() {
        val t = trip(0, 8.0, 50.0, to = Place.HOME).copy(fuelKmPerL = null)
        val r = BatterySim.run(m, listOf(t), ChargingPlan(homeCharging = true))
        assertEquals(50.0, r.fuelUnknownKm, 1e-9)
        assertEquals(0.0, r.fuelCostWon, 1e-9)
        val withFallback = BatterySim.run(m, listOf(t), ChargingPlan(homeCharging = true), fallbackFuelKmPerL = 10.0)
        assertEquals(0.0, withFallback.fuelUnknownKm, 1e-9)
    }
}
