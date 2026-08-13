package dev.shadowgps.core

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.METERS_PER_DEGREE_LAT
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.routing.Directions
import dev.shadowgps.core.routing.Maneuver
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RoutePlanner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DirectionsTest {

    private val grid = GraphBuilder.build(Fixtures.grid(rows = 4, cols = 4))

    @Test
    fun `a straight run is one instruction plus arrival`() {
        val planner = RoutePlanner(grid, emptyList())
        val route = planner.plan(
            Fixtures.position(0, 0),
            Fixtures.position(0, 3),
            listOf(PrivacyProfile.FASTEST),
        ).routes.single()

        assertEquals(2, route.steps.size)
        assertEquals(Maneuver.DEPART, route.steps[0].maneuver)
        assertEquals(Maneuver.ARRIVE, route.steps[1].maneuver)
        assertTrue(route.steps[0].instruction.startsWith("Head east"), route.steps[0].instruction)
        assertEquals("A Street", route.steps[0].roadName)
    }

    @Test
    fun `a corner produces a single turn instruction`() {
        val planner = RoutePlanner(grid, emptyList())
        val route = planner.plan(
            Fixtures.position(0, 0),
            Fixtures.position(2, 2),
            listOf(PrivacyProfile.FASTEST),
        ).routes.single()

        assertEquals(3, route.steps.size, route.steps.joinToString { it.instruction })
        assertTrue(
            route.steps[1].maneuver in setOf(Maneuver.LEFT, Maneuver.RIGHT),
            "expected a turn, got ${route.steps[1].maneuver}",
        )
        assertTrue(route.steps[1].instruction.startsWith("Turn "), route.steps[1].instruction)
        assertNotNull(route.steps[1].roadName)
    }

    @Test
    fun `step distances add up to the route length`() {
        val planner = RoutePlanner(grid, emptyList())
        val route = planner.plan(
            Fixtures.position(0, 0),
            Fixtures.position(3, 3),
            listOf(PrivacyProfile.FASTEST),
        ).routes.single()

        assertEquals(route.distanceMeters, route.steps.sumOf { it.distanceMeters }, 1.0)
        // Each step must start further along than the one before it.
        val offsets = route.steps.map { it.startAlongRouteMeters }
        assertEquals(offsets.sorted(), offsets)
    }

    @Test
    fun `cameras are attributed to the step that drives past them`() {
        val camera = Fixtures.alpr("node/1", Fixtures.midBlock(2, 0, 2, 1))
        val planner = RoutePlanner(grid, listOf(camera))

        val route = planner.plan(
            Fixtures.position(2, 0),
            Fixtures.position(2, 3),
            listOf(PrivacyProfile.FASTEST),
        ).routes.single()

        assertEquals(1, route.exposure.totalCount)
        assertEquals(1, route.steps.sumOf { it.detectorCount })
        assertEquals(1, route.steps.first().detectorCount)
    }

    @Test
    fun `turn classification maps angles to manoeuvres`() {
        assertEquals(Maneuver.CONTINUE, Directions.classify(5.0, isUTurn = false))
        assertEquals(Maneuver.SLIGHT_RIGHT, Directions.classify(30.0, isUTurn = false))
        assertEquals(Maneuver.SLIGHT_LEFT, Directions.classify(-30.0, isUTurn = false))
        assertEquals(Maneuver.RIGHT, Directions.classify(90.0, isUTurn = false))
        assertEquals(Maneuver.LEFT, Directions.classify(-90.0, isUTurn = false))
        assertEquals(Maneuver.SHARP_RIGHT, Directions.classify(140.0, isUTurn = false))
        assertEquals(Maneuver.U_TURN, Directions.classify(175.0, isUTurn = false))
        assertEquals(Maneuver.U_TURN, Directions.classify(5.0, isUTurn = true))
    }

    @Test
    fun `compass wording covers all eight points`() {
        assertEquals("north", Directions.compass(0.0))
        assertEquals("north-east", Directions.compass(45.0))
        assertEquals("east", Directions.compass(90.0))
        assertEquals("south", Directions.compass(180.0))
        assertEquals("west", Directions.compass(271.0))
        assertEquals("north", Directions.compass(359.0))
    }

    @Test
    fun `ordinals read naturally`() {
        assertEquals("1st", Directions.ordinal(1))
        assertEquals("2nd", Directions.ordinal(2))
        assertEquals("3rd", Directions.ordinal(3))
        assertEquals("4th", Directions.ordinal(4))
    }

    @Test
    fun `a roundabout collapses into one exit instruction`() {
        val planner = RoutePlanner(roundaboutTown(), emptyList())

        val route = planner.plan(northApproach, westApproach, listOf(PrivacyProfile.FASTEST))
            .routes.single()

        val roundaboutStep = route.steps.firstOrNull { it.maneuver == Maneuver.ROUNDABOUT }
        assertNotNull(roundaboutStep, route.steps.joinToString { it.instruction })
        assertEquals(1, roundaboutStep!!.roundaboutExit)
        assertTrue(
            roundaboutStep.instruction.contains("take the 1st exit"),
            roundaboutStep.instruction,
        )
        assertEquals("West Road", roundaboutStep.roadName, "the exit road names the manoeuvre")
        // The ring itself must not generate a turn instruction per segment.
        assertTrue(route.steps.size <= 4, route.steps.joinToString { it.instruction })
    }

    @Test
    fun `exits are counted past the ones the route drives by`() {
        val planner = RoutePlanner(roundaboutTown(), emptyList())

        val route = planner.plan(northApproach, eastApproach, listOf(PrivacyProfile.FASTEST))
            .routes.single()

        // Circulation runs north -> west -> south -> east, so leaving eastbound means
        // driving past the west and south exits first.
        val roundaboutStep = route.steps.first { it.maneuver == Maneuver.ROUNDABOUT }
        assertEquals(3, roundaboutStep.roundaboutExit)
        assertTrue(roundaboutStep.instruction.contains("take the 3rd exit"), roundaboutStep.instruction)
    }

    private val center = LatLon(35.99, -78.89)
    private val northApproach = destinationPoint(center, 0.0, 400.0)
    private val eastApproach = destinationPoint(center, 90.0, 400.0)
    private val westApproach = destinationPoint(center, 270.0, 400.0)

    /**
     * A four-way roundabout with straight approach roads at each compass point.
     *
     * The ring runs anticlockwise as seen from above (north -> west -> south -> east), so
     * entering from the north and leaving east passes three ring segments and one exit.
     */
    private fun roundaboutTown(): dev.shadowgps.core.graph.RoadGraph {
        val ringRadius = 30.0
        val ring = listOf(0.0, 270.0, 180.0, 90.0).map { destinationPoint(center, it, ringRadius) }
        val approaches = listOf(0.0, 90.0, 180.0, 270.0).map { destinationPoint(center, it, 400.0) }

        val elements = ArrayList<OsmElement>()
        // Ring nodes 1..4 (north, west, south, east), approach ends 11..14.
        ring.forEachIndexed { i, p -> elements.add(OsmElement("node", (i + 1).toLong(), lat = p.lat, lon = p.lon)) }
        approaches.forEachIndexed { i, p -> elements.add(OsmElement("node", (i + 11).toLong(), lat = p.lat, lon = p.lon)) }

        elements.add(
            OsmElement(
                "way", 100,
                nodes = listOf(1, 2, 3, 4, 1),
                tags = mapOf("highway" to "tertiary", "junction" to "roundabout"),
            ),
        )

        val names = listOf("North Road", "East Road", "South Road", "West Road")
        // Approach i attaches to the ring node at the same compass point:
        // north approach -> ring node 1, east -> 4, south -> 3, west -> 2.
        val ringForApproach = listOf(1L, 4L, 3L, 2L)
        names.forEachIndexed { i, name ->
            elements.add(
                OsmElement(
                    "way", 200L + i,
                    nodes = listOf(11L + i, ringForApproach[i]),
                    tags = mapOf("highway" to "tertiary", "name" to name),
                ),
            )
        }

        return GraphBuilder.build(elements)
    }

    @Test
    fun `grid spacing fixture is actually the requested distance`() {
        val a = Fixtures.position(0, 0)
        val b = Fixtures.position(1, 0)
        assertEquals(
            Fixtures.DEFAULT_SPACING_METERS,
            (b.lat - a.lat) * METERS_PER_DEGREE_LAT,
            0.5,
        )
    }
}
