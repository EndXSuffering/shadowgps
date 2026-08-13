package dev.shadowgps.core.graph

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.PolylineProjection
import dev.shadowgps.core.geo.SpatialGrid
import dev.shadowgps.core.geo.bearingDegrees
import dev.shadowgps.core.geo.coordAt
import dev.shadowgps.core.geo.coordsBounds
import dev.shadowgps.core.geo.coordsCount
import dev.shadowgps.core.geo.coordsToList
import dev.shadowgps.core.geo.projectOntoCoords

/**
 * One directed stretch of road between two junctions.
 *
 * Every drivable edge exists twice — once per direction — except on one-way roads. The
 * pair is linked through [reverseIndex] so the router can forbid u-turns cheaply.
 */
class RoadEdge(
    val index: Int,
    val fromNode: Int,
    val toNode: Int,
    /** Packed `[lat, lon, …]` in the direction of travel, from [fromNode] to [toNode]. */
    val coords: DoubleArray,
    val lengthMeters: Double,
    val speedKph: Double,
    val wayId: Long,
    val name: String?,
    val ref: String?,
    val highway: String,
    val roundabout: Boolean,
) {
    /** Free-flow traversal time in seconds. */
    val travelSeconds: Double = lengthMeters / (speedKph / 3.6)

    /** Index of the same road travelled the other way, or -1 on a one-way. */
    var reverseIndex: Int = -1
        internal set

    val pointCount: Int get() = coordsCount(coords)

    val startPoint: LatLon get() = coordAt(coords, 0)
    val endPoint: LatLon get() = coordAt(coords, pointCount - 1)

    /** Heading when entering the edge. */
    val startHeading: Double by lazy(LazyThreadSafetyMode.NONE) {
        if (pointCount < 2) 0.0 else bearingDegrees(coordAt(coords, 0), coordAt(coords, 1))
    }

    /** Heading when leaving the edge. */
    val endHeading: Double by lazy(LazyThreadSafetyMode.NONE) {
        if (pointCount < 2) 0.0 else bearingDegrees(coordAt(coords, pointCount - 2), coordAt(coords, pointCount - 1))
    }

    val bounds: BoundingBox by lazy(LazyThreadSafetyMode.NONE) { coordsBounds(coords) }

    /** Display name, preferring the street name and falling back to the route number. */
    val displayName: String? get() = name ?: ref

    fun geometry(): List<LatLon> = coordsToList(coords)

    fun project(target: LatLon): PolylineProjection = projectOntoCoords(coords, target)

    override fun toString(): String = "RoadEdge#$index(${displayName ?: highway}, ${lengthMeters.toInt()}m)"
}

/** Where a free-form coordinate lands on the road network. */
data class EdgeSnap(
    val edgeIndex: Int,
    val projection: PolylineProjection,
) {
    val distanceMeters: Double get() = projection.distanceMeters
    val alongMeters: Double get() = projection.alongMeters
    val point: LatLon get() = projection.point
}

/**
 * A routable road network.
 *
 * Nodes are junctions (and way endpoints); the shape of the road between them lives on the
 * edge, not in extra nodes. Adjacency is stored in compressed-sparse-row form — one offset
 * per node into a flat array of outgoing edge indices — which keeps the whole structure to
 * a handful of arrays and makes the router's inner loop a contiguous scan.
 */
class RoadGraph internal constructor(
    val nodeCount: Int,
    private val nodeLat: DoubleArray,
    private val nodeLon: DoubleArray,
    /** Seconds lost at each node to signals, stops and level crossings. */
    val nodeDelaySeconds: DoubleArray,
    val osmNodeIds: LongArray,
    val edges: List<RoadEdge>,
    private val adjacencyOffsets: IntArray,
    private val adjacencyEdges: IntArray,
) {
    val edgeCount: Int get() = edges.size

    val bounds: BoundingBox by lazy(LazyThreadSafetyMode.NONE) {
        require(nodeCount > 0) { "empty graph has no bounds" }
        var box = BoundingBox(nodeLat[0], nodeLon[0], nodeLat[0], nodeLon[0])
        for (i in 1 until nodeCount) {
            box = box.union(BoundingBox(nodeLat[i], nodeLon[i], nodeLat[i], nodeLon[i]))
        }
        box
    }

    fun position(node: Int): LatLon = LatLon(nodeLat[node], nodeLon[node])

    /** Indices into [edges] of every road leaving [node]. */
    fun outgoing(node: Int): IntArray {
        val from = adjacencyOffsets[node]
        val to = adjacencyOffsets[node + 1]
        return adjacencyEdges.copyOfRange(from, to)
    }

    /** Allocation-free iteration over the roads leaving [node]. */
    inline fun forEachOutgoing(node: Int, action: (edgeIndex: Int) -> Unit) {
        val from = adjacencyStart(node)
        val to = adjacencyEnd(node)
        for (i in from until to) action(adjacencyEdgeAt(i))
    }

    @PublishedApi internal fun adjacencyStart(node: Int): Int = adjacencyOffsets[node]

    @PublishedApi internal fun adjacencyEnd(node: Int): Int = adjacencyOffsets[node + 1]

    @PublishedApi internal fun adjacencyEdgeAt(slot: Int): Int = adjacencyEdges[slot]

    private val edgeIndexGrid: SpatialGrid<Int> by lazy(LazyThreadSafetyMode.NONE) {
        val grid = SpatialGrid<Int>(cellSizeDegrees = 0.004)
        for (edge in edges) grid.insert(edge.bounds, edge.index)
        grid
    }

    /**
     * Snaps [point] onto the nearest roads.
     *
     * Returns up to [limit] candidates ordered by distance, at most one per underlying road
     * direction pair, so a caller can seed a search from a point that could plausibly be on
     * either side of a dual carriageway. An empty list means nothing drivable is within
     * [maxDistanceMeters].
     */
    fun snap(point: LatLon, maxDistanceMeters: Double = 150.0, limit: Int = 4): List<EdgeSnap> {
        var radius = 60.0
        var candidates: List<Int> = emptyList()
        while (radius <= maxDistanceMeters * 2 && candidates.isEmpty()) {
            candidates = edgeIndexGrid.queryRadius(point, radius)
            radius *= 2
        }
        if (candidates.isEmpty()) candidates = edgeIndexGrid.queryRadius(point, maxDistanceMeters * 2)

        return candidates
            .asSequence()
            .map { EdgeSnap(it, edges[it].project(point)) }
            .filter { it.distanceMeters <= maxDistanceMeters }
            .sortedBy { it.distanceMeters }
            .take(limit)
            .toList()
    }

    /** The single closest drivable point, or null if the network does not reach [point]. */
    fun snapNearest(point: LatLon, maxDistanceMeters: Double = 150.0): EdgeSnap? =
        snap(point, maxDistanceMeters, limit = 1).firstOrNull()

    /**
     * Indices of every edge whose bounding box overlaps [box].
     *
     * Conservative by design — callers do their own exact test. This is how the exposure
     * model finds the roads a camera can see without scanning the whole network.
     */
    fun edgesIntersecting(box: BoundingBox): List<Int> = edgeIndexGrid.query(box)

    override fun toString(): String = "RoadGraph($nodeCount nodes, $edgeCount directed edges)"
}
