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
    private const val BEND_DEGREES = 28.0

    /**
     * Distance over which a turn angle is measured.
     *
     * Comparing the single vertex pair either side of a junction measures mapping noise as
     * much as geometry; over twenty-five metres the noise averages out and what is left is
     * the turn a driver would actually perceive.
     */
    private const val TURN_BASELINE_METERS = 25.0

    /**
     * Two ways out of a junction closer than this in heading are a fork the driver has to
     * choose between, so one gets a "keep left"/"keep right" even though neither is a turn.
     */
    private const val FORK_SEPARATION_DEGREES = 45.0

    /**
     * A corner this sharp is announced even where the driver has no alternative.
     *
     * Well above [BEND_DEGREES]: a road that merely bends round without a junction needs no
     * commentary, but one that turns ninety degrees does, choice or not.
     */
    private const val FORCED_TURN_DEGREES = 60.0

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

            val decision = decisionAt(graph, previous, next)
            val turn = decision.turnDegrees

            if (decision.isManeuver) {
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
                pendingManeuver = when {
                    exitingRoundabout -> Maneuver.CONTINUE
                    decision.forkSide != null -> decision.forkSide
                    else -> classify(turn, previous.reverseIndex == next.index)
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

    /** Whether a junction needs telling the driver about, and what to say. */
    data class Decision(
        val turnDegrees: Double,
        val isManeuver: Boolean,
        /** Set for a fork, where the instruction is "keep left"/"keep right", not a turn. */
        val forkSide: Maneuver? = null,
    )

    /**
     * Decides whether a junction earns an instruction.
     *
     * Three rules, each removing a class of instruction that was being given for no reason:
     *
     *  - **No choice, no instruction.** If the only way on is the way the driver is already
     *    going, saying anything is noise however much the road bends. Roads are split into
     *    edges at every junction, so a curving street generates plenty of angle with no
     *    decision attached to it.
     *  - **A road is the same road across a missing tag.** OSM frequently splits a street
     *    and leaves the name off one half. Treating name-to-null as a change is what
     *    produced a stream of anonymous "Continue straight" instructions on a street whose
     *    name never actually changed.
     *  - **A fork still needs a side**, even when neither branch is a turn — that is the
     *    one case where a small angle genuinely matters.
     */
    fun decisionAt(graph: RoadGraph, previous: RoadEdge, next: RoadEdge): Decision {
        val turn = signedTurnDegrees(
            previous.headingApproaching(TURN_BASELINE_METERS),
            next.headingLeaving(TURN_BASELINE_METERS),
        )

        // Leaving a roundabout is always worth marking, choice or not.
        if (previous.roundabout && !next.roundabout) return Decision(turn, isManeuver = true)

        val alternatives = alternativesAt(graph, previous, next)
        if (alternatives.isEmpty()) {
            // Nowhere else to go, so no decision to announce — unless the road genuinely
            // turns a corner, which the driver has to be told about whether or not there
            // was any choice about it.
            return Decision(turn, isManeuver = abs(turn) >= FORCED_TURN_DEGREES)
        }

        if (abs(turn) >= BEND_DEGREES) return Decision(turn, isManeuver = true)

        // A fork: another way out of this junction is also near-straight, so "carry on"
        // would not tell the driver which side to take. Checked before the name, because
        // which side to take matters more than what the road is called.
        val rival = alternatives.minByOrNull { abs(it) }
        if (rival != null) {
            val ambiguous = abs(rival) < BEND_DEGREES &&
                abs(signedTurnDegrees(rival, turn)) < FORK_SEPARATION_DEGREES &&
                abs(turn - rival) > 1.0
            if (ambiguous) {
                return Decision(
                    turnDegrees = turn,
                    isManeuver = true,
                    forkSide = if (turn > rival) Maneuver.SLIGHT_RIGHT else Maneuver.SLIGHT_LEFT,
                )
            }
        }

        // Same physical way, or a name that merely went missing, is not a change of road.
        val sameWay = previous.wayId == next.wayId
        val before = previous.displayName
        val after = next.displayName
        val nameChanged = !sameWay && before != null && after != null && before != after
        return Decision(turn, isManeuver = nameChanged)
    }

    /** Turn angles of the other ways out of the junction, excluding the u-turn and the one taken. */
    private fun alternativesAt(graph: RoadGraph, previous: RoadEdge, next: RoadEdge): List<Double> {
        val approach = previous.headingApproaching(TURN_BASELINE_METERS)
        val options = ArrayList<Double>(3)
        graph.forEachOutgoing(previous.toNode) { candidate ->
            if (candidate == previous.reverseIndex || candidate == next.index) return@forEachOutgoing
            options.add(signedTurnDegrees(approach, graph.edges[candidate].headingLeaving(TURN_BASELINE_METERS)))
        }
        return options
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
