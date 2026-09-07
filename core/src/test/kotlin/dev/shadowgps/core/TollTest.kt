package dev.shadowgps.core

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RoutePlanner
import dev.shadowgps.core.routing.RoutingOptions
import dev.shadowgps.core.store.RegionFile
import dev.shadowgps.core.store.RegionMetadata
import dev.shadowgps.core.store.RegionPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Avoiding roads that charge.
 *
 * A multiplier rather than a ban, and the tests below pin both halves of why: a free route
 * wins whenever it is remotely competitive, and a toll road is still offered when it is the
 * only way through — because refusing to route at all is a worse answer than a route the
 * driver can look at and reject.
 */
class TollTest {

    private val origin = LatLon(35.99, -78.90)
    private val destination = destinationPoint(origin, bearing = 90.0, meters = 6_000.0)

    /**
     * A fast toll road straight there, and a slower free road round the houses.
     *
     * @param freeDetourMeters how far south the free road dips, which is what decides
     *   whether avoiding the toll is a small inconvenience or an absurd one
     */
    private fun town(freeDetourMeters: Double): List<OsmElement> {
        val elements = ArrayList<OsmElement>()
        var nextId = 1L

        fun node(point: LatLon): Long {
            val id = nextId++
            elements.add(OsmElement("node", id, lat = point.lat, lon = point.lon))
            return id
        }

        val start = node(origin)
        val end = node(destination)

        elements.add(
            OsmElement(
                "way", nextId++,
                nodes = listOf(start, end),
                tags = mapOf(
                    "highway" to "motorway",
                    "name" to "Turnpike",
                    "toll" to "yes",
                ),
            ),
        )

        val southWest = destinationPoint(origin, 180.0, freeDetourMeters)
        val southEast = destinationPoint(southWest, 90.0, 6_000.0)
        elements.add(
            OsmElement(
                "way", nextId++,
                nodes = listOf(start, node(southWest), node(southEast), end),
                tags = mapOf("highway" to "primary", "name" to "Free Road"),
            ),
        )
        return elements
    }

    private fun routeName(elements: List<OsmElement>, options: RoutingOptions): String? =
        RoutePlanner(GraphBuilder.build(elements), emptyList(), options = options)
            .plan(origin, destination, listOf(PrivacyProfile.FASTEST))
            .routes.single().steps.first().roadName

    @Test
    fun `a toll road is read from the tags`() {
        val graph = GraphBuilder.build(town(freeDetourMeters = 500.0))
        val turnpike = graph.edges.first { it.displayName == "Turnpike" }
        val free = graph.edges.first { it.displayName == "Free Road" }

        assertTrue(turnpike.toll)
        assertFalse(free.toll)
    }

    @Test
    fun `the quickest route takes the toll road when tolls are not being avoided`() {
        assertEquals(
            "Turnpike",
            routeName(town(freeDetourMeters = 500.0), RoutingOptions()),
        )
    }

    @Test
    fun `avoiding tolls takes the free road`() {
        assertEquals(
            "Free Road",
            routeName(town(freeDetourMeters = 500.0), RoutingOptions(tollAversion = 3.0)),
        )
    }

    @Test
    fun `a toll road is still offered when the free way round is absurd`() {
        // Sixty kilometres out of the way to save a toll is not avoiding a toll, it is
        // refusing to arrive. A multiplier bends; a ban would have broken here.
        assertEquals(
            "Turnpike",
            routeName(town(freeDetourMeters = 60_000.0), RoutingOptions(tollAversion = 3.0)),
        )
    }

    @Test
    fun `a route says how far it runs on toll roads`() {
        val plan = RoutePlanner(GraphBuilder.build(town(freeDetourMeters = 500.0)), emptyList())
            .plan(origin, destination, listOf(PrivacyProfile.FASTEST))
        val route = plan.routes.single()

        assertTrue(route.usesTolls)
        assertEquals(route.distanceMeters, route.tollMeters, 50.0)
    }

    @Test
    fun `a free route reports no tolls at all`() {
        val plan = RoutePlanner(
            GraphBuilder.build(town(freeDetourMeters = 500.0)),
            emptyList(),
            options = RoutingOptions(tollAversion = 3.0),
        ).plan(origin, destination, listOf(PrivacyProfile.FASTEST))

        assertFalse(plan.routes.single().usesTolls)
        assertEquals(0.0, plan.routes.single().tollMeters)
    }

    @Test
    fun `a saved region remembers which roads charge`() {
        val graph = GraphBuilder.build(town(freeDetourMeters = 500.0))
        val payload = RegionPayload(
            RegionMetadata("Test", BoundingBox(35.90, -79.00, 36.10, -78.70), 0L),
            graph,
            emptyList(),
        )

        val bytes = ByteArrayOutputStream().also { RegionFile.write(it, payload) }.toByteArray()
        val restored = RegionFile.read(ByteArrayInputStream(bytes))

        assertTrue(restored.graph.edges.first { it.displayName == "Turnpike" }.toll)
        assertFalse(restored.graph.edges.first { it.displayName == "Free Road" }.toll)
    }

    @Test
    fun `the flags byte keeps roundabouts and tolls apart`() {
        // They share a byte now, so a mix-up would be silent: a toll road that reads back as
        // a roundabout would quietly rewrite the driver's instructions.
        val elements = listOf(
            OsmElement("node", 1, lat = 35.99, lon = -78.90),
            OsmElement("node", 2, lat = 35.9905, lon = -78.8995),
            OsmElement("node", 3, lat = 35.9910, lon = -78.90),
            OsmElement(
                "way", 4,
                nodes = listOf(1, 2, 3, 1),
                tags = mapOf("highway" to "primary", "junction" to "roundabout", "name" to "Ring"),
            ),
        )
        val graph = GraphBuilder.build(elements)
        val payload = RegionPayload(
            RegionMetadata("Test", BoundingBox(35.90, -79.00, 36.10, -78.70), 0L),
            graph,
            emptyList(),
        )

        val bytes = ByteArrayOutputStream().also { RegionFile.write(it, payload) }.toByteArray()
        val restored = RegionFile.read(ByteArrayInputStream(bytes))

        assertTrue(restored.graph.edges.all { it.roundabout })
        assertTrue(restored.graph.edges.none { it.toll })
    }
}
