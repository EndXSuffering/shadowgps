package dev.shadowgps.app.data

import kotlin.coroutines.cancellation.CancellationException
import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorParser
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.osm.OverpassQueries
import dev.shadowgps.core.osm.parseOverpassResponse
import dev.shadowgps.core.store.RegionMetadata
import dev.shadowgps.core.store.RegionPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.IOException

/** A routable area: the road network plus everything watching it. */
class AreaData(
    val bounds: BoundingBox,
    val graph: RoadGraph,
    val detectors: List<Detector>,
    /** The saved region this came from, or null when it was downloaded for this trip. */
    val savedRegion: SavedRegion? = null,
    /**
     * Where this area has car parks and driveways, when it does not have them everywhere.
     *
     * A trip downloads those only around its two ends, so the same area is not enough for a
     * later trip that starts or ends somewhere else in it. Null means everywhere: a saved
     * region, fetched whole.
     */
    val serviceRoadAreas: List<BoundingBox>? = null,
) {
    val isOffline: Boolean get() = savedRegion != null

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
        val ends = listOf(origin, destination)

        // An area already in memory is reused when it holds enough map. A downloaded one
        // has to cover the padded box, since that is all it ever fetched, and have the car
        // parks at both of this trip's ends; a saved region only has to cover the trip,
        // because the rest of the region is itself the padding.
        cached?.let { area ->
            val sufficient = if (area.isOffline) {
                area.bounds.contains(trip)
            } else {
                area.bounds.contains(padded) && area.hasServiceRoadsAround(ends)
            }
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
        val area = loadTrip(padded.snappedOutward(DOWNLOAD_GRID_DEGREES), ends)
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
        return try {
            val payload = regions.load(region)
            AreaData(
                bounds = payload.metadata.bounds,
                graph = payload.graph,
                detectors = payload.detectors,
                savedRegion = region,
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A region that will not open is worse than none: drop it so the trip falls
            // through to a download instead of failing outright.
            //
            // Only for a failure of the file, though. This used to catch everything, so
            // running short of memory while opening a large region — a passing condition
            // the caller already knows how to recover from — permanently erased a map the
            // user had downloaded on purpose for a trip without signal. Errors propagate.
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

    /**
     * Downloads what one trip needs: through roads across [box], every device in it, and
     * the car parks and driveways around the trip's [ends].
     *
     * Three downloads, run as two lanes side by side. The through roads are by far the
     * largest, so they get a lane to themselves; the cameras and the two ends' service roads
     * are small and share the other. Each answer is parsed the moment it lands, while the
     * other lane is still downloading. Public Overpass servers give each client a couple of
     * slots, so two lanes is what fits — more would only queue, or be refused as busy.
     *
     * Service roads were part of the one big download until this. Across a whole trip's
     * area they are no use — nobody's best route runs through a car park — but in an
     * American city they are a large share of everything mapped: every parking aisle and
     * driveway, each with dozens of points.
     */
    private suspend fun loadTrip(box: BoundingBox, ends: List<LatLon>): AreaData = coroutineScope {
        val serviceAreas = ends
            .map { BoundingBox.around(it, SERVICE_ROAD_RADIUS_METERS).snappedOutward(DOWNLOAD_GRID_DEGREES) }
            .distinct()

        val throughRoads = async {
            val json = overpass.query(
                OverpassQueries.roadNetwork(box, includeService = false),
                ROAD_CACHE_MILLIS,
                isUsable = ::containsRoads,
            )
            withContext(Dispatchers.Default) { parseOverpassResponse(json).elements }
        }
        val camerasAndEnds = async {
            val cameraJson = overpass.query(OverpassQueries.surveillance(box), DETECTOR_CACHE_MILLIS)
            val detectors = withContext(Dispatchers.Default) {
                DetectorParser.parseAll(parseOverpassResponse(cameraJson).elements)
            }
            // A trip can do without these — one that starts in a car park is then routed
            // from the nearest road instead — so failing to get them is not worth failing
            // the trip over. Recorded as covering nowhere, so the next trip tries again.
            val service = try {
                val json = overpass.query(OverpassQueries.serviceRoads(serviceAreas), ROAD_CACHE_MILLIS)
                withContext(Dispatchers.Default) { parseOverpassResponse(json).elements } to serviceAreas
            } catch (e: IOException) {
                emptyList<OsmElement>() to emptyList()
            }
            detectors to service
        }

        val roads = throughRoads.await()
        val (detectors, service) = camerasAndEnds.await()
        val (serviceElements, coveredEnds) = service
        withContext(Dispatchers.Default) {
            AreaData(
                bounds = box,
                graph = GraphBuilder.build(roads + serviceElements),
                detectors = detectors,
                serviceRoadAreas = coveredEnds,
            )
        }
    }

    /**
     * Whether a road-network answer has any roads in it.
     *
     * A trip's area always does. One that comes back empty is a server that answered for
     * somewhere else, or ran out of time without saying so — and caching it would make every
     * route in the area look like open country for the next two weeks.
     */
    private fun containsRoads(json: String): Boolean = WAY_ELEMENT.containsMatchIn(json)

    /**
     * Just the surveillance layer for a box, without the road network.
     *
     * Keeps the map's camera markers populated as the user pans, without dragging a
     * multi-megabyte road download along with it.
     *
     * Local sources come first and matter more than they look. Going to Overpass for every
     * pan is why cameras used to trail the map by seconds, and it meant a saved region —
     * which already contains every camera in it — still showed an empty map with no signal.
     * Anything already in memory or on disk answers immediately and offline.
     */
    suspend fun loadDetectors(box: BoundingBox): List<Detector> {
        detectorsFromMemory(box)?.let { return it }

        loadFromSavedRegion(trip = box, preferred = box, onStage = {})?.let { area ->
            // Holding the region also means the trip planned from here needs no download.
            cached = area
            return area.detectors.filter { box.contains(it.position) }
        }

        return withContext(Dispatchers.Default) {
            val json = overpass.query(OverpassQueries.surveillance(box), DETECTOR_CACHE_MILLIS)
            DetectorParser.parseAll(parseOverpassResponse(json).elements)
        }
    }

    private fun detectorsFromMemory(box: BoundingBox): List<Detector>? {
        val area = cached ?: return null
        if (!area.bounds.contains(box)) return null
        return area.detectors.filter { box.contains(it.position) }
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

        /**
         * The grid trip downloads are snapped to, in degrees — about a kilometre.
         *
         * The download cache is keyed by query, and a box built straight from a GPS fix is
         * never the same twice, so the same commute downloaded the whole area afresh every
         * day. Snapped, a start that has wandered a few metres asks for exactly the same
         * area as last time, and gets it from the cache. Grows each side by half a cell on
         * average, a few percent of a typical trip's area.
         */
        private const val DOWNLOAD_GRID_DEGREES = 0.01

        /** How far around each end of a trip car parks and driveways are fetched. */
        private const val SERVICE_ROAD_RADIUS_METERS = 1_000.0

        private val WAY_ELEMENT = Regex("\"type\"\\s*:\\s*\"way\"")

        private const val ROAD_CACHE_MILLIS = 14L * 24 * 60 * 60 * 1000
        private const val DETECTOR_CACHE_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** A region the user asked for should be current, not whatever is lying in the cache. */
        private const val FRESH_ONLY = 0L
    }
}
