package dev.shadowgps.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shadowgps.app.data.AppSettings
import dev.shadowgps.app.data.AreaData
import dev.shadowgps.app.data.AreaTooLargeException
import dev.shadowgps.app.data.MapDataRepository
import dev.shadowgps.app.data.DiskCache
import dev.shadowgps.app.data.GeocodingClient
import dev.shadowgps.app.data.OverpassClient
import dev.shadowgps.app.data.Place
import dev.shadowgps.app.data.PlaceBook
import dev.shadowgps.app.data.SavedPlace
import dev.shadowgps.app.data.RegionStore
import dev.shadowgps.app.data.SavedRegion
import dev.shadowgps.app.data.SettingsStore
import dev.shadowgps.app.location.LocationSource
import dev.shadowgps.app.nav.NavigationBanner
import dev.shadowgps.app.nav.NavigationHub
import dev.shadowgps.app.nav.NavigationService
import dev.shadowgps.app.nav.Speaker
import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.format.Formatting
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.nav.Announcement
import dev.shadowgps.core.nav.NavigationConfig
import dev.shadowgps.core.nav.NavigationEngine
import dev.shadowgps.core.nav.NavigationState
import dev.shadowgps.core.nav.PositionFix
import dev.shadowgps.core.nav.StartJoinWatcher
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.ProvisionalStart
import dev.shadowgps.core.routing.Route
import dev.shadowgps.core.routing.RouteFailure
import dev.shadowgps.core.routing.RoutePlanner
import dev.shadowgps.core.routing.RoutingOptions
import dev.shadowgps.core.routing.SnapRadius
import dev.shadowgps.core.traffic.CONGESTION_AVERSION
import dev.shadowgps.core.traffic.TrafficModel
import java.time.LocalDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/** Progress of a region download the user is watching. */
data class RegionDownloadState(
    val name: String,
    val stage: MapDataRepository.RegionProgress,
    val areaKm2: Double,
) {
    val message: String
        get() = when (stage) {
            MapDataRepository.RegionProgress.ROADS -> "Downloading roads for $name…"
            MapDataRepository.RegionProgress.CAMERAS -> "Downloading cameras for $name…"
            MapDataRepository.RegionProgress.BUILDING -> "Building the map…"
            MapDataRepository.RegionProgress.SAVING -> "Saving to this device…"
        }
}

/**
 * What a finished trip cost in surveillance.
 *
 * Shown on arrival because it is the one number this app exists to move, and a driver has
 * no other way to know it: the cameras are behind them by then. Counted from devices
 * actually passed rather than from the plan, so a reroute half way is reflected honestly.
 */
data class TripSummary(
    val seenByCount: Int,
    val countsByKind: Map<DetectorKind, Int>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val destinationName: String?,
) {
    val wasUnseen: Boolean get() = seenByCount == 0
}

enum class Phase {
    /** Nothing chosen yet: the map and whatever cameras are nearby. */
    BROWSING,

    /** Downloading data or searching for routes. */
    WORKING,

    /** Routes computed, waiting for the driver to pick one. */
    CHOOSING,

    NAVIGATING,
}

