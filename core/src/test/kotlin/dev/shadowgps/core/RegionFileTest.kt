package dev.shadowgps.core

import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.RoutePlanner
import dev.shadowgps.core.store.RegionFile
import dev.shadowgps.core.store.RegionFormatException
import dev.shadowgps.core.store.RegionMetadata
import dev.shadowgps.core.store.RegionPayload
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * The saved-region format.
 *
 * This is the one place where a bug is silent and delayed: a region written wrongly reads
 * back as a subtly different city, and the driver finds out mid-trip. So the tests check
 * that a restored graph *routes identically*, not merely that it deserialises.
 */
class RegionFileTest {

    private val graph = GraphBuilder.build(Fixtures.grid(rows = 5, cols = 6))

    private val detectors = listOf(
        Fixtures.alpr("node/1", Fixtures.midBlock(0, 2, 0, 3), heading = 145.0),
        Fixtures.alpr("node/2", Fixtures.midBlock(2, 1, 2, 2), kind = DetectorKind.SPEED_CAMERA),
        Fixtures.alpr("node/3", Fixtures.position(3, 3), kind = DetectorKind.CCTV),
    )

    private val metadata = RegionMetadata(
        name = "Durham",
        bounds = BoundingBox(35.98, -78.92, 36.02, -78.86),
        createdAtMillis = 1_760_000_000_000L,
    )

