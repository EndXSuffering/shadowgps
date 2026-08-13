package dev.shadowgps.app.data

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorParser
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.osm.OverpassQueries
import dev.shadowgps.core.osm.parseOverpassResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A routable area: the road network plus everything watching it. */
class AreaData(
    val bounds: BoundingBox,
    val graph: RoadGraph,
    val detectors: List<Detector>,
)

/** Raised when the requested trip is too large to route on-device. */
class AreaTooLargeException(val areaKm2: Double, val limitKm2: Double) :
    IOException("Area of ${areaKm2.toInt()} km² exceeds the ${limitKm2.toInt()} km² limit")

/**
 * Downloads and assembles the data one trip needs.
 *
 * Everything happens on the phone: the road graph and the camera positions are fetched
 * from OpenStreetMap, and routing runs locally. No origin or destination is ever sent to a
 * routing service, which for an app about not being tracked is the whole point.
 */
class MapDataRepository(
    private val overpass: OverpassClient,
) {
    /** The most recently built area, reused whenever the next trip fits inside it. */
    @Volatile
    private var cached: AreaData? = null

    /**
     * Loads everything needed to route between two points.
     *
     * The download box is the trip's own bounding box, padded by [paddingMeters] so the
     * router has room to detour around whatever it finds. That padding is what makes
     * avoidance possible at all: a corridor exactly as wide as the direct line has nowhere
     * else to go.
     */
    suspend fun loadFor(
        origin: LatLon,
        destination: LatLon,
        paddingMeters: Double = DEFAULT_PADDING_METERS,
        maxAreaKm2: Double = MAX_AREA_KM2,
    ): AreaData {
        val box = BoundingBox.of(listOf(origin, destination)).expandMeters(paddingMeters)
        if (box.areaKm2 > maxAreaKm2) throw AreaTooLargeException(box.areaKm2, maxAreaKm2)

        cached?.let { if (it.bounds.contains(box)) return it }

        val area = load(box)
        cached = area
        return area
    }

    /** Loads one explicit box, e.g. to show cameras around the map view. */
    suspend fun load(box: BoundingBox): AreaData = withContext(Dispatchers.Default) {
        val roadsJson = overpass.query(OverpassQueries.roadNetwork(box), ROAD_CACHE_MILLIS)
        val surveillanceJson = overpass.query(OverpassQueries.surveillance(box), DETECTOR_CACHE_MILLIS)

        val graph = GraphBuilder.build(parseOverpassResponse(roadsJson).elements)
        val detectors = DetectorParser.parseAll(parseOverpassResponse(surveillanceJson).elements)

        AreaData(bounds = box, graph = graph, detectors = detectors)
    }

    /**
     * Just the surveillance layer for a box, without the road network.
     *
     * Used to keep the map's camera markers populated as the user pans, which should not
     * drag a multi-megabyte road download along with it.
     */
    suspend fun loadDetectors(box: BoundingBox): List<Detector> = withContext(Dispatchers.Default) {
        val json = overpass.query(OverpassQueries.surveillance(box), DETECTOR_CACHE_MILLIS)
        DetectorParser.parseAll(parseOverpassResponse(json).elements)
    }

    fun cachedArea(): AreaData? = cached

    fun forget() {
        cached = null
    }

    companion object {
        /**
         * How far outside the direct line to download.
         *
         * Wide enough for a real detour around a camera, narrow enough that a cross-town
         * trip stays a few megabytes.
         */
        const val DEFAULT_PADDING_METERS = 3_000.0

        /** On-device routing budget. Beyond this the graph stops fitting comfortably in RAM. */
        const val MAX_AREA_KM2 = 4_000.0

        private const val ROAD_CACHE_MILLIS = 14L * 24 * 60 * 60 * 1000
        private const val DETECTOR_CACHE_MILLIS = 3L * 24 * 60 * 60 * 1000
    }
}
