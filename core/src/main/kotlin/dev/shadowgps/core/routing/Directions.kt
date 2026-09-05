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
     * How much straighter the route has to run than its rival before it is simply the road
     * carrying on, rather than one side of a fork.
     *
     * This is what separates a fork from a side turning. At a fork the two branches leave at
     * comparable angles and neither is obviously "straight on" — that is the whole reason
     * the driver needs telling which side to take. A lane or slip road peeling off at twenty
     * degrees from a road that continues dead ahead is not that: there is nothing to choose,
     * and calling it a fork is where the phantom "bear right" on an unchanging road came
     * from. Deliberately one-sided — when the *route* is the more angled of the two, the
     * driver is leaving the straight line and does need to hear about it.
     */
    private const val OBVIOUS_CONTINUATION_DEGREES = 10.0

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
        val fork = forkSideAt(alternatives, next, turn)
        if (fork != null) return Decision(turn, isManeuver = true, forkSide = fork)

        // Same physical way, or a name that merely went missing, is not a change of road.
        val sameWay = previous.wayId == next.wayId
        val before = previous.displayName
        val after = next.displayName
        val nameChanged = !sameWay && before != null && after != null && before != after
        return Decision(turn, isManeuver = nameChanged)
    }

    /** Another way out of a junction, and how far off the approach it leaves. */
    private class Branch(val edge: RoadEdge, val turnDegrees: Double)

    /** The other ways out of the junction, excluding the u-turn and the one taken. */
    private fun alternativesAt(graph: RoadGraph, previous: RoadEdge, next: RoadEdge): List<Branch> {
        val approach = previous.headingApproaching(TURN_BASELINE_METERS)
        val options = ArrayList<Branch>(3)
        graph.forEachOutgoing(previous.toNode) { candidate ->
            if (candidate == previous.reverseIndex || candidate == next.index) return@forEachOutgoing
            val edge = graph.edges[candidate]
            options.add(Branch(edge, signedTurnDegrees(approach, edge.headingLeaving(TURN_BASELINE_METERS))))
        }
        return options
    }

    /**
     * Which side of a fork the route takes, or null when this junction is not a fork.
     *
     * A fork is a junction where the road effectively ends and two comparable roads carry
     * on, so "continue" would leave the driver guessing. Telling that apart from an ordinary
     * side turning is the entire job here, and getting it wrong in the permissive direction
     * is what produced "bear right" on a road that visibly runs straight: any lane, driveway
     * or slip road leaving at a shallow angle used to qualify.
     *
     * Two things now have to hold beyond the branches being close together in heading.
     *
     *  - **Neither branch is the obvious continuation.** If the route runs nearly straight
     *    on and its rival departs at a noticeably greater angle, there is nothing to choose
     *    between: the driver simply carries on, and the rival is a turning they are passing.
     *  - **The branches are comparable roads.** A road does not fork into a driveway or a
     *    parking aisle. Where the rival is a rank below anything worth calling a road, or
     *    well below the road being taken, it is an access track and not a choice.
     */
    private fun forkSideAt(alternatives: List<Branch>, next: RoadEdge, turn: Double): Maneuver? {
        // The nearest rival in heading is the only one that can make this ambiguous.
        val rival = alternatives.minByOrNull { abs(it.turnDegrees) } ?: return null
        val rivalTurn = rival.turnDegrees

        if (abs(rivalTurn) >= BEND_DEGREES) return null
        if (abs(signedTurnDegrees(rivalTurn, turn)) >= FORK_SEPARATION_DEGREES) return null
        // Identical headings offer the driver nothing to act on either way.
        if (abs(turn - rivalTurn) <= 1.0) return null

        if (abs(rivalTurn) - abs(turn) > OBVIOUS_CONTINUATION_DEGREES) return null
        if (!comparableRoads(rival.edge, next)) return null

        return if (turn > rivalTurn) Maneuver.SLIGHT_RIGHT else Maneuver.SLIGHT_LEFT
    }

    /** Whether two branches are the same sort of road, so that choosing between them is real. */
    private fun comparableRoads(rival: RoadEdge, taken: RoadEdge): Boolean {
        val rivalRank = roadRank(rival.highway)
        return rivalRank >= MINIMUM_FORK_RANK && rivalRank >= roadRank(taken.highway) - 1
    }

    /**
     * Rough importance of a road class.
     *
     * Only the ordering matters, and only for deciding whether two ways out of a junction
     * are peers. Links sit one below the class they serve, which is what keeps a motorway
     * and its slip road reading as a genuine fork while a service road never does.
     */
    private fun roadRank(highway: String): Int = when (highway) {
        "motorway", "trunk" -> 5
        "motorway_link", "trunk_link", "primary" -> 4
        "primary_link", "secondary" -> 3
        "secondary_link", "tertiary", "tertiary_link" -> 2
        "unclassified", "residential", "living_street", "road" -> 1
        // service, track, driveway, parking aisle and everything else.
        else -> 0
    }

    /** Below this a way is access rather than a road, and never one side of a fork. */
    private const val MINIMUM_FORK_RANK = 1

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
        val roadName = roadNameOverride
            ?: pickRoadName(graph, edgeIndices, from, toExclusive, edgeDistances, distance)

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

    /**
     * Which road an instruction should name.
     *
     * A step can span several named roads, so simply taking the first name that appears
     * anywhere in it can announce a street well past the junction — "turn left onto Oak
     * Avenue" when Oak Avenue is the road after the unnamed one being turned onto. Two
     * candidates matter and they are not always the same: the road at the junction, which
     * is what the sign says and what the driver is looking for, and the road the step is
     * mostly spent on, which is what they will be driving along.
     *
     * The junction wins, because that is the decision being described — unless it is a
     * brief stub of a slip road or a close, in which case naming it would send the driver
     * hunting for a sign that is barely there, and the road it leads onto is the useful
     * answer.
     */
    private fun pickRoadName(
        graph: RoadGraph,
        edgeIndices: IntArray,
        from: Int,
        toExclusive: Int,
        edgeDistances: DoubleArray,
        stepDistance: Double,
    ): String? {
        val distanceByName = LinkedHashMap<String, Double>()
        for (i in from until toExclusive) {
            val name = graph.edges[edgeIndices[i]].displayName ?: continue
            distanceByName[name] = (distanceByName[name] ?: 0.0) + edgeDistances[i]
        }
        if (distanceByName.isEmpty()) return null

        val dominant = distanceByName.maxByOrNull { it.value }?.key
        val atJunction = graph.edges[edgeIndices[from]].displayName ?: return dominant

        val share = distanceByName[atJunction] ?: 0.0
        val worthNaming = share >= MINIMUM_NAMED_STRETCH_METERS ||
            (stepDistance > 0 && share / stepDistance >= MINIMUM_NAMED_SHARE)
        return if (worthNaming) atJunction else dominant
    }

    /** A named stretch shorter than this is a stub, not the road the driver wants told. */
    private const val MINIMUM_NAMED_STRETCH_METERS = 40.0

    /** …or it can earn its place by being most of the step regardless. */
    private const val MINIMUM_NAMED_SHARE = 0.25

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
