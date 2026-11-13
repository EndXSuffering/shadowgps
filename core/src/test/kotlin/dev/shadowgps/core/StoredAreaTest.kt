package dev.shadowgps.core

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.store.RegionSelection
import dev.shadowgps.core.store.StoredArea
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Choosing among areas already on the phone, which is what decides whether a trip needs the
 * network at all.
 */
class StoredAreaTest {

    private val home = LatLon(29.470, -98.700)
    private val shop = LatLon(29.490, -98.670)
    private val ends = listOf(home, shop)
    private val trip = BoundingBox.of(ends)
    private val padded = trip.expandMeters(1_500.0)
    private val needed = trip.expandMeters(1_000.0)

    private fun choose(vararg areas: StoredArea) =
        RegionSelection.choose(areas.toList(), trip, padded, needed, ends)

    /** What an earlier trip's download leaves: its padded box, car parks at its two ends. */
    private fun tripArea(box: BoundingBox, vararg serviceAt: LatLon) =
        StoredArea(box, serviceAt.map { BoundingBox.around(it, 1_000.0) })

    @Test
    fun `nothing stored means nothing chosen`() {
        assertEquals(RegionSelection.NONE, choose())
    }

    @Test
    fun `a complete area only has to contain the trip`() {
        val snug = StoredArea(trip.expandMeters(100.0))
        assertTrue(snug.isComplete)
        assertEquals(0, choose(snug))
    }

    /** The same box left by a trip download has no room to go round anything. */
    @Test
    fun `an earlier trip's area needs room to spare`() {
        assertEquals(RegionSelection.NONE, choose(tripArea(trip.expandMeters(100.0), home, shop)))
        assertEquals(0, choose(tripArea(trip.expandMeters(1_200.0), home, shop)))
    }

    /**
     * The repeat-trip case this exists for: the same commute from a start a few metres off
     * still fits the area the first one left behind, so it needs no download.
     */
    @Test
    fun `a repeat trip from a slightly different start reuses the first one's area`() {
        val first = tripArea(padded, home, shop)
        val nudged = listOf(LatLon(home.lat + 0.0003, home.lon - 0.0003), shop)
        val nudgedTrip = BoundingBox.of(nudged)
        val chosen = RegionSelection.choose(
            listOf(first),
            nudgedTrip,
            nudgedTrip.expandMeters(1_500.0),
            nudgedTrip.expandMeters(1_000.0),
            nudged,
        )
        assertEquals(0, chosen)
    }

    @Test
    fun `an area with car parks at both ends beats one without`() {
        val elsewhere = LatLon(29.480, -98.690)
        val without = tripArea(padded, elsewhere)
        val with = tripArea(padded.expandMeters(2_000.0), home, shop)
        assertFalse(without.hasServiceRoadsAround(ends))
        assertTrue(with.hasServiceRoadsAround(ends))
        assertEquals(1, choose(without, with))
        // ...but one without is still better than the network.
        assertEquals(0, choose(without))
    }

    @Test
    fun `among equals the smallest wins`() {
        val small = StoredArea(padded.expandMeters(500.0))
        val large = StoredArea(padded.expandMeters(20_000.0))
        assertEquals(1, choose(large, small))
    }

    @Test
    fun `an area that misses an end is never chosen`() {
        val half = StoredArea(BoundingBox.around(home, 1_000.0))
        assertEquals(RegionSelection.NONE, choose(half))
    }
}
