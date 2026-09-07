package dev.shadowgps.app.car

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import dev.shadowgps.app.nav.CarNavState
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.METERS_PER_DEGREE_LAT
import dev.shadowgps.core.geo.coordsCount
import dev.shadowgps.core.geo.metersPerDegreeLon
import kotlin.math.cos
import kotlin.math.sin

/**
 * The map on the car screen.
 *
 * Drawn from the road graph the router is already using rather than from map tiles, which
 * buys three things that matter in a car. It works with no signal, because the graph is
 * whatever was downloaded or saved for the trip. It is legible at a glance, because a
 * raster street map is dense with detail nobody needs at seventy miles an hour. And it needs
 * no view attached to a window, which a tile-drawing map view would, and there is no window
 * here — only a [android.view.Surface] handed over by the car.
 *
 * The projection is deliberately flat. Over the couple of kilometres visible on a car screen
 * the error from ignoring the curvature of the Earth is far below one pixel, and a flat
 * projection is a handful of multiplications rather than a page of trigonometry per point.
 */
class CarMapRenderer {

    private val roadPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val routePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    private val path = Path()

    fun draw(
        canvas: Canvas,
        state: CarNavState,
        width: Int,
        height: Int,
        dpi: Int,
        visibleArea: Rect? = null,
    ) {
        canvas.drawColor(GROUND)
        if (width <= 0 || height <= 0) return

        val centre = state.vehiclePosition ?: state.route?.geometry?.firstOrNull() ?: return
        val scale = (dpi.coerceAtLeast(120) / 160f)

        // Put the vehicle inside whatever the host has left uncovered, low down it for the
        // same reason as on the phone: half a map of road already driven is half a map
        // wasted.
        val area = visibleArea?.takeIf { !it.isEmpty } ?: Rect(0, 0, width, height)
        val projection = Projection(
            centre = centre,
            metersPerPixel = metersPerPixelFor(state, scale),
            rotationDegrees = state.vehicleHeadingDegrees ?: 0.0,
            originX = area.centerX().toFloat(),
            originY = area.top + area.height() * VEHICLE_SCREEN_POSITION,
        )

        val visible = projection.coverage(width, height)
        state.graph?.let { drawRoads(canvas, it, projection, visible, scale) }
        state.route?.let { drawRoute(canvas, it, projection, scale) }
        drawDetectors(canvas, state, projection, visible, scale)
        drawVehicle(canvas, projection, scale)
    }

    /**
     * How much ground fits on the screen.
     *
     * Closes in near a manoeuvre and pulls back out afterwards, for the same reason the phone
     * map does: a view wide enough to show the road ahead is too wide to show which lane
     * peels off at an exit.
     *
     * Divided by the screen density so a high-dpi head unit shows the same amount of road as
     * a low-dpi one rather than the same number of pixels — a car screen may be anything from
     * 120 to 320 dpi, and a fixed metres-per-pixel would be a different map on each.
     */
    private fun metersPerPixelFor(state: CarNavState, scale: Float): Double {
        val toManeuver = state.navigation?.distanceToManeuverMeters ?: Double.MAX_VALUE
        val base = if (toManeuver < NEAR_TURN_METERS) {
            NEAR_TURN_METERS_PER_PIXEL
        } else {
            CRUISING_METERS_PER_PIXEL
        }
        return base / scale
    }

    private fun drawRoads(
        canvas: Canvas,
        graph: dev.shadowgps.core.graph.RoadGraph,
        projection: Projection,
        visible: BoundingBox,
        scale: Float,
    ) {
        for (edge in graph.edges) {
            // Each stretch of road is in the graph twice, once per direction; drawing the
            // reverse would double the work for identical pixels.
            if (edge.reverseIndex in 0 until edge.index) continue
            if (!edge.bounds.intersects(visible)) continue

            val width = roadWidth(edge.highway) * scale
            if (width <= 0f) continue

            roadPaint.strokeWidth = width
            roadPaint.color = if (roadRank(edge.highway) >= 3) MAJOR_ROAD else MINOR_ROAD
            strokeCoords(canvas, edge.coords, projection, roadPaint)
        }
    }

    private fun drawRoute(
        canvas: Canvas,
        route: dev.shadowgps.core.routing.Route,
        projection: Projection,
        scale: Float,
    ) {
        routePaint.color = ROUTE_CASING
        routePaint.strokeWidth = 13f * scale
        strokePoints(canvas, route.geometry, projection, routePaint)

        routePaint.color = ROUTE
        routePaint.strokeWidth = 9f * scale
        strokePoints(canvas, route.geometry, projection, routePaint)
    }

