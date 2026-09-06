package dev.shadowgps.core

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.store.RegionFile
import dev.shadowgps.core.store.RegionMetadata
import dev.shadowgps.core.store.RegionPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The posted speed limit.
 *
 * Held apart from the speed the router uses, and for a reason worth stating: the router's
 * figure is a realistic average after junctions and traffic, and putting that on a
 * speed-limit sign would show the driver a number no sign anywhere agrees with. Only a real
 * `maxspeed` tag counts, and where there is none the app shows nothing.
 */
class SpeedLimitTest {

    private fun road(maxspeed: String?, highway: String = "primary"): OsmElement {
        val tags = buildMap {
            put("highway", highway)
            put("name", "Test Road")
            if (maxspeed != null) put("maxspeed", maxspeed)
        }
        return OsmElement("way", 100, nodes = listOf(1, 2, 3), tags = tags)
    }

    private fun nodes(): List<OsmElement> = listOf(
        OsmElement("node", 1, lat = 35.99, lon = -78.90),
        OsmElement("node", 2, lat = 35.99, lon = -78.89),
        OsmElement("node", 3, lat = 35.99, lon = -78.88),
    )

    private fun limitOf(maxspeed: String?): Double? =
        GraphBuilder.build(nodes() + road(maxspeed)).edges.first().maxspeedKph

    @Test
    fun `a posted limit in mph is carried through`() {
        val limit = limitOf("45 mph")!!
        assertEquals(72.4, limit, 0.5)
    }

    @Test
    fun `a bare number is kilometres per hour`() {
        assertEquals(50.0, limitOf("50")!!, 0.001)
    }

    @Test
    fun `an untagged road has no limit to show`() {
        // The router still assumes a speed for it; the driver is simply told nothing, which
        // is the honest answer to "what does the sign say" when there is no sign in the data.
        assertNull(limitOf(null))
    }

    @Test
    fun `the routing speed is not the posted speed`() {
        // 65 mph posted is about 105 km/h; nobody averages that on a primary road, and the
        // router knows it. Both numbers are kept because they answer different questions.
        val edge = GraphBuilder.build(nodes() + road("65 mph")).edges.first()

        assertEquals(104.6, edge.maxspeedKph!!, 0.5)
        assertEquals(edge.maxspeedKph!! * 0.8, edge.speedKph, 1.0)
    }

    @Test
    fun `a saved region remembers the limit`() {
        val graph = GraphBuilder.build(nodes() + road("35 mph"))
        val payload = RegionPayload(
            RegionMetadata("Test", BoundingBox(35.98, -78.92, 36.02, -78.86), 0L),
            graph,
            emptyList(),
        )

        val bytes = ByteArrayOutputStream().also { RegionFile.write(it, payload) }.toByteArray()
        val restored = RegionFile.read(ByteArrayInputStream(bytes))

        assertEquals(
            graph.edges.first().maxspeedKph!!,
            restored.graph.edges.first().maxspeedKph!!,
            0.5,
        )
    }

    @Test
    fun `a saved region with no limit reads back as no limit`() {
        // NaN carries "no tag" through the file; it must not come back as a speed of zero.
        val graph = GraphBuilder.build(nodes() + road(null))
        val payload = RegionPayload(
            RegionMetadata("Test", BoundingBox(35.98, -78.92, 36.02, -78.86), 0L),
            graph,
            emptyList(),
        )

        val bytes = ByteArrayOutputStream().also { RegionFile.write(it, payload) }.toByteArray()
        val restored = RegionFile.read(ByteArrayInputStream(bytes))

        assertNull(restored.graph.edges.first().maxspeedKph)
    }
}
