package com.ifev.app.scenario

import android.content.Context
import com.ifev.app.engine.DistanceSource
import com.ifev.app.engine.Place
import com.ifev.app.engine.Trip
import org.json.JSONArray
import org.json.JSONObject

/**
 * Real people's drives, recorded with a phone GPS logger and converted by tools/gpx_to_trips.py (no
 * coordinates). Bundled as assets/real/<id>/trips.json + profile.json for the presentation, so the
 * same screens show real weeks next to the made-up demo. Absent folder, absent button.
 */
data class RealDrive(val id: String, val label: String, val homeCharging: Boolean, val trips: List<Trip>)

object RealDrives {
    fun load(context: Context): List<RealDrive> {
        val assets = context.assets
        val ids = runCatching { assets.list("real")?.toList() }.getOrNull().orEmpty().sorted()
        return ids.mapNotNull { id ->
            runCatching {
                val trips = JSONArray(assets.open("real/$id/trips.json").bufferedReader().readText())
                val profile = runCatching { JSONObject(assets.open("real/$id/profile.json").bufferedReader().readText()) }.getOrElse { JSONObject() }
                RealDrive(
                    id = id,
                    label = profile.optString("label", id),
                    homeCharging = profile.optBoolean("homeCharging", true),
                    trips = (0 until trips.length()).map { i ->
                        val o = trips.getJSONObject(i)
                        Trip(
                            startAt = o.getLong("s"), endAt = o.getLong("e"), distanceKm = o.getDouble("km"),
                            startPlace = Place.valueOf(o.getString("from")), endPlace = Place.valueOf(o.getString("to")),
                            tempC = o.getDouble("t"), climateShare = o.getDouble("c"),
                            fuelKmPerL = if (o.isNull("f")) null else o.getDouble("f"),
                            distanceSource = DistanceSource.entries.firstOrNull { it.name == o.optString("ds") } ?: DistanceSource.TRACK,
                        )
                    }.sortedBy { it.startAt },
                )
            }.getOrNull()?.takeIf { it.trips.isNotEmpty() }
        }
    }
}
