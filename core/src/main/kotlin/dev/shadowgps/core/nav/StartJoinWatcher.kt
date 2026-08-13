package dev.shadowgps.core.nav

import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.graph.RoadGraph

/**
 * Watches for the moment a driver who started off the road network reaches it.
 *
 * When a trip is planned from a place the router cannot reach — a basement car park, the
 * middle of a site, a ferry — the route begins at the nearest road instead and the driver
 * makes their own way over. This decides when they have arrived somewhere the app can
 * genuinely guide from, so guidance can be replanned from where they actually are rather
 * than from the point that was guessed for them.
 *
 * "Somewhere routable" deliberately means more than "a road is nearby". A position with
 * 500 m of claimed error can sit on top of a road by luck, and starting turn-by-turn on
 * that basis would send the driver down a street they are not on. So a fix has to be
 * trustworthy as well as close, and it has to hold up across consecutive fixes.
 */
class StartJoinWatcher(
    private val graph: RoadGraph,
    private val config: Config = Config(),
) {
    data class Config(
        /** How close to a road counts as being on the network. */
        val joinSnapMeters: Double = RoadGraph.DEFAULT_SNAP_METERS,
        /**
         * Worst reported accuracy still trusted to prove a position. Deliberately looser
         * than a survey fix — plenty of phones sit around 50-80 m outdoors and would
         * otherwise never qualify.
         */
        val maxAccuracyMeters: Double = 100.0,
        /**
         * Speed above which the accuracy gate is waived. Nothing travels at this rate
         * anywhere but a road, so a moving fix that snaps is convincing on its own.
         */
        val movingSpeedMetersPerSecond: Double = 5.0,
        /** Consecutive qualifying fixes before declaring the driver has joined. */
        val requiredFixes: Int = 2,
    )

    private var streak = 0

    /** How many qualifying fixes have arrived in a row; exposed for diagnostics. */
    val consecutiveQualifyingFixes: Int get() = streak

    /**
     * Feeds in a position.
     *
     * @return the position to replan from once the driver is somewhere routable, or null
     *   while they are not there yet.
     */
    fun update(fix: PositionFix): LatLon? {
        if (!isTrustworthy(fix) || graph.snapNearest(fix.position, config.joinSnapMeters) == null) {
            streak = 0
            return null
        }

        streak++
        if (streak < config.requiredFixes) return null
        return fix.position
    }

    fun reset() {
        streak = 0
    }

    private fun isTrustworthy(fix: PositionFix): Boolean {
        val moving = (fix.speedMetersPerSecond ?: 0.0) >= config.movingSpeedMetersPerSecond
        if (moving) return true
        val accuracy = fix.accuracyMeters ?: return true
        return accuracy <= config.maxAccuracyMeters
    }
}
