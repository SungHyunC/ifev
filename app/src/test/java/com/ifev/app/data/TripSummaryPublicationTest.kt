package com.ifev.app.data

import com.ifev.app.engine.Place
import com.ifev.app.engine.TripRecorder
import org.junit.Assert.*
import org.junit.Test

class TripSummaryPublicationTest {
    @Test fun tenHzDistanceUpdatesPublishOncePerSecondBelowDistanceThreshold() {
        val gate = TripSummaryPublicationGate()
        val published = (0L..10_000L step 100).filter { at ->
            gate.shouldPublish(tripKm = at * 0.00001, tripStartAt = 0, elapsedMs = at)
        }
        assertEquals((0L..10_000L step 1_000).toList(), published)
    }

    @Test fun distanceThresholdUsesLastPublicationRatherThanLastSample() {
        val gate = TripSummaryPublicationGate()
        assertTrue(gate.shouldPublish(0.0, 10, 0))
        for (i in 1..4) assertFalse(gate.shouldPublish(i * 0.01, 10, i * 100L))
        assertTrue(gate.shouldPublish(0.05, 10, 500))
        assertFalse(gate.shouldPublish(0.06, 10, 600))
        assertTrue(gate.shouldPublish(0.10, 10, 900))
    }

    @Test fun gearChangePublishesImmediatelyAndRestartsTheInterval() {
        val gate = TripSummaryPublicationGate()
        assertTrue(gate.shouldPublish(0.0, 10, 0))
        assertTrue(gate.shouldPublish(0.001, 10, 100, force = true))
        assertFalse(gate.shouldPublish(0.002, 10, 1_000))
        assertTrue(gate.shouldPublish(0.002, 10, 1_100))
    }

    @Test fun parkingAndNewTripCannotKeepAnOldTripSummary() {
        val gate = TripSummaryPublicationGate()
        assertTrue(gate.shouldPublish(0.01, 10, 0))
        assertTrue(gate.shouldPublish(0.0, null, 100))
        assertTrue(gate.shouldPublish(0.0, 20, 200))
        assertFalse(gate.shouldPublish(0.001, 20, 300))
    }

    @Test fun unchangedDistanceStillPublishesClimateAndRegenSummaryEverySecond() {
        val gate = TripSummaryPublicationGate()
        assertTrue(gate.shouldPublish(1.0, 10, 5_000))
        assertFalse(gate.shouldPublish(1.0, 10, 5_999))
        assertTrue(gate.shouldPublish(1.0, 10, 6_000))
    }

    @Test fun pacingDisplayDoesNotDiscardRawBrakingOrDistanceSamples() {
        fun recorder() = TripRecorder(placeOf = { _, _ -> Place.OTHER }, tempAt = { 20.0 })
        val displayed = recorder()
        val baseline = recorder()
        val gate = TripSummaryPublicationGate()
        displayed.onGear(false, 0)
        baseline.onGear(false, 0)
        var publications = 0
        // Speed varies within every one-second window: sampling the engine at UI cadence loses regen.
        for (at in 0L..60_000L step 100) {
            val speed = 20.0 - (at / 100 % 10)
            displayed.onSpeed(speed, at)
            baseline.onSpeed(speed, at)
            if (gate.shouldPublish(displayed.currentKm, displayed.currentStartAt, at)) publications++
        }
        assertEquals(61, publications)
        assertTrue(displayed.currentRegenKwh > 0)
        assertEquals(baseline.snapshot(60_000), displayed.snapshot(60_000))
        assertEquals(baseline.onGear(true, 60_100), displayed.onGear(true, 60_100))
    }
}
