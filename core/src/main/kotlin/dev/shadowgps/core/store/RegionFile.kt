package dev.shadowgps.core.store

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.graph.RoadEdge
import dev.shadowgps.core.graph.RoadGraph
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.roundToInt

/** What a saved region says about itself, readable without loading the whole graph. */
data class RegionMetadata(
    val name: String,
    val bounds: BoundingBox,
    /** When the underlying OpenStreetMap data was downloaded. */
    val createdAtMillis: Long,
    val formatVersion: Int = RegionFile.VERSION,
)

/** A complete saved region: everything needed to route inside it, with no network. */
class RegionPayload(
    val metadata: RegionMetadata,
    val graph: RoadGraph,
    val detectors: List<Detector>,
)

/** Raised when a file is not a region, or is one this build cannot read. */
class RegionFormatException(message: String) : IOException(message)

/**
 * On-disk format for a downloaded area.
 *
 * Storing the *built* graph rather than the OpenStreetMap JSON it came from is the whole
 * point: reopening a saved city then costs a read, not a multi-megabyte JSON parse followed
 * by rebuilding every junction. Three things keep it small enough to be worth doing:
 *
 *  - coordinates as fixed-point integers at 1e-7 degrees, roughly a centimetre, which is
 *    far finer than anything OSM records and half the size of a double;
 *  - a string table, because road names, `highway` values and operators repeat enormously
 *    across a city and would otherwise be written out thousands of times each;
 *  - gzip over the lot.
 *
 * The adjacency index is not stored at all — rebuilding it on load is a linear pass and
 * cheaper than reading it back.
 */
object RegionFile {

    /** Identifies the file and guards against reading something else entirely. */
    private const val MAGIC = "SGPSRGN"

    /** Bumped on any incompatible change; older files are rejected, not misread. */
/**
     * Bumped to 2 when the posted speed limit joined each edge. A saved region written by
     * the previous version has no limit to give, and rather than invent one the reader
     * refuses it so the app re-downloads — a wrong number on a speed-limit sign is worse
     * than a slow download.
     */
    const val VERSION: Int = 2

    private const val COORD_SCALE = 1e7

    private const val NO_STRING = -1

    fun write(sink: OutputStream, payload: RegionPayload) {
        DataOutputStream(BufferedOutputStream(GZIPOutputStream(sink), BUFFER_BYTES)).use { out ->
            writeHeader(out, payload.metadata)

            val strings = StringTable()
            for (edge in payload.graph.edges) {
                strings.intern(edge.name)
                strings.intern(edge.ref)
                strings.intern(edge.highway)
            }
            for (detector in payload.detectors) {
                strings.intern(detector.name)
                strings.intern(detector.operator)
                strings.intern(detector.brand)
                strings.intern(detector.mount)
                strings.intern(detector.source)
                strings.intern(detector.kind.name)
            }
            strings.writeTo(out)

            writeNodes(out, payload.graph)
            writeEdges(out, payload.graph, strings)
            writeDetectors(out, payload.detectors, strings)
        }
    }

    fun read(source: InputStream): RegionPayload {
        DataInputStream(BufferedInputStream(GZIPInputStream(source), BUFFER_BYTES)).use { input ->
            val metadata = readHeader(input)
            val strings = StringTable.readFrom(input)

            val nodes = readNodes(input)
            val edges = readEdges(input, strings)
            val detectors = readDetectors(input, strings)

            val graph = RoadGraph.assemble(
                nodeLat = nodes.lat,
                nodeLon = nodes.lon,
                nodeDelaySeconds = nodes.delay,
                osmNodeIds = nodes.osmIds,
                edges = edges,
            )
            return RegionPayload(metadata, graph, detectors)
        }
    }

    /** Reads only the header, for listing saved regions or validating a file. */
    fun readMetadata(source: InputStream): RegionMetadata =
        DataInputStream(BufferedInputStream(GZIPInputStream(source), BUFFER_BYTES)).use(::readHeader)

    // ------------------------------------------------------------------ header

    private fun writeHeader(out: DataOutputStream, metadata: RegionMetadata) {
        out.writeUTF(MAGIC)
        out.writeInt(VERSION)
        out.writeUTF(metadata.name)
        out.writeDouble(metadata.bounds.south)
        out.writeDouble(metadata.bounds.west)
        out.writeDouble(metadata.bounds.north)
        out.writeDouble(metadata.bounds.east)
        out.writeLong(metadata.createdAtMillis)
    }