    private fun drawDetectors(
        canvas: Canvas,
        state: CarNavState,
        projection: Projection,
        visible: BoundingBox,
        scale: Float,
    ) {
        for (detector in state.detectors) {
            if (!visible.contains(detector.position)) continue
            val (x, y) = projection.project(detector.position)
            fillPaint.color = WATCHED
            canvas.drawCircle(x, y, 5f * scale, fillPaint)
        }
    }

    /** A plain triangle. It has to read as "me, pointing that way" in a glance and nothing more. */
    private fun drawVehicle(canvas: Canvas, projection: Projection, scale: Float) {
        val x = projection.originX
        val y = projection.originY
        val size = 16f * scale

        path.reset()
        path.moveTo(x, y - size)
        path.lineTo(x - size * 0.62f, y + size * 0.75f)
        path.lineTo(x, y + size * 0.35f)
        path.lineTo(x + size * 0.62f, y + size * 0.75f)
        path.close()

        fillPaint.color = VEHICLE
        canvas.drawPath(path, fillPaint)
    }

    private fun strokeCoords(canvas: Canvas, coords: DoubleArray, projection: Projection, paint: Paint) {
        val count = coordsCount(coords)
        if (count < 2) return
        path.reset()
        for (i in 0 until count) {
            val (x, y) = projection.project(coords[i * 2], coords[i * 2 + 1])
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    private fun strokePoints(canvas: Canvas, points: List<LatLon>, projection: Projection, paint: Paint) {
        if (points.size < 2) return
        path.reset()
        points.forEachIndexed { index, point ->
            val (x, y) = projection.project(point)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        canvas.drawPath(path, paint)
    }

    /** Thicker for roads that carry more, which is what makes a bare line drawing readable. */
    private fun roadWidth(highway: String): Float = when (roadRank(highway)) {
        5 -> 7f
        4 -> 6f
        3 -> 5f
        2 -> 3.5f
        1 -> 2.5f
        else -> 0f
    }

    private fun roadRank(highway: String): Int = when (highway) {
        "motorway", "trunk" -> 5
        "motorway_link", "trunk_link", "primary" -> 4
        "primary_link", "secondary" -> 3
        "secondary_link", "tertiary", "tertiary_link" -> 2
        "unclassified", "residential", "living_street", "road" -> 1
        // Service roads and tracks are noise at this scale.
        else -> 0
    }

    private companion object {
        const val GROUND = 0xFF12161C.toInt()
        const val MINOR_ROAD = 0xFF3A4552.toInt()
        const val MAJOR_ROAD = 0xFF5C6B7E.toInt()
        const val ROUTE_CASING = 0xFF0B3C52.toInt()
        const val ROUTE = 0xFF38BDF8.toInt()
        const val VEHICLE = 0xFF4ADE80.toInt()
        const val WATCHED = 0xFFF87171.toInt()

        const val VEHICLE_SCREEN_POSITION = 0.72f
        /** Close enough to a turn that the junction matters more than the road ahead. */
        const val NEAR_TURN_METERS = 200.0

        const val CRUISING_METERS_PER_PIXEL = 3.2
        const val NEAR_TURN_METERS_PER_PIXEL = 1.1
    }
}

/**
 * Flat projection of the world onto the car screen, rotated so the driver's heading is up.
 *
 * Kept separate from the renderer so the geometry can be reasoned about on its own: every
 * point goes through the same three steps, and there is nowhere else for a sign error to
 * hide.
 */
class Projection(
    private val centre: LatLon,
    private val metersPerPixel: Double,
    rotationDegrees: Double,
    val originX: Float,
    val originY: Float,
) {
    private val cosR = cos(-rotationDegrees * Math.PI / 180.0)
    private val sinR = sin(-rotationDegrees * Math.PI / 180.0)
    private val metersPerLon = metersPerDegreeLon(centre.lat)

    fun project(point: LatLon): Pair<Float, Float> = project(point.lat, point.lon)

    fun project(lat: Double, lon: Double): Pair<Float, Float> {
        val east = (lon - centre.lon) * metersPerLon / metersPerPixel
        // Screen y grows downwards while latitude grows northwards.
        val north = -(lat - centre.lat) * METERS_PER_DEGREE_LAT / metersPerPixel

        val x = east * cosR - north * sinR
        val y = east * sinR + north * cosR
        return (originX + x).toFloat() to (originY + y).toFloat()
    }

    /**
     * The ground the screen could be showing.
     *
     * Deliberately generous: the map is rotated, so the box that covers every rotation is the
     * one drawn round the screen's diagonal. Being too generous only costs a few roads drawn
     * off-screen; being too tight would clip roads out of the corners as the car turned.
     */
    fun coverage(width: Int, height: Int): BoundingBox {
        val diagonal = Math.hypot(width.toDouble(), height.toDouble())
        return BoundingBox.around(centre, diagonal * metersPerPixel)
    }
}
