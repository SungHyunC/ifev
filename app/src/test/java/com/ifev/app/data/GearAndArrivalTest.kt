package com.ifev.app.data

import com.ifev.app.vehicle.ArrivalUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GearAndArrivalTest {

    @Test
    fun pleosGearIsUsedUntilAaosAnswers() {
        val g = GearArbiter()
        assertTrue(g.acceptPleos(parked = false, current = null, now = 0))
        g.onAaos(now = 1_000)
        assertFalse(g.acceptPleos(parked = false, current = true, now = 2_000))
    }

    @Test
    fun pleosGearIsUsedAgainWhenAaosGoesQuietAndPleosReportsAChange() {
        val g = GearArbiter(aaosStaleMs = 60_000)
        g.onAaos(now = 0)
        // Within the window AAOS still owns the gear.
        assertFalse(g.acceptPleos(parked = false, current = true, now = 60_000))
        // Quiet for longer, and Pleos reports a different gear: AAOS has stopped.
        assertTrue(g.acceptPleos(parked = false, current = true, now = 60_001))
        // The same gear again from Pleos changes nothing and keeps the AAOS label.
        assertFalse(g.acceptPleos(parked = true, current = true, now = 120_000))
        // AAOS back: it owns the gear again.
        g.onAaos(now = 130_000)
        assertFalse(g.acceptPleos(parked = true, current = false, now = 131_000))
    }

    @Test
    fun knownPercentIsNotGuessed() {
        assertEquals(63.0, LiveRecorder.arrivalPercent(63, ArrivalUnit.PERCENT, 77.4)!!, 1e-9)
        assertEquals(0.0, LiveRecorder.arrivalPercent(0, ArrivalUnit.PERCENT, null)!!, 1e-9)
        assertNull(LiveRecorder.arrivalPercent(49_216, ArrivalUnit.PERCENT, 77.4))
    }

    @Test
    fun knownWhUsesCapacityEvenForSmallValues() {
        assertEquals(0.0646, LiveRecorder.arrivalPercent(50, ArrivalUnit.WH, 77.4)!!, 1e-3)
        assertEquals(63.6, LiveRecorder.arrivalPercent(49_216, ArrivalUnit.WH, 77.4)!!, 0.1)
        assertNull(LiveRecorder.arrivalPercent(49_216, ArrivalUnit.WH, null))
        assertNull(LiveRecorder.arrivalPercent(90_000, ArrivalUnit.WH, 77.4))
    }

    @Test
    fun unspecifiedUnitIsGuessedBySize() {
        assertEquals(0.0, LiveRecorder.arrivalPercent(0, ArrivalUnit.UNSPECIFIED, 77.4)!!, 1e-9)
        assertEquals(42.0, LiveRecorder.arrivalPercent(42, ArrivalUnit.UNSPECIFIED, 77.4)!!, 1e-9)
        assertEquals(63.6, LiveRecorder.arrivalPercent(49_216, ArrivalUnit.UNSPECIFIED, 77.4)!!, 0.1)
        assertNull(LiveRecorder.arrivalPercent(49_216, ArrivalUnit.UNSPECIFIED, null))
    }
}
