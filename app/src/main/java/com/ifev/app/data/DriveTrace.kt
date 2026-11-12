package com.ifev.app.data

/** In-memory drawing inputs. [tMs] is elapsed realtime, not wall-clock time; no coordinates. */
data class TracePoint(
    val tMs: Long,
    val speedMs: Double?,
    val steeringDeg: Double?,
    val brake: Boolean?,
    val turn: TurnState,
    val gear: GearState,
    val climateOn: Boolean?,
)

/**
 * At most 30 minutes / 18,000 points per drive at the default 10 Hz. Only D/R/N is recorded, and P
 * freezes the most recent drive. Starting another drive keeps the previous result until the next P.
 * This object never persists anything. Its owner serializes [record] and [lastDrive].
 */
class DriveTrace(
    private val maxDurationMs: Long = 30 * 60_000L,
    private val sampleIntervalMs: Long = 100L,
) {
    init {
        require(maxDurationMs > 0)
        require(sampleIntervalMs > 0)
    }

    private val maxPoints = (maxDurationMs / sampleIntervalMs).coerceIn(1, Int.MAX_VALUE.toLong()).toInt()
    private val points = ArrayDeque<TracePoint>()
    private var completed: List<TracePoint> = emptyList()
    private var lastSeenAt: Long? = null

    /** Returns true only when a new last-drive snapshot was finalized. */
    fun record(state: VehicleVisualState, tMs: Long): Boolean {
        if (lastSeenAt?.let { tMs < it } == true) return false
        lastSeenAt = tMs
        if (state.gear == GearState.PARK) {
            if (points.isEmpty()) return false
            completed = points.toList()
            points.clear()
            return true
        }
        if (state.gear !in DRIVING_GEARS) return false
        while (points.isNotEmpty() && tMs - points.first().tMs >= maxDurationMs) points.removeFirst()
        if (points.lastOrNull()?.let { tMs - it.tMs < sampleIntervalMs } == true) return false
        points.addLast(TracePoint(tMs, state.speedMs, state.steeringDeg, state.brake, state.turn, state.gear, state.climateOn))
        while (points.size > maxPoints) points.removeFirst()
        return false
    }

    fun lastDrive(): List<TracePoint> = completed.toList()

    private companion object {
        val DRIVING_GEARS = setOf(GearState.DRIVE, GearState.REVERSE, GearState.NEUTRAL)
    }
}
