package com.ifev.app.scenario

import android.content.Context
import android.content.res.AssetManager
import com.ifev.app.engine.DistanceSource
import com.ifev.app.engine.Place
import com.ifev.app.engine.Trip
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real drives, without coordinates, next to the made-up demo. Absent folder, absent button.
 *
 * - assets/real/<id>/: people's own weeks from a phone GPS logger (tools/gpx_to_trips.py). Not in git.
 * - assets/public/<id>/: weeks from the public Vehicle Energy Dataset (tools/ved_to_trips.py). In git.
 *
 * Each folder holds trips.json and profile.json ({"label", "homeCharging", "source"}).
 */
data class RealDrive(
    val id: String,
    val label: String,
    val homeCharging: Boolean,
    val trips: List<Trip>,
    /** Where the drives come from, shown next to the label. */
    val source: String = DEFAULT_SOURCE,
) {
    companion object {
        const val DEFAULT_SOURCE = "폰 GPS로 수집"
    }
}

object RealDrives {
    /** Asset folders, in the order they are shown: participants first, then public data. */
    private val ROOTS = listOf("real", "public")

    fun load(context: Context): List<RealDrive> {
        val assets = context.assets
        return ROOTS.flatMap { root ->
            val ids = runCatching { assets.list(root)?.toList() }.getOrNull().orEmpty().sorted()
            ids.mapNotNull { id -> runCatching { read(assets, root, id) }.getOrNull()?.takeIf { it.trips.isNotEmpty() } }
        }
    }

    private fun read(assets: AssetManager, root: String, id: String): RealDrive {
        val trips = JSONArray(assets.open("$root/$id/trips.json").bufferedReader().readText())
        val profile = runCatching { JSONObject(assets.open("$root/$id/profile.json").bufferedReader().readText()) }.getOrElse { JSONObject() }
        return RealDrive(
            id = id,
            label = profile.optString("label", id),
            homeCharging = profile.optBoolean("homeCharging", true),
            source = profile.optString("source", "").ifBlank { RealDrive.DEFAULT_SOURCE },
            trips = (0 until trips.length()).map { i ->
                val o = trips.getJSONObject(i)
                Trip(
                    startAt = o.getLong("s"), endAt = o.getLong("e"), distanceKm = o.getDouble("km"),
                    startPlace = Place.valueOf(o.getString("from")), endPlace = Place.valueOf(o.getString("to")),
                    tempC = o.getDouble("t"), climateShare = o.getDouble("c"),
                    fuelKmPerL = if (o.isNull("f")) null else o.getDouble("f"),
                    distanceSource = DistanceSource.entries.firstOrNull { it.name == o.optString("ds") } ?: DistanceSource.TRACK,
                    evKwhUsed = if (o.has("ev") && !o.isNull("ev")) o.getDouble("ev") else null,
                )
            }.sortedBy { it.startAt },
        )
    }
}
