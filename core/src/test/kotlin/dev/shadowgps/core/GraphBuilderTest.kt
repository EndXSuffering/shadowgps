package dev.shadowgps.core

import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.graph.Speeds
import dev.shadowgps.core.osm.OsmElement
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GraphBuilderTest {

    @Test
    fun `ways are split at every shared junction`() {
        // A 3x3 grid: 9 intersections, and 12 blocks each drivable both ways.
        val graph = GraphBuilder.build(Fixtures.grid(rows = 3, cols = 3))

        assertEquals(9, graph.nodeCount)
        assertEquals(24, graph.edgeCount)
    }

    @Test
    fun `edge geometry keeps the shape between junctions`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 2, cols = 2))
        val edge = graph.edges.first()

        assertEquals(2, edge.pointCount)
        assertEquals(Fixtures.DEFAULT_SPACING_METERS, edge.lengthMeters, 1.0)
        assertEquals(
            edge.lengthMeters,
            haversineMeters(edge.startPoint, edge.endPoint),
            1.0,
        )
    }

    @Test
    fun `paired directions are linked to each other`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 2, cols = 2))

        for (edge in graph.edges) {
            assertTrue(edge.reverseIndex >= 0, "two-way street should have a reverse")
            val reverse = graph.edges[edge.reverseIndex]
            assertEquals(edge.index, reverse.reverseIndex)
            assertEquals(edge.fromNode, reverse.toNode)
            assertEquals(edge.toNode, reverse.fromNode)
        }
    }

    @Test
    fun `one-way streets produce a single direction`() {
        val elements = Fixtures.grid(rows = 2, cols = 2, extraWayTags = mapOf("oneway" to "yes"))
        val graph = GraphBuilder.build(elements)

        assertTrue(graph.edges.all { it.reverseIndex == -1 })
        assertEquals(4, graph.edgeCount)
    }

    @Test
    fun `reverse oneway flips the direction of travel`() {
        val nodes = listOf(
            OsmElement("node", 1, lat = 0.0, lon = 0.0),
            OsmElement("node", 2, lat = 0.0, lon = 0.001),
        )
        val way = OsmElement(
            "way", 10,
            nodes = listOf(1, 2),
            tags = mapOf("highway" to "residential", "oneway" to "-1"),
        )

        val graph = GraphBuilder.build(nodes + way)

        assertEquals(1, graph.edgeCount)
        val edge = graph.edges.single()
        // Travel runs from node 2 back to node 1, so it heads west.
        assertEquals(270.0, edge.startHeading, 1.0)
    }

    @Test
    fun `motorways and roundabouts are implicitly one-way`() {
        val nodes = listOf(
            OsmElement("node", 1, lat = 0.0, lon = 0.0),
            OsmElement("node", 2, lat = 0.0, lon = 0.001),
        )

        val motorway = GraphBuilder.build(
            nodes + OsmElement("way", 10, nodes = listOf(1, 2), tags = mapOf("highway" to "motorway")),
        )
        assertEquals(1, motorway.edgeCount)

        val roundabout = GraphBuilder.build(
            nodes + OsmElement(
                "way", 11,
                nodes = listOf(1, 2),
                tags = mapOf("highway" to "residential", "junction" to "roundabout"),
            ),
        )
        assertEquals(1, roundabout.edgeCount)
        assertTrue(roundabout.edges.single().roundabout)
    }

    @Test
    fun `undrivable ways are excluded`() {
        assertFalse(GraphBuilder.isDrivable(mapOf("highway" to "footway")))
        assertFalse(GraphBuilder.isDrivable(mapOf("highway" to "cycleway")))
        assertFalse(GraphBuilder.isDrivable(mapOf("highway" to "residential", "access" to "private")))
        assertFalse(GraphBuilder.isDrivable(mapOf("highway" to "residential", "motor_vehicle" to "no")))
        assertFalse(GraphBuilder.isDrivable(mapOf("building" to "yes")))

        assertTrue(GraphBuilder.isDrivable(mapOf("highway" to "residential")))
        assertTrue(GraphBuilder.isDrivable(mapOf("highway" to "service")))
        // An explicit motor vehicle permission beats a restrictive generic access tag.
        assertTrue(GraphBuilder.isDrivable(mapOf("highway" to "service", "access" to "private", "motor_vehicle" to "yes")))
    }

    @Test
    fun `junction delays land on the right nodes`() {
        val elements = Fixtures.grid(rows = 2, cols = 2).map { element ->
            if (element.isNode && element.id == Fixtures.nodeId(0, 0)) {
                element.copy(tags = mapOf("highway" to "traffic_signals"))
            } else {
                element
            }
        }

        val graph = GraphBuilder.build(elements)
        val signalIndex = graph.osmNodeIds.indexOfFirst { it == Fixtures.nodeId(0, 0) }

        assertTrue(signalIndex >= 0)
        assertEquals(12.0, graph.nodeDelaySeconds[signalIndex])
        assertEquals(0.0, graph.nodeDelaySeconds[graph.osmNodeIds.indexOfFirst { it == Fixtures.nodeId(1, 1) }])
    }

    @Test
    fun `snapping finds the road under a point`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 3, cols = 3))
        val target = Fixtures.midBlock(0, 0, 0, 1)

        val snap = graph.snapNearest(target)

        assertNotNull(snap)
        assertTrue(snap!!.distanceMeters < 1.0)
        assertEquals(Fixtures.DEFAULT_SPACING_METERS / 2, snap.alongMeters, 5.0)
    }

    @Test
    fun `snapping gives up when nothing is near`() {
        val graph = GraphBuilder.build(Fixtures.grid(rows = 2, cols = 2))
        val faraway = dev.shadowgps.core.geo.LatLon(40.0, -100.0)

        assertTrue(graph.snap(faraway, maxDistanceMeters = 150.0).isEmpty())
    }

    @Test
    fun `speeds come from maxspeed when present`() {
        assertEquals(50.0, Speeds.parseMaxspeedKph("50"))
        assertEquals(48.28, Speeds.parseMaxspeedKph("30 mph")!!, 0.1)
        assertEquals(130.0, Speeds.parseMaxspeedKph("none"))
        assertEquals(30.0, Speeds.parseMaxspeedKph("DE:urban"))
        assertEquals(null, Speeds.parseMaxspeedKph("signals"))
        assertEquals(null, Speeds.parseMaxspeedKph(null))

        val fast = Speeds.speedKph(mapOf("highway" to "motorway"))
        val slow = Speeds.speedKph(mapOf("highway" to "residential"))
        assertTrue(fast > slow, "a motorway should be faster than a residential street")
        assertTrue(fast <= Speeds.MAX_PLAUSIBLE_KPH)
    }

    @Test
    fun `unpaved surfaces slow a road down`() {
        val paved = Speeds.speedKph(mapOf("highway" to "unclassified"))
        val gravel = Speeds.speedKph(mapOf("highway" to "unclassified", "surface" to "gravel"))

        assertTrue(gravel < paved)
    }
}