    private fun roundTrip(payload: RegionPayload = RegionPayload(metadata, graph, detectors)): RegionPayload {
        val bytes = ByteArrayOutputStream()
        RegionFile.write(bytes, payload)
        return RegionFile.read(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun `metadata survives a round trip`() {
        val restored = roundTrip().metadata

        assertEquals("Durham", restored.name)
        assertEquals(metadata.bounds, restored.bounds)
        assertEquals(metadata.createdAtMillis, restored.createdAtMillis)
        assertEquals(RegionFile.VERSION, restored.formatVersion)
    }

    @Test
    fun `the graph comes back identical`() {
        val restored = roundTrip().graph

        assertEquals(graph.nodeCount, restored.nodeCount)
        assertEquals(graph.edgeCount, restored.edgeCount)

        for (i in 0 until graph.nodeCount) {
            // Fixed-point coordinates are precise to about a centimetre.
            assertTrue(haversineMeters(graph.position(i), restored.position(i)) < 0.05)
            assertEquals(graph.nodeDelaySeconds[i], restored.nodeDelaySeconds[i], 1e-3)
            assertEquals(graph.osmNodeIds[i], restored.osmNodeIds[i])
        }

        for (i in 0 until graph.edgeCount) {
            val original = graph.edges[i]
            val copy = restored.edges[i]
            assertEquals(original.fromNode, copy.fromNode)
            assertEquals(original.toNode, copy.toNode)
            assertEquals(original.reverseIndex, copy.reverseIndex)
            assertEquals(original.wayId, copy.wayId)
            assertEquals(original.name, copy.name)
            assertEquals(original.ref, copy.ref)
            assertEquals(original.highway, copy.highway)
            assertEquals(original.roundabout, copy.roundabout)
            assertEquals(original.pointCount, copy.pointCount)
            assertEquals(original.lengthMeters, copy.lengthMeters, 0.5)
            assertEquals(original.speedKph, copy.speedKph, 0.01)
        }
    }

    @Test
    fun `adjacency is rebuilt so the restored graph is traversable`() {
        val restored = roundTrip().graph

        for (node in 0 until graph.nodeCount) {
            assertEquals(
                graph.outgoing(node).toList().sorted(),
                restored.outgoing(node).toList().sorted(),
                "outgoing edges differ at node $node",
            )
        }
    }

    @Test
    fun `detectors come back intact`() {
        val restored = roundTrip().detectors

        assertEquals(detectors.size, restored.size)
        for ((original, copy) in detectors.zip(restored)) {
            assertEquals(original.id, copy.id)
            assertEquals(original.kind, copy.kind)
            assertEquals(original.brand, copy.brand)
            assertEquals(original.rangeMeters, copy.rangeMeters, 0.01)
            assertEquals(original.fovDegrees, copy.fovDegrees, 0.01)
            assertTrue(haversineMeters(original.position, copy.position) < 0.05)
            if (original.headingDegrees == null) {
                assertNull(copy.headingDegrees)
            } else {
                assertEquals(original.headingDegrees!!, copy.headingDegrees!!, 0.01)
            }
        }
    }

    @Test
    fun `a restored region routes exactly like the original`() {
        val restored = roundTrip()

        val start = Fixtures.position(0, 0)
        val end = Fixtures.position(0, 5)
        val profiles = listOf(PrivacyProfile.FASTEST, PrivacyProfile.GHOST)

        val before = RoutePlanner(graph, detectors).plan(start, end, profiles)
        val after = RoutePlanner(restored.graph, restored.detectors).plan(start, end, profiles)

        assertEquals(before.routes.size, after.routes.size)
        for ((original, copy) in before.routes.zip(after.routes)) {
            assertEquals(original.profile, copy.profile)
            assertEquals(original.distanceMeters, copy.distanceMeters, 1.0)
            assertEquals(original.durationSeconds, copy.durationSeconds, 1.0)
            assertEquals(original.exposure.totalCount, copy.exposure.totalCount)
            assertEquals(
                original.steps.map { it.instruction },
                copy.steps.map { it.instruction },
            )
        }
    }

    @Test
    fun `the header can be read without loading the whole region`() {
        val bytes = ByteArrayOutputStream()
        RegionFile.write(bytes, RegionPayload(metadata, graph, detectors))

        val header = RegionFile.readMetadata(ByteArrayInputStream(bytes.toByteArray()))

        assertEquals("Durham", header.name)
        assertEquals(metadata.bounds, header.bounds)
    }

    @Test
    fun `an empty region is handled`() {
        val empty = GraphBuilder.build(emptyList())
        val restored = roundTrip(RegionPayload(metadata, empty, emptyList()))

        assertEquals(0, restored.graph.edgeCount)
        assertTrue(restored.graph.isEmpty)
        assertTrue(restored.detectors.isEmpty())
    }

    @Test
    fun `something that is not a region is rejected`() {
        val garbage = ByteArrayInputStream("this is not a region file".toByteArray())

        // Gzip rejects it before the magic does; either way it must not be read as a map.
        assertThrows(Exception::class.java) { RegionFile.read(garbage) }
    }

    @Test
    fun `a truncated region is rejected rather than half-read`() {
        val bytes = ByteArrayOutputStream()
        RegionFile.write(bytes, RegionPayload(metadata, graph, detectors))
        val truncated = bytes.toByteArray().copyOf(bytes.size() / 2)

        assertThrows(Exception::class.java) { RegionFile.read(ByteArrayInputStream(truncated)) }
    }

    @Test
    fun `a file from a different format version is refused, not misread`() {
        val bytes = ByteArrayOutputStream()
        RegionFile.write(bytes, RegionPayload(metadata, graph, detectors))
        val stored = bytes.toByteArray()

        // Rewrite the version field inside the gzip stream by round-tripping it.
        val inflated = java.util.zip.GZIPInputStream(ByteArrayInputStream(stored)).readBytes()
        // Version is the 4 bytes following the UTF-encoded magic (2 length + 7 chars).
        val versionOffset = 2 + 7
        inflated[versionOffset + 3] = 99

        val reGzipped = ByteArrayOutputStream()
        java.util.zip.GZIPOutputStream(reGzipped).use { it.write(inflated) }

        val failure = assertThrows(RegionFormatException::class.java) {
            RegionFile.read(ByteArrayInputStream(reGzipped.toByteArray()))
        }
        assertTrue(failure.message!!.contains("cannot be read"), failure.message!!)
    }

    @Test
    fun `the format is meaningfully smaller than the graph it stores`() {
        val bytes = ByteArrayOutputStream()
        RegionFile.write(bytes, RegionPayload(metadata, graph, detectors))

        // Every edge carries two node ids, a way id, geometry and several strings; a naive
        // encoding runs well past 100 bytes each. The string table and fixed-point
        // coordinates are what keep a whole city practical to store.
        val bytesPerEdge = bytes.size().toDouble() / graph.edgeCount
        assertTrue(bytesPerEdge < 60, "region uses $bytesPerEdge bytes per edge")
        assertNotNull(RegionFile.readMetadata(ByteArrayInputStream(bytes.toByteArray())))
    }
}
