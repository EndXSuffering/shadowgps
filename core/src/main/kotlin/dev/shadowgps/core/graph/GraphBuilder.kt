package dev.shadowgps.core.graph

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.coordsLengthMeters
import dev.shadowgps.core.geo.listToCoords
import dev.shadowgps.core.geo.reverseCoords
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.osm.OverpassQueries

/**
 * Builds a routable [RoadGraph] from raw OSM elements.
 *
 * The interesting part is splitting: OSM ways run for kilometres through many junctions,
 * but a router needs to be able to turn off at each one. Ways are therefore cut at every
 * node shared with another way, and the shape in between is kept on the edge.
 */
object GraphBuilder {

    private val DRIVABLE = OverpassQueries.DRIVABLE_HIGHWAY_VALUES.toSet()

    /** `access`-family values that keep a car off a road entirely. */
    private val BLOCKED_ACCESS = setOf("no", "private", "customers", "delivery", "agricultural", "forestry")

    /** Ways that are mapped but not currently drivable. */
    private val NON_DRIVABLE_LIFECYCLE = setOf("construction", "proposed", "abandoned", "disused", "razed")

    fun build(elements: Iterable<OsmElement>): RoadGraph {
        val nodePositions = HashMap<Long, LatLon>()
        val nodeTags = HashMap<Long, Map<String, String>>()
        val ways = ArrayList<OsmElement>()

        for (element in elements) {
            when {
                element.isNode -> {
                    val lat = element.lat
                    val lon = element.lon
                    if (lat != null && lon != null) nodePositions[element.id] = LatLon(lat, lon)
                    if (element.tags.isNotEmpty()) nodeTags[element.id] = element.tags
                }
                element.isWay && isDrivable(element.tags) -> ways.add(element)
            }
        }

        // A node is a junction if more than one way touches it — which also catches a node
        // visited twice by a single way, as happens where a road loops back on itself.
        val usage = HashMap<Long, Int>()
        for (way in ways) {
            for (nodeId in way.nodes) usage[nodeId] = (usage[nodeId] ?: 0) + 1
        }

        val junctionIds = HashSet<Long>()
        for (way in ways) {
            val nodes = way.nodes
            if (nodes.size < 2) continue
            junctionIds.add(nodes.first())
            junctionIds.add(nodes.last())
            for (nodeId in nodes) {
                if ((usage[nodeId] ?: 0) > 1) junctionIds.add(nodeId)
            }
        }

        // Assign dense indices to the junction nodes that actually have coordinates.
        val nodeIndex = HashMap<Long, Int>(junctionIds.size * 2)
        val latList = ArrayList<Double>(junctionIds.size)
        val lonList = ArrayList<Double>(junctionIds.size)
        val delayList = ArrayList<Double>(junctionIds.size)
        val osmIdList = ArrayList<Long>(junctionIds.size)

        for (nodeId in junctionIds) {
            val position = nodePositions[nodeId] ?: continue
            nodeIndex[nodeId] = latList.size
            latList.add(position.lat)
            lonList.add(position.lon)
            delayList.add(nodeTags[nodeId]?.let(Speeds::nodeDelaySeconds) ?: 0.0)
            osmIdList.add(nodeId)
        }

        val edges = ArrayList<RoadEdge>()
        for (way in ways) {
            buildEdgesForWay(way, nodePositions, nodeIndex, edges)
        }

        return RoadGraph.assemble(
            nodeLat = latList.toDoubleArray(),
            nodeLon = lonList.toDoubleArray(),
            nodeDelaySeconds = delayList.toDoubleArray(),
            osmNodeIds = osmIdList.toLongArray(),
            edges = edges,
        )
    }

