package com.ifev.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRecorderTest {
    private val home = 37.5560 to 126.9100
    private val office = 37.4980 to 127.0280

    private fun recorder() = TripRecorder(
        placeOf = { lat, lon ->
            when {
                TripRecorder.haversineKm(lat, lon, home.first, home.second) < 0.3 -> Place.HOME
                TripRecorder.haversineKm(lat, lon, office.first, office.second) < 0.3 -> Place.OFFICE
                else -> Place.OTHER
            }
        },
        tempAt = { 7.5 },
    )

    @Test
    fun odometerDeltaMakesTheTripFromPToP() {
        val r = recorder()
        r.onPosition(home.first, home.second)
        r.onOdometer(18_400.0)
        assertNull(r.onGear(isPark = false, at = 0))
        r.onOdometer(18_417.3)
        r.onTripFuelEconomy(12.4)
        r.onPosition(office.first, office.second)
        val t = r.onGear(isPark = true, at = 40 * 60_000L)
        assertNotNull(t)
        assertEquals(17.3, t!!.distanceKm, 1e-6)
        assertEquals(Place.HOME, t.startPlace)
        assertEquals(Place.OFFICE, t.endPlace)
        assertEquals(12.4, t.fuelKmPerL!!, 1e-9)
        assertEquals(7.5, t.tempC, 1e-9)
    }

    @Test
    fun withoutOdometerThePositionTrackGivesTheDistance() {
        val r = recorder()
        val track = listOf(home, 37.545 to 126.940, 37.530 to 126.970, 37.515 to 127.000, office)
        r.onPosition(home.first, home.second)
        r.onGear(isPark = false, at = 0)
        track.drop(1).forEach { (lat, lon) -> r.onPosition(lat, lon) }
        val expected = track.zipWithNext().sumOf { (a, b) -> TripRecorder.haversineKm(a.first, a.second, b.first, b.second) }
        assertEquals(expected, r.currentKm, 1e-9)
        val t = r.onGear(isPark = true, at = 30 * 60_000L)!!
        assertEquals(expected, t.distanceKm, 1e-9)
        assertEquals(Place.OFFICE, t.endPlace)
    }

    @Test
    fun aPositionJumpIsNotCountedAsDriving() {
        val r = recorder()
        r.onPosition(home.first, home.second)
        r.onGear(isPark = false, at = 0)
        r.onPosition(office.first, office.second)
        assertEquals(0.0, r.currentKm, 1e-9)
    }

    @Test
    fun shufflingInAndOutOfParkIsNotATrip() {
        val r = recorder()
        r.onOdometer(100.0)
        r.onGear(isPark = false, at = 0)
        r.onOdometer(100.1)
        assertNull(r.onGear(isPark = true, at = 60_000))
        assertTrue(!r.driving)
    }

    @Test
    fun climateShareIsTimeWeighted() {
        val r = recorder()
        r.onOdometer(0.5)
        r.onGear(isPark = false, at = 0)
        r.onClimate(true, at = 15 * 60_000L)
        assertEquals(0.5, r.currentClimateShare(30 * 60_000L), 1e-9)
        r.onOdometer(20.0)
        val t = r.onGear(isPark = true, at = 60 * 60_000L)!!
        assertEquals(0.75, t.climateShare, 1e-9)
    }

    @Test
    fun withAStillOdometerTheIntegratedSpeedGivesTheDistance() {
        val r = recorder()
        r.onOdometer(500.0)
        r.onSpeed(0.0, at = 0)
        r.onGear(isPark = false, at = 0)
        // 20 m/s (72 km/h) sampled every second for 60 s: 1.2 km. The odometer never moves.
        for (s in 1..60) r.onSpeed(20.0, at = s * 1_000L)
        assertEquals(1.18, r.currentKm, 0.03)
        val t = r.onGear(isPark = true, at = 61_000)!!
        assertEquals(1.2, t.distanceKm, 0.03)
    }

    @Test
    fun aSilenceInSpeedSamplesIsNotDrivenThrough() {
        val r = recorder()
        r.onGear(isPark = false, at = 0)
        r.onSpeed(20.0, at = 0)
        r.onSpeed(20.0, at = 600_000) // ten minutes without data
        assertTrue(r.currentKm < 0.21)
    }
}
