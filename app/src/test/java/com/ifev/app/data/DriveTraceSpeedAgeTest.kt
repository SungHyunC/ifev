package com.ifev.app.data

import org.junit.Assert.*
import org.junit.Test

class DriveTraceSpeedAgeTest {
    private val park = VehicleVisualState(gear = GearState.PARK)

    @Test fun samplingClockDoesNotRefreshAnOldSpeedReceipt() {
        val trace = DriveTrace()
        val drive = VehicleVisualState(gear = GearState.DRIVE, speedMs = 10.0, speedAtMs = 1_000)
        for (at in 1_000L..11_000L step 100) trace.record(drive, at)
        trace.record(park, 11_001)

        val points = trace.lastDrive()
        assertEquals(101, points.size)
        assertTrue(points.all { it.speedMs == 10.0 && it.speedAtMs == 1_000L })
        assertEquals(10_000, points.last().tMs - points.last().speedAtMs!!)
    }

    @Test fun continuousSpeedAndInterruptedSpeedProduceDistinctTraces() {
        val continuous = DriveTrace()
        val interrupted = DriveTrace()
        val drive = VehicleVisualState(gear = GearState.DRIVE, speedMs = 10.0)
        for (at in 1_000L..11_000L step 100) {
            continuous.record(drive.copy(speedAtMs = at), at)
            interrupted.record(drive.copy(speedAtMs = 1_000), at)
        }
        continuous.record(park, 11_001)
        interrupted.record(park, 11_001)

        assertNotEquals(continuous.lastDrive(), interrupted.lastDrive())
        assertTrue(continuous.lastDrive().all { it.speedAtMs == it.tMs })
        assertEquals(continuous.lastDrive().map { it.speedMs }, interrupted.lastDrive().map { it.speedMs })
    }

    @Test fun receiptMayPrecedeTraceWindowAndUnknownReceiptRemainsNull() {
        val trace = DriveTrace(maxDurationMs = 300)
        val drive = VehicleVisualState(gear = GearState.REVERSE, speedMs = -2.0, speedAtMs = 0)
        trace.record(drive, 1_000)
        trace.record(drive, 1_400)
        trace.record(drive.copy(speedAtMs = null), 1_500)
        trace.record(park, 1_501)

        assertEquals(listOf(1_400L, 1_500L), trace.lastDrive().map { it.tMs })
        assertEquals(listOf(0L, null), trace.lastDrive().map { it.speedAtMs })
    }
}
