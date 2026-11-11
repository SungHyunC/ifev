package com.ifev.app.scenario

import com.ifev.app.data.CarState
import com.ifev.app.data.Km
import com.ifev.app.engine.Destination
import com.ifev.app.engine.Place
import com.ifev.app.engine.SeoulClimate
import com.ifev.app.engine.Trip
import com.ifev.app.vehicle.NaviPlace
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Made-up data for showing the app on the emulator, which neither drives nor has a saved home. The
 * shapes match what the SDK delivers (saved places, recent destinations, odometer, trips from P to P),
 * and every number goes through the same engine as live data.
 */
object DemoScenario {
    val home = NaviPlace("집 (마포구 합정동)", 37.5495, 126.9139, null, null)
    val office = NaviPlace("회사 (강남역)", 37.4979, 127.0276, null, null)
    private const val COMMUTE_KM = 16.8

    /** What the car and navigation would report on the day the app is installed. */
    fun dayZero(): CarState = CarState(
        vehicleStatus = "시연",
        naviStatus = "시연",
        parked = true,
        // Pleos Connect cars are new (the Grandeur came first, in May 2026): about seven months old.
        odometerKm = 9_210.0,
        fuelPercent = 54,
        averageFuelKmPerL = 13.6,
        climateOn = true,
        home = home,
        office = office,
        commute = Km(COMMUTE_KM, routed = true),
        destinations = listOf(
            Destination("강릉 경포해변", 236.0) to true,
            Destination("대전 성심당 본점", 162.0) to true,
            Destination("수원 화성행궁", 41.0) to true,
            Destination("일산 호수공원", 22.0) to true,
        ),
        modelYear = 2026,
    )

    /** Entered by the driver: the model year alone does not tell when the car was bought. */
    const val MONTHS_OWNED = 7

    /** First recorded day of the four weeks. */
    val firstDay: LocalDate = LocalDate.of(2026, 10, 12)

    /**
     * Four weeks of a commuter with a family: weekday commutes, a Wednesday shop, Saturday outings,
     * Suwon on the second Sunday and a Gangneung weekend in the third week.
     */
    fun fourWeeks(): List<Trip> {
        val trips = mutableListOf<Trip>()
        fun add(day: LocalDate, hour: Double, km: Double, minutes: Double, from: Place, to: Place, fuel: Double) {
            val start = LocalDateTime.of(day, java.time.LocalTime.MIDNIGHT).plusMinutes((hour * 60).toLong())
            val startMs = start.atZone(Trip.KST).toInstant().toEpochMilli()
            trips += Trip(
                startAt = startMs,
                endAt = startMs + (minutes * 60_000).toLong(),
                distanceKm = km,
                startPlace = from,
                endPlace = to,
                tempC = SeoulClimate.meanC(day.monthValue),
                climateShare = 1.0,
                fuelKmPerL = fuel,
            )
        }
        for (i in 0 until 28) {
            val day = firstDay.plusDays(i.toLong())
            val week = i / 7
            when (day.dayOfWeek) {
                DayOfWeek.SATURDAY -> if (week == 2) {
                    add(day, 9.0, 236.0, 190.0, Place.HOME, Place.OTHER, 15.8)
                } else {
                    add(day, 11.0, 22.0, 40.0, Place.HOME, Place.OTHER, 12.9)
                    add(day, 16.5, 22.0, 45.0, Place.OTHER, Place.HOME, 12.9)
                }
                DayOfWeek.SUNDAY -> when (week) {
                    1 -> {
                        add(day, 10.0, 41.0, 60.0, Place.HOME, Place.OTHER, 13.9)
                        add(day, 15.0, 41.0, 65.0, Place.OTHER, Place.HOME, 13.9)
                    }
                    2 -> add(day, 15.0, 236.0, 215.0, Place.OTHER, Place.HOME, 15.6)
                    else -> {
                        add(day, 12.0, 4.0, 12.0, Place.HOME, Place.OTHER, 10.5)
                        add(day, 13.5, 4.0, 12.0, Place.OTHER, Place.HOME, 10.5)
                    }
                }
                else -> {
                    add(day, 7.8, COMMUTE_KM, 45.0, Place.HOME, Place.OFFICE, 11.8)
                    add(day, 18.7, COMMUTE_KM, 52.0, Place.OFFICE, Place.HOME, 11.6)
                    if (day.dayOfWeek == DayOfWeek.WEDNESDAY) {
                        add(day, 20.0, 3.1, 10.0, Place.HOME, Place.OTHER, 10.2)
                        add(day, 21.0, 3.1, 10.0, Place.OTHER, Place.HOME, 10.2)
                    }
                }
            }
        }
        return trips
    }

    /** "Now" at the end of the four weeks. */
    val fourWeeksNow: Long = LocalDateTime.of(firstDay.plusDays(27), java.time.LocalTime.of(21, 30))
        .atZone(Trip.KST).toInstant().toEpochMilli()

    /** One commute played back in about a minute: (seconds from start, km driven so far). */
    val drive: List<Pair<Int, Double>> = (0..56).map { s -> s to (COMMUTE_KM * s / 56.0) }

    /** Real duration the played-back commute stands for. */
    const val DRIVE_REAL_MINUTES = 45.0
}