data class MainUiState(
    val phase: Phase = Phase.BROWSING,
    val busyMessage: String? = null,
    val error: String? = null,
    val locationGranted: Boolean = false,
    val locationEnabled: Boolean = true,
    val userFix: PositionFix? = null,
    val origin: Place? = null,
    val destination: Place? = null,
    val routes: List<Route> = emptyList(),
    val selectedRouteIndex: Int = 0,
    val detectors: List<Detector> = emptyList(),
    val navigation: NavigationState? = null,
    val settings: AppSettings = AppSettings(),
    val query: String = "",
    val suggestions: List<Place> = emptyList(),
    val searching: Boolean = false,
    /** The last query a search actually completed for, so "no matches" can be said out loud. */
    val searchedQuery: String? = null,
    val isRerouting: Boolean = false,
    /** Set when the route starts away from the driver, because nothing drivable is nearer. */
    val provisionalStart: ProvisionalStart? = null,
    /** True while the driver makes their own way to that start and guidance waits. */
    val awaitingJoin: Boolean = false,
    /** Bumped to ask the map to jump back to the driver's position. */
    val recenterTick: Int = 0,
    /** Areas kept on the device for offline routing. */
    val savedRegions: List<SavedRegion> = emptyList(),
    /** Non-null while a region is downloading. */
    val regionDownload: RegionDownloadState? = null,
    /** Set when the routes on screen came from a saved region rather than the network. */
    val routedOffline: Boolean = false,
    /** Whatever the map is currently showing, for "save this area". */
    val viewport: BoundingBox? = null,
    /** Looking at the whole route mid-drive, instead of following the driver. */
    val overview: Boolean = false,
    val savedPlaces: List<SavedPlace> = emptyList(),
    val recentPlaces: List<SavedPlace> = emptyList(),
    /** Conditions the routes on screen were planned under. */
    val traffic: TrafficModel = TrafficModel.FREE_FLOW,
    /** Set on arrival, until the driver dismisses it. */
    val tripSummary: TripSummary? = null,
    /** Metres per second from the last fix, for the speedometer. */
    val speedMetersPerSecond: Double? = null,
    /** Posted limit for the road under the driver, in km/h, where the map has one. */
    val speedLimitKph: Double? = null,
) {
    val selectedRoute: Route? get() = routes.getOrNull(selectedRouteIndex)

    /** Where a route would start from: an explicit pin, else wherever the driver is. */
    val effectiveOrigin: LatLon? get() = origin?.position ?: userFix?.position
}

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()

    private val cache = DiskCache(File(application.cacheDir, "osm-data"))
    private val overpass = OverpassClient(http, cache)
    private val geocoder = GeocodingClient(http, cache)

    // Saved regions live in filesDir, not cacheDir: Android must not reclaim the map the
    // user deliberately downloaded for a trip with no signal.
    private val regionStore = RegionStore(application.filesDir)
    private val placeBook = PlaceBook(application.filesDir)
    private val repository = MapDataRepository(overpass, regionStore)
    private val settingsStore = SettingsStore(application)
    private val locationSource = LocationSource(application)
    private val speaker by lazy { Speaker(application) }

    private val _state = MutableStateFlow(MainUiState(settings = settingsStore.settings.value))
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var planner: RoutePlanner? = null
    private var area: AreaData? = null
    private var engine: NavigationEngine? = null
    private var joinWatcher: StartJoinWatcher? = null

    private var locationJob: Job? = null
    private var searchJob: Job? = null

    // Held so the user can call them off. Downloading map data for a long trip on a poor
    // connection is the slowest thing this app does, and until these were tracked there was
    // no way to stop it short of killing the app.
    private var planJob: Job? = null
    private var regionJob: Job? = null

    /**
     * Which attempt at planning is the live one.
     *
     * Cancelling a coroutine does not stop the blocking download inside it mid-call, so its
     * progress callbacks can still fire afterwards. Without this they would put the "…"
     * message back up over a map the user had just got back, leaving a spinner nothing could
     * clear. Every callback checks it is still the current attempt before saying anything.
     */
    private var planGeneration = 0

    private var lastRerouteAt = 0L

    /**
     * Devices already driven past on this trip.
     *
     * Accumulated rather than read off the finished route, because a reroute replaces the
     * route wholesale and would otherwise wipe out everything passed before it.
     */
    private val passedDetectors = LinkedHashMap<String, DetectorKind>()
    private var tripStartedAtMillis = 0L
    private var tripDistanceMeters = 0.0

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            NavigationHub.stopRequests.collect { stopNavigation() }
        }
        viewModelScope.launch {
            placeBook.places.collect {
                _state.update { current ->
                    current.copy(savedPlaces = placeBook.saved, recentPlaces = placeBook.recents)
                }
            }
        }
        refreshSavedRegions()
        refreshPermissionState()
    }

    // ---------------------------------------------------------------- location

    fun refreshPermissionState() {
        _state.update {
            it.copy(
                locationGranted = locationSource.hasPermission(),
                locationEnabled = locationSource.isEnabled(),
            )
        }
        if (locationSource.hasPermission()) startLocationUpdates()
    }

    private fun startLocationUpdates() {
        if (locationJob?.isActive == true) return
        locationJob = viewModelScope.launch {
            locationSource.updates().collect { fix -> onFix(fix) }
        }
    }

    private fun onFix(fix: PositionFix) {
        _state.update {
            it.copy(
                userFix = fix,
                // Read on every fix, not only while navigating: a driver glancing at their
                // speed has not necessarily asked the app for a route.
                speedMetersPerSecond = fix.speedMetersPerSecond,
                speedLimitKph = speedLimitAt(fix),
            )
        }
        if (_state.value.phase != Phase.NAVIGATING) return

        // Still making our own way to a start the router could reach.
        if (_state.value.awaitingJoin) {
            handleApproach(fix)
            return
        }

        val engine = engine ?: return

        val navigation = engine.update(fix)
        rememberPassedDetectors(navigation)
        tripDistanceMeters = maxOf(tripDistanceMeters, navigation.distanceAlongRouteMeters)
        _state.update { it.copy(navigation = navigation) }

        announce(navigation)
        publishBanner(navigation)

        if (navigation.isOffRoute) rerouteIfDue(fix)
        if (navigation.hasArrived) finishNavigation()
    }

    /**
     * Notes every device the driver has now gone past.
     *
     * By id, so a camera counted on the old route is not counted again on the new one after
     * a reroute, and so the tally survives the route being replaced underneath it.
     */
    private fun rememberPassedDetectors(navigation: NavigationState) {
        val route = _state.value.selectedRoute ?: return
        for (encounter in route.exposure.encounters) {
            if (encounter.alongRouteMeters <= navigation.distanceAlongRouteMeters) {
                passedDetectors[encounter.detector.id] = encounter.detector.kind
            }
        }
    }

    /**
     * The posted limit on the road under the driver.
     *
     * Snapped fresh rather than read off the route, so it is still right after a wrong turn
     * — which is exactly when a driver is most likely to be on an unfamiliar road. Null
     * whenever the map has no `maxspeed` tag there, because a guess on a speed-limit sign is
     * worse than an empty space.
     */
    private fun speedLimitAt(fix: PositionFix): Double? {
        val graph = planner?.graph ?: return null
        val snap = graph.snapNearest(fix.position, SPEED_LIMIT_SNAP_METERS) ?: return null
        return graph.edges[snap.edgeIndex].maxspeedKph
    }

    private fun announce(navigation: NavigationState) {
        val settings = _state.value.settings
        for (announcement in navigation.announcements) {
            val allowed = when (announcement.kind) {
                Announcement.Kind.DETECTOR -> settings.warnAboutCameras
                else -> settings.speakDirections
            }
            if (!allowed) continue
            speaker.speak(announcement.text, urgent = announcement.kind == Announcement.Kind.DETECTOR)
        }
    }

    private fun publishBanner(navigation: NavigationState) {
        val units = _state.value.settings.units
        val next = navigation.nextStep
        val instruction = next?.instruction ?: navigation.currentStep?.instruction.orEmpty()
        val distance = Formatting.distance(navigation.distanceToManeuverMeters, units)
        val remaining = Formatting.duration(navigation.secondsRemaining)

        NavigationHub.publish(
            NavigationBanner(
                instruction = if (next != null) "$distance · $instruction" else instruction,
                detail = "$remaining left · ${Formatting.distance(navigation.distanceRemainingMeters, units)}",
            ),
        )
    }

    // ---------------------------------------------------------------- search

    fun onQueryChanged(query: String) {
        _state.update { it.copy(query = query) }
        searchJob?.cancel()

        if (query.isBlank()) {
            _state.update { it.copy(suggestions = emptyList(), searching = false, searchedQuery = null) }
            return
        }

        // Nominatim asks for at most one request a second; wait for a pause in typing.
        runSearch(query, waitForPauseInTyping = true)
    }

    /**
     * Searches for what has been typed without waiting out the debounce.
     *
     * Wired to the keyboard's search key. Waiting half a second after pressing "search" is
     * the sort of thing that makes a search field feel broken.
     */
    fun searchNow() {
        val query = _state.value.query
        if (query.isBlank()) return
        searchJob?.cancel()
        runSearch(query, waitForPauseInTyping = false)
    }

    private fun runSearch(query: String, waitForPauseInTyping: Boolean) {
        searchJob = viewModelScope.launch {
            if (waitForPauseInTyping) delay(SEARCH_DEBOUNCE_MILLIS)
            _state.update { it.copy(searching = true) }
            val results = geocoder.search(query, near = _state.value.userFix?.position)
            _state.update {
                it.copy(
                    suggestions = results,
                    searching = false,
                    // Recorded so the panel can tell "nothing found" apart from "nothing
                    // searched for yet". Without it an empty result set simply showed no
                    // panel at all, which reads as the search having silently failed.
                    searchedQuery = query,
                )
            }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update {
            it.copy(query = "", suggestions = emptyList(), searching = false, searchedQuery = null)
        }
    }

    fun chooseDestination(place: Place) {
        _state.update {
            it.copy(
                destination = place,
                query = "",
                suggestions = emptyList(),
                searchedQuery = null,
                routes = emptyList(),
                navigation = null,
            )
        }
        planRoutes()
    }

    /** Long-pressing the map drops a destination pin there. */
    fun chooseDestination(position: LatLon) {
        viewModelScope.launch {
            val place = geocoder.reverse(position)
                ?: Place(name = "Dropped pin", position = position)
            chooseDestination(place)
        }
    }

    fun setOrigin(place: Place?) {
        _state.update { it.copy(origin = place, routes = emptyList(), error = null) }
        if (_state.value.destination != null) planRoutes()
    }

    /**
     * Starts the trip from a point on the map instead of the device's own position.
     *
     * The escape hatch for a bad fix: underground car parks, tall buildings and any indoor
     * planning session routinely put the reported location nowhere near a road, and without
     * this the driver has no way past it.
     */
    fun setOriginFromMap(position: LatLon) {
        viewModelScope.launch {
            val place = geocoder.reverse(position) ?: Place(name = "Chosen start", position = position)
            setOrigin(place.copy(name = place.shortName))
        }
    }

    /** Goes back to routing from wherever the device thinks it is. */
    fun useCurrentLocationAsOrigin() = setOrigin(null)

    /** Ends the current planning attempt and makes anything it says from now on ignorable. */
    private fun stopPlanning() {
        planJob?.cancel()
        planJob = null
        planGeneration++
    }

    fun clearDestination() {
        stopPlanning()
        joinWatcher = null
        _state.update {
            it.copy(
                destination = null,
                routes = emptyList(),
                navigation = null,
                phase = Phase.BROWSING,
                busyMessage = null,
                provisionalStart = null,
                awaitingJoin = false,
            )
        }
    }

    /**
     * Abandons a download or a search in progress and gives the map back.
     *
     * The origin and destination are deliberately kept: the usual reason to stop a slow
     * download is to try again in a moment, not to throw the trip away, and re-entering both
     * ends to retry would be its own punishment. Use [resetTrip] to throw it away.
     *
     * A request already on the wire is left to finish into the disk cache rather than being
     * torn down — it costs nothing to let it land, and a retry is then instant. What stops
     * immediately is everything the user can see.
     */
    fun cancelWork() {
        stopPlanning()
        searchJob?.cancel()
        _state.update {
            it.copy(
                // Falling back to any routes already on screen rather than to a blank map,
                // so cancelling a re-plan leaves the driver where they were.
                phase = if (it.routes.isEmpty()) Phase.BROWSING else Phase.CHOOSING,
                busyMessage = null,
                searching = false,
            )
        }
    }

    /** Stops a region download without touching anything already saved. */
    fun cancelRegionDownload() {
        regionJob?.cancel()
        regionJob = null
        _state.update { it.copy(regionDownload = null) }
    }

    /**
     * Throws the whole trip away and goes back to a bare map.
     *
     * The escape hatch for a start or a destination set by mistake. Undoing one of those
     * piecemeal meant knowing which control had set it — the origin chip, the search field's
     * cross, the route sheet's close button — and none of them touched the other end, so a
     * long-press in the wrong place could leave a start pin stuck on the map with no obvious
     * way to be rid of it. This clears both ends, the routes, the search and any work in
     * flight, in one action.
     */
    fun resetTrip() {
        stopPlanning()
        searchJob?.cancel()

        if (_state.value.phase == Phase.NAVIGATING) {
            speaker.stop()
            NavigationService.stop(getApplication())
        }
        engine = null
        joinWatcher = null

        _state.update {
            it.copy(
                phase = Phase.BROWSING,
                busyMessage = null,
                error = null,
                origin = null,
                destination = null,
                routes = emptyList(),
                selectedRouteIndex = 0,
                navigation = null,
                isRerouting = false,
                provisionalStart = null,
                awaitingJoin = false,
                overview = false,
                query = "",
                suggestions = emptyList(),
                searching = false,
                searchedQuery = null,
            )
        }
    }

    // ---------------------------------------------------------------- routing

    fun planRoutes() {
        val from = _state.value.effectiveOrigin
        val to = _state.value.destination?.position

        if (from == null) {
            showError("Waiting for your location. Grant location access or set a starting point.")
            return
        }
        if (to == null) return

        // A second attempt supersedes the first; two downloads racing to set the same
        // state is how a cancelled plan comes back from the dead.
        stopPlanning()
        val generation = planGeneration
        planJob = viewModelScope.launch {
            // Deliberately neutral until the repository says which it is doing: claiming a
            // download while opening a saved map is exactly the bug this replaced.
            _state.update { it.copy(phase = Phase.WORKING, busyMessage = "Preparing the map…", error = null) }
            try {
                val loaded = withContext(Dispatchers.IO) {
                    repository.loadFor(from, to) { stage ->
                        if (generation != planGeneration) return@loadFor
                        val message = when (stage) {
                            MapDataRepository.LoadStage.OPENING_SAVED -> "Opening your saved map…"
                            MapDataRepository.LoadStage.DOWNLOADING -> "Downloading map data…"
                            MapDataRepository.LoadStage.READY -> null
                        }
                        _state.update { it.copy(busyMessage = message ?: it.busyMessage) }
                    }
                }
                area = loaded

                _state.update { it.copy(busyMessage = "Looking for a quiet way round…") }
                val plan = withContext(Dispatchers.Default) {
                    val builtPlanner = RoutePlanner(
                        graph = loaded.graph,
                        detectors = loaded.detectors,
                        settings = _state.value.settings.toAvoidanceSettings(),
                        options = routingOptions(),
                        traffic = currentTraffic(),
                    )
                    planner = builtPlanner
                    builtPlanner.plan(
                        origin = from,
                        destination = to,
                        originSnapMeters = originSnapMeters(),
                        // Rather than refusing when there is no road at the start, begin at
                        // the nearest one there is and pick guidance up on arrival. Capped
                        // to the downloaded padding, since roads beyond it are not loaded.
                        fallbackStartMeters = MapDataRepository.DEFAULT_PADDING_METERS,
                    )
                }

                if (plan.isEmpty) {
                    _state.update {
                        it.copy(
                            phase = Phase.BROWSING,
                            busyMessage = null,
                            detectors = loaded.detectors,
                            error = describe(plan.failure),
                        )
                    }
                    return@launch
                }

                // Default to the least-watched option rather than the quickest, because
                // that is what the driver opened this app for. The planner has already
                // thrown out anything with an absurd detour.
                val preferred = plan.leastExposed
                    ?.let { plan.routes.indexOf(it) }
                    ?.takeIf { it >= 0 }
                    ?: 0

                // A saved region can hold thousands of devices, and each one drawn is a
                // marker plus a coverage shape. Only those near the routes are worth
                // showing, and the route's own camera count comes from its exposure
                // report rather than from this list, so trimming it changes no numbers.
                val nearby = detectorsNear(plan.routes, loaded.detectors)

                _state.update {
                    it.copy(
                        phase = Phase.CHOOSING,
                        busyMessage = null,
                        routes = plan.routes,
                        selectedRouteIndex = preferred,
                        detectors = nearby,
                        provisionalStart = plan.provisionalStart,
                        routedOffline = loaded.isOffline,
                        traffic = plan.traffic,
                    )
                }
            } catch (e: AreaTooLargeException) {
                fail("That trip covers ${e.areaKm2.toInt()} km², which is too much to route on the phone. Try a shorter journey.")
            } catch (e: IOException) {
                fail(e.message ?: "Could not download map data.")
            } catch (e: OutOfMemoryError) {
                repository.forget()
                fail("Ran out of memory building the map for that area. Try a shorter journey.")
            }
        }
    }

    /** Devices worth drawing for a set of routes: those within sight of any of them. */
    private fun detectorsNear(routes: List<Route>, detectors: List<Detector>): List<Detector> {
        val points = routes.flatMap { it.geometry }
        if (points.isEmpty()) return detectors
        val area = BoundingBox.of(points).expandMeters(DETECTOR_KEEP_MARGIN_METERS)
        return detectors.filter { area.contains(it.position) }
    }

    fun selectRoute(index: Int) {
        _state.update { it.copy(selectedRouteIndex = index.coerceIn(0, (it.routes.size - 1).coerceAtLeast(0))) }
    }

    /**
     * How far the router may look for a road under the start point.
     *
     * A pin the user placed themselves is exact, so it gets the default. A live fix is only
     * as good as the device says it is, and indoors that is often hundreds of metres.
     */
    private fun originSnapMeters(): Double {
        val current = _state.value
        if (current.origin != null) return SnapRadius.DEFAULT_METERS
        return SnapRadius.forAccuracy(current.userFix?.accuracyMeters)
    }

    /**
     * Congestion expected at this moment.
     *
     * Read at planning time rather than held, so a trip planned at ten to five and one
     * planned at ten past get the conditions each actually faces.
     */
    /** Search settings, including whether busy roads are worth a detour in their own right. */
    private fun routingOptions(): RoutingOptions = RoutingOptions(
        congestionAversion = if (_state.value.settings.avoidHeavyTraffic) CONGESTION_AVERSION else 0.0,
    )

    private fun currentTraffic(): TrafficModel =
        if (_state.value.settings.allowForTraffic) {
            TrafficModel.at(LocalDateTime.now())
        } else {
            TrafficModel.FREE_FLOW
        }

    private fun describe(failure: RouteFailure?): String = when (failure) {
        RouteFailure.NO_MAP_DATA ->
            "No road data downloaded for this area. Check your connection and try again."

        // Say what to do about it. On its own this reads as a dead end, when in fact the
        // driver can place the start themselves and carry on.
        RouteFailure.ORIGIN_UNREACHABLE ->
            "Couldn't find a road near your start. GPS is often poor indoors — long-press " +
                "the map to set your starting point."

        RouteFailure.DESTINATION_UNREACHABLE ->
            "No road found near that destination. Try a point closer to a street."

        RouteFailure.SEARCH_EXHAUSTED -> "That trip is too complex to route on the phone."
        else -> "No route found between those two points."
    }

    // ---------------------------------------------------------------- navigation

    /** Steps back to the whole route mid-drive, or returns to following the driver. */
    fun setOverview(showing: Boolean) = _state.update { it.copy(overview = showing) }

    // ---------------------------------------------------------------- saved addresses

    fun starPlace(place: Place, starred: Boolean) = placeBook.setStarred(place, starred)

    fun forgetPlace(saved: SavedPlace) = placeBook.forget(saved)

    fun clearRecentPlaces() = placeBook.clearRecents()

    fun isStarred(place: Place?): Boolean = place != null && placeBook.isStarred(place)

    fun startNavigation() {
        val route = _state.value.selectedRoute ?: return
        _state.value.destination?.let(placeBook::remember)
        val settings = _state.value.settings

        // The route starts somewhere the driver is not. Guiding from its first turn would
        // be nonsense, so hold guidance until they reach a road and plan again from there.
        val provisional = _state.value.provisionalStart
        if (provisional != null) {
            val graph = planner?.graph
            if (graph != null) {
                startApproach(route, provisional, graph)
                return
            }
        }

        engine = NavigationEngine(
            route = route,
            config = NavigationConfig(
                units = settings.units,
                announceDetectors = settings.warnAboutCameras,
            ),
        )
        beginTrip()
        _state.update {
            it.copy(
                phase = Phase.NAVIGATING,
                routes = listOf(route),
                selectedRouteIndex = 0,
                tripSummary = null,
            )
        }

        NavigationHub.publish(NavigationBanner(route.steps.firstOrNull()?.instruction ?: "Navigating", ""))
        NavigationService.start(getApplication())

        _state.value.userFix?.let(::onFix)
    }

    /**
     * Waits for the driver to reach the road the route starts on.
     *
     * The route stays on screen so they can see where they are heading, but no turn-by-turn
     * is spoken: until they are actually on the network, every instruction would be about a
     * road they are not on.
     */
    private fun startApproach(route: Route, provisional: ProvisionalStart, graph: RoadGraph) {
        engine = null
        joinWatcher = StartJoinWatcher(graph)
        _state.update {
            it.copy(
                phase = Phase.NAVIGATING,
                awaitingJoin = true,
                routes = listOf(route),
                selectedRouteIndex = 0,
                navigation = null,
            )
        }

        NavigationHub.publish(
            NavigationBanner(
                instruction = "Head to ${provisional.roadName ?: "the nearest road"}",
                detail = "Guidance starts once you reach it",
            ),
        )
        NavigationService.start(getApplication())

        if (_state.value.settings.speakDirections) {
            speaker.speak(
                "No road at your start. Head to ${provisional.roadName ?: "the nearest road"}, " +
                    "and navigation will begin automatically.",
            )
        }

        _state.value.userFix?.let(::onFix)
    }

    /** Keeps the driver posted while they cover the gap, and starts guidance when they arrive. */
    private fun handleApproach(fix: PositionFix) {
        val provisional = _state.value.provisionalStart
        val units = _state.value.settings.units

        if (provisional != null) {
            val remaining = haversineMeters(fix.position, provisional.joinPoint)
            NavigationHub.publish(
                NavigationBanner(
                    instruction = "Head to ${provisional.roadName ?: "the nearest road"}",
                    detail = "${Formatting.distance(remaining, units)} away · guidance starts automatically",
                ),
            )
        }

        val joined = joinWatcher?.update(fix) ?: return
        beginGuidanceFrom(joined)
    }

    /**
     * Replans from where the driver actually ended up and hands over to turn-by-turn.
     *
     * Deliberately routes from the real position rather than the join point that was
     * guessed earlier — leaving a car park by a different exit is normal, and the route
     * should reflect the road they are really on.
     */
    private fun beginGuidanceFrom(position: LatLon) {
        val destination = _state.value.destination?.position ?: return
        val activePlanner = planner ?: return
        val profile = _state.value.selectedRoute?.profile ?: PrivacyProfile.BALANCED
        val settings = _state.value.settings

        joinWatcher = null
        viewModelScope.launch {
            _state.update { it.copy(isRerouting = true) }
            val plan = withContext(Dispatchers.Default) {
                activePlanner.plan(position, destination, listOf(profile))
            }

            val route = plan.routes.firstOrNull()
            if (route == null) {
                // Snapped a moment ago but cannot route now; keep waiting rather than
                // dropping the driver back to a blank map.
                joinWatcher = StartJoinWatcher(activePlanner.graph)
                _state.update { it.copy(isRerouting = false) }
                return@launch
            }

            engine = NavigationEngine(
                route = route,
                config = NavigationConfig(
                    units = settings.units,
                    announceDetectors = settings.warnAboutCameras,
                ),
            )
            _state.update {
                it.copy(
                    routes = listOf(route),
                    selectedRouteIndex = 0,
                    awaitingJoin = false,
                    provisionalStart = null,
                    isRerouting = false,
                )
            }
            if (settings.speakDirections) speaker.speak("Starting navigation", urgent = true)
            _state.value.userFix?.let(::onFix)
        }
    }

    fun stopNavigation() {
        // Ending a trip early is still a trip, and the cameras behind the driver are still
        // behind them. Nothing to report on a trip that never got going, though.
        val summary = if (passedDetectors.isNotEmpty() || tripDistanceMeters > 0.0) {
            summariseTrip()
        } else {
            null
        }
        engine = null
        joinWatcher = null
        speaker.stop()
        NavigationService.stop(getApplication())
        _state.update {
            it.copy(
                tripSummary = summary,
                phase = if (it.routes.isEmpty()) Phase.BROWSING else Phase.CHOOSING,
                navigation = null,
                isRerouting = false,
                awaitingJoin = false,
                overview = false,
            )
        }
    }

    private fun beginTrip() {
        passedDetectors.clear()
        tripDistanceMeters = 0.0
        tripStartedAtMillis = System.currentTimeMillis()
    }

    /** What the trip cost, counted from the devices actually driven past. */
    private fun summariseTrip(): TripSummary = TripSummary(
        seenByCount = passedDetectors.size,
        countsByKind = passedDetectors.values.groupingBy { it }.eachCount(),
        distanceMeters = tripDistanceMeters,
        durationSeconds = if (tripStartedAtMillis == 0L) {
            0.0
        } else {
            (System.currentTimeMillis() - tripStartedAtMillis) / 1000.0
        },
        destinationName = _state.value.destination?.shortName,
    )

    private fun finishNavigation() {
        NavigationService.stop(getApplication())
        val summary = summariseTrip()
        engine = null
        joinWatcher = null
        _state.update {
            it.copy(
                phase = Phase.BROWSING,
                navigation = null,
                destination = null,
                routes = emptyList(),
                provisionalStart = null,
                awaitingJoin = false,
                overview = false,
                tripSummary = summary,
            )
        }
    }

    fun dismissTripSummary() = _state.update { it.copy(tripSummary = null) }

    /**
     * Recomputes the route after a wrong turn.
     *
     * Rate-limited, because a driver stuck on the wrong side of a dual carriageway will
     * report off-route on every fix, and replanning is the most expensive thing this app
     * does. Reuses the already-downloaded area whenever the new position is still inside it.
     */
    private fun rerouteIfDue(fix: PositionFix) {
        val now = System.currentTimeMillis()
        if (now - lastRerouteAt < REROUTE_COOLDOWN_MILLIS) return
        val destination = _state.value.destination?.position ?: return
        lastRerouteAt = now

        viewModelScope.launch {
            _state.update { it.copy(isRerouting = true) }
            try {
                val currentArea = area
                val inArea = currentArea != null &&
                    currentArea.bounds.contains(BoundingBox.of(listOf(fix.position, destination)))

                val activePlanner = if (inArea) {
                    planner
                } else {
                    val reloaded = withContext(Dispatchers.IO) { repository.loadFor(fix.position, destination) }
                    area = reloaded
                    RoutePlanner(
                        graph = reloaded.graph,
                        detectors = reloaded.detectors,
                        settings = _state.value.settings.toAvoidanceSettings(),
                        options = routingOptions(),
                        traffic = currentTraffic(),
                    ).also { planner = it }
                } ?: return@launch

                val profile = _state.value.selectedRoute?.profile ?: return@launch
                val plan = withContext(Dispatchers.Default) {
                    activePlanner.plan(
                        origin = fix.position,
                        destination = destination,
                        profiles = listOf(profile),
                        originSnapMeters = SnapRadius.forAccuracy(fix.accuracyMeters),
                    )
                }

                val route = plan.routes.firstOrNull() ?: return@launch
                engine = NavigationEngine(
                    route = route,
                    config = NavigationConfig(
                        units = _state.value.settings.units,
                        announceDetectors = _state.value.settings.warnAboutCameras,
                    ),
                )
                _state.update { it.copy(routes = listOf(route), selectedRouteIndex = 0, detectors = area?.detectors ?: it.detectors) }
            } catch (e: IOException) {
                // Keep guiding along the old line; the driver may rejoin it.
            } finally {
                _state.update { it.copy(isRerouting = false) }
            }
        }
    }

    // ---------------------------------------------------------------- map layer

    /** Loads the surveillance layer for whatever the map is currently showing. */
    fun loadDetectorsFor(box: BoundingBox) {
        // Remembered so "save this area" has something to save.
        _state.update { it.copy(viewport = box) }
        if (box.areaKm2 > DETECTOR_LAYER_MAX_KM2) return
        viewModelScope.launch {
            runCatching { repository.loadDetectors(box) }
                .onSuccess { found ->
                    _state.update { current ->
                        // Every drawn detector is a marker plus a coverage shape, so the
                        // set has to stay bounded — accumulating everything ever seen made
                        // panning slower the longer the app had been open.
                        val keep = box.expandMeters(DETECTOR_KEEP_MARGIN_METERS)
                        val merged = (current.detectors + found)
                            .distinctBy { it.id }
                            .filter { keep.contains(it.position) }
                        current.copy(detectors = merged)
                    }
                }
        }
    }

    // ---------------------------------------------------------------- offline maps

    fun refreshSavedRegions() {
        viewModelScope.launch {
            _state.update { it.copy(savedRegions = regionStore.list()) }
        }
    }

    /**
     * Downloads and keeps an area so later trips inside it need no network.
     *
     * @param box the area to keep; typically the current map view
     * @param name what to call it in the list
     * @param id supply an existing region's id to refresh it in place
     */
    fun saveRegion(box: BoundingBox, name: String, id: String = newRegionId()) {
        if (_state.value.regionDownload != null) return

        regionJob = viewModelScope.launch {
            _state.update {
                it.copy(
                    regionDownload = RegionDownloadState(name, MapDataRepository.RegionProgress.ROADS, box.areaKm2),
                    error = null,
                )
            }
            try {
                repository.downloadRegion(id = id, name = name, box = box) { stage ->
                    _state.update { current ->
                        current.copy(regionDownload = current.regionDownload?.copy(stage = stage))
                    }
                }
                _state.update { it.copy(savedRegions = regionStore.list(), regionDownload = null) }
            } catch (e: AreaTooLargeException) {
                failRegion(
                    "That area is ${e.areaKm2.toInt()} km², larger than the " +
                        "${e.limitKm2.toInt()} km² a saved map can hold. Zoom in and try again.",
                )
            } catch (e: IOException) {
                failRegion(e.message ?: "Could not download that area.")
            } catch (e: OutOfMemoryError) {
                failRegion("Not enough memory to build a map that large. Zoom in and try again.")
            }
        }
    }

    /**
     * Saves whatever the map is showing, named after the place at its centre.
     *
     * Asking the user to type a name before anything happens would be friction for no
     * benefit; a reverse-geocoded name is right nearly always, and coordinates are a
     * serviceable fallback when the lookup is unavailable — including when it is
     * unavailable precisely because there is no connection.
     */
    fun saveCurrentViewport() {
        val box = _state.value.viewport ?: return
        viewModelScope.launch {
            val name = geocoder.reverse(box.center)?.shortName ?: "Saved area"
            saveRegion(box, name)
        }
    }

    fun deleteRegion(region: SavedRegion) {
        viewModelScope.launch {
            regionStore.delete(region.id)
            repository.forget()
            _state.update { it.copy(savedRegions = regionStore.list()) }
        }
    }

    /** Re-downloads a saved region in place, which is how camera data gets refreshed. */
    fun refreshRegion(region: SavedRegion) = saveRegion(region.bounds, region.name, region.id)

    private fun failRegion(message: String) =
        _state.update { it.copy(regionDownload = null, error = message) }

    private fun newRegionId(): String = "region-${System.currentTimeMillis()}"

    // ---------------------------------------------------------------- settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = _state.value.settings
        settingsStore.update(transform)
        val after = settingsStore.settings.value

        // Changing what counts as worth avoiding — cameras or congestion — invalidates any
        // route already on screen, and leaving a stale one there would misrepresent the
        // setting the driver has just changed.
        val routingChanged = after.avoidedKinds != before.avoidedKinds ||
            after.avoidHeavyTraffic != before.avoidHeavyTraffic ||
            after.allowForTraffic != before.allowForTraffic
        if (routingChanged && _state.value.phase == Phase.CHOOSING) {
            planRoutes()
        }
    }

    fun cacheSizeBytes(): Long = cache.sizeBytes()

    fun clearDownloadedData() {
        cache.clear()
        repository.forget()
        planner = null
        area = null
        _state.update { it.copy(detectors = emptyList(), routes = emptyList()) }
    }

    // ---------------------------------------------------------------- misc

    /** Asks the map to jump back to the driver, without disturbing the route. */
    fun recenterOnUser() = _state.update { it.copy(recenterTick = it.recenterTick + 1) }

    fun clearError() = _state.update { it.copy(error = null) }

    private fun showError(message: String) = _state.update { it.copy(error = message) }

    private fun fail(message: String) = _state.update {
        it.copy(phase = Phase.BROWSING, busyMessage = null, error = message)
    }

    override fun onCleared() {
        speaker.release()
        NavigationService.stop(getApplication())
        super.onCleared()
    }

    private companion object {
        const val SEARCH_DEBOUNCE_MILLIS = 350L
        /**
         * Minimum gap between reroutes.
         *
         * Replanning is the most expensive thing the app does, and a driver held off the
         * centreline — a slip road, a wide junction, a parallel carriageway — can report
         * off-route repeatedly without having gone anywhere wrong.
         */
        const val REROUTE_COOLDOWN_MILLIS = 25_000L
        /** How far to look for a road when reading the posted limit under the driver. */
        const val SPEED_LIMIT_SNAP_METERS = 40.0

        const val DETECTOR_LAYER_MAX_KM2 = 900.0

        /** How far outside the view to keep drawn cameras, so a small pan shows no gap. */
        const val DETECTOR_KEEP_MARGIN_METERS = 3_000.0
    }
}