    private fun buildEdgesForWay(
        way: OsmElement,
        nodePositions: Map<Long, LatLon>,
        nodeIndex: Map<Long, Int>,
        out: MutableList<RoadEdge>,
    ) {
        val nodes = way.nodes
        if (nodes.size < 2) return

        val tags = way.tags
        val direction = onewayOf(tags)
        val speed = Speeds.speedKph(tags)
        val roundabout = tags["junction"].let { it == "roundabout" || it == "circular" }
        val name = tags["name"]?.takeIf { it.isNotBlank() }
        val ref = tags["ref"]?.takeIf { it.isNotBlank() }
        val highway = tags["highway"] ?: "road"

        var segmentStart: Long? = null
        var shape = ArrayList<LatLon>()

        for (nodeId in nodes) {
            val position = nodePositions[nodeId] ?: continue
            if (segmentStart == null) {
                if (nodeIndex.containsKey(nodeId)) {
                    segmentStart = nodeId
                    shape = arrayListOf(position)
                }
                continue
            }

            shape.add(position)
            if (!nodeIndex.containsKey(nodeId)) continue

            // Reached the next junction: emit the stretch behind us.
            val fromIndex = nodeIndex.getValue(segmentStart)
            val toIndex = nodeIndex.getValue(nodeId)
            if (fromIndex != toIndex && shape.size >= 2) {
                val coords = listToCoords(shape)
                val length = coordsLengthMeters(coords)
                if (length > 0.0) {
                    var forward: RoadEdge? = null
                    var backward: RoadEdge? = null

                    if (direction != Oneway.BACKWARD_ONLY) {
                        forward = RoadEdge(
                            index = out.size,
                            fromNode = fromIndex,
                            toNode = toIndex,
                            coords = coords,
                            lengthMeters = length,
                            speedKph = speed,
                            wayId = way.id,
                            name = name,
                            ref = ref,
                            highway = highway,
                            roundabout = roundabout,
                        )
                        out.add(forward)
                    }
                    if (direction != Oneway.FORWARD_ONLY) {
                        backward = RoadEdge(
                            index = out.size,
                            fromNode = toIndex,
                            toNode = fromIndex,
                            coords = reverseCoords(coords),
                            lengthMeters = length,
                            speedKph = speed,
                            wayId = way.id,
                            name = name,
                            ref = ref,
                            highway = highway,
                            roundabout = roundabout,
                        )
                        out.add(backward)
                    }
                    if (forward != null && backward != null) {
                        forward.reverseIndex = backward.index
                        backward.reverseIndex = forward.index
                    }
                }
            }

            segmentStart = nodeId
            shape = arrayListOf(position)
        }
    }

    /** Whether a car may use this way at all. */
    fun isDrivable(tags: Map<String, String>): Boolean {
        val highway = tags["highway"] ?: return false
        if (highway !in DRIVABLE) return false
        if (highway in NON_DRIVABLE_LIFECYCLE) return false
        if (tags["area"] == "yes") return false
        if (tags["construction"] != null && highway == "construction") return false

        // Explicit motor vehicle permissions win over the generic access tag.
        tags["motor_vehicle"]?.let { return it !in BLOCKED_ACCESS }
        tags["motorcar"]?.let { return it !in BLOCKED_ACCESS }
        tags["vehicle"]?.let { if (it in BLOCKED_ACCESS) return false }
        tags["access"]?.let { if (it in BLOCKED_ACCESS) return false }

        return true
    }

    enum class Oneway { BOTH, FORWARD_ONLY, BACKWARD_ONLY }

    /**
     * Resolves which directions of travel a way permits.
     *
     * Beyond the explicit `oneway` tag, OSM treats roundabouts and motorways as implicitly
     * one-way, and both conventions are relied on heavily in real data.
     */
    fun onewayOf(tags: Map<String, String>): Oneway {
        when (tags["oneway"]) {
            "yes", "true", "1" -> return Oneway.FORWARD_ONLY
            "-1", "reverse" -> return Oneway.BACKWARD_ONLY
            "no", "false", "0" -> return Oneway.BOTH
        }
        if (tags["junction"] == "roundabout" || tags["junction"] == "circular") return Oneway.FORWARD_ONLY
        if (tags["highway"] == "motorway" || tags["highway"] == "motorway_link") return Oneway.FORWARD_ONLY
        return Oneway.BOTH
    }
}
