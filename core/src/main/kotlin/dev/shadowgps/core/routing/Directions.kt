package dev.shadowgps.core.routing

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.signedTurnDegrees
import dev.shadowgps.core.graph.RoadEdge
import dev.shadowgps.core.graph.RoadGraph
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Turns a chain of directed edges into human instructions.
 *
 * A new instruction starts wherever the driver has to decide something: a real turn, a
 * change of road name, or a roundabout. Everything between two decisions collapses into
 * one step, which is why a straight run through twenty mapped junctions reads as a single
 * "continue for 2 km".
 */
object Directions {

    /** Below this the road is just bending and needs no instruction. */
    private const val BEND_DEGREES = 22.0

    /** A name change on an almost-straight road is worth mentioning, but only just. */
    private const val NAME_CHANGE_DEGREES = 10.0

    fun build(
        graph: RoadGraph,
        edgeIndices: IntArray,
        startAlongMeters: Double,
        endAlongMeters: Double,
        edgeDurations: DoubleArray,
        edgeDistances: DoubleArray,
        edgeStartOffsets: DoubleArray,
    ): List<RouteStep> {
        if (edgeIndices.isEmpty()) return emptyList()

        val steps = ArrayList<RouteStep>()
        val firstEdge = graph.edges[edgeIndices.first()]

        var groupStart = 0
        var pendingManeuver = Maneuver.DEPART
        var pendingExit: Int? = null
        var pendingPoint = pointAt(firstEdge, startAlongMeters)

        var index = 1
        while (index <= edgeIndices.size) {
            val atEnd = index == edgeIndices.size
            val previous = graph.edges[edgeIndices[index - 1]]

            if (atEnd) {
                steps.add(
                    makeStep(
                        graph = graph,
                        edgeIndices = edgeIndices,
                        from = groupStart,
                        toExclusive = index,
                        maneuver = pendingManeuver,
                        roundaboutExit = pendingExit,
                        startPoint = pendingPoint,
                        edgeDurations = edgeDurations,
                        edgeDistances = edgeDistances,
                        edgeStartOffsets = edgeStartOffsets,
                    ),
                )
                break
            }

            val next = graph.edges[edgeIndices[index]]

            // Entering a roundabout: swallow the whole circulation into one instruction and
            // work out which exit the route leaves by.
            if (next.roundabout && !previous.roundabout) {
                steps.add(
                    makeStep(
                        graph, edgeIndices, groupStart, index, pendingManeuver, pendingExit, pendingPoint,
                        edgeDurations, edgeDistances, edgeStartOffsets,
                    ),
                )

                val exitInfo = traverseRoundabout(graph, edgeIndices, index)
                groupStart = index
                pendingManeuver = Maneuver.ROUNDABOUT
                pendingExit = exitInfo.exitNumber
                pendingPoint = next.startPoint
                index = exitInfo.leaveIndex
                continue
            }

            val turn = signedTurnDegrees(previous.endHeading, next.startHeading)
            val nameChanged = previous.displayName != next.displayName
            val isDecision = abs(turn) >= BEND_DEGREES ||
                (nameChanged && abs(turn) >= NAME_CHANGE_DEGREES) ||
                (previous.roundabout && !next.roundabout)

            if (isDecision) {
                // "Take the 2nd exit onto Mill Road" needs the name of the road being left
                // *onto*, which is the one after the ring, not the ring itself.
                val exitingRoundabout = previous.roundabout && !next.roundabout
                steps.add(
                    makeStep(
                        graph, edgeIndices, groupStart, index, pendingManeuver, pendingExit, pendingPoint,
                        edgeDurations, edgeDistances, edgeStartOffsets,
                        roadNameOverride = if (pendingManeuver == Maneuver.ROUNDABOUT) next.displayName else null,
                    ),
                )
                groupStart = index
                // Leaving a roundabout is not a second manoeuvre; the exit instruction
                // already told the driver where to go.
                pendingManeuver = if (exitingRoundabout) {
                    Maneuver.CONTINUE
                } else {
                    classify(turn, previous.reverseIndex == next.index)
                }
                pendingExit = null
                pendingPoint = next.startPoint
            }

            index++
        }

        val lastEdge = graph.edges[edgeIndices.last()]
        steps.add(
            RouteStep(
                maneuver = Maneuver.ARRIVE,
                instruction = "You have arrived at your destination",
                roadName = lastEdge.displayName,
                distanceMeters = 0.0,
                durationSeconds = 0.0,
                startAlongRouteMeters = edgeStartOffsets.last() + edgeDistances.last(),
                startPoint = pointAt(lastEdge, endAlongMeters),
            ),
        )
        return steps
    }

    private data class RoundaboutExit(val exitNumber: Int, val leaveIndex: Int)

