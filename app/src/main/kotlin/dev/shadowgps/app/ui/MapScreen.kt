package dev.shadowgps.app.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.geo.LatLon

/**
 * The whole app: a map with a search bar on top and a panel at the bottom whose contents
 * depend on what the driver is doing.
 */
@Composable
fun MapScreen(viewModel: MainViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showSettings by remember { mutableStateOf(false) }
    var tappedDetector by remember { mutableStateOf<Detector?>(null) }
    var pendingPin by remember { mutableStateOf<LatLon?>(null) }
    // Hidden by default: the map is the thing, and the list is for when a driver wants to
    // check a turn that is still several manoeuvres away.
    var showDirections by remember { mutableStateOf(false) }
    var confirmStop by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshPermissionState() }

    // Back undoes the last thing that happened, one step at a time. There was no handler at
    // all before, so the gesture went straight to the system and closed ShadowGPS outright —
    // mid-download, mid-route-choice and mid-drive alike, with everything chosen so far lost.
    // Now it only falls through to leaving the app once there is genuinely nothing left to
    // undo, and leaving mid-drive asks first, because that is not a thing to do by accident
    // on a gesture that starts at the edge of the screen.
    val canGoBack = showSettings ||
        state.tripSummary != null ||
        pendingPin != null ||
        tappedDetector != null ||
        showDirections ||
        state.phase != Phase.BROWSING ||
        state.query.isNotEmpty() ||
        state.destination != null ||
        state.origin != null

    BackHandler(enabled = canGoBack) {
        when {
            showSettings -> showSettings = false
            state.tripSummary != null -> viewModel.dismissTripSummary()
            pendingPin != null -> pendingPin = null
            tappedDetector != null -> tappedDetector = null
            showDirections -> showDirections = false
            state.overview -> viewModel.setOverview(false)
            state.phase == Phase.NAVIGATING -> confirmStop = true
            state.phase == Phase.WORKING -> viewModel.cancelWork()
            state.phase == Phase.CHOOSING -> viewModel.clearDestination()
            state.query.isNotEmpty() -> viewModel.clearSearch()
            else -> viewModel.resetTrip()
        }
    }

    LaunchedEffect(Unit) {
        if (!state.locationGranted) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
            )
        }
    }

    Box(Modifier.fillMaxSize()) {
        MapCanvas(
            modifier = Modifier.fillMaxSize(),
            routes = state.routes,
            selectedRouteIndex = state.selectedRouteIndex,
            detectors = state.detectors,
            userFix = state.userFix,
            origin = state.origin,
            destination = state.destination,
            joinPoint = state.provisionalStart?.joinPoint,
            followUser = state.phase == Phase.NAVIGATING && !state.overview,
            showDetectorRanges = state.settings.showAllDetectors,
            recenterTick = state.recenterTick,
            mapTheme = state.settings.mapTheme,
            // While guiding, draw the route-matched position rather than the raw fix.
            vehiclePosition = state.navigation?.snappedPosition,
            vehicleHeadingDegrees = state.navigation?.routeHeadingDegrees,
            overview = state.overview,
            metersToManeuver = state.navigation?.distanceToManeuverMeters,
            zoomForTurns = state.settings.zoomForTurns,
            onLongPress = { position -> pendingPin = position },
            onDetectorTapped = { detector -> tappedDetector = detector },
            onViewportChanged = viewModel::loadDetectorsFor,
        )

        // ------------------------------------------------------------- directions
        state.selectedRoute?.let { route ->
            if (showDirections) {
                DirectionsPanel(
                    steps = route.steps,
                    currentStepIndex = state.navigation?.currentStepIndex,
                    congestionSpans = route.congestionSpans,
                    units = state.settings.units,
                    onClose = { showDirections = false },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .fillMaxWidth(0.6f)
                        .heightIn(max = 340.dp),
                )
            }
        }

        // ------------------------------------------------------------- top
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .fillMaxWidth(),
        ) {
            if (state.awaitingJoin && state.provisionalStart != null) {
                JoinPromptCard(
                    provisional = state.provisionalStart!!,
                    currentPosition = state.userFix?.position,
                    units = state.settings.units,
                    isRerouting = state.isRerouting,
                    onCancel = viewModel::stopNavigation,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else if (state.phase == Phase.NAVIGATING && state.navigation != null) {
                NavigationInstructionCard(
                    navigation = state.navigation!!,
                    units = state.settings.units,
                    isRerouting = state.isRerouting,
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                SearchPanel(
                    query = state.query,
                    suggestions = state.suggestions,
                    searching = state.searching,
                    searchedQuery = state.searchedQuery,
                    destination = state.destination,
                    savedPlaces = state.savedPlaces,
                    recentPlaces = state.recentPlaces,
                    starredKeys = state.savedPlaces.map { placeKey(it.place) }.toSet(),
                    userPosition = state.userFix?.position,
                    units = state.settings.units,
                    onQueryChanged = viewModel::onQueryChanged,
                    onSearchNow = viewModel::searchNow,
                    onPick = viewModel::chooseDestination,
                    onStar = viewModel::starPlace,
                    onClear = {
                        viewModel.clearSearch()
                        viewModel.clearDestination()
                    },
                    onOpenSettings = { showSettings = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.phase != Phase.NAVIGATING && (state.origin != null || state.destination != null)) {
                Spacer(Modifier.height(8.dp))
                TripChip(
                    originName = state.origin?.shortName,
                    destinationName = state.destination?.shortName,
                    onUseCurrentLocation = viewModel::useCurrentLocationAsOrigin,
                    onStartOver = viewModel::resetTrip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            state.busyMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            message,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        // Downloading a large area on a poor connection can take minutes,
                        // and until this existed the only way out of it was to kill the app.
                        TextButton(onClick = viewModel::cancelWork) { Text("Cancel") }
                    }
                }
            }
        }

        // ------------------------------------------------------------- speed
        if (state.settings.showSpeedometer && state.locationGranted) {
            SpeedPanel(
                speedMetersPerSecond = state.speedMetersPerSecond,
                speedLimitKph = state.speedLimitKph,
                units = state.settings.units,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 16.dp, bottom = 140.dp)
                    .navigationBarsPadding(),
            )
        }

        // ------------------------------------------------------------- bottom
        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.End,
        ) {
            pendingPin?.let { position ->
                PinChoiceCard(
                    onStartHere = {
                        viewModel.setOriginFromMap(position)
                        pendingPin = null
                    },
                    onGoHere = {
                        viewModel.chooseDestination(position)
                        pendingPin = null
                    },
                    onDismiss = { pendingPin = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            tappedDetector?.let { detector ->
                DetectorCard(
                    detector = detector,
                    onDismiss = { tappedDetector = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }

            if (state.selectedRoute != null) {
                DirectionsToggle(
                    open = showDirections,
                    onToggle = { showDirections = !showDirections },
                    modifier = Modifier.padding(end = 16.dp, bottom = 10.dp),
                )
            }

            if (state.locationGranted && state.phase != Phase.NAVIGATING) {
                FloatingActionButton(
                    onClick = viewModel::recenterOnUser,
                    modifier = Modifier.padding(end = 16.dp, bottom = 12.dp),
                    containerColor = MaterialTheme.colorScheme.surface,
                ) {
                    Icon(Icons.Rounded.MyLocation, contentDescription = "Centre on my location")
                }
            }

            when {
                // Arriving is the one moment the exposure number is a fact rather than a
                // forecast, so it takes the bottom of the screen until dismissed.
                state.tripSummary != null ->
                    TripSummaryCard(
                        summary = state.tripSummary!!,
                        units = state.settings.units,
                        onDismiss = viewModel::dismissTripSummary,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    )

                // The approach card at the top already says everything there is to say.
                state.awaitingJoin -> Unit

                state.phase == Phase.NAVIGATING && state.navigation != null && state.selectedRoute != null ->
                    NavigationFooter(
                        navigation = state.navigation!!,
                        route = state.selectedRoute!!,
                        units = state.settings.units,
                        overview = state.overview,
                        onToggleOverview = { viewModel.setOverview(!state.overview) },
                        onStop = viewModel::stopNavigation,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    )

                state.phase == Phase.CHOOSING && state.routes.isNotEmpty() ->
                    RouteChooser(
                        routes = state.routes,
                        selectedIndex = state.selectedRouteIndex,
                        units = state.settings.units,
                        provisionalStart = state.provisionalStart,
                        offline = state.routedOffline,
                        traffic = state.traffic,
                        onSelect = viewModel::selectRoute,
                        onStart = viewModel::startNavigation,
                        onStartOver = viewModel::resetTrip,
                        onDismiss = viewModel::clearDestination,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    )

                else -> HintCard(
                    granted = state.locationGranted,
                    enabled = state.locationEnabled,
                    modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                )
            }
        }

        // ------------------------------------------------------------- errors
        state.error?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .navigationBarsPadding(),
                action = { TextButton(onClick = viewModel::clearError) { Text("Dismiss") } },
            ) {
                Text(message)
            }
        }
    }

    if (confirmStop) {
        StopNavigationDialog(
            onConfirm = {
                confirmStop = false
                viewModel.stopNavigation()
            },
            onDismiss = { confirmStop = false },
        )
    }

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            cacheBytes = viewModel.cacheSizeBytes(),
            savedRegions = state.savedRegions,
            regionDownload = state.regionDownload,
            canSaveCurrentArea = state.viewport != null,
            onUpdate = viewModel::updateSettings,
            onClearCache = viewModel::clearDownloadedData,
            onSaveCurrentArea = { viewModel.saveCurrentViewport() },
            onRefreshRegion = viewModel::refreshRegion,
            onDeleteRegion = viewModel::deleteRegion,
            onCancelDownload = viewModel::cancelRegionDownload,
            onDismiss = { showSettings = false },
        )
    }
}

/**
 * What to do with a long-pressed point.
 *
 * Offering the start as well as the destination is what rescues a driver whose location
 * fix is unusable — an underground car park, a covered market, anywhere indoors.
 */
@Composable
private fun PinChoiceCard(
    onStartHere: () -> Unit,
    onGoHere: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Use this point as",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onStartHere) { Text("Start") }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = onGoHere) { Text("Destination") }
            IconButton(onClick = onDismiss) {
                Icon(Icons.Rounded.Close, contentDescription = "Dismiss")
            }
        }
    }
}

