package dev.shadowgps.core.store

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon

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

    /**
     * Index of the stored area to route a trip in, or [NONE].
     *
     * Stored areas come in two kinds, and they qualify differently. A complete one — saved
     * on purpose, or kept around the driver — is its own padding, as above, and only has to
     * contain the trip. One left behind by an earlier trip's download is exactly as big as
     * that trip needed, so a new trip has to sit inside it with room to spare, or the
     * router would have nowhere to go round anything.
     *
     * Among those that qualify, one that has the car parks and driveways at both of this
     * trip's ends comes first, then one with room for the full padding, then the smallest.
     *
     * @param needed the trip plus the least room the router should have; a partial area must
     *   contain this
     */
    fun choose(
        areas: List<StoredArea>,
        trip: BoundingBox,
        preferred: BoundingBox,
        needed: BoundingBox,
        ends: List<LatLon>,
    ): Int {
        var best = NONE
        var bestRank = Int.MAX_VALUE
        var bestSize = Double.MAX_VALUE
        for (index in areas.indices) {
            val area = areas[index]
            if (!area.suffices(trip, needed)) continue
            val rank = (if (area.hasServiceRoadsAround(ends)) 0 else 2) +
                (if (area.bounds.contains(preferred)) 0 else 1)
            val size = area.bounds.areaKm2
            if (rank < bestRank || (rank == bestRank && size < bestSize)) {
                best = index
                bestRank = rank
                bestSize = size
            }
        }
        return best
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

/**
 * What a stored area covers, as far as choosing one for a trip is concerned.
 *
 * @property serviceRoadAreas where the area has car parks and driveways, when it does not
 *   have them everywhere. A trip's own download fetches those only around its two ends.
 *   Null means everywhere: an area fetched whole.
 */
data class StoredArea(
    val bounds: BoundingBox,
    val serviceRoadAreas: List<BoundingBox>? = null,
) {
    /** Fetched whole, with every kind of road everywhere in it. */
    val isComplete: Boolean get() = serviceRoadAreas == null

    /** Whether this area holds enough map to route a trip spanning [trip]. */
    fun suffices(trip: BoundingBox, needed: BoundingBox): Boolean =
        if (isComplete) bounds.contains(trip) else bounds.contains(needed)

    /** Whether the car parks and driveways around each of [points] are in this area. */
    fun hasServiceRoadsAround(points: List<LatLon>): Boolean {
        val areas = serviceRoadAreas ?: return true
        return points.all { point ->
            val near = BoundingBox.around(point, SERVICE_ROAD_MARGIN_METERS)
            areas.any { it.contains(near) }
        }
    }

    private companion object {
        /** How much of a car park around an end has to be present to count as covered. */
        const val SERVICE_ROAD_MARGIN_METERS = 300.0
    }
}
