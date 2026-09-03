package dev.shadowgps.core.nav

import dev.shadowgps.core.format.Formatting
import dev.shadowgps.core.format.UnitSystem
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.coordsLengthMeters
import dev.shadowgps.core.geo.listToCoords
import dev.shadowgps.core.geo.projectOntoCoords
import dev.shadowgps.core.geo.sliceCoords
import dev.shadowgps.core.routing.DetectorEncounter
import dev.shadowgps.core.routing.Maneuver
import dev.shadowgps.core.routing.Route
import dev.shadowgps.core.routing.RouteStep
import kotlin.math.max

/** One position report from the device. */
data class PositionFix(
    val position: LatLon,
    val bearingDegrees: Double? = null,
    val speedMetersPerSecond: Double? = null,
    val accuracyMeters: Double? = null,
    val timestampMillis: Long = 0L,
)

/** Something worth saying out loud, at most once each. */
data class Announcement(
    val kind: Kind,
    val text: String,
) {
    enum class Kind {
        /** The next turn is coming up. */
        MANEUVER,

        /** A tracked device lies ahead on the current road. */
        DETECTOR,

        /** The driver has left the planned route. */
        OFF_ROUTE,

        ARRIVAL,
    }
}

data class NavigationConfig(
    val units: UnitSystem = UnitSystem.METRIC,
    /**
     * Perpendicular distance from the line that counts as having left it.
     *
     * Generous on purpose. The route is a centreline, so a dual carriageway, a slip road
     * beside the main one, or simply a wide junction puts an honestly-positioned vehicle
     * tens of metres off it, and every metre shaved here buys another spurious reroute.
     */
    val offRouteMeters: Double = 55.0,
    /** Consecutive off-route fixes before the engine says so, to ride out GPS noise. */
    val offRouteFixes: Int = 4,
    /**
     * Below this speed a deviation is not believed.
     *
     * A stationary phone wanders by tens of metres, and a car park or a set of lights is
     * exactly where that happens — precisely the wrong moment to announce a wrong turn.
     */
    val movingSpeedMetersPerSecond: Double = 1.5,
    /** Distances before a turn at which to speak. */
    val maneuverAnnounceMeters: List<Double> = listOf(600.0, 200.0, 50.0),
    /** How far ahead to warn about a camera. */
    val detectorAnnounceMeters: Double = 300.0,
    /** Route is complete within this distance of the end. */
    val arrivalMeters: Double = 25.0,
    val announceDetectors: Boolean = true,
)

/** Everything the navigation UI needs for one frame. */
data class NavigationState(
    /**
     * The vehicle's position matched onto the route.
     *
     * This, not the raw fix, is what should be drawn. A raw GPS position jitters between
     * buildings and sits off the carriageway, so an arrow drawn from it wanders across
     * fields and rooftops; matched to the route it moves along the road the driver is on.
     */
    val snappedPosition: LatLon,
    /** Direction of the route where the vehicle is, for orienting the map and the arrow. */
    val routeHeadingDegrees: Double,
    val distanceAlongRouteMeters: Double,
    val distanceRemainingMeters: Double,
    val secondsRemaining: Double,
    val currentStepIndex: Int,
    val currentStep: RouteStep?,
    val nextStep: RouteStep?,
    val distanceToManeuverMeters: Double,
    /**
     * The manoeuvre after the next one.
     *
     * Turns often arrive in pairs — off one road and straight onto another — and knowing
     * the second one is coming is the difference between taking the first calmly and
     * taking it in the wrong lane.
     */
    val followingStep: RouteStep? = null,
    /** How far past the next manoeuvre [followingStep] is. */
    val metersBetweenManeuvers: Double = 0.0,
    val deviationMeters: Double,
    val isOffRoute: Boolean,
    val hasArrived: Boolean,
    /** Devices ahead on the route, nearest first. */
    val detectorsAhead: List<DetectorEncounter>,
    val announcements: List<Announcement> = emptyList(),
)

