package dev.shadowgps.core.routing

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.concatCoords
import dev.shadowgps.core.geo.coordsToList
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.geo.sliceCoords
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.traffic.TrafficModel
import dev.shadowgps.core.traffic.TrafficTable

/**
 * Plans a trip several ways at once and reports what each one costs in time and exposure.
 *
 * The exposure index is built once and shared by every profile, which is what makes running
 * four searches over the same graph affordable on a phone.
 */
class RoutePlanner(
    val graph: RoadGraph,
    val detectors: List<Detector>,
    settings: AvoidanceSettings = AvoidanceSettings.DEFAULT,
    private val options: RoutingOptions = RoutingOptions(),
    /** Typical congestion for the departure time; free-flow ignores traffic entirely. */
    val traffic: TrafficModel = TrafficModel.FREE_FLOW,
) {
    val exposureIndex: ExposureIndex = ExposureIndex(graph, detectors, ExposureModel(settings))
    private val trafficTable = TrafficTable(graph, traffic)
    private val router = Router(graph, exposureIndex, options, trafficTable)

    /**
     * Routes [origin] to [destination] once per profile.
     *
     * Profiles that produce a route the driver has already been shown are dropped, as are
     * ones whose detour blows past [PrivacyProfile.maxDetourFactor] — a "private" option
     * that triples the journey is not an option, it is a joke, and showing it would bury
     * the ones that are actually usable.
     */
    /**
     * @param fallbackStartMeters how far to look for a road when nothing is within
     *   [originSnapMeters]. Non-zero turns an outright failure into a route that starts at
     *   the nearest reachable road, flagged through [RoutePlan.provisionalStart]. Zero
     *   keeps the strict behaviour.
     */
    fun plan(
        origin: LatLon,
        destination: LatLon,
        profiles: List<PrivacyProfile> = PrivacyProfile.entries,
        originSnapMeters: Double = SnapRadius.DEFAULT_METERS,
        fallbackStartMeters: Double = 0.0,
    ): RoutePlan {
        val direct = planFrom(origin, destination, profiles, originSnapMeters)

        // Only a start that is genuinely off the network is worth relocating. Every other
        // failure — no map, unreachable destination, no path — would be misrepresented by
        // silently moving the driver somewhere else.
        if (direct.failure != RouteFailure.ORIGIN_UNREACHABLE) return direct
        if (fallbackStartMeters <= originSnapMeters) return direct

        val nearest = graph.snapNearest(origin, fallbackStartMeters) ?: return direct
        val fromNearest = planFrom(nearest.point, destination, profiles, SnapRadius.DEFAULT_METERS)
        if (fromNearest.isEmpty) return direct

        return fromNearest.copy(
            provisionalStart = ProvisionalStart(
                requested = origin,
                joinPoint = nearest.point,
                distanceMeters = haversineMeters(origin, nearest.point),
                roadName = graph.edges[nearest.edgeIndex].displayName,
            ),
        )
    }

    /** Whether a trip could start from [point] without any help. */
    fun isRoutableFrom(point: LatLon, snapMeters: Double = SnapRadius.DEFAULT_METERS): Boolean =
        graph.snapNearest(point, snapMeters) != null

    private fun planFrom(
        origin: LatLon,
        destination: LatLon,
        profiles: List<PrivacyProfile>,
        originSnapMeters: Double,
    ): RoutePlan {
        val routes = ArrayList<Route>(profiles.size)
        var firstFailure: RouteFailure? = null
        var fastestSeconds: Double? = null
        val seenShapes = HashSet<List<Int>>()

        for (profile in profiles) {
            when (val result = router.route(origin, destination, profile.secondsPerDetector, originSnapMeters)) {
                is RouteSearchResult.Failed -> {
                    if (firstFailure == null) firstFailure = result.reason
                }

                is RouteSearchResult.Found -> {
                    val shape = result.route.edgeIndices.toList()
                    if (!seenShapes.add(shape)) continue

                    val route = materialize(profile, result.route)
                    if (profile == PrivacyProfile.FASTEST) fastestSeconds = route.durationSeconds

                    val baseline = fastestSeconds
                    val tooSlow = baseline != null &&
                        route.durationSeconds > baseline * profile.maxDetourFactor + DETOUR_GRACE_SECONDS
                    if (tooSlow) continue

                    routes.add(route)
                }
            }
        }

        return RoutePlan(
            routes = routes,
            failure = if (routes.isEmpty()) firstFailure ?: RouteFailure.NO_PATH else null,
            traffic = traffic,
        )
    }

    /** Expands a raw edge chain into geometry, timings, instructions and an exposure report. */
    fun materialize(profile: PrivacyProfile, raw: RawRoute): Route {
        val edgeIndices = raw.edgeIndices
        val count = edgeIndices.size

        val pieces = ArrayList<DoubleArray>(count)
        val distances = DoubleArray(count)
        val durations = DoubleArray(count)
        val freeFlowDurations = DoubleArray(count)
        val startOffsets = DoubleArray(count)

        val encountersByDetector = LinkedHashMap<String, DetectorEncounter>()
        var cumulative = 0.0

        for (position in 0 until count) {
            val edgeIndex = edgeIndices[position]
            val edge = graph.edges[edgeIndex]

            val from = if (position == 0) raw.startAlongMeters.coerceIn(0.0, edge.lengthMeters) else 0.0
            val to = if (position == count - 1) raw.endAlongMeters.coerceIn(from, edge.lengthMeters) else edge.lengthMeters
            val span = (to - from).coerceAtLeast(0.0)
            val fraction = if (edge.lengthMeters <= 0.0) 0.0 else span / edge.lengthMeters

            pieces.add(sliceCoords(edge.coords, from, to))
            distances[position] = span
            startOffsets[position] = cumulative

            // Junction and turn delays are real time the driver spends, so they belong in
            // the duration even though they are not part of any single edge.
            val turn = if (position > 0) turnSeconds(edgeIndices[position - 1], edgeIndex) else 0.0

            var seconds = trafficTable.edgeSeconds[edgeIndex] * fraction
            var freeFlow = edge.travelSeconds * fraction
            if (position > 0) {
                seconds += trafficTable.nodeSeconds[edge.fromNode] + turn
                freeFlow += graph.nodeDelaySeconds[edge.fromNode] + turn
            }
            durations[position] = seconds
            freeFlowDurations[position] = freeFlow

            for (hit in exposureIndex.hits(edgeIndex)) {
                if (hit.alongMeters < from || hit.alongMeters > to) continue
                val detector = exposureIndex.detectorAt(hit)
                val alongRoute = cumulative + (hit.alongMeters - from)
                val existing = encountersByDetector[detector.id]
                // The same camera can cover two consecutive edges; keep the closest pass.
                if (existing == null || hit.distanceMeters < existing.distanceMeters) {
                    encountersByDetector[detector.id] = DetectorEncounter(
                        detector = detector,
                        distanceMeters = hit.distanceMeters,
                        alongRouteMeters = alongRoute,
                        weight = hit.weight,
                    )
                }
            }

            cumulative += span
        }

        val steps = Directions.build(
            graph = graph,
            edgeIndices = edgeIndices,
            startAlongMeters = raw.startAlongMeters,
            endAlongMeters = raw.endAlongMeters,
            edgeDurations = durations,
            edgeDistances = distances,
            edgeStartOffsets = startOffsets,
        )

        val encounters = encountersByDetector.values.sortedBy { it.alongRouteMeters }
        val exposure = RouteExposure(
            encounters = encounters,
            score = encounters.sumOf { it.weight },
            countsByKind = encounters.groupingBy { it.detector.kind }.eachCount(),
        )

        return Route(
            profile = profile,
            geometry = coordsToList(concatCoords(pieces)),
            distanceMeters = distances.sum(),
            durationSeconds = durations.sum(),
            steps = withDetectorCounts(steps, encounters),
            exposure = exposure,
            freeFlowSeconds = freeFlowDurations.sum(),
        )
    }

    /** Attributes each encounter to the instruction the driver will be following at the time. */
    private fun withDetectorCounts(steps: List<RouteStep>, encounters: List<DetectorEncounter>): List<RouteStep> {
        if (steps.isEmpty() || encounters.isEmpty()) return steps
        val counts = IntArray(steps.size)
        for (encounter in encounters) {
            var stepIndex = steps.indexOfLast { it.startAlongRouteMeters <= encounter.alongRouteMeters }
            if (stepIndex < 0) stepIndex = 0
            counts[stepIndex]++
        }
        return steps.mapIndexed { index, step -> step.copy(detectorCount = counts[index]) }
    }

    private fun turnSeconds(fromEdge: Int, toEdge: Int): Double {
        val previous = graph.edges[fromEdge]
        val next = graph.edges[toEdge]
        if (previous.reverseIndex == toEdge) return U_TURN_REAL_SECONDS
        if (previous.roundabout && next.roundabout) return 0.0
        val turn = dev.shadowgps.core.geo.angularDifference(previous.endHeading, next.startHeading)
        return when {
            turn < 20.0 -> 0.0
            turn < 45.0 -> 2.0
            turn < 110.0 -> 5.0
            else -> 9.0
        }
    }

    /** Devices anywhere near the plan, for drawing on the map rather than for routing. */
    fun detectorsOfKind(kinds: Set<DetectorKind>): List<Detector> =
        detectors.filter { it.kind in kinds }

    private companion object {
        /** Absolute slack on top of the detour multiplier, so short trips are not over-policed. */
        const val DETOUR_GRACE_SECONDS = 240.0

        /** Time a u-turn really costs, as opposed to what the router is charged to avoid one. */
        const val U_TURN_REAL_SECONDS = 20.0
    }
}
