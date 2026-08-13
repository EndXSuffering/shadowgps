package dev.shadowgps.core.geo

import kotlin.math.floor

/**
 * A uniform latitude/longitude grid for "what is near here" lookups.
 *
 * Queries are conservative: everything whose cells overlap the query box is returned, so
 * callers still have to do the exact distance test. That is exactly what the exposure model
 * wants, since it needs the precise distance anyway.
 */
class SpatialGrid<T>(private val cellSizeDegrees: Double = 0.004) {

    init {
        require(cellSizeDegrees > 0) { "cell size must be positive" }
    }

    private val cells = HashMap<Long, MutableList<T>>()

    var size: Int = 0
        private set

    fun insert(point: LatLon, item: T) {
        cellFor(point.lat, point.lon).add(item)
        size++
    }

    fun insert(box: BoundingBox, item: T) {
        val x0 = cellIndex(box.west)
        val x1 = cellIndex(box.east)
        val y0 = cellIndex(box.south)
        val y1 = cellIndex(box.north)
        for (x in x0..x1) {
            for (y in y0..y1) {
                cells.getOrPut(key(x, y)) { ArrayList() }.add(item)
            }
        }
        size++
    }

    /** Everything stored in cells overlapping [box]; may contain items just outside it. */
    fun query(box: BoundingBox): List<T> {
        val x0 = cellIndex(box.west)
        val x1 = cellIndex(box.east)
        val y0 = cellIndex(box.south)
        val y1 = cellIndex(box.north)
        val seen = LinkedHashSet<T>()
        for (x in x0..x1) {
            for (y in y0..y1) {
                cells[key(x, y)]?.let(seen::addAll)
            }
        }
        return seen.toList()
    }

    /** Everything within [radiusMeters] of [center], before the exact distance test. */
    fun queryRadius(center: LatLon, radiusMeters: Double): List<T> =
        query(BoundingBox.around(center, radiusMeters))

    private fun cellFor(lat: Double, lon: Double): MutableList<T> =
        cells.getOrPut(key(cellIndex(lon), cellIndex(lat))) { ArrayList() }

    private fun cellIndex(degrees: Double): Int = floor(degrees / cellSizeDegrees).toInt()

    private fun key(x: Int, y: Int): Long = (x.toLong() shl 32) xor (y.toLong() and 0xFFFF_FFFFL)
}
