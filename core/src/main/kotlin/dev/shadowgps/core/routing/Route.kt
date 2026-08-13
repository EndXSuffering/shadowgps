package dev.shadowgps.core.routing

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.LatLon
import kotlinx.serialization.Serializable

/** How hard the router should work to stay out of sight. */
@Serializable
enum class PrivacyProfile(
    val id: String,
    val label: String,
    /**
     * Seconds of detour the driver is willing to accept to avoid one fully-covering plate
     * reader. This single number is what separates the profiles.
     */
    val secondsPerDetector: Double,
    /** Reject the result if it takes more than this multiple of the fastest route. */
    val maxDetourFactor: Double,
) {
    FASTEST("fastest", "Fastest", 0.0, 1.0),
    BALANCED("balanced", "Balanced", 90.0, 1.4),
    DISCREET("discreet", "Discreet", 480.0, 2.0),
    GHOST("ghost", "Ghost", 3_000.0, 3.5),
    ;

    val description: String
        get() = when (this) {
            FASTEST -> "Ignores surveillance entirely — the baseline to compare against."
            BALANCED -> "Takes small detours around cameras when they are nearly free."
            DISCREET -> "Accepts a real detour to stay unseen."
            GHOST -> "Avoids everything it possibly can, however long that takes."
        }

    companion object {
        fun fromId(id: String): PrivacyProfile? = entries.firstOrNull { it.id == id }
    }
}

/** A single manoeuvre in a set of directions. */
@Serializable
enum class Maneuver {
    DEPART,
    CONTINUE,
    SLIGHT_LEFT,
    LEFT,
    SHARP_LEFT,
    SLIGHT_RIGHT,
    RIGHT,
    SHARP_RIGHT,
    U_TURN,
    ROUNDABOUT,
    ARRIVE,
}

/** One instruction, covering everything up to the next manoeuvre. */
@Serializable
data class RouteStep(
    val maneuver: Maneuver,
    val instruction: String,
    val roadName: String?,
    val distanceMeters: Double,
    val durationSeconds: Double,
    /** Distance from the start of the whole route to where this step begins. */
    val startAlongRouteMeters: Double,
    val startPoint: LatLon,
    /** Which exit to take, for [Maneuver.ROUNDABOUT]. */
    val roundaboutExit: Int? = null,
    /** How many tracked devices this step drives past. */
    val detectorCount: Int = 0,
)

/** A device the route drives within range of. */
@Serializable
data class DetectorEncounter(
    val detector: Detector,
    /** Closest the route comes to the device. */
    val distanceMeters: Double,
    /** How far into the route that happens. */
    val alongRouteMeters: Double,
    /** Kind-scaled coverage, matching [ExposureModel]. */
    val weight: Double,
)

/** Everything that watched the trip. */
@Serializable
data class RouteExposure(
    val encounters: List<DetectorEncounter>,
    /** Sum of encounter weights: the number the router actually minimises. */
    val score: Double,
    val countsByKind: Map<DetectorKind, Int>,
) {
    val totalCount: Int get() = encounters.size
    val alprCount: Int get() = countsByKind[DetectorKind.ALPR] ?: 0

    companion object {
        val NONE = RouteExposure(emptyList(), 0.0, emptyMap())
    }
}

/** A complete, drivable answer. */
@Serializable
data class Route(
    val profile: PrivacyProfile,
    val geometry: List<LatLon>,
    val distanceMeters: Double,
    /** Realistic driving time including junction and turn delays, excluding penalties. */
    val durationSeconds: Double,
    val steps: List<RouteStep>,
    val exposure: RouteExposure,
) {
    /** Stable identity for a route's shape, used to spot duplicates across profiles. */
    val shapeKey: Int get() = geometry.hashCode()
}

/**
 * A route that begins somewhere other than where the driver is.
 *
 * Produced when nothing drivable is within reach of the start — an underground car park, a
 * field, a campus, a ferry terminal. Rather than refusing to plan, the router falls back to
 * the nearest point the road network actually reaches and says so, leaving the driver to
 * cover the gap however they like.
 */
@Serializable
data class ProvisionalStart(
    /** Where the driver said they were. */
    val requested: LatLon,
    /** Where the route begins instead. */
    val joinPoint: LatLon,
    /** Straight-line gap between the two. */
    val distanceMeters: Double,
    val roadName: String? = null,
)

/** The outcome of planning a trip across several profiles. */
data class RoutePlan(
    val routes: List<Route>,
    val failure: RouteFailure? = null,
    /** Set when the routes start away from the requested origin; null when they start at it. */
    val provisionalStart: ProvisionalStart? = null,
) {
    val isEmpty: Boolean get() = routes.isEmpty()

    /** The zero-penalty route, when it was requested — the yardstick for everything else. */
    val fastest: Route? get() = routes.firstOrNull { it.profile == PrivacyProfile.FASTEST }

    /** Fewest devices passed, breaking ties on time. */
    val leastExposed: Route?
        get() = routes.minWithOrNull(
            compareBy<Route> { it.exposure.score }.thenBy { it.durationSeconds },
        )
}
