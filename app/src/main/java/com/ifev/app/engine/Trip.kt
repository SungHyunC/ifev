package com.ifev.app.engine

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

enum class Place { HOME, OFFICE, OTHER }

/** One drive from leaving park to parking again, as the car recorded it. */
data class Trip(
    val startAt: Long,
    val endAt: Long,
    val distanceKm: Double,
    val startPlace: Place,
    val endPlace: Place,
    /** Outside temperature used for the trip. */
    val tempC: Double,
    /** Share of the trip (0..1) with the climate system on. */
    val climateShare: Double,
    /** The car's own fuel economy for the trip, km/L, when it reported one. */
    val fuelKmPerL: Double?,
) {
    val hours: Double get() = ((endAt - startAt) / 3_600_000.0).coerceAtLeast(1.0 / 60)
    val avgSpeedKmh: Double get() = distanceKm / hours
    fun date(zone: ZoneId = KST): LocalDate = Instant.ofEpochMilli(startAt).atZone(zone).toLocalDate()

    companion object {
        val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}
