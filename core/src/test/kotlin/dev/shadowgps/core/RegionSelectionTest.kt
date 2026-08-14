package dev.shadowgps.core

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.store.RegionSelection
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Picking a saved area for a trip.
 *
 * The bug these pin: a trip is padded by kilometres before being downloaded, and gating
 * saved regions on that padded box meant a region saved from the visible map was rejected
 * for trips inside it — so the app re-downloaded the area the driver had just saved.
 */
class RegionSelectionTest {

    /** Roughly what the map shows at city zoom: a few kilometres across. */
    private val viewport = BoundingBox.of(
        listOf(LatLon(35.97, -78.93), LatLon(36.02, -78.86)),
    )

    /** A trip well inside that view. */
    private val trip = BoundingBox.of(
        listOf(LatLon(35.99, -78.91), LatLon(36.00, -78.88)),
    )

    private val padded = trip.expandMeters(3_000.0)

    @Test
    fun `the padded trip really does escape a viewport-sized region`() {
        // The premise of the bug. If this ever stops holding, the rest of these tests are
        // testing nothing.
        assertTrue(viewport.contains(trip), "the trip is inside the saved area")
        assertFalse(viewport.contains(padded), "but the trip plus routing padding is not")
    }

    @Test
    fun `a region covering the trip is used even without room for the padding`() {
        val chosen = RegionSelection.chooseIndex(listOf(viewport), trip, padded)

        assertEquals(0, chosen, "the saved area covers the trip and should be used")
    }

    @Test
    fun `a region with room for the padding is preferred`() {
        val roomy = padded.expandMeters(2_000.0)
        val regions = listOf(viewport, roomy)

        assertEquals(1, RegionSelection.chooseIndex(regions, trip, padded))
    }

    @Test
    fun `among equally valid regions the smallest is chosen`() {
        // Both cover the trip, neither has room for the full padding, so they compete at
        // the same tier and the tighter one wins: it opens faster and carries less
        // irrelevant map.
        val snug = trip.expandMeters(500.0)
        val looser = trip.expandMeters(1_500.0)
        val regions = listOf(looser, snug)

        assertFalse(looser.contains(padded), "neither candidate has room for the padding")
        assertEquals(1, RegionSelection.chooseIndex(regions, trip, padded))
    }

    @Test
    fun `a much larger region wins when only it has room for the padding`() {
        // The flip side: size is only a tie-break, never a reason to reject the one region
        // that gives the router space to detour.
        val wholeCounty = trip.expandMeters(50_000.0)
        val regions = listOf(wholeCounty, viewport)

        assertTrue(wholeCounty.contains(padded))
        assertFalse(viewport.contains(padded))
        assertEquals(0, RegionSelection.chooseIndex(regions, trip, padded))
    }

    @Test
    fun `among regions with room the smallest is still chosen`() {
        val justEnough = padded.expandMeters(500.0)
        val enormous = padded.expandMeters(60_000.0)
        val regions = listOf(enormous, justEnough)

        assertEquals(1, RegionSelection.chooseIndex(regions, trip, padded))
    }

    @Test
    fun `a region that does not cover the trip is refused`() {
        val elsewhere = BoundingBox.of(listOf(LatLon(40.0, -74.1), LatLon(40.1, -74.0)))

        assertEquals(RegionSelection.NONE, RegionSelection.chooseIndex(listOf(elsewhere), trip, padded))
    }

    @Test
    fun `a region covering only part of the trip is refused`() {
        val half = BoundingBox.of(listOf(LatLon(35.985, -78.915), LatLon(35.995, -78.895)))

        assertFalse(half.contains(trip))
        assertEquals(RegionSelection.NONE, RegionSelection.chooseIndex(listOf(half), trip, padded))
    }

    @Test
    fun `no saved regions means no choice`() {
        assertEquals(RegionSelection.NONE, RegionSelection.chooseIndex(emptyList(), trip, padded))
    }

    @Test
    fun `a region matching the trip exactly still counts as covering it`() {
        assertEquals(0, RegionSelection.chooseIndex(listOf(trip), trip, padded))
    }

    @Test
    fun `the right region is picked out of a realistic collection`() {
        val home = viewport
        val work = BoundingBox.of(listOf(LatLon(35.90, -79.10), LatLon(35.95, -79.00)))
        val holiday = BoundingBox.of(listOf(LatLon(51.4, -0.2), LatLon(51.6, 0.0)))
        val regions = listOf(work, holiday, home)

        assertEquals(2, RegionSelection.chooseIndex(regions, trip, padded))
    }
}
