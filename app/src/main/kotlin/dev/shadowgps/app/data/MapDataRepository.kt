package dev.shadowgps.app.data

import kotlin.coroutines.cancellation.CancellationException
import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorParser
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.GraphBuilder
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.osm.OsmElement
import dev.shadowgps.core.osm.OverpassQueries
import dev.shadowgps.core.osm.parseOverpassResponse
import dev.shadowgps.core.store.RegionMetadata
import dev.shadowgps.core.store.RegionPayload
import dev.shadowgps.core.store.StoredArea
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

/** A routable area: the road network plus everything watching it. */
class AreaData(
    val bounds: BoundingBox,
    val graph: RoadGraph,
    val detectors: List<Detector>,
    /** The stored region this came from, or null when it has only just been downloaded. */
    val savedRegion: SavedRegion? = null,
    /**
     * Where this area has car parks and driveways, when it does not have them everywhere.
     *
     * A trip downloads those only around its two ends. Null means everywhere: an area
     * fetched whole.
     */
    val serviceRoadAreas: List<BoundingBox>? = null,
    /** When the cameras were fetched, which can be more recently than the roads. */
    val camerasAtMillis: Long = System.currentTimeMillis(),
) {
    /** Came off the phone's own storage rather than the network. */
    val isOffline: Boolean get() = savedRegion != null

    private val coverage: StoredArea get() = StoredArea(bounds, serviceRoadAreas)

    /** Whether this area holds enough map to route a trip spanning [trip]. */
    fun suffices(trip: BoundingBox, needed: BoundingBox): Boolean = coverage.suffices(trip, needed)

    /** Whether the car parks and driveways around each of [points] are in this area. */
    fun hasServiceRoadsAround(points: List<LatLon>): Boolean = coverage.hasServiceRoadsAround(points)
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

    /** For work that should outlive whoever asked for it, such as storing a finished download. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** Called when the set of stored areas changes behind the caller's back. */
    @Volatile
    var onStoredAreasChanged: () -> Unit = {}

    private val keepingLocalArea = AtomicBoolean(false)

    @Volatile
    private var lastLocalAttemptAt = 0L

    /** Set when building the local area ran the phone out of memory; not tried again this run. */
    @Volatile
    private var localAreaTooBig = false

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
        val ends = listOf(origin, destination)
        val trip = BoundingBox.of(ends)
        val padding = paddingFor(origin, destination, paddingMeters)
        val padded = trip.expandMeters(padding)
        // What an area left behind by an earlier trip has to contain to be worth reusing:
        // the trip, and enough around it to go round the block.
        val needed = trip.expandMeters(minOf(padding, REUSE_MARGIN_METERS))

        // Anything already on the phone beats the network, by a wide margin: opening a
        // stored area takes a fraction of a second, and a download takes however long a
        // volunteer-run server feels like. So the order is memory, then storage, and the
        // network only when neither has the trip. An area that also has the car parks at
        // both ends is preferred, but one without still wins over a download — the trip
        // then starts or ends at the nearest street rather than inside the car park.
        val inMemory = cached?.takeIf { it.suffices(trip, needed) }
        if (inMemory != null && inMemory.hasServiceRoadsAround(ends)) return use(inMemory, onStage)

        val stored = regions.regionCovering(trip = trip, preferred = padded, needed = needed, ends = ends)
        val storedIsBetter = stored != null && stored.id != inMemory?.savedRegion?.id &&
            (inMemory == null || stored.toStoredArea().hasServiceRoadsAround(ends))
        if (stored != null && storedIsBetter) {
            // Checked before the size guard: the limit exists because downloading and
            // building a huge area on the phone is painful, and one that is already
            // downloaded and already built is neither.
            open(stored, onStage)?.let {
                cached = it
                return use(it, onStage)
            }
        }
        if (inMemory != null) return use(inMemory, onStage)

        if (padded.areaKm2 > maxAreaKm2) throw AreaTooLargeException(padded.areaKm2, maxAreaKm2)

        onStage(LoadStage.DOWNLOADING)
        val area = loadTrip(padded.snappedOutward(DOWNLOAD_GRID_DEGREES), ends)
        cached = area
        keep(area)
        return area
    }

    private fun use(area: AreaData, onStage: (LoadStage) -> Unit): AreaData {
        onStage(LoadStage.READY)
        area.savedRegion?.let { region -> background.launch { regions.markUsed(region.id) } }
        return area
    }

    /**
     * Stores a freshly downloaded trip area, so the next trip inside it needs no download.
     *
     * In the built form, not as the text it arrived in: reopening it is a read, where the
     * download cache would still have to be parsed and every junction rebuilt. Done in the
     * background — the driver is waiting for routes, not for a file to be written.
     */
    private fun keep(area: AreaData) {
        if (area.graph.isEmpty) return
        background.launch {
            try {
                val now = System.currentTimeMillis()
                val saved = regions.save(
                    id = "auto-trip-$now",
                    name = TRIP_AREA_NAME,
                    bounds = area.bounds,
                    payload = RegionPayload(
                        metadata = RegionMetadata(TRIP_AREA_NAME, area.bounds, now),
                        graph = area.graph,
                        detectors = area.detectors,
                    ),
                    automatic = true,
                    serviceRoadAreas = area.serviceRoadAreas,
                    camerasAtMillis = area.camerasAtMillis,
                )
                if (cached === area) {
                    cached = AreaData(
                        area.bounds, area.graph, area.detectors, saved, area.serviceRoadAreas, area.camerasAtMillis,
                    )
                }
                regions.pruneAutomatic(MAX_AUTOMATIC_AREAS, AUTOMATIC_MAX_AGE_MILLIS)
                onStoredAreasChanged()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Not being able to keep it costs a download next time, nothing more.
            }
        }
    }

    /** What [loadFor] is doing, so the screen can say something true about the wait. */
    enum class LoadStage { OPENING_SAVED, DOWNLOADING, READY }

    /** Opens a stored area, or returns null — and drops it — when its file cannot be read. */
    private suspend fun open(region: SavedRegion, onStage: (LoadStage) -> Unit = {}): AreaData? {
        onStage(LoadStage.OPENING_SAVED)
        return try {
            val payload = regions.load(region)
            AreaData(
                bounds = payload.metadata.bounds,
                graph = payload.graph,
                detectors = payload.detectors,
                savedRegion = region,
                serviceRoadAreas = region.serviceRoadAreas,
                camerasAtMillis = region.camerasFetchedAtMillis,
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
     * Gets the map around [position] into memory ahead of the first trip.
     *
     * Most trips start where the driver is. Opening the stored area for there as soon as
     * there is a fix means that by the time a destination has been typed, the map is already
     * loaded and the routes are a search away.
     */
    suspend fun warm(position: LatLon) {
        val here = BoundingBox.around(position, WARM_COVER_METERS)
        if (cached?.bounds?.contains(here) == true) return
        val region = regions.regionCovering(
            trip = here,
            preferred = BoundingBox.around(position, LOCAL_COVER_METERS),
            needed = here,
            ends = listOf(position),
        ) ?: return
        try {
            open(region)?.let { if (cached?.bounds?.contains(here) != true) cached = it }
        } catch (e: OutOfMemoryError) {
            // Only a head start. The trip itself will report it if memory is really short.
            cached = null
        }
    }

    /**
     * Makes sure the area around [position] is stored on the phone, fetching it if not.
     *
     * This is what makes everyday trips quick: with the surroundings already on the phone,
     * a trip inside them needs no download at all — and, as it happens, tells no server
     * anything, not even roughly where it is going. Fetched whole, car parks included, once,
     * in the background, and again when it is a couple of weeks old. A region the user saved
     * themselves that covers the same ground counts, and is left alone.
     *
     * @return whether an area was stored
     */
    suspend fun keepAreaAround(position: LatLon): Boolean {
        if (localAreaTooBig) return false
        val now = System.currentTimeMillis()
        if (now - lastLocalAttemptAt < LOCAL_RETRY_MILLIS) return false
        if (!keepingLocalArea.compareAndSet(false, true)) return false
        try {
            val surroundings = BoundingBox.around(position, LOCAL_COVER_METERS)
            val covering = regions.list().filter { it.serviceRoadAreas == null && it.bounds.contains(surroundings) }
            if (covering.any { !it.automatic || now - it.downloadedAtMillis < LOCAL_REFRESH_MILLIS }) return false

            lastLocalAttemptAt = now
            // One that has merely gone stale is refreshed in place; it stays usable meanwhile.
            val stale = covering.firstOrNull { it.automatic }
            downloadRegion(
                id = stale?.id ?: "auto-local-$now",
                name = LOCAL_AREA_NAME,
                box = stale?.bounds
                    ?: BoundingBox.around(position, LOCAL_RADIUS_METERS).snappedOutward(DOWNLOAD_GRID_DEGREES),
                automatic = true,
            )
            regions.pruneAutomatic(MAX_AUTOMATIC_AREAS, AUTOMATIC_MAX_AGE_MILLIS)
            return true
        } catch (e: IOException) {
            // Busy servers, no signal. Tried again later; nothing depends on it.
            return false
        } catch (e: OutOfMemoryError) {
            localAreaTooBig = true
            cached = null
            return false
        } finally {
            keepingLocalArea.set(false)
        }
    }

    /**
     * Brings the cameras in [area] up to date, when they are more than a few days old.
     *
     * Roads barely change from month to month; plate readers go up every week. A stored
     * area would otherwise freeze the cameras at whenever it was fetched, which for an app
     * about cameras is the wrong thing to let go stale. The camera list for an area is a
     * small download next to its roads, so it is fetched again by itself and written back.
     *
     * @return the area with its new cameras when the set actually changed — in which case
     *   routes planned on the old one deserve another look — or null when it did not, or
     *   could not be checked
     */
    suspend fun freshenCameras(area: AreaData): AreaData? {
        val now = System.currentTimeMillis()
        if (now - area.camerasAtMillis < CAMERA_REFRESH_MILLIS) return null

        val detectors = try {
            val json = overpass.query(
                OverpassQueries.surveillance(area.bounds, timeoutSeconds = 120),
                DETECTOR_CACHE_MILLIS,
            )
            withContext(Dispatchers.Default) { DetectorParser.parseAll(parseOverpassResponse(json).elements) }
        } catch (e: IOException) {
            return null
        }

        var region = area.savedRegion
        if (region != null) {
            region = try {
                regions.save(
                    id = region.id,
                    name = region.name,
                    bounds = region.bounds,
                    payload = RegionPayload(
                        metadata = RegionMetadata(region.name, region.bounds, region.downloadedAtMillis),
                        graph = area.graph,
                        detectors = detectors,
                    ),
                    automatic = region.automatic,
                    serviceRoadAreas = region.serviceRoadAreas,
                    camerasAtMillis = now,
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                region
            }
            onStoredAreasChanged()
        }

        val updated = AreaData(area.bounds, area.graph, detectors, region, area.serviceRoadAreas, now)
        val current = cached
        if (current === area || (region != null && current?.savedRegion?.id == region.id)) cached = updated
        return updated.takeIf { detectors.toSet() != area.detectors.toSet() }
    }

    /** Removes every area the app kept by itself. Regions the user saved are left alone. */
    suspend fun clearAutomaticAreas() {
        regions.pruneAutomatic(keep = 0, maxAgeMillis = Long.MAX_VALUE)
        cached = cached?.takeIf { it.savedRegion?.automatic != true }
        lastLocalAttemptAt = 0L
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
        automatic: Boolean = false,
        onProgress: (RegionProgress) -> Unit = {},
    ): SavedRegion {
        if (box.areaKm2 > maxAreaKm2) throw AreaTooLargeException(box.areaKm2, maxAreaKm2)

        // Neither answer goes into the download cache: the region file is the copy that
        // gets used, and the text it was built from is tens of megabytes nobody reads again.
        onProgress(RegionProgress.ROADS)
        val roadsJson = overpass.query(
            OverpassQueries.roadNetwork(box, timeoutSeconds = 180),
            FRESH_ONLY,
            isUsable = ::containsRoads,
            cacheResult = false,
        )

        onProgress(RegionProgress.CAMERAS)
        val surveillanceJson = overpass.query(
            OverpassQueries.surveillance(box, timeoutSeconds = 120),
            FRESH_ONLY,
            cacheResult = false,
        )

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
        val saved = regions.save(id, name, box, payload, automatic = automatic)

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
            detectors to serviceRoadsAround(serviceAreas)
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
     * The car parks and driveways in [areas], and the areas they actually cover.
     *
     * A trip can do without these — one that starts or ends in a car park is then routed from
     * or to the nearest road instead — so failing to get them is not worth failing the trip
     * over. But going without is a worse trip, and the usual reason for failing is a busy
     * server straight after the cameras were asked of it, so it is worth one more try once
     * the server has had a moment. Recorded as covering nowhere if that fails too, so the next
     * trip asks again.
     */
    private suspend fun serviceRoadsAround(areas: List<BoundingBox>): Pair<List<OsmElement>, List<BoundingBox>> {
        repeat(SERVICE_ROAD_ATTEMPTS) { attempt ->
            try {
                val json = overpass.query(OverpassQueries.serviceRoads(areas), ROAD_CACHE_MILLIS)
                return withContext(Dispatchers.Default) { parseOverpassResponse(json).elements } to areas
            } catch (e: IOException) {
                if (attempt < SERVICE_ROAD_ATTEMPTS - 1) delay(SERVICE_ROAD_RETRY_MILLIS)
            }
        }
        return emptyList<OsmElement>() to emptyList()
    }

    /**
     * How far past the trip itself to download.
     *
     * In proportion to the trip, within limits. A fixed three kilometres on every side was
     * right for a long trip and wildly more than a short one could use: a two-kilometre trip
     * downloaded an area nearly three times the size it needed, and the server takes longer
     * the more area it has to gather. Never less than enough for the router to go round the
     * block, nor for the fallback that ends a trip at the nearest road.
     */
    private fun paddingFor(origin: LatLon, destination: LatLon, maximum: Double): Double =
        (haversineMeters(origin, destination) * PADDING_PER_TRIP_METER)
            .coerceIn(minOf(MIN_PADDING_METERS, maximum), maximum)

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

        regions.regionCovering(trip = box)?.let { region -> open(region) }?.let { area ->
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

        /**
         * How much room past the trip an area left by an earlier trip must still have.
         *
         * Less than a trip is downloaded with, so the same commute from a start that has
         * moved a street over still fits what the first one left behind.
         */
        private const val REUSE_MARGIN_METERS = 1_000.0

        /** How far around the driver the automatically kept area reaches. */
        private const val LOCAL_RADIUS_METERS = 6_000.0

        /** How much of the driver's surroundings must already be stored to count as covered. */
        private const val LOCAL_COVER_METERS = 2_500.0

        private const val WARM_COVER_METERS = 300.0

        /** The automatically kept area is fetched again once it is this old. */
        private const val LOCAL_REFRESH_MILLIS = 14L * 24 * 60 * 60 * 1000

        /** After a failed attempt at the local area, how long before another. */
        private const val LOCAL_RETRY_MILLIS = 15L * 60 * 1000

        /** Cameras older than this are fetched again when their area is used. */
        private const val CAMERA_REFRESH_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** How many automatically kept areas there may be before the least used goes. */
        private const val MAX_AUTOMATIC_AREAS = 12

        /** An automatically kept area this old is dropped: its roads are due a fresh look. */
        private const val AUTOMATIC_MAX_AGE_MILLIS = 45L * 24 * 60 * 60 * 1000

        const val LOCAL_AREA_NAME = "Around you"
        const val TRIP_AREA_NAME = "Recent trip"

        /** Download margin per metre of trip, between the minimum and the default maximum. */
        private const val PADDING_PER_TRIP_METER = 0.4

        /** The least margin a trip is downloaded with. */
        private const val MIN_PADDING_METERS = 1_500.0

        private const val SERVICE_ROAD_ATTEMPTS = 2
        private const val SERVICE_ROAD_RETRY_MILLIS = 3_000L

        /** How far around each end of a trip car parks and driveways are fetched. */
        private const val SERVICE_ROAD_RADIUS_METERS = 1_000.0

        private val WAY_ELEMENT = Regex("\"type\"\\s*:\\s*\"way\"")

        private const val ROAD_CACHE_MILLIS = 14L * 24 * 60 * 60 * 1000
        private const val DETECTOR_CACHE_MILLIS = 3L * 24 * 60 * 60 * 1000

        /** A region the user asked for should be current, not whatever is lying in the cache. */
        private const val FRESH_ONLY = 0L
    }
}
