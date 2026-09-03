package dev.shadowgps.core

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.routing.Maneuver
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RoutePlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When an instruction is worth giving.
 *
 * These pin the two complaints that the old rules produced: constant "continue straight"
 * on a road that never changed, and "bear right" where the road ran straight on.
 */
class InstructionQualityTest {

    /**
     * Builds OSM elements for a handful of streets.
     *
     * Node identity is by coordinate, so two ways given the same point genuinely meet
     * there. Minting a fresh id per way instead leaves the roads lying on top of each
     * other but unconnected, and nothing routes at all.
     */
    private class Town {
        private val idsByPoint = LinkedHashMap<String, Long>()
        private val nodes = ArrayList<OsmElement>()
        private val ways = ArrayList<OsmElement>()
        private var next = 1L

        fun road(points: List<LatLon>, name: String?): Town {
            val ids = points.map(::nodeAt)
            ways.add(
                OsmElement(
                    type = "way",
                    id = next++,
                    nodes = ids,
                    tags = buildMap {
                        put("highway", "residential")
                        if (name != null) put("name", name)
                    },
                ),
            )
            return this
        }

        fun elements(): List<OsmElement> = nodes + ways

        private fun nodeAt(point: LatLon): Long {
            val key = "%.7f,%.7f".format(point.lat, point.lon)
            return idsByPoint.getOrPut(key) {
                val id = next++
                nodes.add(OsmElement("node", id, lat = point.lat, lon = point.lon))
                id
            }
        }
    }

    private fun straightRunEastward(from: LatLon, segments: Int, metres: Double): List<LatLon> =
        (0..segments).map { destinationPoint(from, bearing = 90.0, meters = metres * it) }

    private fun instructionsFor(town: Town, start: LatLon, end: LatLon) =
        RoutePlanner(GraphBuilder.build(town.elements()), emptyList())
            .plan(start, end, listOf(PrivacyProfile.FASTEST))
            .routes.single().steps

    @Test
    fun `a straight road split into many ways gives one instruction`() {
        // OSM splits long streets constantly. Each split used to be a chance to announce
        // something, which is where the stream of pointless instructions came from.
        val origin = LatLon(35.99, -78.90)
        val points = straightRunEastward(origin, segments = 6, metres = 150.0)

        val town = Town()
            .road(points.subList(0, 3), "Long Road")
            .road(points.subList(2, 5), "Long Road")
            .road(points.subList(4, 7), "Long Road")

        val steps = instructionsFor(town, points.first(), points.last())

        assertEquals(2, steps.size, steps.joinToString { it.instruction })
        assertEquals(Maneuver.DEPART, steps[0].maneuver)
        assertEquals(Maneuver.ARRIVE, steps[1].maneuver)
    }

    @Test
    fun `a missing name on half a street is not a change of road`() {
        // Half a street with the name tag left off is extremely common, and treating that
        // as a new road produced anonymous "Continue straight" instructions.
        val origin = LatLon(35.99, -78.90)
        val points = straightRunEastward(origin, segments = 4, metres = 150.0)

        val town = Town()
            .road(points.subList(0, 3), "Mill Road")
            .road(points.subList(2, 5), null)

        val steps = instructionsFor(town, points.first(), points.last())

        assertTrue(
            steps.none { it.maneuver == Maneuver.CONTINUE },
            steps.joinToString { it.instruction },
        )
        assertEquals(2, steps.size)
    }

    @Test
    fun `a genuine change of street name is announced`() {
        val origin = LatLon(35.99, -78.90)
        val points = straightRunEastward(origin, segments = 4, metres = 150.0)

        val town = Town()
            .road(points.subList(0, 3), "Mill Road")
            .road(points.subList(2, 5), "Bridge Street")
            // A side road, so the junction is a real one with something to decide.
            .road(listOf(points[2], destinationPoint(points[2], bearing = 0.0, meters = 150.0)), "Side Lane")

        val steps = instructionsFor(town, points.first(), points.last())

        val continues = steps.filter { it.maneuver == Maneuver.CONTINUE }
        assertEquals(1, continues.size, steps.joinToString { it.instruction })
        assertEquals("Bridge Street", continues.single().roadName)
    }

    @Test
    fun `a gentle kink in the geometry is not a turn`() {
        // Mapped roads wobble. Measuring the angle across the single vertex pair either
        // side of a junction turned that wobble into "bear right".
        val origin = LatLon(35.99, -78.90)
        val a = origin
        val b = destinationPoint(a, bearing = 90.0, meters = 200.0)
        // Carries on east, but the first few metres kink by 25 degrees.
        val kink = destinationPoint(b, bearing = 65.0, meters = 12.0)
        val c = destinationPoint(kink, bearing = 92.0, meters = 200.0)

        val town = Town()
            .road(listOf(a, b), "Kinked Road")
            .road(listOf(b, kink, c), "Kinked Road")
            .road(listOf(b, destinationPoint(b, bearing = 180.0, meters = 150.0)), "South Lane")

        val steps = instructionsFor(town, a, c)

        assertTrue(
            steps.none { it.maneuver == Maneuver.SLIGHT_RIGHT || it.maneuver == Maneuver.SLIGHT_LEFT },
            steps.joinToString { it.instruction },
        )
    }

    @Test
    fun `a real turn is still announced`() {
        val origin = LatLon(35.99, -78.90)
        val corner = destinationPoint(origin, bearing = 90.0, meters = 300.0)
        val north = destinationPoint(corner, bearing = 0.0, meters = 300.0)

        val town = Town()
            .road(listOf(origin, corner), "Approach Road")
            .road(listOf(corner, north), "North Road")
            .road(listOf(corner, destinationPoint(corner, bearing = 90.0, meters = 200.0)), "East Road")

        val steps = instructionsFor(town, origin, north)

        assertTrue(
            steps.any { it.maneuver == Maneuver.LEFT },
            steps.joinToString { it.instruction },
        )
    }

    @Test
    fun `a forced corner is announced even with nowhere else to go`() {
        // No junction, no choice — but the road turns ninety degrees and the driver has to
        // know. Suppressing this was a bug in the first cut of the "no choice" rule.
        val origin = LatLon(35.99, -78.90)
        val corner = destinationPoint(origin, bearing = 90.0, meters = 300.0)
        val north = destinationPoint(corner, bearing = 0.0, meters = 300.0)

        val town = Town()
            .road(listOf(origin, corner), "Bent Road")
            .road(listOf(corner, north), "Bent Road")

        val steps = instructionsFor(town, origin, north)

        assertTrue(
            steps.any { it.maneuver == Maneuver.LEFT },
            steps.joinToString { it.instruction },
        )
    }

    @Test
    fun `a fork gets a side even though neither branch is a turn`() {
        // The one case where a small angle genuinely matters: two ways on, both nearly
        // straight, so "carry on" would not say which.
        val origin = LatLon(35.99, -78.90)
        val split = destinationPoint(origin, bearing = 90.0, meters = 300.0)
        val leftBranch = destinationPoint(split, bearing = 80.0, meters = 300.0)
        val rightBranch = destinationPoint(split, bearing = 100.0, meters = 300.0)

        val town = Town()
            .road(listOf(origin, split), "Approach Road")
            .road(listOf(split, leftBranch), "Left Fork")
            .road(listOf(split, rightBranch), "Right Fork")

        val steps = instructionsFor(town, origin, rightBranch)

        assertTrue(
            steps.any { it.maneuver == Maneuver.SLIGHT_RIGHT },
            steps.joinToString { it.instruction },
        )
    }
}
