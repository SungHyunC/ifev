package com.ifev.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MeasuredTest {
    private val m = EvCatalog.IONIQ6_LONG
    private val plan = ChargingPlan(homeCharging = true)

    private fun at(day: Int, hour: Double): Long =
        LocalDateTime.of(2026, 11, 2, 0, 0).plusDays(day.toLong()).plusMinutes((hour * 60).toLong())
            .atZone(Trip.KST).toInstant().toEpochMilli()

    private fun commuteDays(days: Int) = (0 until days).flatMap { d ->
        listOf(
            Trip(at(d, 8.0), at(d, 8.6), 17.3, Place.HOME, Place.OFFICE, 7.5, 1.0, 13.0),
            Trip(at(d, 18.5), at(d, 19.2), 17.3, Place.OFFICE, Place.HOME, 7.5, 1.0, 13.0),
        )
    }

    @Test
    fun noTripsNoReport() {
        assertNull(Measured.report(m, emptyList(), plan, nowMs = at(0, 9.0)))
    }

    @Test
    fun confidenceGrowsWithRecordedDays() {
        assertEquals(Confidence.LOW, Measured.report(m, commuteDays(5), plan, nowMs = at(4, 20.0))!!.confidence)
        assertEquals(Confidence.MEDIUM, Measured.report(m, commuteDays(20), plan, nowMs = at(19, 20.0))!!.confidence)
        assertEquals(Confidence.HIGH, Measured.report(m, commuteDays(28), plan, nowMs = at(27, 20.0))!!.confidence)
    }

    @Test
    fun monthlyFiguresScaleToThirtyDays() {
        val r = Measured.report(m, commuteDays(15), plan, nowMs = at(14, 20.0))!!
        assertEquals(15, r.days)
        assertEquals(15 * 34.6 * 2, r.monthly.km, 1e-6)
    }

    @Test
    fun aCommuteOnlyEstimateHearsAboutTheLongTrips() {
        val trips = commuteDays(14) + listOf(
            Trip(at(5, 9.0), at(5, 12.0), 237.0, Place.HOME, Place.OTHER, 7.5, 1.0, 15.0),
            Trip(at(6, 15.0), at(6, 18.0), 237.0, Place.OTHER, Place.HOME, 7.5, 1.0, 15.0),
        )
        val prelim = Preliminary.estimate(
            m,
            PrelimInput(17.3, 5, emptyList(), null, null, 13.0, true, 11),
            plan,
        )
        val report = Measured.report(m, trips, plan, nowMs = at(13, 20.0))!!
        assertEquals(2, report.longTrips)
        val lines = Measured.insights(prelim, Measured.predictedMonthly(prelim), report)
        assertTrue(lines.any { "출퇴근만 반영" in it && "2번" in it })
        assertTrue(lines.any { "더 달렸어요" in it })
    }

    @Test
    fun comparisonRowsLineUpPredictedAndActual() {
        val prelim = Preliminary.estimate(m, PrelimInput(17.3, 5, emptyList(), 18_400.0, 14, 13.8, true, 11), plan)
        val report = Measured.report(m, commuteDays(28), plan, fallbackFuelKmPerL = 13.8, nowMs = at(27, 20.0))!!
        val rows = Measured.compare(Measured.predictedMonthly(prelim), report.monthly)
        assertEquals(6, rows.size)
        // The day-one estimate makes no claim about single low days or long-trip charging.
        assertTrue(rows.all { it.predicted != "-" || it.label == "배터리 20% 미만인 날" || it.label == "장거리 날 급속" })
    }

    @Test
    fun aLowBatteryOnAWeekendTripDoesNotFailAHomeChargingRoutine() {
        val trips = commuteDays(14) + listOf(
            Trip(at(5, 9.0), at(5, 12.0), 236.0, Place.HOME, Place.OTHER, 7.5, 1.0, 15.0),
            Trip(at(6, 15.0), at(6, 18.5), 236.0, Place.OTHER, Place.HOME, 7.5, 1.0, 15.0),
        )
        val r = Measured.report(m, trips, plan, nowMs = at(13, 20.0))!!
        assertTrue(r.sim.lowSocDays > 0)
        assertEquals(0, r.routineLowDays)
        assertEquals(Verdict.GOOD, r.verdict)
    }

    @Test
    fun aFewDaysAreTooFewToJudgeTheMonthlyDistance() {
        val prelim = Preliminary.estimate(m, PrelimInput(17.3, 5, emptyList(), 18_400.0, 14, 13.8, true, 11), plan)
        val r = Measured.report(m, commuteDays(1), plan, nowMs = at(0, 20.0))!!
        val lines = Measured.insights(prelim, Measured.predictedMonthly(prelim), r)
        assertTrue(lines.none { "달렸어요" in it })
        assertTrue(lines.any { "${Measured.MIN_COMPARE_DAYS}일부터" in it })
    }

    @Test
    fun wonFormatting() {
        assertEquals("14만 원", Measured.won(140_000.0))
        assertEquals("−3만 원", Measured.won(-30_000.0))
        assertEquals("9,500원", Measured.won(9_500.0))
    }
}
