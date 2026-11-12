package com.ifev.app.vehicle

import ai.pleos.playground.vehicle.constant.control.VehicleGear
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import com.ifev.app.data.GearState
import org.junit.Assert.*
import org.junit.Test

class VisualSignalsTest {
    @Test fun aaosGearsIncludeNumberedDriveGears() {
        assertEquals(GearState.PARK, gearStateFromAaos(4))
        assertEquals(GearState.REVERSE, gearStateFromAaos(2))
        assertEquals(GearState.NEUTRAL, gearStateFromAaos(1))
        for (raw in listOf(8, 16, 32, 64, 128, 256, 512, 1024, 2048, 4096)) {
            assertEquals("AAOS $raw", GearState.DRIVE, gearStateFromAaos(raw))
        }
        for (raw in listOf(0, -1, 3, 8192)) assertEquals(GearState.UNKNOWN, gearStateFromAaos(raw))
    }

    @Test fun pleosGearsMapEverySdkValue() {
        assertEquals(GearState.UNKNOWN, gearStateFromPleos(null))
        VehicleGear.entries.forEach { gear ->
            val expected = when (gear) {
                VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
                VehicleGear.GEAR_PARK -> GearState.PARK
                VehicleGear.GEAR_REVERSE -> GearState.REVERSE
                VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
                else -> GearState.DRIVE
            }
            assertEquals(gear.name, expected, gearStateFromPleos(gear))
        }
    }

    @Test fun fullGearFallbackCanDetectReverseEvenThoughBothAreNonPark() {
        val arbiter = VisualGearArbiter(1_000)
        assertTrue(arbiter.acceptPleos(GearState.REVERSE, GearState.UNKNOWN, 0))
        arbiter.onAaos(100)
        assertFalse(arbiter.acceptPleos(GearState.REVERSE, GearState.DRIVE, 1_100))
        assertTrue(arbiter.acceptPleos(GearState.REVERSE, GearState.DRIVE, 1_101))
        assertFalse(arbiter.acceptPleos(GearState.DRIVE, GearState.DRIVE, 1_101))
        arbiter.onAaos(2_000)
        assertFalse(arbiter.acceptPleos(GearState.REVERSE, GearState.DRIVE, 2_001))
    }

    @Test fun missingUxServiceRestrictsEveryGearExceptConfirmedPark() {
        for (gear in GearState.entries) assertEquals(gear != GearState.PARK, visualRestricted(null, gear))
    }

    @Test fun reportedUxStateTakesPrecedenceOverGear() {
        for (gear in GearState.entries) {
            assertTrue(visualRestricted(true, gear))
            assertFalse(visualRestricted(false, gear))
        }
    }

    @Test fun uxRequiresOptimizationOrNoVideo() {
        assertFalse(requiresVisualRestriction(false, 0))
        assertTrue(requiresVisualRestriction(true, 0))
        assertTrue(requiresVisualRestriction(false, 0x10))
        assertTrue(requiresVisualRestriction(false, 0x50))
        assertFalse(requiresVisualRestriction(false, 0x40))
    }

    @Test fun speedKeepsReverseAndReceiptTime() {
        val sample = speedSample(VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyValue.STATUS_AVAILABLE, -3.5f, 120)
        assertEquals(SpeedSample(-3.5, 120), sample)
        assertEquals(SpeedSample(0.0, 121), speedSample(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0, 0f, 121))
    }

    @Test fun unavailableOrUnrelatedSpeedPayloadsAreDiscarded() {
        for (status in listOf(CarPropertyValue.STATUS_UNAVAILABLE, CarPropertyValue.STATUS_ERROR, 99)) {
            assertNull(speedSample(VehiclePropertyIds.PERF_VEHICLE_SPEED, status, 12f, 10))
        }
        assertNull(speedSample(VehiclePropertyIds.GEAR_SELECTION, 0, 12f, 10))
        for (raw in listOf(null, 3, 3.0, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull(speedSample(VehiclePropertyIds.PERF_VEHICLE_SPEED, 0, raw, 10))
        }
    }

    @Test fun speedRateStaysWithinConfigBounds() {
        assertEquals(10f, speedSampleRate(1f, 100f))
        assertEquals(5f, speedSampleRate(1f, 5f))
        assertEquals(20f, speedSampleRate(20f, 100f))
        assertEquals(10f, speedSampleRate(0f, 10f))
        assertNull(speedSampleRate(null, null))
        assertNull(speedSampleRate(0f, 0f))
        assertNull(speedSampleRate(20f, 10f))
        assertNull(speedSampleRate(Float.NaN, 10f))
        assertNull(speedSampleRate(1f, Float.POSITIVE_INFINITY))
        assertNull(speedSampleRate(-1f, 10f))
    }
}
