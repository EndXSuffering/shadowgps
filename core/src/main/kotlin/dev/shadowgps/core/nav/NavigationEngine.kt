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
    /** Perpendicular distance from the line that counts as having left it. */
    val offRouteMeters: Double = 40.0,
    /** Consecutive off-route fixes before the engine says so, to ride out GPS noise. */
    val offRouteFixes: Int = 3,
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
    val snappedPosition: LatLon,
    val distanceAlongRouteMeters: Double,
    val distanceRemainingMeters: Double,
    val secondsRemaining: Double,
    val currentStepIndex: Int,
    val currentStep: RouteStep?,
    val nextStep: RouteStep?,
    val distanceToManeuverMeters: Double,
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
        val tolerance = max(config.offRouteMeters, (fix.accuracyMeters ?: 0.0) * 1.5)
        if (deviation > tolerance) consecutiveOffRoute++ else consecutiveOffRoute = 0
        val offRoute = consecutiveOffRoute >= config.offRouteFixes

        val remaining = (totalLength - progressMeters).coerceAtLeast(0.0)
        val stepIndex = stepIndexAt(progressMeters)
        val currentStep = route.steps.getOrNull(stepIndex)
        val nextStep = route.steps.getOrNull(stepIndex + 1)

        val maneuverAt = nextStep?.startAlongRouteMeters ?: totalLength
        val toManeuver = (maneuverAt - progressMeters).coerceAtLeast(0.0)

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
            distanceAlongRouteMeters = progressMeters,
            distanceRemainingMeters = remaining,
            secondsRemaining = estimateRemainingSeconds(remaining),
            currentStepIndex = stepIndex,
            currentStep = currentStep,
            nextStep = nextStep,
            distanceToManeuverMeters = toManeuver,
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

        val lookBehind = 60.0
        val lookAhead = max(250.0, (fix.speedMetersPerSecond ?: 0.0) * 15.0)
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
