package com.ifev.app.vehicle

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TripReplyMatcherTest {
    private val office = TripReplyMatcher.Point(37.4980, 127.0280, "poi-office")
    private val mart = TripReplyMatcher.Point(37.5300, 126.9600, "poi-mart")

    @Test
    fun replyAnswersTheWaitingRequest() {
        val m = TripReplyMatcher()
        val id = m.begin(office)
        assertEquals(id, m.match(office, now = 1_000))
        // No destination in the reply is fine while no earlier request is owed one.
        assertEquals(id, m.match(null, now = 1_000))
    }

    @Test
    fun replyWithNoRequestWaitingIsDropped() {
        val m = TripReplyMatcher()
        assertNull(m.match(office, now = 0))
        val id = m.begin(office)
        m.end(id, lateReplyPossible = false, now = 10)
        assertNull(m.match(office, now = 20))
    }

    @Test
    fun lateReplyToTimedOutRequestIsNotTakenAsTheNextAnswer() {
        val m = TripReplyMatcher()
        val a = m.begin(office)
        m.end(a, lateReplyPossible = true, now = 6_000)
        val b = m.begin(mart)
        // A's reply arrives while B waits: its destination is the office, not the mart.
        assertNull(m.match(office, now = 6_500))
        // B's own reply is accepted afterwards.
        assertEquals(b, m.match(mart, now = 7_000))
    }

    @Test
    fun ambiguousReplyAfterTimeoutIsDroppedOnceOnly() {
        val m = TripReplyMatcher()
        val a = m.begin(office)
        m.end(a, lateReplyPossible = true, now = 6_000)
        val b = m.begin(mart)
        assertNull(m.match(null, now = 6_500)) // could be A's: dropped
        assertEquals(b, m.match(null, now = 6_600)) // nothing else owed: B's
    }

    @Test
    fun matchingDestinationIsAcceptedEvenWhileALateReplyIsOwed() {
        val m = TripReplyMatcher()
        val a = m.begin(office)
        m.end(a, lateReplyPossible = true, now = 6_000)
        val b = m.begin(mart)
        // Snapped to the road ~100 m away and no POI id: still the mart.
        assertEquals(b, m.match(TripReplyMatcher.Point(37.5309, 126.9601, null), now = 6_500))
    }

    @Test
    fun lateExpectationExpires() {
        val m = TripReplyMatcher(lateWindowMs = 30_000)
        val a = m.begin(office)
        m.end(a, lateReplyPossible = true, now = 6_000)
        val b = m.begin(mart)
        assertEquals(b, m.match(null, now = 40_000))
    }
}
