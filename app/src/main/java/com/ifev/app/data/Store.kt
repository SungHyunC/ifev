package com.ifev.app.data

import android.content.Context
import com.ifev.app.engine.DistanceSource
import com.ifev.app.engine.Place
import com.ifev.app.engine.Trip
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

data class Settings(
    val modelId: String? = null,
    val homeCharging: Boolean? = null,
    val monthsOwned: Int? = null,
    /** True when [monthsOwned] came from the VIN, not from the driver. */
    val monthsFromVin: Boolean = false,
    /** The car being driven, asked only when the car does not report its own fuel economy. */
    val myCarId: String? = null,
)

/**
 * Everything stays on the car: settings in SharedPreferences, trips in a JSON file in app storage.
 * Trips hold distance, time, temperature, climate share, fuel economy and a home/office/other tag,
 * never coordinates.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ifev", Context.MODE_PRIVATE)
    private val tripsFile = File(context.filesDir, "trips.json")

    fun settings() = Settings(
        modelId = prefs.getString(KEY_MODEL, null),
        homeCharging = when (prefs.getInt(KEY_HOME, -1)) { 1 -> true; 0 -> false; else -> null },
        monthsOwned = prefs.getInt(KEY_MONTHS, -1).takeIf { it > 0 },
        monthsFromVin = prefs.getBoolean(KEY_MONTHS_VIN, false),
        myCarId = prefs.getString(KEY_MY_CAR, null),
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_MODEL, s.modelId)
            .putInt(KEY_HOME, when (s.homeCharging) { true -> 1; false -> 0; null -> -1 })
            .putInt(KEY_MONTHS, s.monthsOwned ?: -1)
            .putBoolean(KEY_MONTHS_VIN, s.monthsFromVin)
            .putString(KEY_MY_CAR, s.myCarId)
            .apply()
    }

    @Synchronized
    fun trips(): List<Trip> {
        if (!tripsFile.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(tripsFile.readText())
            (0 until arr.length()).map { i -> arr.getJSONObject(i).toTrip() }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun append(trip: Trip) {
        val arr = runCatching { JSONArray(tripsFile.readText()) }.getOrDefault(JSONArray())
        arr.put(trip.toJson())
        val tmp = File(tripsFile.parentFile, "trips.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(tripsFile)
    }

    @Synchronized
    fun clearTrips() {
        tripsFile.delete()
    }

    private fun Trip.toJson() = JSONObject()
        .put("s", startAt).put("e", endAt).put("km", distanceKm)
        .put("from", startPlace.name).put("to", endPlace.name)
        .put("t", tempC).put("c", climateShare)
        .put("f", fuelKmPerL ?: JSONObject.NULL)
        .put("ds", distanceSource.name)

    private fun JSONObject.toTrip() = Trip(
        startAt = getLong("s"),
        endAt = getLong("e"),
        distanceKm = getDouble("km"),
        startPlace = Place.valueOf(getString("from")),
        endPlace = Place.valueOf(getString("to")),
        tempC = getDouble("t"),
        climateShare = getDouble("c"),
        fuelKmPerL = if (isNull("f")) null else getDouble("f"),
        distanceSource = optString("ds").let { s -> DistanceSource.entries.firstOrNull { it.name == s } ?: DistanceSource.UNKNOWN },
    )

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_HOME = "homeCharging"
        const val KEY_MONTHS = "monthsOwned"
        const val KEY_MONTHS_VIN = "monthsFromVin"
        const val KEY_MY_CAR = "myCar"
    }
}
