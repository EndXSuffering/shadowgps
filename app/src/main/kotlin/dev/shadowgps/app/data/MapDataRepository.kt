package dev.shadowgps.app.data

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorParser
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.osm.OverpassQueries
import dev.shadowgps.core.osm.parseOverpassResponse
import dev.shadowgps.core.store.RegionMetadata
import dev.shadowgps.core.store.RegionPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

/** A routable area: the road network plus everything watching it. */
class AreaData(
    val bounds: BoundingBox,
    val graph: RoadGraph,
    val detectors: List<Detector>,
    /** The saved region this came from, or null when it was downloaded for this trip. */
    val savedRegion: SavedRegion? = null,
) {
    val isOffline: Boolean get() = savedRegion != null
}

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
    private val regions: RegionStore,
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
        onStage: (LoadStage) -> Unit = {},
    ): AreaData {
        val trip = BoundingBox.of(listOf(origin, destination))
        val padded = trip.expandMeters(paddingMeters)

        // An area already in memory is reused when it holds enough map. A downloaded one
        // has to cover the padded box, since that is all it ever fetched; a saved region
        // only has to cover the trip, because the rest of the region is itself the padding.
        cached?.let { area ->
            val sufficient =
                if (area.isOffline) area.bounds.contains(trip) else area.bounds.contains(padded)
            if (sufficient) {
                onStage(if (area.isOffline) LoadStage.OPENING_SAVED else LoadStage.READY)
                return area
            }
        }

        // Saved regions are checked before the size guard: the limit exists because
        // downloading and building a huge area on the phone is painful, and a region that
        // is already downloaded and already built is neither.
        loadFromSavedRegion(trip = trip, preferred = padded, onStage = onStage)?.let {
            cached = it
            return it
        }

        if (padded.areaKm2 > maxAreaKm2) throw AreaTooLargeException(padded.areaKm2, maxAreaKm2)

        onStage(LoadStage.DOWNLOADING)
        val area = load(padded)
        cached = area
        return area
    }

    /** What [loadFor] is doing, so the screen can say something true about the wait. */
    enum class LoadStage { OPENING_SAVED, DOWNLOADING, READY }

    /** Uses a saved region when one covers the trip, so it needs no network at all. */
    private suspend fun loadFromSavedRegion(
        trip: BoundingBox,
        preferred: BoundingBox,
        onStage: (LoadStage) -> Unit,
    ): AreaData? {
        val region = regions.regionCovering(trip = trip, preferred = preferred) ?: return null
        onStage(LoadStage.OPENING_SAVED)
        return runCatching {
            val payload = regions.load(region)
            AreaData(
                bounds = payload.metadata.bounds,
                graph = payload.graph,
                detectors = payload.detectors,
                savedRegion = region,
            )
        }.getOrElse {
            // A region that will not open is worse than none: drop it so the trip falls
            // through to a download instead of failing outright.
            regions.delete(region.id)
            null
        }
    }

    /**
     * Downloads an area and stores it for offline use.
     *
     * @param onProgress reports which stage is running, for a screen the user is watching.
     */
    suspend fun downloadRegion(
        id: String,
        name: String,
        box: BoundingBox,
        maxAreaKm2: Double = MAX_REGION_AREA_KM2,
        onProgress: (RegionProgress) -> Unit = {},
    ): SavedRegion {
        if (box.areaKm2 > maxAreaKm2) throw AreaTooLargeException(box.areaKm2, maxAreaKm2)

        onProgress(RegionProgress.ROADS)
        val roadsJson = overpass.query(OverpassQueries.roadNetwork(box, timeoutSeconds = 180), FRESH_ONLY)

        onProgress(RegionProgress.CAMERAS)
        val surveillanceJson = overpass.query(OverpassQueries.surveillance(box, timeoutSeconds = 120), FRESH_ONLY)

        onProgress(RegionProgress.BUILDING)
        val payload = withContext(Dispatchers.Default) {
            val graph = GraphBuilder.build(parseOverpassResponse(roadsJson).elements)
            val detectors = DetectorParser.parseAll(parseOverpassResponse(surveillanceJson).elements)
            RegionPayload(
                metadata = RegionMetadata(name = name, bounds = box, createdAtMillis = System.currentTimeMillis()),
                graph = graph,
                detectors = detectors,
            )
        }

        onProgress(RegionProgress.SAVING)
        val saved = regions.save(id, name, box, payload)

        // The freshly built graph is almost certainly what the next trip wants.
        cached = AreaData(box, payload.graph, payload.detectors, saved)
        return saved
    }

    /** Stages of a region download, for progress reporting. */
    enum class RegionProgress { ROADS, CAMERAS, BUILDING, SAVING }

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

        /**
         * Cap on a deliberately saved region.
         *
         * Larger than a single trip's allowance because the user asked for this one and is
         * watching it download, but still bounded by having to hold the built graph in
         * memory when the region is opened.
         */
        const val MAX_REGION_AREA_KM2 = 12_000.0

        private const val ROAD_CACHE_MILLIS = 14L * 24 * 60 * 60 * 1000
        private const val DETECTOR_CACHE_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** A region the user asked for should be current, not whatever is lying in the cache. */
        private const val FRESH_ONLY = 0L
    }
}
