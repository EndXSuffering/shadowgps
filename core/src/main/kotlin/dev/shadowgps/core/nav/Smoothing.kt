package dev.shadowgps.core.nav

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.normalizeBearing
import dev.shadowgps.core.geo.signedTurnDegrees
import kotlin.math.exp

/**
 * Turning once-a-second position fixes into something continuous.
 *
 * A GPS fix arrives about once a second. Drawing each one the moment it lands makes the
 * vehicle hop a car's length at a time and the map snap to each new heading, which is
 * exactly when a driver loses track of where they are on the road. Everything here exists to
 * put the frames in between those fixes.
 *
 * The filter is a plain exponential approach — each frame, close some fraction of the gap to
 * where the fix says the vehicle is. That is deliberately not a constant fraction per frame:
 * a constant fraction makes the smoothing depend on the frame rate, so the same drive looks
 * different on a 60 Hz phone and a 120 Hz one, and stutters whenever a frame is dropped.
 * Deriving the fraction from the time actually elapsed gives the same motion on any device.
 *
 * The cost is lag: the drawn position trails the real one by roughly the time constant. At
 * a third of a second and motorway speed that is about ten metres, far less than the error
 * already in the fix, and well worth paying for motion that reads as driving rather than as
 * a slideshow.
 */

/**
 * How much of the remaining gap to close this frame.
 *
 * `1 - e^(-dt/tau)`, which is the closed form of "approach exponentially with time constant
 * [timeConstantSeconds]". After one time constant roughly 63% of the gap is gone, after
 * three about 95%.
 *
 * A non-positive time constant means "no smoothing at all", which returns 1.0 — jump
 * straight there. That is the honest reading of asking for a filter with no memory, and it
 * keeps callers from having to special-case a setting turned off.
 */
fun smoothingFactor(elapsedSeconds: Double, timeConstantSeconds: Double): Double {
    if (timeConstantSeconds <= 0.0) return 1.0
    if (elapsedSeconds <= 0.0) return 0.0
    if (!elapsedSeconds.isFinite()) return 1.0
    return 1.0 - exp(-elapsedSeconds / timeConstantSeconds)
}

/**
 * Moves [current] a [factor] of the way towards [target].
 *
 * Longitude is stepped along the shorter way round the globe, so a vehicle crossing the
 * antimeridian drifts across it rather than taking the scenic route through Greenwich.
 */
fun approachPosition(current: LatLon, target: LatLon, factor: Double): LatLon {
    val t = factor.coerceIn(0.0, 1.0)
    val lat = current.lat + (target.lat - current.lat) * t
    val lonDelta = ((target.lon - current.lon + 540.0) % 360.0) - 180.0
    val lon = current.lon + lonDelta * t
    return LatLon(lat, ((lon + 540.0) % 360.0) - 180.0)
}

/**
 * Turns [current] a [factor] of the way towards [target], the short way round.
 *
 * Interpolating bearings as plain numbers is the classic way to make a compass needle spin:
 * from 350° to 10° is ten degrees clockwise, not three hundred and forty anticlockwise.
 */
fun approachBearing(current: Double, target: Double, factor: Double): Double {
    val t = factor.coerceIn(0.0, 1.0)
    val turn = signedTurnDegrees(current, target)
    return normalizeBearing(current + turn * t)
}

/** Moves a plain scalar — a zoom level, say — a [factor] of the way towards [target]. */
fun approachValue(current: Double, target: Double, factor: Double): Double {
    val t = factor.coerceIn(0.0, 1.0)
    return current + (target - current) * t
}

/**
 * How quickly the drawn position chases the fix.
 *
 * Short enough that the vehicle is never visibly behind where the road says it is, long
 * enough to absorb the jitter between two fixes taken standing still.
 */
const val POSITION_TIME_CONSTANT_SECONDS = 0.32

/**
 * How quickly the map turns to a new heading.
 *
 * Slightly tighter than the position, because a heading that lags is felt as the whole map
 * sliding sideways through a bend, which is far more disorienting than a few metres of
 * position lag.
 */
const val HEADING_TIME_CONSTANT_SECONDS = 0.28

/** How quickly the map closes in and pulls back out between framings. */
const val ZOOM_TIME_CONSTANT_SECONDS = 0.45

/**
 * Past this, stop gliding and jump.
 *
 * A reroute, a lost fix reacquired, or the first fix of a trip can move the vehicle
 * kilometres at once. Sliding smoothly across all of that would be a long, confusing
 * animation of the map flying over countryside; landing on the answer is what a driver
 * wants. Comfortably beyond any distance a car covers between two fixes.
 */
const val SNAP_DISTANCE_METERS = 250.0
