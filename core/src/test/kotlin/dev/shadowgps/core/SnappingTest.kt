package dev.shadowgps.core

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.bearingDegrees
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.osm.parseOverpassResponse
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RouteFailure
import dev.shadowgps.core.routing.RoutePlanner
import dev.shadowgps.core.routing.SnapRadius
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Everything about getting a trip started, which is where the app meets a real GPS fix and
 * a real OSM extract rather than a tidy grid.
 */
class SnappingTest {

    private var nextId = 1L

    private fun road(from: LatLon, to: LatLon, name: String): List<OsmElement> {
        val a = nextId++
        val b = nextId++
        val way = nextId++
        return listOf(
            OsmElement("node", a, lat = from.lat, lon = from.lon),
            OsmElement("node", b, lat = to.lat, lon = to.lon),
            OsmElement("way", way, nodes = listOf(a, b), tags = mapOf("highway" to "residential", "name" to name)),
        )
    }

    @Test
    fun `a road just past the first probe is still found`() {
        // The regression that made this fail in the field: a long diagonal road whose
        // *bounding box* contains the query point but which physically passes 400 m away.
        // Searching only the first neighbourhood finds that decoy, finds it unusable, and
        // used to stop there — reporting no road while a perfectly good one sat 150 m off.
        val diagonalStart = LatLon(35.99, -78.90)
        val diagonalEnd = LatLon(36.00, -78.89)
        val midpoint = LatLon(
            (diagonalStart.lat + diagonalEnd.lat) / 2,
            (diagonalStart.lon + diagonalEnd.lon) / 2,
        )
        val across = bearingDegrees(diagonalStart, diagonalEnd) + 90.0
        val stranded = destinationPoint(midpoint, across, 400.0)

        val realRoadCentre = destinationPoint(stranded, bearing = 0.0, meters = 150.0)
        val elements = road(diagonalStart, diagonalEnd, "Long Diagonal") +
            road(
                destinationPoint(realRoadCentre, 270.0, 100.0),
                destinationPoint(realRoadCentre, 90.0, 100.0),
                "Nearby Street",
            )

        val graph = GraphBuilder.build(elements)

        // Sanity: the decoy really is in range of the query point's own grid cell.
        assertTrue(
            graph.edges.any { it.displayName == "Long Diagonal" && it.bounds.contains(stranded) },
            "the decoy's bounding box should contain the query point",
        )

        val snap = graph.snapNearest(stranded, maxDistanceMeters = 200.0)

        assertNotNull(snap, "a road 150 m away should be found within a 200 m allowance")
        assertEquals("Nearby Street", graph.edges[snap!!.edgeIndex].displayName)
        assertEquals(150.0, snap.distanceMeters, 5.0)
    }

    @Test
    fun `snapping still refuses a road beyond the allowance`() {
        val graph = GraphBuilder.build(
            road(LatLon(35.99, -78.90), LatLon(35.99, -78.89), "Far Street"),
        )
        val stranded = destinationPoint(LatLon(35.99, -78.895), bearing = 0.0, meters = 600.0)

        assertNull(graph.snapNearest(stranded, maxDistanceMeters = 200.0))
        // …but a fix that admits to being inaccurate can reach it.
        assertNotNull(graph.snapNearest(stranded, maxDistanceMeters = 900.0))
    }