    private fun readHeader(input: DataInputStream): RegionMetadata {
        val magic = runCatching { input.readUTF() }.getOrNull()
        if (magic != MAGIC) throw RegionFormatException("Not a ShadowGPS region file")

        val version = input.readInt()
        if (version != VERSION) {
            throw RegionFormatException("Region format $version cannot be read by this version ($VERSION)")
        }

        return RegionMetadata(
            name = input.readUTF(),
            bounds = BoundingBox(
                south = input.readDouble(),
                west = input.readDouble(),
                north = input.readDouble(),
                east = input.readDouble(),
            ),
            createdAtMillis = input.readLong(),
            formatVersion = version,
        )
    }

    // ------------------------------------------------------------------ nodes

    private class Nodes(val lat: DoubleArray, val lon: DoubleArray, val delay: DoubleArray, val osmIds: LongArray)

    private fun writeNodes(out: DataOutputStream, graph: RoadGraph) {
        out.writeInt(graph.nodeCount)
        for (i in 0 until graph.nodeCount) {
            val position = graph.position(i)
            out.writeInt(toFixed(position.lat))
            out.writeInt(toFixed(position.lon))
        }
        // Junction delays are whole-second-ish values; float precision is ample.
        for (i in 0 until graph.nodeCount) out.writeFloat(graph.nodeDelaySeconds[i].toFloat())
        for (i in 0 until graph.nodeCount) out.writeLong(graph.osmNodeIds[i])
    }

    private fun readNodes(input: DataInputStream): Nodes {
        val count = input.readInt()
        requireSane(count, "node count")

        val lat = DoubleArray(count)
        val lon = DoubleArray(count)
        for (i in 0 until count) {
            lat[i] = fromFixed(input.readInt())
            lon[i] = fromFixed(input.readInt())
        }
        val delay = DoubleArray(count) { input.readFloat().toDouble() }
        val osmIds = LongArray(count) { input.readLong() }
        return Nodes(lat, lon, delay, osmIds)
    }

    // ------------------------------------------------------------------ edges

    private fun writeEdges(out: DataOutputStream, graph: RoadGraph, strings: StringTable) {
        out.writeInt(graph.edgeCount)
        for (edge in graph.edges) {
            out.writeInt(edge.fromNode)
            out.writeInt(edge.toNode)
            out.writeInt(edge.reverseIndex)
            out.writeLong(edge.wayId)
            out.writeFloat(edge.lengthMeters.toFloat())
            out.writeFloat(edge.speedKph.toFloat())
            // NaN carries "no tag" through a float without a second field.
            out.writeFloat((edge.maxspeedKph ?: Double.NaN).toFloat())
            out.writeInt(strings.indexOf(edge.name))
            out.writeInt(strings.indexOf(edge.ref))
            out.writeInt(strings.indexOf(edge.highway))
            out.writeBoolean(edge.roundabout)

            out.writeInt(edge.pointCount)
            for (i in 0 until edge.pointCount) {
                out.writeInt(toFixed(edge.coords[i * 2]))
                out.writeInt(toFixed(edge.coords[i * 2 + 1]))
            }
        }
    }

    private fun readEdges(input: DataInputStream, strings: StringTable): List<RoadEdge> {
        val count = input.readInt()
        requireSane(count, "edge count")

        val edges = ArrayList<RoadEdge>(count)
        val reverses = IntArray(count)

        for (index in 0 until count) {
            val fromNode = input.readInt()
            val toNode = input.readInt()
            reverses[index] = input.readInt()
            val wayId = input.readLong()
            val length = input.readFloat().toDouble()
            val speed = input.readFloat().toDouble()
            val posted = input.readFloat().toDouble().takeIf { !it.isNaN() }
            val name = strings.at(input.readInt())
            val ref = strings.at(input.readInt())
            val highway = strings.at(input.readInt()) ?: "road"
            val roundabout = input.readBoolean()

            val pointCount = input.readInt()
            requireSane(pointCount, "edge point count")
            val coords = DoubleArray(pointCount * 2)
            for (i in 0 until pointCount) {
                coords[i * 2] = fromFixed(input.readInt())
                coords[i * 2 + 1] = fromFixed(input.readInt())
            }

            edges.add(
                RoadEdge(
                    index = index,
                    fromNode = fromNode,
                    toNode = toNode,
                    coords = coords,
                    lengthMeters = length,
                    speedKph = speed,
                    wayId = wayId,
                    name = name,
                    ref = ref,
                    highway = highway,
                    roundabout = roundabout,
                    maxspeedKph = posted,
                ),
            )
        }

        for (index in 0 until count) edges[index].reverseIndex = reverses[index]
        return edges
    }

