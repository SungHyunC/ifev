package com.ifev.app.data

import org.junit.Assert.*
import org.junit.Test

class DriveTraceTest {
    private val drive = VehicleVisualState(
        speedMs = -2.0, steeringDeg = -15.0, brake = true, turn = TurnState.LEFT,
        gear = GearState.REVERSE, climateOn = true,
    )
    private val park = VehicleVisualState(gear = GearState.PARK)

    @Test fun onlyParkFinalizesAndKeepsTheDrawingInputs() {
        val trace = DriveTrace()
        assertFalse(trace.record(drive, 1_000))
        assertTrue(trace.lastDrive().isEmpty())
        assertTrue(trace.record(park, 1_001)) // P must not be lost to the 100 ms downsample.
        assertEquals(listOf(TracePoint(1_000, -2.0, -15.0, true, TurnState.LEFT, GearState.REVERSE, true)), trace.lastDrive())
        assertFalse(trace.record(park, 1_002))
        assertEquals(1, trace.lastDrive().size)
    }

    @Test fun downsampleAndRingBoundBothDurationAndCount() {
        val trace = DriveTrace(maxDurationMs = 300, sampleIntervalMs = 100)
        for (time in 0L..500L) trace.record(drive.copy(speedMs = time.toDouble()), time)
        trace.record(park, 501)
        assertEquals(listOf(300L, 400L, 500L), trace.lastDrive().map { it.tMs })
        assertEquals(listOf(300.0, 400.0, 500.0), trace.lastDrive().map { it.speedMs })
    }

    @Test fun longSignalGapEvictsOldPoints() {
        val trace = DriveTrace(maxDurationMs = 300, sampleIntervalMs = 100)
        trace.record(drive, 0)
        trace.record(drive, 100)
        trace.record(drive, 10_000)
        trace.record(park, 10_001)
        assertEquals(listOf(10_000L), trace.lastDrive().map { it.tMs })
    }

    @Test fun allDrivingGearsAreRecordedWhileUnknownDoesNotEndTheDrive() {
        val trace = DriveTrace()
        trace.record(park, 0)
        trace.record(VehicleVisualState(), 100)
        assertTrue(trace.lastDrive().isEmpty())
        val gears = listOf(GearState.DRIVE, GearState.REVERSE, GearState.NEUTRAL)
        gears.forEachIndexed { i, gear -> trace.record(drive.copy(gear = gear), 200L + i * 100L) }
        assertFalse(trace.record(VehicleVisualState(), 500))
        assertTrue(trace.record(park, 600))
        assertEquals(gears, trace.lastDrive().map { it.gear })
    }

    @Test fun nextDriveReplacesOnlyWhenParkedAndDoesNotMutateAnEarlierSnapshot() {
        val trace = DriveTrace()
        trace.record(drive, 0)
        trace.record(park, 10)
        val previous = trace.lastDrive()
        trace.record(drive.copy(gear = GearState.NEUTRAL), 20)
        assertEquals(previous, trace.lastDrive())
        trace.record(park, 30)
        assertEquals(listOf(20L), trace.lastDrive().map { it.tMs })
        assertEquals(listOf(0L), previous.map { it.tMs })
    }

    @Test fun backwardTimestampsCannotFinalizeOrPolluteTheDrive() {
        val trace = DriveTrace()
        trace.record(drive, 1_000)
        assertFalse(trace.record(park, 900))
        trace.record(drive, 800)
        trace.record(drive, 1_100)
        assertTrue(trace.record(park, 1_200))
        assertEquals(listOf(1_000L, 1_100L), trace.lastDrive().map { it.tMs })
    }

    @Test fun unknownMeasurementsRemainUnknown() {
        val trace = DriveTrace()
        trace.record(VehicleVisualState(gear = GearState.DRIVE), 0)
        trace.record(park, 1)
        with(trace.lastDrive().single()) {
            assertNull(speedMs)
            assertNull(steeringDeg)
            assertNull(brake)
            assertNull(climateOn)
        }
    }
}