    @Test
    fun `snapping returns distinct roads rather than both directions of one`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 3, cols = 3))
        val junction = Fixtures.position(1, 1)

        val snaps = graph.snap(junction, maxDistanceMeters = 200.0, limit = 4)

        assertEquals(4, snaps.size)
        val roads = snaps.map { snap ->
            val edge = graph.edges[snap.edgeIndex]
            minOf(edge.index, if (edge.reverseIndex >= 0) edge.reverseIndex else edge.index)
        }
        assertEquals(roads.size, roads.distinct().size, "each candidate should be a different road")
    }

    @Test
    fun `snapping is nearest first`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 3, cols = 3))

        // Just off a junction: 10 m from the east-west street, 30 m from the avenue, so
        // the ordering between them is unambiguous.
        val offJunction = destinationPoint(
            destinationPoint(Fixtures.position(1, 1), bearing = 90.0, meters = 30.0),
            bearing = 0.0,
            meters = 10.0,
        )

        val snaps = graph.snap(offJunction, maxDistanceMeters = 200.0, limit = 4)

        assertTrue(snaps.size >= 2, "both the street and the avenue are within range")
        assertEquals(snaps.map { it.distanceMeters }.sorted(), snaps.map { it.distanceMeters })
        assertEquals("B Street", graph.edges[snaps.first().edgeIndex].displayName)
        assertEquals(10.0, snaps.first().distanceMeters, 2.0)
    }

    @Test
    fun `an empty graph snaps to nothing without blowing up`() {
        val graph = GraphBuilder.build(emptyList())

        assertTrue(graph.isEmpty)
        assertTrue(graph.snap(LatLon(35.99, -78.89)).isEmpty())
        assertNull(graph.snapNearest(LatLon(35.99, -78.89)))
    }

    @Test
    fun `an area with no roads is reported as missing map data not a missing start`() {
        // Telling a driver "no road near you" when the download actually came back empty
        // sends them looking for a problem with where they are standing.
        val planner = RoutePlanner(GraphBuilder.build(emptyList()), detectors = emptyList())

        val plan = planner.plan(Fixtures.position(0, 0), Fixtures.position(0, 2))

        assertTrue(plan.isEmpty)
        assertEquals(RouteFailure.NO_MAP_DATA, plan.failure)
    }

    @Test
    fun `a poor fix can still start a trip when a road is further away`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 4, cols = 6))
        val planner = RoutePlanner(graph, detectors = emptyList())

        // 400 m off the network, as a cell-tower fix indoors can easily be.
        val vagueStart = destinationPoint(Fixtures.position(0, 1), bearing = 180.0, meters = 400.0)
        val destination = Fixtures.position(0, 5)

        val strict = planner.plan(vagueStart, destination, listOf(PrivacyProfile.FASTEST))
        assertEquals(RouteFailure.ORIGIN_UNREACHABLE, strict.failure)

        val forgiving = planner.plan(
            vagueStart,
            destination,
            listOf(PrivacyProfile.FASTEST),
            originSnapMeters = SnapRadius.forAccuracy(accuracyMeters = 500.0),
        )
        assertFalse(forgiving.isEmpty, "a fix reporting ±500 m should be routed from the nearest road")
        assertNull(forgiving.failure)

        // It starts on the road, not at the reported position out in the field.
        val start = forgiving.routes.single().geometry.first()
        assertTrue(
            haversineMeters(start, vagueStart) > 200,
            "the route should begin on the road, not at the inaccurate fix",
        )
    }

    @Test
    fun `snap radius follows the accuracy the device reports`() {
        assertEquals(SnapRadius.DEFAULT_METERS, SnapRadius.forAccuracy(null))
        assertEquals(SnapRadius.DEFAULT_METERS, SnapRadius.forAccuracy(5.0))
        assertEquals(SnapRadius.DEFAULT_METERS, SnapRadius.forAccuracy(-1.0))
        assertEquals(750.0, SnapRadius.forAccuracy(500.0))
        assertEquals(SnapRadius.MAX_METERS, SnapRadius.forAccuracy(50_000.0))
    }

    @Test
    fun `an accurate fix is not allowed to snap across town`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 3, cols = 3))
        val planner = RoutePlanner(graph, detectors = emptyList())

        // A good fix a long way from any road is a genuine failure, not something to paper
        // over by snapping to whatever road happens to be nearest.
        val remote = destinationPoint(Fixtures.position(0, 0), bearing = 180.0, meters = 900.0)
        val plan = planner.plan(
            remote,
            Fixtures.position(0, 2),
            listOf(PrivacyProfile.FASTEST),
            originSnapMeters = SnapRadius.forAccuracy(accuracyMeters = 8.0),
        )

        assertEquals(RouteFailure.ORIGIN_UNREACHABLE, plan.failure)
    }

    @Test
    fun `an Overpass timeout is recognised rather than read as an empty map`() {
        val timedOut = """
            {
              "version": 0.6,
              "generator": "Overpass API",
              "elements": [],
              "remark": "runtime error: Query timed out in \"query\" at line 3 after 90 seconds."
            }
        """.trimIndent()

        val response = parseOverpassResponse(timedOut)

        assertTrue(response.elements.isEmpty())
        assertNotNull(response.errorRemark)
        assertTrue(response.errorRemark!!.contains("timed out"))
    }

    @Test
    fun `a healthy Overpass response carries no error remark`() {
        val fine = """
            { "version": 0.6, "elements": [], "remark": "considered 3 elements" }
        """.trimIndent()

        assertNull(parseOverpassResponse(fine).errorRemark)
        assertNull(parseOverpassResponse("""{ "elements": [] }""").errorRemark)
    }

    @Test
    fun `default snap allowance is shared by the graph and the router`() {
        assertEquals(RoadGraph.DEFAULT_SNAP_METERS, SnapRadius.DEFAULT_METERS)
    }
}
