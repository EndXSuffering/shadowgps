package dev.shadowgps.app.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.shadowgps.app.data.AppSettings
import dev.shadowgps.app.data.AreaData
import dev.shadowgps.app.data.AreaTooLargeException
import dev.shadowgps.app.data.DiskCache
import dev.shadowgps.app.data.GeocodingClient
import dev.shadowgps.app.data.MapDataRepository
import dev.shadowgps.app.data.OverpassClient
import dev.shadowgps.app.data.Place
import dev.shadowgps.app.data.SettingsStore
import dev.shadowgps.app.location.LocationSource
import dev.shadowgps.app.nav.NavigationBanner
import dev.shadowgps.app.nav.NavigationHub
import dev.shadowgps.app.nav.NavigationService
import dev.shadowgps.app.nav.Speaker
import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.format.Formatting
import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.nav.Announcement
import dev.shadowgps.core.nav.NavigationConfig
import dev.shadowgps.core.nav.NavigationEngine
import dev.shadowgps.core.nav.NavigationState
import dev.shadowgps.core.nav.PositionFix
import dev.shadowgps.core.routing.Route
import dev.shadowgps.core.routing.RouteFailure
import dev.shadowgps.core.routing.RoutePlanner
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
    val isRerouting: Boolean = false,
    /** Bumped to ask the map to jump back to the driver's position. */
    val recenterTick: Int = 0,
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
    private val repository = MapDataRepository(overpass)
    private val settingsStore = SettingsStore(application)
    private val locationSource = LocationSource(application)
    private val speaker by lazy { Speaker(application) }

    private val _state = MutableStateFlow(MainUiState(settings = settingsStore.settings.value))
    val state: StateFlow<MainUiState> = _state.asStateFlow()

    private var planner: RoutePlanner? = null
    private var area: AreaData? = null
    private var engine: NavigationEngine? = null

    private var locationJob: Job? = null
    private var searchJob: Job? = null
    private var lastRerouteAt = 0L

    init {
        viewModelScope.launch {
            settingsStore.settings.collect { settings ->
                _state.update { it.copy(settings = settings) }
            }
        }
        viewModelScope.launch {
            NavigationHub.stopRequests.collect { stopNavigation() }
        }
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
        _state.update { it.copy(userFix = fix) }

        val engine = engine ?: return
        if (_state.value.phase != Phase.NAVIGATING) return

        val navigation = engine.update(fix)
        _state.update { it.copy(navigation = navigation) }

        announce(navigation)
        publishBanner(navigation)

        if (navigation.isOffRoute) rerouteIfDue(fix)
        if (navigation.hasArrived) finishNavigation()
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
            _state.update { it.copy(suggestions = emptyList(), searching = false) }
            return
        }

        searchJob = viewModelScope.launch {
            // Nominatim asks for at most one request a second; wait for a pause in typing.
            delay(SEARCH_DEBOUNCE_MILLIS)
            _state.update { it.copy(searching = true) }
            val results = geocoder.search(query, near = _state.value.userFix?.position)
            _state.update { it.copy(suggestions = results, searching = false) }
        }
    }

    fun clearSearch() {
        searchJob?.cancel()
        _state.update { it.copy(query = "", suggestions = emptyList(), searching = false) }
    }

    fun chooseDestination(place: Place) {
        _state.update {
            it.copy(
                destination = place,
                query = "",
                suggestions = emptyList(),
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
        _state.update { it.copy(origin = place, routes = emptyList()) }
        if (_state.value.destination != null) planRoutes()
    }

    fun clearDestination() {
        _state.update {
            it.copy(
                destination = null,
                routes = emptyList(),
                navigation = null,
                phase = Phase.BROWSING,
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

        viewModelScope.launch {
            _state.update { it.copy(phase = Phase.WORKING, busyMessage = "Downloading map data…", error = null) }
            try {
                val loaded = withContext(Dispatchers.IO) { repository.loadFor(from, to) }
                area = loaded

                _state.update { it.copy(busyMessage = "Looking for a quiet way round…") }
                val plan = withContext(Dispatchers.Default) {
                    val builtPlanner = RoutePlanner(
                        graph = loaded.graph,
                        detectors = loaded.detectors,
                        settings = _state.value.settings.toAvoidanceSettings(),
                    )
                    planner = builtPlanner
                    builtPlanner.plan(from, to)
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

                _state.update {
                    it.copy(
                        phase = Phase.CHOOSING,
                        busyMessage = null,
                        routes = plan.routes,
                        selectedRouteIndex = preferred,
                        detectors = loaded.detectors,
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

    fun selectRoute(index: Int) {
        _state.update { it.copy(selectedRouteIndex = index.coerceIn(0, (it.routes.size - 1).coerceAtLeast(0))) }
    }

    private fun describe(failure: RouteFailure?): String = when (failure) {
        RouteFailure.ORIGIN_UNREACHABLE -> "No road found near your starting point."
        RouteFailure.DESTINATION_UNREACHABLE -> "No road found near that destination."
        RouteFailure.SEARCH_EXHAUSTED -> "That trip is too complex to route on the phone."
        else -> "No route found between those two points."
    }

    // ---------------------------------------------------------------- navigation

    fun startNavigation() {
        val route = _state.value.selectedRoute ?: return
        val settings = _state.value.settings

        engine = NavigationEngine(
            route = route,
            config = NavigationConfig(
                units = settings.units,
                announceDetectors = settings.warnAboutCameras,
            ),
        )
        _state.update { it.copy(phase = Phase.NAVIGATING, routes = listOf(route), selectedRouteIndex = 0) }

        NavigationHub.publish(NavigationBanner(route.steps.firstOrNull()?.instruction ?: "Navigating", ""))
        NavigationService.start(getApplication())

        _state.value.userFix?.let(::onFix)
    }

    fun stopNavigation() {
        engine = null
        speaker.stop()
        NavigationService.stop(getApplication())
        _state.update {
            it.copy(
                phase = if (it.routes.isEmpty()) Phase.BROWSING else Phase.CHOOSING,
                navigation = null,
                isRerouting = false,
            )
        }
    }

    private fun finishNavigation() {
        NavigationService.stop(getApplication())
        engine = null
        _state.update {
            it.copy(phase = Phase.BROWSING, navigation = null, destination = null, routes = emptyList())
        }
    }

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
                    ).also { planner = it }
                } ?: return@launch

                val profile = _state.value.selectedRoute?.profile ?: return@launch
                val plan = withContext(Dispatchers.Default) {
                    activePlanner.plan(fix.position, destination, listOf(profile))
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
        if (box.areaKm2 > DETECTOR_LAYER_MAX_KM2) return
        viewModelScope.launch {
            runCatching { repository.loadDetectors(box) }
                .onSuccess { found ->
                    _state.update { current ->
                        val merged = (current.detectors + found).distinctBy { it.id }
                        current.copy(detectors = merged)
                    }
                }
        }
    }

    // ---------------------------------------------------------------- settings

    fun updateSettings(transform: (AppSettings) -> AppSettings) {
        val before = _state.value.settings
        settingsStore.update(transform)
        val after = settingsStore.settings.value

        // Changing what counts as worth avoiding invalidates any route already on screen.
        if (after.avoidedKinds != before.avoidedKinds && _state.value.phase == Phase.CHOOSING) {
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
        const val REROUTE_COOLDOWN_MILLIS = 12_000L
        const val DETECTOR_LAYER_MAX_KM2 = 900.0
    }
}
