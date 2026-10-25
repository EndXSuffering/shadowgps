package dev.shadowgps.core

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.osm.OverpassQueries
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Locale

class DownloadAreaTest {

    private val cell = 0.01

    @Test
    fun `a snapped box still covers what was asked for`() {
        val box = BoundingBox.of(listOf(LatLon(29.4237, -98.4936), LatLon(29.5112, -98.3801))).expandMeters(3_000.0)
        val snapped = box.snappedOutward(cell)
        assertTrue(snapped.contains(box))
        // ...and grows by less than one cell on any side.
        assertTrue(box.south - snapped.south < cell && snapped.north - box.north < cell)
        assertTrue(box.west - snapped.west < cell && snapped.east - box.east < cell)
    }

    /**
     * The point of snapping: the same commute, started from a GPS fix that has wandered a
     * few metres, asks for the same area — and so the same download, which the cache keys
     * by query text — instead of a fresh one every morning.
     */
    @Test
    fun `nearby starts ask for the same area`() {
        val destination = LatLon(29.5112, -98.3801)
        fun query(start: LatLon) = OverpassQueries.roadNetwork(
            BoundingBox.of(listOf(start, destination)).expandMeters(3_000.0).snappedOutward(cell),
            includeService = false,
        )
        val monday = query(LatLon(29.42371, -98.49361))
        val tuesday = query(LatLon(29.42379, -98.49352))
        assertEquals(monday, tuesday)
    }

    /**
     * Coordinates go to the server in the root locale whatever the phone's language. With a
     * decimal comma the box came out as `29,400000,…`, and every download failed.
     */
    @Test
    fun `coordinates are written with a decimal point in any locale`() {
        val previous = Locale.getDefault()
        try {
            for (locale in listOf(Locale.GERMANY, Locale.FRANCE, Locale.forLanguageTag("es-MX"))) {
                Locale.setDefault(locale)
                val text = BoundingBox(29.4, -98.5, 29.5, -98.4).toOverpassString()
                assertEquals("29.400000,-98.500000,29.500000,-98.400000", text, "in $locale")
            }
        } finally {
            Locale.setDefault(previous)
        }
    }

    @Test
    fun `a trip's through roads leave out service roads, which come separately`() {
        val box = BoundingBox(29.4, -98.5, 29.5, -98.4)
        val through = OverpassQueries.roadNetwork(box, includeService = false)
        assertFalse(through.contains("service"), through)
        assertTrue(OverpassQueries.roadNetwork(box).contains("service"))

        val ends = listOf(BoundingBox(29.40, -98.50, 29.41, -98.49), BoundingBox(29.49, -98.41, 29.50, -98.40))
        val service = OverpassQueries.serviceRoads(ends + ends.first())
        assertEquals(2, Regex("""way\["highway"="service"]""").findAll(service).count(), service)
        assertTrue(service.contains("out skel qt;"))
    }

    /** A way that comes back in two downloads must still be one road. */
    @Test
    fun `a way delivered twice is built once`() {
        val elements = Fixtures.grid(rows = 3, cols = 4)
        val once = GraphBuilder.build(elements)
        val twice = GraphBuilder.build(elements + elements.filter { it.isWay })
        assertEquals(once.edgeCount, twice.edgeCount)
        assertEquals(once.nodeCount, twice.nodeCount)
    }
}
