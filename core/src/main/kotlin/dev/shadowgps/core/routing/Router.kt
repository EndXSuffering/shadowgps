package dev.shadowgps.core.routing

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.angularDifference
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.graph.Speeds
import kotlin.math.abs

/** Knobs that shape the search itself, independent of how much privacy is being bought. */
data class RoutingOptions(
    /** How far from a road a start or end point may sit before routing gives up. */
    val maxSnapMeters: Double = RoadGraph.DEFAULT_SNAP_METERS,
    /** Seconds charged for reversing direction; high enough to make u-turns a last resort. */
    val uTurnPenaltySeconds: Double = 90.0,
    /** Safety valve so a disconnected or enormous graph cannot spin forever. */
    val maxExpandedStates: Int = 2_000_000,
)

/**
 * How far to look for a road, given how sure the device is about where it is.
 *
 * A phone indoors — which is exactly where a trip gets planned — often falls back to a
 * network fix accurate to hundreds of metres, and that reported position can land in the
 * middle of a field. Refusing to route because no road is within a fixed 200 m of a fix
 * the device itself says is ±800 m makes the app useless in the one place it is used most.
 * Snapping stays nearest-first, so a wider allowance only ever takes effect when there is
 * genuinely nothing closer.
 */
object SnapRadius {
    const val DEFAULT_METERS: Double = RoadGraph.DEFAULT_SNAP_METERS

    /** Beyond this the snapped position is too speculative to route from honestly. */
    const val MAX_METERS: Double = 2_000.0

    fun forAccuracy(accuracyMeters: Double?): Double {
        val accuracy = accuracyMeters ?: return DEFAULT_METERS
        if (accuracy <= 0 || accuracy.isNaN()) return DEFAULT_METERS
        return (accuracy * 1.5).coerceIn(DEFAULT_METERS, MAX_METERS)
    }
}

/**
 * A route as the search produces it: a chain of directed edges plus where along the first
 * and last ones the trip actually starts and stops.
 */
class RawRoute(
    val edgeIndices: IntArray,
    val startAlongMeters: Double,
    val endAlongMeters: Double,
    /** Search cost, including surveillance penalties. Not a real-world duration. */
    val penalizedSeconds: Double,
)

/** Why no route came back. */
enum class RouteFailure {
    /** The area has no road network at all — the download failed or returned nothing. */
    NO_MAP_DATA,
    ORIGIN_UNREACHABLE,
    DESTINATION_UNREACHABLE,
    NO_PATH,
    SEARCH_EXHAUSTED,
}

sealed interface RouteSearchResult {
    data class Found(val route: RawRoute) : RouteSearchResult
    data class Failed(val reason: RouteFailure) : RouteSearchResult
}

/**
 * Edge-based A* over a [RoadGraph], with surveillance exposure priced into the cost.
 *
 * Search states are *directed edges*, not nodes. That costs roughly twice the memory of a
 * node-based search and buys two things a driver notices: turn penalties (which need to
 * know where you came from) and u-turn suppression.
 *
 * The cost of traversing an edge is
 *
 * ```
 * seconds + lambda * exposureWeight
 * ```
 *
 * where `lambda` is how many seconds of detour the driver is willing to spend to dodge one
 * fully-covering plate reader. Because the penalty is never negative, the plain
 * distance/speed heuristic stays admissible and A* still returns the true optimum for the
 * combined cost.
 */