    /**
     * Counts exits from the point the route enters a roundabout until it leaves.
     *
     * Every junction on the ring that offers a way off the ring is an exit, including ones
     * the route drives past, which is exactly how a driver counts them.
     */
    private fun traverseRoundabout(graph: RoadGraph, edgeIndices: IntArray, enterIndex: Int): RoundaboutExit {
        var exits = 0
        var i = enterIndex
        while (i < edgeIndices.size) {
            val edge = graph.edges[edgeIndices[i]]
            if (!edge.roundabout) break

            var offersExit = false
            graph.forEachOutgoing(edge.toNode) { candidate ->
                if (!graph.edges[candidate].roundabout) offersExit = true
            }
            if (offersExit) exits++

            val nextIsRoundabout = i + 1 < edgeIndices.size && graph.edges[edgeIndices[i + 1]].roundabout
            if (!nextIsRoundabout) {
                i++
                break
            }
            i++
        }
        return RoundaboutExit(exitNumber = exits.coerceAtLeast(1), leaveIndex = i)
    }

    private fun makeStep(
        graph: RoadGraph,
        edgeIndices: IntArray,
        from: Int,
        toExclusive: Int,
        maneuver: Maneuver,
        roundaboutExit: Int?,
        startPoint: LatLon,
        edgeDurations: DoubleArray,
        edgeDistances: DoubleArray,
        edgeStartOffsets: DoubleArray,
        roadNameOverride: String? = null,
    ): RouteStep {
        var distance = 0.0
        var duration = 0.0
        for (i in from until toExclusive) {
            distance += edgeDistances[i]
            duration += edgeDurations[i]
        }

        val leadEdge = graph.edges[edgeIndices[from]]
        val roadName = roadNameOverride ?: (from until toExclusive)
            .asSequence()
            .map { graph.edges[edgeIndices[it]] }
            .mapNotNull { it.displayName }
            .firstOrNull()

        return RouteStep(
            maneuver = maneuver,
            instruction = phrase(maneuver, roadName, roundaboutExit, leadEdge),
            roadName = roadName,
            distanceMeters = distance,
            durationSeconds = duration,
            startAlongRouteMeters = edgeStartOffsets[from],
            startPoint = startPoint,
            roundaboutExit = roundaboutExit,
        )
    }

    fun classify(turnDegrees: Double, isUTurn: Boolean): Maneuver {
        if (isUTurn) return Maneuver.U_TURN
        val magnitude = abs(turnDegrees)
        val right = turnDegrees > 0
        return when {
            magnitude < BEND_DEGREES -> Maneuver.CONTINUE
            magnitude < 45 -> if (right) Maneuver.SLIGHT_RIGHT else Maneuver.SLIGHT_LEFT
            magnitude < 120 -> if (right) Maneuver.RIGHT else Maneuver.LEFT
            magnitude < 165 -> if (right) Maneuver.SHARP_RIGHT else Maneuver.SHARP_LEFT
            else -> Maneuver.U_TURN
        }
    }

    private fun phrase(maneuver: Maneuver, roadName: String?, roundaboutExit: Int?, leadEdge: RoadEdge): String {
        val onto = roadName?.let { " onto $it" } ?: ""
        return when (maneuver) {
            Maneuver.DEPART -> "Head ${compass(leadEdge.startHeading)}" + (roadName?.let { " on $it" } ?: "")
            Maneuver.CONTINUE -> roadName?.let { "Continue on $it" } ?: "Continue straight"
            Maneuver.SLIGHT_LEFT -> "Bear left$onto"
            Maneuver.LEFT -> "Turn left$onto"
            Maneuver.SHARP_LEFT -> "Turn sharply left$onto"
            Maneuver.SLIGHT_RIGHT -> "Bear right$onto"
            Maneuver.RIGHT -> "Turn right$onto"
            Maneuver.SHARP_RIGHT -> "Turn sharply right$onto"
            Maneuver.U_TURN -> "Make a U-turn$onto"
            Maneuver.ROUNDABOUT -> "At the roundabout, take the ${ordinal(roundaboutExit ?: 1)} exit$onto"
            Maneuver.ARRIVE -> "You have arrived at your destination"
        }
    }

    fun compass(bearing: Double): String {
        val points = listOf(
            "north", "north-east", "east", "south-east",
            "south", "south-west", "west", "north-west",
        )
        val index = ((bearing % 360 + 360) % 360 / 45.0).roundToInt() % 8
        return points[index]
    }

    fun ordinal(n: Int): String = when (n) {
        1 -> "1st"
        2 -> "2nd"
        3 -> "3rd"
        21 -> "21st"
        22 -> "22nd"
        23 -> "23rd"
        else -> "${n}th"
    }

    private fun pointAt(edge: RoadEdge, alongMeters: Double): LatLon =
        dev.shadowgps.core.geo.interpolateAlongCoords(edge.coords, alongMeters)
}
