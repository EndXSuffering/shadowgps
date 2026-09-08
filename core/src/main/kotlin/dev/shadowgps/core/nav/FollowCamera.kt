package dev.shadowgps.core.nav

/**
 * How close the map should be sitting while it follows the driver.
 *
 * The map does not need a number here, it needs a decision, and the decision is the part
 * worth getting right: a view wide enough to show the road ahead is too wide to show which
 * of three lanes peels off at an exit, and the moment that detail matters is the last few
 * hundred metres. Which zoom level each band corresponds to is a map concern and lives with
 * the map; when to change band is navigation, and lives here where it can be tested.
 */
enum class FollowFraming {
    /** Between manoeuvres: as much road ahead as will fit. */
    CRUISING,

    /** A manoeuvre is coming: close enough to pick out the junction and its slip road. */
    APPROACHING,

    /** At the manoeuvre: close enough to see which lane, and where the kerb is. */
    AT_MANEUVER,
}

/**
 * Picks the framing for the next frame.
 *
 * Two things stop this flapping. The bands are wide — hundreds of metres apart, not tens —
 * and each has to be left by a margin wider than GPS noise, so a fix that jitters either
 * side of a boundary does not send the map in and out. [previous] is what makes the second
 * part possible: without it the same distance would always give the same answer, and a
 * boundary would be a boundary in both directions.
 */
fun followFraming(
    metersToManeuver: Double?,
    previous: FollowFraming = FollowFraming.CRUISING,
): FollowFraming {
    // Nothing to aim at — no route, or no manoeuvre left on it — is cruising by definition.
    if (metersToManeuver == null || metersToManeuver.isNaN()) return FollowFraming.CRUISING

    // Widen the band the map is already in, so leaving it takes a real change in distance
    // rather than a wobble. Entering is on the plain threshold: being late to close in for
    // a turn is a worse failure than being early to pull back out after one.
    val closeLimit = MANEUVER_CLOSE_METERS + hysteresisFor(previous, FollowFraming.AT_MANEUVER)
    val approachLimit = MANEUVER_APPROACH_METERS + hysteresisFor(previous, FollowFraming.APPROACHING)

    return when {
        metersToManeuver <= closeLimit -> FollowFraming.AT_MANEUVER
        metersToManeuver <= approachLimit -> FollowFraming.APPROACHING
        else -> FollowFraming.CRUISING
    }
}

private fun hysteresisFor(previous: FollowFraming, band: FollowFraming): Double =
    if (previous == band) FRAMING_HYSTERESIS_METERS else 0.0

/**
 * Far enough out that a motorway exit is still several seconds away.
 *
 * At motorway speed this is roughly fifteen seconds of warning, which is about when a driver
 * starts looking for the exit rather than being told about it.
 */
const val MANEUVER_APPROACH_METERS = 500.0

/** Close enough that the junction itself, and not the road leading to it, is the subject. */
const val MANEUVER_CLOSE_METERS = 150.0

/** How far past a boundary the driver has to get before the map gives up that framing. */
const val FRAMING_HYSTERESIS_METERS = 60.0
