package dev.shadowgps.app.ui

import android.Manifest
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MyLocation
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { viewModel.refreshPermissionState() }

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
            followUser = state.phase == Phase.NAVIGATING,
            showDetectorRanges = state.settings.showAllDetectors,
            recenterTick = state.recenterTick,
            onLongPress = { position -> pendingPin = position },
            onDetectorTapped = { detector -> tappedDetector = detector },
            onViewportChanged = viewModel::loadDetectorsFor,
        )

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
                    destination = state.destination,
                    onQueryChanged = viewModel::onQueryChanged,
                    onPick = viewModel::chooseDestination,
                    onClear = {
                        viewModel.clearSearch()
                        viewModel.clearDestination()
                    },
                    onOpenSettings = { showSettings = true },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            if (state.phase != Phase.NAVIGATING) {
                state.origin?.let { origin ->
                    Spacer(Modifier.height(8.dp))
                    OriginChip(
                        name = origin.shortName,
                        onUseCurrentLocation = viewModel::useCurrentLocationAsOrigin,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            state.busyMessage?.let { message ->
                Spacer(Modifier.height(8.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MaterialTheme.colorScheme.surface,
                    tonalElevation = 3.dp,
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(message, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
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
                // The approach card at the top already says everything there is to say.
                state.awaitingJoin -> Unit

                state.phase == Phase.NAVIGATING && state.navigation != null && state.selectedRoute != null ->
                    NavigationFooter(
                        navigation = state.navigation!!,
                        route = state.selectedRoute!!,
                        units = state.settings.units,
                        onStop = viewModel::stopNavigation,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding(),
                    )

                state.phase == Phase.CHOOSING && state.routes.isNotEmpty() ->
                    RouteChooser(
                        routes = state.routes,
                        selectedIndex = state.selectedRouteIndex,
                        units = state.settings.units,
                        provisionalStart = state.provisionalStart,
                        onSelect = viewModel::selectRoute,
                        onStart = viewModel::startNavigation,
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

    if (showSettings) {
        SettingsSheet(
            settings = state.settings,
            cacheBytes = viewModel.cacheSizeBytes(),
            onUpdate = viewModel::updateSettings,
            onClearCache = viewModel::clearDownloadedData,
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

/** Shows a manually chosen start point, with the way back to the live position. */
@Composable
private fun OriginChip(name: String, onUseCurrentLocation: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
    ) {
        Row(
            Modifier.padding(start = 14.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Starting from $name",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUseCurrentLocation) { Text("Use my location") }
        }
    }
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
