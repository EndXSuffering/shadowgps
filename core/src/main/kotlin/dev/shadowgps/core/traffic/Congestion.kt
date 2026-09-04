package dev.shadowgps.core.traffic

import kotlinx.serialization.Serializable

/**
 * How badly a stretch of road is expected to be moving.
 *
 * Four bands rather than a continuous number, because this is for a driver glancing at a
 * map: the useful question is "is that bit red?", not "is that bit 0.63?".
 */
@Serializable
enum class CongestionLevel(val label: String) {
    /** Moving at or near the road's normal speed. */
    FREE("Clear"),
    LIGHT("Slow"),
    HEAVY("Heavy"),
    SEVERE("Very heavy"),
    ;

    val isNotable: Boolean get() = this != FREE

    companion object {
        /**
         * Bands a speed factor.
         *
         * The thresholds are set so that the same rush hour puts a motorway in the worst
         * band and a residential street in the mildest — which is the point, since that is
         * how congestion actually distributes itself across a city.
         */
        fun of(speedFactor: Double): CongestionLevel = when {
            speedFactor >= 0.90 -> FREE
            speedFactor >= 0.75 -> LIGHT
            speedFactor >= 0.58 -> HEAVY
            else -> SEVERE
        }
    }
}

/**
 * A run of route with the same congestion band, measured along the route.
 *
 * Spans rather than per-edge values so the map can draw a handful of coloured stretches
 * instead of one polyline per road segment.
 */
@Serializable
data class CongestionSpan(
    val fromMeters: Double,
    val toMeters: Double,
    val level: CongestionLevel,
) {
    val lengthMeters: Double get() = (toMeters - fromMeters).coerceAtLeast(0.0)
}

/** Merges consecutive same-band stretches into spans. */
object CongestionSpans {

    /**
     * @param lengths distance of each traversed edge, in order
     * @param levels congestion band of each traversed edge, in order
     */
    fun build(lengths: DoubleArray, levels: List<CongestionLevel>): List<CongestionSpan> {
        require(lengths.size == levels.size) { "lengths and levels must line up" }
        if (lengths.isEmpty()) return emptyList()

        val spans = ArrayList<CongestionSpan>()
        var spanStart = 0.0
        var cumulative = 0.0
        var current = levels[0]

        for (i in lengths.indices) {
            if (levels[i] != current) {
                if (cumulative > spanStart) spans.add(CongestionSpan(spanStart, cumulative, current))
                spanStart = cumulative
                current = levels[i]
            }
            cumulative += lengths[i]
        }
        if (cumulative > spanStart) spans.add(CongestionSpan(spanStart, cumulative, current))
        return spans
    }
}
