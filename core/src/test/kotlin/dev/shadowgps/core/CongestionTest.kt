package dev.shadowgps.core

import dev.shadowgps.core.traffic.CongestionLevel
import dev.shadowgps.core.traffic.CongestionSpans
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Banding and span-building: the bit that turns a per-edge model into something drawable.
 *
 * The spans are what the map paints, so the properties that matter are that they tile the
 * route without gaps or overlaps and that they never fragment a uniform stretch into a
 * hundred one-segment pieces.
 */
class CongestionTest {

    @Test
    fun `bands run from clear to very heavy`() {
        assertEquals(CongestionLevel.FREE, CongestionLevel.of(1.0))
        assertEquals(CongestionLevel.FREE, CongestionLevel.of(0.90))
        assertEquals(CongestionLevel.LIGHT, CongestionLevel.of(0.80))
        assertEquals(CongestionLevel.HEAVY, CongestionLevel.of(0.60))
        assertEquals(CongestionLevel.SEVERE, CongestionLevel.of(0.40))
    }

    @Test
    fun `only a clear road is unremarkable`() {
        assertFalse(CongestionLevel.FREE.isNotable)
        assertTrue(CongestionLevel.LIGHT.isNotable)
        assertTrue(CongestionLevel.SEVERE.isNotable)
    }

    @Test
    fun `consecutive edges in the same band become one span`() {
        val spans = CongestionSpans.build(
            lengths = doubleArrayOf(100.0, 150.0, 250.0),
            levels = listOf(CongestionLevel.FREE, CongestionLevel.FREE, CongestionLevel.FREE),
        )

        assertEquals(1, spans.size)
        assertEquals(0.0, spans.single().fromMeters)
        assertEquals(500.0, spans.single().toMeters)
        assertEquals(CongestionLevel.FREE, spans.single().level)
    }

    @Test
    fun `spans tile the route end to end`() {
        val lengths = doubleArrayOf(100.0, 100.0, 200.0, 50.0, 50.0)
        val spans = CongestionSpans.build(
            lengths = lengths,
            levels = listOf(
                CongestionLevel.FREE,
                CongestionLevel.HEAVY,
                CongestionLevel.HEAVY,
                CongestionLevel.SEVERE,
                CongestionLevel.FREE,
            ),
        )

        assertEquals(4, spans.size, "four runs, not five edges")
        assertEquals(0.0, spans.first().fromMeters)
        assertEquals(lengths.sum(), spans.last().toMeters)
        // No gaps and no overlaps: each span starts exactly where the last one stopped.
        for (i in 1 until spans.size) {
            assertEquals(spans[i - 1].toMeters, spans[i].fromMeters)
        }
        assertEquals(lengths.sum(), spans.sumOf { it.lengthMeters })
        assertEquals(300.0, spans.first { it.level == CongestionLevel.HEAVY }.lengthMeters)
    }

    @Test
    fun `an empty route has no spans`() {
        assertTrue(CongestionSpans.build(DoubleArray(0), emptyList()).isEmpty())
    }

    @Test
    fun `a zero length edge cannot open an empty span`() {
        // Degenerate edges do turn up in OSM data; they must not litter the map with
        // zero-length coloured stubs.
        val spans = CongestionSpans.build(
            lengths = doubleArrayOf(0.0, 120.0),
            levels = listOf(CongestionLevel.SEVERE, CongestionLevel.FREE),
        )

        assertEquals(1, spans.size)
        assertEquals(CongestionLevel.FREE, spans.single().level)
        assertEquals(120.0, spans.single().lengthMeters)
    }
}
