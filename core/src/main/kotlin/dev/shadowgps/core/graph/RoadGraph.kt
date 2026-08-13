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
     *
     * The search starts small and widens. That is purely a cost optimisation — the grid is
     * conservative, so a single query at [maxDistanceMeters] would already return every
     * edge in range, but it would also mean projecting every road in a city block onto the
     * point for the common case where one is a few metres away. Widening must therefore key
     * off whether an *acceptable snap* was found, never off whether the grid handed back
     * any candidates at all: a cell can easily contain nothing but a long road that passes
     * hundreds of metres away, and stopping there would report no road while one sits just
     * over the cell boundary.
     */
    fun snap(
        point: LatLon,
        maxDistanceMeters: Double = DEFAULT_SNAP_METERS,
        limit: Int = 4,
    ): List<EdgeSnap> {
        if (edges.isEmpty()) return emptyList()

        var probe = INITIAL_PROBE_METERS
        while (true) {
            val reach = minOf(probe, maxDistanceMeters)
            val found = snapWithin(point, reach, limit)
            if (found.isNotEmpty()) return found
            if (reach >= maxDistanceMeters) return emptyList()
            probe *= 2
        }
    }

    private fun snapWithin(point: LatLon, radiusMeters: Double, limit: Int): List<EdgeSnap> {
        val roadsTaken = HashSet<Int>()
        return edgeIndexGrid.queryRadius(point, radiusMeters)
            .map { EdgeSnap(it, edges[it].project(point)) }
            .filter { it.distanceMeters <= radiusMeters }
            .sortedBy { it.distanceMeters }
            // Both directions of one road snap to the same place as far as a driver is
            // concerned, so keeping both would spend the limit on one street. The router
            // re-derives the opposite direction from the pairing anyway.
            .filter { snap -> roadsTaken.add(roadKey(snap.edgeIndex)) }
            .take(limit)
    }

    /** Identifies the physical road behind a directed edge, shared by both directions. */
    private fun roadKey(edgeIndex: Int): Int {
        val reverse = edges[edgeIndex].reverseIndex
        return if (reverse >= 0) minOf(edgeIndex, reverse) else edgeIndex
    }

    /** The single closest drivable point, or null if the network does not reach [point]. */
    fun snapNearest(point: LatLon, maxDistanceMeters: Double = DEFAULT_SNAP_METERS): EdgeSnap? =
        snap(point, maxDistanceMeters, limit = 1).firstOrNull()

    /**
     * Indices of every edge whose bounding box overlaps [box].
     *
     * Conservative by design — callers do their own exact test. This is how the exposure
     * model finds the roads a camera can see without scanning the whole network.
     */
    fun edgesIntersecting(box: BoundingBox): List<Int> = edgeIndexGrid.query(box)

    /** True when nothing was built — an area with no downloaded roads. */
    val isEmpty: Boolean get() = edges.isEmpty()

    override fun toString(): String = "RoadGraph($nodeCount nodes, $edgeCount directed edges)"

    companion object {
        /** How far a start or end point may sit from a road before routing gives up. */
        const val DEFAULT_SNAP_METERS: Double = 200.0

        /** First neighbourhood examined; widened until something acceptable turns up. */
        const val INITIAL_PROBE_METERS: Double = 80.0

        /**
         * Builds the compressed-sparse-row adjacency and assembles a graph.
         *
         * Shared by the builder that reads OSM and the reader that loads a saved region, so
         * a graph restored from disk is indistinguishable from a freshly built one.
         */
        internal fun assemble(
            nodeLat: DoubleArray,
            nodeLon: DoubleArray,
            nodeDelaySeconds: DoubleArray,
            osmNodeIds: LongArray,
            edges: List<RoadEdge>,
        ): RoadGraph {
            val nodeCount = nodeLat.size
            val offsets = IntArray(nodeCount + 1)
            for (edge in edges) offsets[edge.fromNode + 1]++
            for (i in 1..nodeCount) offsets[i] += offsets[i - 1]

            val cursor = offsets.copyOf()
            val adjacency = IntArray(edges.size)
            for (edge in edges) adjacency[cursor[edge.fromNode]++] = edge.index

            return RoadGraph(
                nodeCount = nodeCount,
                nodeLat = nodeLat,
                nodeLon = nodeLon,
                nodeDelaySeconds = nodeDelaySeconds,
                osmNodeIds = osmNodeIds,
                edges = edges,
                adjacencyOffsets = offsets,
                adjacencyEdges = adjacency,
            )
        }
    }
}
