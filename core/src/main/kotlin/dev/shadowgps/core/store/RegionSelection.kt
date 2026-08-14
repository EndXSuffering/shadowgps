package dev.shadowgps.core.store

import dev.shadowgps.core.geo.BoundingBox

/**
 * Chooses which saved area to route a trip inside.
 *
 * The subtlety that makes this worth isolating: a trip is normally downloaded with several
 * kilometres of padding around it, because the router needs roads either side of the direct
 * line to find a way around a camera. It is tempting to reuse that padded box when asking
 * whether a saved region covers the trip — and it is wrong. A region saved from the visible
 * map is only viewport-sized, so almost any trip inside it produces a padded box that spills
 * over the edges, and the region gets rejected in favour of downloading the very area the
 * driver already saved.
 *
 * A saved region is its own padding: whatever it holds beyond the trip is exactly the room
 * the router needs. So coverage is decided on the trip itself, while the padded box is only
 * a preference used to pick between regions that all qualify.
 */
object RegionSelection {

    /** No saved region covers the trip. */
    const val NONE: Int = -1

    /**
     * Index of the region to use, or [NONE].
     *
     * @param regions bounds of each saved region, in the caller's own order
     * @param trip the area the trip actually spans; a region must contain this
     * @param preferred the trip plus routing padding; regions containing it win, because
     *   they leave the router the most room, but failing to contain it is not disqualifying
     */
    fun chooseIndex(regions: List<BoundingBox>, trip: BoundingBox, preferred: BoundingBox): Int {
        // Among equally valid regions, the smallest is the best fit: it holds the least
        // irrelevant map, so it opens fastest and keeps the routing graph tightest.
        val roomy = smallestContaining(regions, preferred)
        if (roomy != NONE) return roomy
        return smallestContaining(regions, trip)
    }

    private fun smallestContaining(regions: List<BoundingBox>, box: BoundingBox): Int {
        var best = NONE
        var bestArea = Double.MAX_VALUE
        for (index in regions.indices) {
            val candidate = regions[index]
            if (!candidate.contains(box)) continue
            val area = candidate.areaKm2
            if (area < bestArea) {
                bestArea = area
                best = index
            }
        }
        return best
    }
}