/**
 * The trip as it currently stands, and the way out of it.
 *
 * Both ends are shown together because that is the question being asked — "is this the
 * journey you meant?" — and either can be wrong. "Start over" clears the lot in one press;
 * before it existed, undoing a mis-dropped pin meant working out which control had set it,
 * and a start pin in particular could end up stranded on the map with nothing obvious to
 * clear it.
 */
@Composable
private fun TripChip(
    originName: String?,
    destinationName: String?,
    onUseCurrentLocation: () -> Unit,
    onStartOver: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "From ${originName ?: "my location"}",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                destinationName?.let {
                    Text(
                        "To $it",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // Only offered when the start is a pin, since otherwise it is already the
            // live position and the button would do nothing.
            if (originName != null) {
                TextButton(onClick = onUseCurrentLocation) { Text("My location") }
            }
            TextButton(onClick = onStartOver) { Text("Start over") }
        }
    }
}

/**
 * Confirms leaving a trip in progress.
 *
 * Back is a gesture from the edge of the screen, which is exactly where a hand lands when
 * reaching for a phone in a cradle. Losing guidance to that is worse than one extra tap.
 */
@Composable
private fun StopNavigationDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Stop navigating?") },
        text = { Text("Guidance will end and you will go back to the map.") },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Stop") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Keep going") } },
    )
}

/** Details for a camera the user tapped on the map. */
@Composable
private fun DetectorCard(detector: Detector, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 8.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(detector.describe(), style = MaterialTheme.typography.titleMedium)
                Text(
                    buildString {
                        append("Sees about ${detector.rangeMeters.toInt()} m")
                        detector.headingDegrees?.let { append(", facing ${it.toInt()}°") }
                        detector.operator?.let { append(" · $it") }
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "OpenStreetMap ${detector.id}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    }
}

/** Shown when there is nothing else to say: what to do next, or what is missing. */
@Composable
private fun HintCard(granted: Boolean, enabled: Boolean, modifier: Modifier = Modifier) {
    val message = when {
        !granted -> "Location access is off, so routes cannot start from where you are. " +
            "Long-press the map to set a start and a destination by hand."
        !enabled -> "Location is switched off on this device. Long-press the map to set a " +
            "start and a destination by hand."
        else -> "Search for somewhere, or long-press the map to set a start or destination."
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Column(
            Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("ShadowGPS", style = MaterialTheme.typography.titleMedium)
            Text(
                message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
