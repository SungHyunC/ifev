package com.ifev.app.data

import android.content.Context
import com.ifev.app.engine.DistanceSource
import com.ifev.app.engine.Place
import com.ifev.app.engine.Trip
import com.ifev.app.engine.TripRecorder
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
    /** One spoken line when a destination is set and when parking. */
    val voiceOn: Boolean = true,
    /** When the last fill-up was seen (fuel level jumping up), for "since the last refuel". */
    val lastRefuelAt: Long? = null,
)

/**
 * Everything stays on the car: settings in SharedPreferences, trips in a JSON file in app storage.
 * Trips hold distance, time, temperature, climate share, fuel economy and a home/office/other tag,
 * never coordinates.
 */
class Store(context: Context) {
    private val prefs = context.getSharedPreferences("ifev", Context.MODE_PRIVATE)
    private val tripsFile = File(context.filesDir, "trips.json")
    private val openFile = File(context.filesDir, "open_trip.json")

    fun settings() = Settings(
        modelId = prefs.getString(KEY_MODEL, null),
        homeCharging = when (prefs.getInt(KEY_HOME, -1)) { 1 -> true; 0 -> false; else -> null },
        monthsOwned = prefs.getInt(KEY_MONTHS, -1).takeIf { it > 0 },
        monthsFromVin = prefs.getBoolean(KEY_MONTHS_VIN, false),
        myCarId = prefs.getString(KEY_MY_CAR, null),
        voiceOn = prefs.getBoolean(KEY_VOICE, true),
        lastRefuelAt = prefs.getLong(KEY_REFUEL, -1).takeIf { it > 0 },
    )

    fun save(s: Settings) {
        prefs.edit()
            .putString(KEY_MODEL, s.modelId)
            .putInt(KEY_HOME, when (s.homeCharging) { true -> 1; false -> 0; null -> -1 })
            .putInt(KEY_MONTHS, s.monthsOwned ?: -1)
            .putBoolean(KEY_MONTHS_VIN, s.monthsFromVin)
            .putString(KEY_MY_CAR, s.myCarId)
            .putBoolean(KEY_VOICE, s.voiceOn)
            .putLong(KEY_REFUEL, s.lastRefuelAt ?: -1)
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
        val arr = if (!tripsFile.exists()) JSONArray() else runCatching { JSONArray(tripsFile.readText()) }.getOrElse {
            // Keep a damaged file aside instead of writing over every trip recorded so far.
            tripsFile.renameTo(File(tripsFile.parentFile, "trips.damaged-${System.currentTimeMillis()}.json"))
            JSONArray()
        }
        arr.put(trip.toJson())
        writeAtomically(tripsFile, arr.toString())
    }

    @Synchronized
    fun clearTrips() {
        tripsFile.delete()
        clearOpenTrip()
    }

    /** The trip in progress, written every few seconds while driving. */
    @Synchronized
    fun saveOpenTrip(s: TripRecorder.Snapshot) {
        val json = JSONObject()
            .put("s", s.startAt).put("o", s.startOdometer ?: JSONObject.NULL).put("from", s.startPlace.name)
            .put("path", s.pathKm).put("speed", s.speedKm).put("cms", s.climateMs).put("con", s.climateOn).put("at", s.savedAt)
        writeAtomically(openFile, json.toString())
    }

    @Synchronized
    fun openTrip(): TripRecorder.Snapshot? {
        if (!openFile.exists()) return null
        return runCatching {
            val j = JSONObject(openFile.readText())
            TripRecorder.Snapshot(
                startAt = j.getLong("s"),
                startOdometer = if (j.isNull("o")) null else j.getDouble("o"),
                startPlace = Place.valueOf(j.getString("from")),
                pathKm = j.getDouble("path"),
                speedKm = j.getDouble("speed"),
                climateMs = j.getLong("cms"),
                climateOn = j.getBoolean("con"),
                savedAt = j.getLong("at"),
            )
        }.getOrNull()
    }

    @Synchronized
    fun clearOpenTrip() {
        openFile.delete()
    }

    private fun writeAtomically(target: File, text: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(target)) {
            target.writeText(text)
            tmp.delete()
        }
    }

    private fun Trip.toJson() = JSONObject()
        .put("s", startAt).put("e", endAt).put("km", distanceKm)
        .put("from", startPlace.name).put("to", endPlace.name)
        .put("t", tempC).put("c", climateShare)
        .put("f", fuelKmPerL ?: JSONObject.NULL)
        .put("ds", distanceSource.name)
        .put("ev", evKwhUsed ?: JSONObject.NULL)

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
        evKwhUsed = if (has("ev") && !isNull("ev")) getDouble("ev") else null,
    )

    private companion object {
        const val KEY_MODEL = "model"
        const val KEY_HOME = "homeCharging"
        const val KEY_MONTHS = "monthsOwned"
        const val KEY_MONTHS_VIN = "monthsFromVin"
        const val KEY_MY_CAR = "myCar"
        const val KEY_VOICE = "voice"
        const val KEY_REFUEL = "lastRefuelAt"
    }
}