/**
 * Tracks a vehicle along a planned [Route].
 *
 * Deliberately free of Android types so the whole thing can be driven by a synthetic trace
 * in a unit test. The Android layer only feeds it locations and reads the state back.
 *
 * Progress is matched inside a moving window rather than against the whole line. A route
 * that crosses itself — which privacy detours do constantly, since they double back around
 * blocks — would otherwise let a fix near a later crossing teleport progress forward.
 */
class NavigationEngine(
    val route: Route,
    private val config: NavigationConfig = NavigationConfig(),
) {
    private val coords: DoubleArray = listToCoords(route.geometry)
    private val totalLength: Double = coordsLengthMeters(coords)

    private var progressMeters: Double = 0.0
    private var initialized: Boolean = false
    private var consecutiveOffRoute: Int = 0
    private var arrived: Boolean = false

    private val spoken = HashSet<String>()

    val totalRouteMeters: Double get() = totalLength

    /** Distance covered so far, for a progress bar. */
    val progress: Double get() = if (totalLength <= 0) 0.0 else (progressMeters / totalLength).coerceIn(0.0, 1.0)

    fun update(fix: PositionFix): NavigationState {
        val projection = matchToRoute(fix)
        progressMeters = projection.alongMeters
        initialized = true

        val deviation = projection.distanceMeters
        val tolerance = max(config.offRouteMeters, (fix.accuracyMeters ?: 0.0) * 2.0)
        // A fix only counts against the driver if the device is confident enough to be
        // worth believing and the vehicle is actually moving.
        val moving = (fix.speedMetersPerSecond ?: config.movingSpeedMetersPerSecond) >=
            config.movingSpeedMetersPerSecond
        if (deviation > tolerance && moving) consecutiveOffRoute++ else consecutiveOffRoute = 0
        val offRoute = consecutiveOffRoute >= config.offRouteFixes

        val remaining = (totalLength - progressMeters).coerceAtLeast(0.0)
        val stepIndex = stepIndexAt(progressMeters)
        val currentStep = route.steps.getOrNull(stepIndex)
        val nextStep = route.steps.getOrNull(stepIndex + 1)

        val maneuverAt = nextStep?.startAlongRouteMeters ?: totalLength
        val toManeuver = (maneuverAt - progressMeters).coerceAtLeast(0.0)

        val followingStep = route.steps.getOrNull(stepIndex + 2)
        val betweenManeuvers = followingStep
            ?.let { (it.startAlongRouteMeters - maneuverAt).coerceAtLeast(0.0) }
            ?: 0.0

        val justArrived = remaining <= config.arrivalMeters
        if (justArrived) arrived = true

        val ahead = route.exposure.encounters
            .filter { it.alongRouteMeters >= progressMeters - PASSED_GRACE_METERS }
            .sortedBy { it.alongRouteMeters }

        val announcements = buildList {
            if (arrived) {
                speakOnce("arrived")?.let { add(Announcement(Announcement.Kind.ARRIVAL, "You have arrived")) }
            } else if (offRoute) {
                // Keyed by a coarse bucket so a long detour re-announces occasionally
                // instead of once ever or on every single fix.
                val bucket = (progressMeters / 500).toInt()
                speakOnce("offroute:$bucket")?.let {
                    add(Announcement(Announcement.Kind.OFF_ROUTE, "Off route — recalculating"))
                }
            } else {
                maneuverAnnouncement(nextStep, toManeuver)?.let(::add)
                if (config.announceDetectors) detectorAnnouncement(ahead)?.let(::add)
            }
        }

        return NavigationState(
            snappedPosition = projection.point,
            routeHeadingDegrees = projection.headingDegrees,
            distanceAlongRouteMeters = progressMeters,
            distanceRemainingMeters = remaining,
            secondsRemaining = estimateRemainingSeconds(remaining),
            currentStepIndex = stepIndex,
            currentStep = currentStep,
            nextStep = nextStep,
            distanceToManeuverMeters = toManeuver,
            followingStep = followingStep,
            metersBetweenManeuvers = betweenManeuvers,
            deviationMeters = deviation,
            isOffRoute = offRoute,
            hasArrived = arrived,
            detectorsAhead = ahead,
            announcements = announcements,
        )
    }

    /**
     * Finds where on the route this fix sits.
     *
     * The first fix is matched against the entire line; later ones only against the stretch
     * from slightly behind the last known progress to as far ahead as the vehicle could
     * plausibly have travelled since.
     */
    private fun matchToRoute(fix: PositionFix): dev.shadowgps.core.geo.PolylineProjection {
        if (!initialized) return projectOntoCoords(coords, fix.position)

        // Wide enough to still contain the vehicle after a gap in fixes — a tunnel, a lost
        // signal, a stretch at speed. Too narrow a window projects onto the window's own
        // edge, which reads as a huge deviation and triggers a reroute that was never needed.
        val lookBehind = 120.0
        val lookAhead = max(500.0, (fix.speedMetersPerSecond ?: 0.0) * 25.0)
        val from = (progressMeters - lookBehind).coerceIn(0.0, totalLength)
        val to = (progressMeters + lookAhead).coerceIn(from, totalLength)
        if (to - from < 1.0) return projectOntoCoords(coords, fix.position)

        val window = sliceCoords(coords, from, to)
        val local = projectOntoCoords(window, fix.position)
        return local.copy(alongMeters = from + local.alongMeters)
    }

    private fun stepIndexAt(along: Double): Int {
        var index = 0
        for (i in route.steps.indices) {
            if (route.steps[i].startAlongRouteMeters <= along + 1e-6) index = i else break
        }
        return index
    }

    /**
     * Scales the route's planned duration by how much of it is left.
     *
     * Good enough while moving, and honest: without live traffic there is nothing better to
     * be had than the planner's own estimate.
     */
    private fun estimateRemainingSeconds(remainingMeters: Double): Double {
        if (totalLength <= 0) return 0.0
        return route.durationSeconds * (remainingMeters / totalLength)
    }

    private fun maneuverAnnouncement(nextStep: RouteStep?, toManeuver: Double): Announcement? {
        val step = nextStep ?: return null
        if (step.maneuver == Maneuver.ARRIVE && toManeuver > config.arrivalMeters * 4) return null

        val trigger = config.maneuverAnnounceMeters.firstOrNull { toManeuver <= it } ?: return null
        val key = "maneuver:${step.startAlongRouteMeters.toInt()}:${trigger.toInt()}"
        speakOnce(key) ?: return null

        val distancePhrase = Formatting.spokenDistance(toManeuver, config.units)
        val text = if (distancePhrase == "now") step.instruction else "$distancePhrase, ${step.instruction.lowercaseFirst()}"
        return Announcement(Announcement.Kind.MANEUVER, text)
    }

    private fun detectorAnnouncement(ahead: List<DetectorEncounter>): Announcement? {
        val next = ahead.firstOrNull { it.alongRouteMeters > progressMeters } ?: return null
        val distance = next.alongRouteMeters - progressMeters
        if (distance > config.detectorAnnounceMeters) return null

        val key = "detector:${next.detector.id}"
        speakOnce(key) ?: return null

        val phrase = Formatting.spokenDistance(distance, config.units)
        return Announcement(
            Announcement.Kind.DETECTOR,
            "${next.detector.kind.label} ahead $phrase",
        )
    }

    /** Returns non-null the first time a key is seen, so each line is spoken once. */
    private fun speakOnce(key: String): Unit? = if (spoken.add(key)) Unit else null

    private fun String.lowercaseFirst(): String =
        if (isEmpty()) this else this[0].lowercaseChar() + substring(1)

    private companion object {
        /** Keep a just-passed camera in the "ahead" list briefly so the UI does not flicker. */
        const val PASSED_GRACE_METERS = 20.0
    }
}
