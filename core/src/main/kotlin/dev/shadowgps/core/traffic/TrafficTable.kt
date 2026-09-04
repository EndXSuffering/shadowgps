package dev.shadowgps.core.traffic

import dev.shadowgps.core.graph.RoadGraph

/**
 * A [TrafficModel] resolved against one graph, ahead of routing.
 *
 * The router consults these numbers millions of times per search, so the per-road-class
 * lookup and division happen once per edge here rather than once per relaxation there.
 * Free-flow times are kept alongside so a route can report both what it will take now and
 * what it would take on empty roads — the difference being the only part of a traffic
 * estimate a driver can sanity-check.
 */
class TrafficTable(
    val graph: RoadGraph,
    val model: TrafficModel,
) {
    /** Seconds to traverse each directed edge under these conditions. */
    val edgeSeconds: DoubleArray = DoubleArray(graph.edgeCount) { model.travelSeconds(graph.edges[it]) }

    /** Seconds lost at each node under these conditions. */
    val nodeSeconds: DoubleArray =
        DoubleArray(graph.nodeCount) { model.junctionDelaySeconds(graph.nodeDelaySeconds[it]) }

    /**
     * Time each edge loses to congestion, over and above its free-flow cost.
     *
     * Separated out because avoiding traffic and simply being slowed by it are different
     * preferences: the delay is already paid in [edgeSeconds], and a driver who wants a
     * calmer route is asking to pay it more than once.
     */
    val edgeDelaySeconds: DoubleArray =
        DoubleArray(graph.edgeCount) { (edgeSeconds[it] - graph.edges[it].travelSeconds).coerceAtLeast(0.0) }

    /**
     * Time each node loses to congestion, over and above its free-flow delay.
     *
     * Queueing at junctions is the larger half of what a peak actually costs, so leaving it
     * out of the aversion would make "avoid heavy traffic" reroute around slow tarmac while
     * happily queueing through six sets of lights.
     */
    val nodeDelaySeconds: DoubleArray =
        DoubleArray(graph.nodeCount) { (nodeSeconds[it] - graph.nodeDelaySeconds[it]).coerceAtLeast(0.0) }

    /** Fraction of free-flow speed this edge is expected to manage. */
    fun speedFactor(edgeIndex: Int): Double = model.speedFactor(graph.edges[edgeIndex].highway)

    /** Congestion band for this edge, for drawing the route. */
    fun levelFor(edgeIndex: Int): CongestionLevel =
        if (!model.isSignificant) CongestionLevel.FREE else CongestionLevel.of(speedFactor(edgeIndex))

    /** The same figures with traffic ignored, for the comparison shown to the driver. */
    fun freeFlowEdgeSeconds(edgeIndex: Int): Double = graph.edges[edgeIndex].travelSeconds

    fun freeFlowNodeSeconds(node: Int): Double = graph.nodeDelaySeconds[node]

    val isActive: Boolean get() = model.isSignificant

    companion object {
        fun freeFlow(graph: RoadGraph): TrafficTable = TrafficTable(graph, TrafficModel.FREE_FLOW)
    }
}