    // ------------------------------------------------------------------ detectors

    private fun writeDetectors(out: DataOutputStream, detectors: List<Detector>, strings: StringTable) {
        out.writeInt(detectors.size)
        for (detector in detectors) {
            out.writeUTF(detector.id)
            // By name rather than ordinal: reordering the enum must not silently turn every
            // saved plate reader into a toll gantry.
            out.writeInt(strings.indexOf(detector.kind.name))
            out.writeInt(toFixed(detector.position.lat))
            out.writeInt(toFixed(detector.position.lon))

            val heading = detector.headingDegrees
            out.writeBoolean(heading != null)
            out.writeFloat((heading ?: 0.0).toFloat())

            out.writeFloat(detector.rangeMeters.toFloat())
            out.writeFloat(detector.fovDegrees.toFloat())
            out.writeInt(strings.indexOf(detector.name))
            out.writeInt(strings.indexOf(detector.operator))
            out.writeInt(strings.indexOf(detector.brand))
            out.writeInt(strings.indexOf(detector.mount))
            out.writeInt(strings.indexOf(detector.source))
        }
    }

    private fun readDetectors(input: DataInputStream, strings: StringTable): List<Detector> {
        val count = input.readInt()
        requireSane(count, "detector count")

        val detectors = ArrayList<Detector>(count)
        for (i in 0 until count) {
            val id = input.readUTF()
            val kindName = strings.at(input.readInt())
            val position = LatLon(fromFixed(input.readInt()), fromFixed(input.readInt()))
            val hasHeading = input.readBoolean()
            val heading = input.readFloat().toDouble()
            val range = input.readFloat().toDouble()
            val fov = input.readFloat().toDouble()
            val name = strings.at(input.readInt())
            val operator = strings.at(input.readInt())
            val brand = strings.at(input.readInt())
            val mount = strings.at(input.readInt())
            val source = strings.at(input.readInt()) ?: "osm"

            // A kind this build does not know about is dropped rather than guessed at.
            val kind = DetectorKind.entries.firstOrNull { it.name == kindName } ?: continue

            detectors.add(
                Detector(
                    id = id,
                    kind = kind,
                    position = position,
                    headingDegrees = if (hasHeading) heading else null,
                    rangeMeters = range,
                    fovDegrees = fov,
                    name = name,
                    operator = operator,
                    brand = brand,
                    mount = mount,
                    source = source,
                ),
            )
        }
        return detectors
    }

    // ------------------------------------------------------------------ helpers

    private const val BUFFER_BYTES = 1 shl 16

    /** Guards against a corrupt length sending the reader off allocating gigabytes. */
    private fun requireSane(count: Int, what: String) {
        if (count < 0 || count > MAX_ELEMENTS) throw RegionFormatException("Implausible $what: $count")
    }

    private const val MAX_ELEMENTS = 50_000_000

    private fun toFixed(degrees: Double): Int = (degrees * COORD_SCALE).roundToInt()

    private fun fromFixed(fixed: Int): Double = fixed / COORD_SCALE

    /**
     * Deduplicates the strings that repeat across a city.
     *
     * A long road is split into hundreds of edges that all carry the same name and the same
     * `highway` value, so writing them inline would dominate the file.
     */
    private class StringTable {
        private val indices = HashMap<String, Int>()
        private val values = ArrayList<String>()

        fun intern(value: String?) {
            if (value == null || indices.containsKey(value)) return
            indices[value] = values.size
            values.add(value)
        }

        fun indexOf(value: String?): Int = if (value == null) NO_STRING else indices[value] ?: NO_STRING

        fun at(index: Int): String? = if (index == NO_STRING) null else values.getOrNull(index)

        fun writeTo(out: DataOutputStream) {
            out.writeInt(values.size)
            for (value in values) out.writeUTF(value)
        }

        companion object {
            fun readFrom(input: DataInputStream): StringTable {
                val count = input.readInt()
                if (count < 0 || count > MAX_ELEMENTS) {
                    throw RegionFormatException("Implausible string table size: $count")
                }
                val table = StringTable()
                repeat(count) { table.intern(input.readUTF()) }
                return table
            }
        }
    }
}
