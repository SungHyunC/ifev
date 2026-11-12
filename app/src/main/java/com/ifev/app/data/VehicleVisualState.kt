package com.ifev.app.data

/**
 * What the car is doing right now, for drawing the virtual EV (not for the energy engine, which keeps
 * its own inputs). Every continuous value carries the elapsed-realtime moment it was received, so the
 * view can tell a fresh value from a stale one.
 *
 * Shared contract between the signal layer (vehicle/, LiveRecorder) and the top view (ui/topview/).
 */
enum class GearState { UNKNOWN, PARK, REVERSE, NEUTRAL, DRIVE }

enum class TurnState { OFF, LEFT, RIGHT }

data class VehicleVisualState(
    /** Signed vehicle speed, m/s (reverse negative, as AAOS reports it); null until the first sample. */
    val speedMs: Double? = null,
    /** SystemClock.elapsedRealtime() when [speedMs] arrived. */
    val speedAtMs: Long? = null,
    val gear: GearState = GearState.UNKNOWN,
    /**
     * Raw steering value from the SDK, degrees, left negative. The Pleos SDK documents it as the front
     * road-wheel angle; the emulator console takes a hand-wheel range (±540). The view decides how to
     * read it ([com.ifev.app.ui.topview.SteeringConfig]); this layer passes it through untouched.
     */
    val steeringDeg: Double? = null,
    val steeringAtMs: Long? = null,
    /** Brake pedal pressed (Pleos Brake: pressed or not, no pressure). */
    val brake: Boolean? = null,
    val turn: TurnState = TurnState.OFF,
    val climateOn: Boolean? = null,
    /**
     * True when the car restricts this app's screen while driving (CarUxRestrictions requiring
     * distraction optimisation / NO_VIDEO), or when the restriction state is unknown and the car is not
     * in park. The view then draws no animation faster than 1 frame per second.
     */
    val restricted: Boolean = false,
    /** Which inputs this car actually delivers, for the honest "신호 대기" / "이 차는 주지 않음" labels. */
    val steeringAvailable: Boolean = false,
    val brakeAvailable: Boolean = false,
    val turnAvailable: Boolean = false,
)
