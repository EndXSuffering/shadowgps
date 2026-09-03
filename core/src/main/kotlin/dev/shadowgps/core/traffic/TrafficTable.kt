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

    /** The same figures with traffic ignored, for the comparison shown to the driver. */
    fun freeFlowEdgeSeconds(edgeIndex: Int): Double = graph.edges[edgeIndex].travelSeconds

    fun freeFlowNodeSeconds(node: Int): Double = graph.nodeDelaySeconds[node]

    val isActive: Boolean get() = model.isSignificant

    companion object {
        fun freeFlow(graph: RoadGraph): TrafficTable = TrafficTable(graph, TrafficModel.FREE_FLOW)
    }
}
