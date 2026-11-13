package dev.shadowgps.core.routing

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.concatCoords
import dev.shadowgps.core.geo.coordsToList
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.geo.sliceCoords
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.traffic.CongestionLevel
import dev.shadowgps.core.traffic.CongestionSpans
import dev.shadowgps.core.traffic.TrafficModel
import dev.shadowgps.core.traffic.TrafficTable
import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Semaphore
import java.util.concurrent.SynchronousQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

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
     *
     * @param fallbackStartMeters how far to look for a road when nothing is within
     *   [originSnapMeters]. Non-zero turns an outright failure into a route that starts at
     *   the nearest reachable road, flagged through [RoutePlan.provisionalStart]. Zero
     *   keeps the strict behaviour.
     * @param fallbackEndMeters the same for the destination: how far to look for a road to
     *   finish on when none is within the normal snapping distance, flagged through
     *   [RoutePlan.provisionalEnd]. Without it, a destination in the middle of a large car
     *   park or complex failed outright, after the whole download.
     */
    fun plan(
        origin: LatLon,
        destination: LatLon,
        profiles: List<PrivacyProfile> = PrivacyProfile.entries,
        originSnapMeters: Double = SnapRadius.DEFAULT_METERS,
        fallbackStartMeters: Double = 0.0,
        fallbackEndMeters: Double = 0.0,
    ): RoutePlan {
        val direct = planFrom(origin, destination, profiles, originSnapMeters)

        var plan = direct
        var start = origin
        var startSnapMeters = originSnapMeters
        var provisionalStart: ProvisionalStart? = null
        var provisionalEnd: ProvisionalEnd? = null

        // Only an end that is genuinely off the network is worth relocating. Every other
        // failure — no map, no path — would be misrepresented by silently moving it.
        if (plan.failure == RouteFailure.ORIGIN_UNREACHABLE && fallbackStartMeters > originSnapMeters) {
            graph.snapNearest(origin, fallbackStartMeters)?.let { nearest ->
                val fromNearest = planFrom(nearest.point, destination, profiles, SnapRadius.DEFAULT_METERS)
                // Kept when it worked, and also when the only thing still wrong is the far
                // end — which the next step may yet be able to fix.
                if (!fromNearest.isEmpty || fromNearest.failure == RouteFailure.DESTINATION_UNREACHABLE) {
                    plan = fromNearest
                    start = nearest.point
                    startSnapMeters = SnapRadius.DEFAULT_METERS
                    provisionalStart = ProvisionalStart(
                        requested = origin,
                        joinPoint = nearest.point,
                        distanceMeters = haversineMeters(origin, nearest.point),
                        roadName = graph.edges[nearest.edgeIndex].displayName,
                    )
                }
            }
        }

        if (plan.failure == RouteFailure.DESTINATION_UNREACHABLE && fallbackEndMeters > SnapRadius.DEFAULT_METERS) {
            graph.snapNearest(destination, fallbackEndMeters)?.let { nearest ->
                val toNearest = planFrom(start, nearest.point, profiles, startSnapMeters)
                if (!toNearest.isEmpty) {
                    plan = toNearest
                    provisionalEnd = ProvisionalEnd(
                        requested = destination,
                        arrivalPoint = nearest.point,
                        distanceMeters = haversineMeters(destination, nearest.point),
                        roadName = graph.edges[nearest.edgeIndex].displayName,
                    )
                }
            }
        }

        // Nothing worked: report what was actually wrong with the trip as asked.
        if (plan.isEmpty) return direct
        return plan.copy(provisionalStart = provisionalStart, provisionalEnd = provisionalEnd)
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

        val results = searchAll(profiles) { profile ->
            router.route(origin, destination, profile.secondsPerDetector, originSnapMeters)
        }

        for ((profile, result) in profiles.zip(results)) {
            when (result) {
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

    /**
     * Runs one search per profile, side by side where the phone has the cores for it.
     *
     * The searches are independent — each reads the same graph and writes only its own
     * working arrays — and they were the whole of the wait between having the map and
     * showing the routes. Results come back in profile order whatever order they finish in,
     * so everything downstream behaves exactly as it did when they ran in turn.
     *
     * A very large graph gets fewer at once: each search holds a few bytes per road for as
     * long as it runs, and four of those across a saved region the size of a state is
     * memory better spent on the map itself.
     */
    private fun searchAll(
        profiles: List<PrivacyProfile>,
        search: (PrivacyProfile) -> RouteSearchResult,
    ): List<RouteSearchResult> {
        val atOnce = minOf(
            profiles.size,
            SEARCH_THREADS,
            if (graph.edgeCount > LARGE_GRAPH_EDGES) 2 else Int.MAX_VALUE,
        )
        if (atOnce <= 1) return profiles.map(search)

        val gate = Semaphore(atOnce)
        val pending = profiles.map { profile ->
            searchPool.submit(
                Callable {
                    gate.acquire()
                    try {
                        search(profile)
                    } finally {
                        gate.release()
                    }
                },
            )
        }
        try {
            return pending.map { it.get() }
        } catch (e: ExecutionException) {
            throw e.cause ?: e
        } catch (e: InterruptedException) {
            // The caller gave up — a cancelled plan. Stop the searches still queued, and
            // pass the interruption on.
            pending.forEach { it.cancel(true) }
            Thread.currentThread().interrupt()
            throw e
        }
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
        val congestion = ArrayList<CongestionLevel>(count)

        val encountersByDetector = LinkedHashMap<String, DetectorEncounter>()
        var cumulative = 0.0
        var tollMeters = 0.0

        for (position in 0 until count) {
            val edgeIndex = edgeIndices[position]
            val edge = graph.edges[edgeIndex]

            val from = if (position == 0) raw.startAlongMeters.coerceIn(0.0, edge.lengthMeters) else 0.0
            val to = if (position == count - 1) raw.endAlongMeters.coerceIn(from, edge.lengthMeters) else edge.lengthMeters
            val span = (to - from).coerceAtLeast(0.0)
            val fraction = if (edge.lengthMeters <= 0.0) 0.0 else span / edge.lengthMeters

            pieces.add(sliceCoords(edge.coords, from, to))
            distances[position] = span
            if (edge.toll) tollMeters += span
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
            congestion.add(trafficTable.levelFor(edgeIndex))

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
            congestionSpans = CongestionSpans.build(distances, congestion),
            tollMeters = tollMeters,
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

        /** Searches run at once: one per profile, but leave a core for the screen. */
        val SEARCH_THREADS: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 4)

        /** Past this many directed edges, searches run two at a time to hold memory down. */
        const val LARGE_GRAPH_EDGES = 1_500_000

        /**
         * Shared by every planner, and made of daemon threads that retire when idle, so it
         * costs nothing between trips and never keeps the process alive.
         */
        val searchPool: ExecutorService = ThreadPoolExecutor(
            0, 4, 30L, TimeUnit.SECONDS, SynchronousQueue(),
            { task -> Thread(task, "route-search").apply { isDaemon = true } },
            ThreadPoolExecutor.CallerRunsPolicy(),
        )
    }
}