class Router(
    private val graph: RoadGraph,
    private val exposure: ExposureIndex,
    private val options: RoutingOptions = RoutingOptions(),
) {

    private val maxSpeedMetersPerSecond = Speeds.MAX_PLAUSIBLE_KPH / 3.6

    /**
     * @param originSnapMeters how far from a road the start may sit. Worth widening when the
     *   start came from a low-confidence location fix; see [SnapRadius].
     */
    fun route(
        origin: LatLon,
        destination: LatLon,
        lambdaSeconds: Double,
        originSnapMeters: Double = options.maxSnapMeters,
    ): RouteSearchResult {
        // Distinguishing "we have no map here" from "you are not near a road" matters: the
        // first is a failed download the driver can retry, the second is about where they
        // are standing, and telling them the wrong one sends them chasing the wrong fix.
        if (graph.isEmpty) return RouteSearchResult.Failed(RouteFailure.NO_MAP_DATA)

        val originSnaps = graph.snap(origin, maxOf(originSnapMeters, options.maxSnapMeters))
        if (originSnaps.isEmpty()) return RouteSearchResult.Failed(RouteFailure.ORIGIN_UNREACHABLE)

        val destinationSnaps = graph.snap(destination, options.maxSnapMeters)
        if (destinationSnaps.isEmpty()) return RouteSearchResult.Failed(RouteFailure.DESTINATION_UNREACHABLE)

        // Where the trip may end: how far along each candidate edge to stop, NaN meaning
        // "not an arrival edge". A flat array rather than a map because the router's inner
        // loop consults it on every single relaxation.
        val goalOffsets = DoubleArray(graph.edgeCount) { Double.NaN }
        for (snap in destinationSnaps) {
            val edge = graph.edges[snap.edgeIndex]
            goalOffsets[snap.edgeIndex] = snap.alongMeters
            // The same tarmac travelled the other way is an equally valid arrival.
            if (edge.reverseIndex >= 0 && goalOffsets[edge.reverseIndex].isNaN()) {
                goalOffsets[edge.reverseIndex] = edge.lengthMeters - snap.alongMeters
            }
        }
        val goalPoint = destinationSnaps.first().point

        val edgeCount = graph.edgeCount
        val gScore = DoubleArray(edgeCount) { Double.POSITIVE_INFINITY }
        val cameFrom = IntArray(edgeCount) { UNVISITED }
        val heap = IntDoubleHeap(1024)

        var bestGoalCost = Double.POSITIVE_INFINITY
        var bestGoalEdge = -1
        var bestGoalPredecessor = UNVISITED
        var bestGoalStartOffset = 0.0

        // Seed the search from every plausible interpretation of "the driver is here".
        val startOffsets = HashMap<Int, Double>()
        for (snap in originSnaps) {
            val edge = graph.edges[snap.edgeIndex]
            startOffsets.putIfAbsent(snap.edgeIndex, snap.alongMeters)
            if (edge.reverseIndex >= 0) {
                startOffsets.putIfAbsent(edge.reverseIndex, edge.lengthMeters - snap.alongMeters)
            }
        }

        for ((edgeIndex, offset) in startOffsets) {
            val edge = graph.edges[edgeIndex]

            // Origin and destination on the same stretch of road, destination ahead: the
            // whole trip is a partial traversal of one edge and needs no search at all.
            val goalOffset = goalOffsets[edgeIndex]
            if (!goalOffset.isNaN() && goalOffset >= offset) {
                val cost = partialCost(edgeIndex, offset, goalOffset, lambdaSeconds)
                if (cost < bestGoalCost) {
                    bestGoalCost = cost
                    bestGoalEdge = edgeIndex
                    bestGoalPredecessor = START
                    bestGoalStartOffset = offset
                }
            }

            val cost = partialCost(edgeIndex, offset, edge.lengthMeters, lambdaSeconds)
            if (cost < gScore[edgeIndex]) {
                gScore[edgeIndex] = cost
                cameFrom[edgeIndex] = START
                heap.push(edgeIndex, cost + heuristic(edge.toNode, goalPoint))
            }
        }

        var expanded = 0
        while (!heap.isEmpty()) {
            val edgeIndex = heap.pop()
            val priority = heap.lastKey
            val cost = gScore[edgeIndex]
            val edge = graph.edges[edgeIndex]

            // A stale duplicate left over from a since-improved push. Entries carry
            // `g + h`, so the comparison has to add the heuristic back on.
            if (priority > cost + heuristic(edge.toNode, goalPoint) + EPSILON) continue

            // Nothing left in the queue can beat the best arrival found so far.
            if (priority >= bestGoalCost) break

            if (++expanded > options.maxExpandedStates) {
                return if (bestGoalEdge >= 0) {
                    RouteSearchResult.Found(
                        reconstruct(bestGoalEdge, bestGoalPredecessor, cameFrom, bestGoalStartOffset, goalOffsets, startOffsets, bestGoalCost),
                    )
                } else {
                    RouteSearchResult.Failed(RouteFailure.SEARCH_EXHAUSTED)
                }
            }

            val junction = edge.toNode
            val junctionDelay = graph.nodeDelaySeconds[junction]

            graph.forEachOutgoing(junction) { nextIndex ->
                val next = graph.edges[nextIndex]
                val transition = junctionDelay + turnPenalty(edgeIndex, nextIndex)

                // Arriving at a destination that sits mid-edge: pay only as far as the stop.
                val goalOffset = goalOffsets[nextIndex]
                if (!goalOffset.isNaN()) {
                    val arrival = cost + transition + partialCost(nextIndex, 0.0, goalOffset, lambdaSeconds)
                    if (arrival < bestGoalCost) {
                        bestGoalCost = arrival
                        bestGoalEdge = nextIndex
                        bestGoalPredecessor = edgeIndex
                        bestGoalStartOffset = 0.0
                    }
                }

                val tentative = cost + transition + fullCost(nextIndex, lambdaSeconds)
                if (tentative + EPSILON < gScore[nextIndex]) {
                    gScore[nextIndex] = tentative
                    cameFrom[nextIndex] = edgeIndex
                    heap.push(nextIndex, tentative + heuristic(next.toNode, goalPoint))
                }
            }
        }

        if (bestGoalEdge < 0) return RouteSearchResult.Failed(RouteFailure.NO_PATH)
        return RouteSearchResult.Found(
            reconstruct(bestGoalEdge, bestGoalPredecessor, cameFrom, bestGoalStartOffset, goalOffsets, startOffsets, bestGoalCost),
        )
    }

    private fun reconstruct(
        goalEdge: Int,
        goalPredecessor: Int,
        cameFrom: IntArray,
        goalStartOffset: Double,
        goalOffsets: DoubleArray,
        startOffsets: Map<Int, Double>,
        penalizedSeconds: Double,
    ): RawRoute {
        val reversed = ArrayList<Int>()
        reversed.add(goalEdge)

        var current = goalPredecessor
        while (current != START && current != UNVISITED) {
            reversed.add(current)
            current = cameFrom[current]
        }
        reversed.reverse()

        val edgeIndices = reversed.toIntArray()
        val startAlong = if (edgeIndices.size == 1 && goalPredecessor == START) {
            goalStartOffset
        } else {
            startOffsets[edgeIndices.first()] ?: 0.0
        }

        val endAlong = goalOffsets[goalEdge]
        return RawRoute(
            edgeIndices = edgeIndices,
            startAlongMeters = startAlong,
            endAlongMeters = if (endAlong.isNaN()) graph.edges[goalEdge].lengthMeters else endAlong,
            penalizedSeconds = penalizedSeconds,
        )
    }

    /** Cost of traversing a whole edge. */
    private fun fullCost(edgeIndex: Int, lambdaSeconds: Double): Double {
        val edge = graph.edges[edgeIndex]
        return edge.travelSeconds + lambdaSeconds * exposure.edgeWeight[edgeIndex]
    }

    /**
     * Cost of traversing part of an edge.
     *
     * Time scales with the fraction driven, but exposure does not: a camera 20 m from the
     * far end of a road is only passed if the driver actually gets that far, so only the
     * hits inside the traversed span are charged.
     */
    private fun partialCost(edgeIndex: Int, fromMeters: Double, toMeters: Double, lambdaSeconds: Double): Double {
        val edge = graph.edges[edgeIndex]
        val span = (toMeters - fromMeters).coerceAtLeast(0.0)
        val fraction = if (edge.lengthMeters <= 0.0) 0.0 else (span / edge.lengthMeters).coerceIn(0.0, 1.0)
        val seconds = edge.travelSeconds * fraction

        if (lambdaSeconds <= 0.0) return seconds

        var weight = 0.0
        for (hit in exposure.hits(edgeIndex)) {
            if (hit.alongMeters in fromMeters..toMeters) weight += hit.weight
        }
        return seconds + lambdaSeconds * weight
    }

    /**
     * Seconds charged for the manoeuvre between two edges.
     *
     * Turning across a junction genuinely costs time, and pricing it stops the router from
     * proposing zig-zags through a grid that are technically a few metres shorter. The
     * u-turn charge is what keeps a privacy detour from degenerating into "turn around
     * here and come back".
     */
    private fun turnPenalty(fromEdge: Int, toEdge: Int): Double {
        val previous = graph.edges[fromEdge]
        val next = graph.edges[toEdge]

        if (previous.reverseIndex == toEdge) return options.uTurnPenaltySeconds

        // Following a roundabout round is not a turn.
        if (previous.roundabout && next.roundabout) return 0.0

        val turn = abs(angularDifference(previous.endHeading, next.startHeading))
        return when {
            turn < 20.0 -> 0.0
            turn < 45.0 -> 2.0
            turn < 110.0 -> 5.0
            else -> 9.0
        }
    }

    /**
     * Straight-line time to the goal at the highest speed the graph allows.
     *
     * Never over-estimates the true remaining cost, which is what keeps A* optimal.
     */
    private fun heuristic(node: Int, goal: LatLon): Double =
        haversineMeters(graph.position(node), goal) / maxSpeedMetersPerSecond

    private companion object {
        const val UNVISITED = -1
        const val START = -2
        const val EPSILON = 1e-9
    }
}
