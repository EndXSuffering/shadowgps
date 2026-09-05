package dev.shadowgps.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Flag
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Map
import androidx.compose.material.icons.rounded.NearMe
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material.icons.rounded.RoundaboutLeft
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.TurnLeft
import androidx.compose.material.icons.rounded.TurnRight
import androidx.compose.material.icons.rounded.TurnSharpLeft
import androidx.compose.material.icons.rounded.TurnSharpRight
import androidx.compose.material.icons.rounded.TurnSlightLeft
import androidx.compose.material.icons.rounded.TurnSlightRight
import androidx.compose.material.icons.rounded.UTurnLeft
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.shadowgps.app.data.AppSettings
import dev.shadowgps.app.data.Place
import dev.shadowgps.app.data.MapTheme
import dev.shadowgps.app.data.SavedPlace
import dev.shadowgps.app.data.SavedRegion
import dev.shadowgps.app.ui.theme.ShadowColors
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.format.Formatting
import dev.shadowgps.core.format.UnitSystem
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.bearingDegrees
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.nav.NavigationState
import dev.shadowgps.core.routing.Directions
import dev.shadowgps.core.routing.Maneuver
import dev.shadowgps.core.routing.PrivacyProfile
import dev.shadowgps.core.routing.ProvisionalStart
import dev.shadowgps.core.routing.Route
import dev.shadowgps.core.routing.RouteStep
import dev.shadowgps.core.traffic.CongestionLevel
import dev.shadowgps.core.traffic.CongestionSpan
import dev.shadowgps.core.traffic.TrafficModel

/**
 * Destination entry.
 *
 * Three things were wrong with the old one and each made finding somewhere harder than
 * typing it. Results were titled after whatever came before the first comma, which on a
 * plain address is the house number, so a list read "500", "512", "530" with the streets
 * underneath in grey. An empty result set drew nothing at all, so a search that found
 * nothing looked identical to a search that never ran. And there was no way to say "go on
 * then" — every query waited out the debounce whatever the driver pressed.
 */
@Composable
fun SearchPanel(
    query: String,
    suggestions: List<Place>,
    searching: Boolean,
    /** The query a search last finished for, which is what makes "no matches" sayable. */
    searchedQuery: String?,
    destination: Place?,
    savedPlaces: List<SavedPlace>,
    recentPlaces: List<SavedPlace>,
    starredKeys: Set<String>,
    /** Where the driver is, for showing how far away each result is. */
    userPosition: LatLon?,
    units: UnitSystem,
    onQueryChanged: (String) -> Unit,
    onSearchNow: () -> Unit,
    onPick: (Place) -> Unit,
    onStar: (Place, Boolean) -> Unit,
    onClear: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    Column(modifier = modifier) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 3.dp,
            shadowElevation = 8.dp,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Search,
                    contentDescription = null,
                    modifier = Modifier.padding(start = 16.dp).size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextField(
                    value = query,
                    onValueChange = onQueryChanged,
                    placeholder = {
                        Text(
                            destination?.shortName ?: "Search an address or place",
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Words,
                        imeAction = ImeAction.Search,
                    ),
                    keyboardActions = KeyboardActions(
                        onSearch = {
                            onSearchNow()
                            keyboard?.hide()
                        },
                    ),
                    colors = TextFieldDefaults.colors(
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent,
                    ),
                )
                if (searching) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp).padding(end = 4.dp),
                        strokeWidth = 2.dp,
                    )
                }
                if (query.isNotEmpty() || destination != null) {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear")
                    }
                }
                IconButton(onClick = onOpenSettings) {
                    Icon(Icons.Rounded.Shield, contentDescription = "Privacy settings")
                }
            }
        }

        // With nothing typed, offer what the driver already goes to rather than a blank
        // panel: the whole point of saving an address is not typing it again.
        val showingShortcuts = query.isBlank() && suggestions.isEmpty() &&
            (savedPlaces.isNotEmpty() || recentPlaces.isNotEmpty())

        // A finished search that found nothing has to say so. Silence here is what made the
        // search feel unreliable — there was no way to tell it apart from not having run.
        val foundNothing = !searching && suggestions.isEmpty() &&
            query.isNotBlank() && searchedQuery == query

        if (suggestions.isNotEmpty() || showingShortcuts || foundNothing) {
            Spacer(Modifier.height(8.dp))
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                tonalElevation = 3.dp,
                shadowElevation = 8.dp,
            ) {
                LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                    when {
                        suggestions.isNotEmpty() -> {
                            // The unit came out of the query to make it findable at all, so
                            // say so rather than letting it look silently ignored.
                            suggestions.first().unit?.let { unit ->
                                item { SectionLabel("Searched without \"$unit\" — it is kept on the result") }
                            }
                            items(suggestions) { place ->
                                PlaceRow(
                                    title = place.shortName,
                                    detail = place.addressLine,
                                    tag = place.category,
                                    distance = userPosition?.let {
                                        Formatting.distance(haversineMeters(it, place.position), units)
                                    },
                                    icon = Icons.Rounded.Place,
                                    starred = starredKeys.contains(placeKey(place)),
                                    onClick = { onPick(place) },
                                    onStar = { onStar(place, !starredKeys.contains(placeKey(place))) },
                                )
                            }
                        }

                        foundNothing -> item { NoMatches(query) }

                        else -> {
                            if (savedPlaces.isNotEmpty()) {
                                item { SectionLabel("Saved") }
                                items(savedPlaces) { saved ->
                                    PlaceRow(
                                        title = saved.title,
                                        detail = saved.place.addressLine,
                                        tag = null,
                                        distance = null,
                                        icon = Icons.Rounded.Star,
                                        starred = true,
                                        onClick = { onPick(saved.place) },
                                        onStar = { onStar(saved.place, false) },
                                    )
                                }
                            }
                            if (recentPlaces.isNotEmpty()) {
                                item { SectionLabel("Recent") }
                                items(recentPlaces) { recent ->
                                    PlaceRow(
                                        title = recent.title,
                                        detail = recent.place.addressLine,
                                        tag = null,
                                        distance = null,
                                        icon = Icons.Rounded.History,
                                        starred = false,
                                        onClick = { onPick(recent.place) },
                                        onStar = { onStar(recent.place, true) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * What to do when a search comes back empty.
 *
 * Concrete advice rather than an apology: these are the three things that actually rescue a
 * failed lookup against OpenStreetMap data, in the order they are worth trying.
 */
@Composable
private fun NoMatches(query: String) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(
            "Nothing found for \u201c$query\u201d",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            "Try the town or postcode as well, search the business name on its own, or " +
                "long-press the map to drop a pin exactly where you want to go.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
    )
}

@Composable
private fun PlaceRow(
    title: String,
    detail: String?,
    /** What sort of place it is, when the geocoder says: "Pharmacy", "Fast food". */
    tag: String?,
    /** How far away it is, which is usually the fastest way to spot the right result. */
    distance: String?,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    starred: Boolean,
    onClick: () -> Unit,
    onStar: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            icon,
            contentDescription = null,
            tint = if (starred) ShadowColors.Caution else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    title,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                distance?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        it,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            // The full address, on two lines if it needs them: one ellipsised line could not
            // tell two similar streets in different towns apart, which is the whole job.
            detail?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            tag?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = ShadowColors.Accent,
                    maxLines = 1,
                )
            }
        }
        IconButton(onClick = onStar) {
            Icon(
                if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                contentDescription = if (starred) "Remove from saved" else "Save this address",
                tint = if (starred) ShadowColors.Caution else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Matches [dev.shadowgps.app.data.SavedPlace.key] so starred state lines up. */
internal fun placeKey(place: Place): String =
    "%.5f,%.5f".format(place.position.lat, place.position.lon)

/**
 * The route chooser.
 *
 * Every option shows the same two numbers — time and cameras passed — because the whole
 * decision this app exists to support is the trade between them.
 */
@Composable
fun RouteChooser(
    routes: List<Route>,
    selectedIndex: Int,
    units: UnitSystem,
    provisionalStart: ProvisionalStart?,
    offline: Boolean,
    traffic: TrafficModel,
    onSelect: (Int) -> Unit,
    onStart: () -> Unit,
    /** Clears both ends of the trip, for when the wrong one was picked. */
    onStartOver: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val baseline = routes.minOfOrNull { it.durationSeconds } ?: 0.0
    val quickest = routes.minByOrNull { it.durationSeconds }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Choose a route", style = MaterialTheme.typography.titleMedium)
                if (offline) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "· from a saved map",
                        style = MaterialTheme.typography.labelMedium,
                        color = ShadowColors.Clear,
                    )
                }
                if (traffic.isSignificant) {
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "· ${traffic.label.lowercase()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = ShadowColors.Caution,
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel")
                }
            }
            Spacer(Modifier.height(8.dp))

            if (provisionalStart != null) {
                DetachedStartNotice(provisionalStart, units)
                Spacer(Modifier.height(12.dp))
            }

            routes.forEachIndexed { index, route ->
                RouteCard(
                    route = route,
                    selected = index == selectedIndex,
                    baselineSeconds = baseline,
                    units = units,
                    quickestNow = routes.size > 1 && route === quickest,
                    onClick = { onSelect(index) },
                )
                Spacer(Modifier.height(8.dp))
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Sits beside Start rather than in the header, because this is the moment
                // the driver is looking at the trip and realising one end is wrong.
                OutlinedButton(
                    onClick = onStartOver,
                    modifier = Modifier.height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Text("Start over")
                }
                Button(
                    onClick = onStart,
                    modifier = Modifier.weight(1f).height(52.dp),
                    shape = RoundedCornerShape(14.dp),
                ) {
                    Icon(Icons.Rounded.NearMe, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Start", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
    }
}

@Composable
private fun RouteCard(
    route: Route,
    selected: Boolean,
    baselineSeconds: Double,
    units: UnitSystem,
    quickestNow: Boolean,
    onClick: () -> Unit,
) {
    val watched = route.exposure.totalCount
    val tone = exposureTone(watched)

    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.surface,
        ),
        border = if (selected) {
            androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
        } else {
            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        },
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (route.profile == PrivacyProfile.FASTEST) Icons.Rounded.Bolt else Icons.Rounded.VisibilityOff,
                contentDescription = null,
                tint = if (route.profile == PrivacyProfile.FASTEST) ShadowColors.Caution else tone,
                modifier = Modifier.size(22.dp),
            )
            Spacer(Modifier.width(12.dp))

            Column(Modifier.weight(1f)) {
                Text(route.profile.label, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${Formatting.duration(route.durationSeconds)} · ${Formatting.distance(route.distanceMeters, units)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val delta = route.durationSeconds - baselineSeconds
                if (delta > 30) {
                    Text(
                        Formatting.durationDelta(delta) + " slower",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (route.trafficDelaySeconds > 60) {
                    Text(
                        "${Formatting.durationDelta(route.trafficDelaySeconds)} for traffic",
                        style = MaterialTheme.typography.labelMedium,
                        color = ShadowColors.Caution,
                    )
                }
                // The happy case worth calling out: quickest right now *and* unseen.
                if (quickestNow) {
                    Text(
                        if (watched == 0) "Quickest right now, and unseen" else "Quickest right now",
                        style = MaterialTheme.typography.labelMedium,
                        color = ShadowColors.Clear,
                    )
                }
            }

            ExposureBadge(count = watched, tone = tone)
        }
    }
}

@Composable
private fun ExposureBadge(count: Int, tone: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .size(44.dp)
                .background(tone.copy(alpha = 0.16f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            if (count == 0) {
                Icon(Icons.Rounded.Shield, contentDescription = null, tint = tone, modifier = Modifier.size(22.dp))
            } else {
                Text(
                    count.toString(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = tone,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            if (count == 0) "unseen" else "seen",
            style = MaterialTheme.typography.labelMedium,
            color = tone,
        )
    }
}

private fun exposureTone(count: Int): Color = when {
    count == 0 -> ShadowColors.Clear
    count <= 2 -> ShadowColors.Caution
    else -> ShadowColors.Watched
}

/** Explains, before the driver commits, that the route does not start where they are. */
@Composable
private fun DetachedStartNotice(provisional: ProvisionalStart, units: UnitSystem) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = ShadowColors.Caution.copy(alpha = 0.12f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                Icons.Rounded.Explore,
                contentDescription = null,
                tint = ShadowColors.Caution,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(12.dp))
            Column {
                Text(
                    "No road where you are",
                    style = MaterialTheme.typography.titleMedium,
                    color = ShadowColors.Caution,
                )
                Text(
                    buildString {
                        append("These routes start ")
                        append(Formatting.distance(provisional.distanceMeters, units))
                        append(" away")
                        provisional.roadName?.let { append(" on $it") }
                        append(". Make your own way there and guidance will begin by itself.")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/**
 * Shown while the driver covers the gap to a start the router could reach.
 *
 * Deliberately not turn-by-turn: the app has no idea what is between them and the road, so
 * it gives a direction and a distance and stays out of the way.
 */
@Composable
fun JoinPromptCard(
    provisional: ProvisionalStart,
    currentPosition: LatLon?,
    units: UnitSystem,
    isRerouting: Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val remaining = currentPosition?.let { haversineMeters(it, provisional.joinPoint) }
        ?: provisional.distanceMeters
    val heading = currentPosition?.let { Directions.compass(bearingDegrees(it, provisional.joinPoint)) }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.Explore,
                    contentDescription = null,
                    modifier = Modifier.size(34.dp),
                    tint = ShadowColors.Caution,
                )
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Formatting.distance(remaining, units) + (heading?.let { " $it" } ?: ""),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        "Head to ${provisional.roadName ?: "the nearest road"}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }

            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(10.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isRerouting) {
                    CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        "Picking up the route…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Waiting until you reach a road — guidance starts on its own.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

/** The big instruction banner shown while driving. */
@Composable
fun NavigationInstructionCard(
    navigation: NavigationState,
    units: UnitSystem,
    isRerouting: Boolean,
    modifier: Modifier = Modifier,
) {
    val next = navigation.nextStep ?: navigation.currentStep

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 10.dp,
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    // The shape of the turn itself, not a generic arrow: seen from the
                    // corner of the eye at speed, the icon registers well before the words
                    // do, and it is on screen for the whole approach to the junction.
                    maneuverIcon(next?.maneuver),
                    contentDescription = null,
                    modifier = Modifier.size(38.dp),
                    tint = MaterialTheme.colorScheme.primary,
                )
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(
                        Formatting.distance(navigation.distanceToManeuverMeters, units),
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(
                        next?.instruction ?: "Continue",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Turns often come in pairs. Knowing the second one is coming is what lets a
            // driver take the first in the right lane.
            val following = navigation.followingStep
            if (following != null &&
                following.maneuver != Maneuver.ARRIVE &&
                navigation.metersBetweenManeuvers <= THEN_PREVIEW_METERS
            ) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "then",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        maneuverIcon(following.maneuver),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        following.instruction,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            if (isRerouting) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "Off route — finding another way…",
                    style = MaterialTheme.typography.labelLarge,
                    color = ShadowColors.Caution,
                )
            }

            val camera = navigation.detectorsAhead.firstOrNull {
                it.alongRouteMeters > navigation.distanceAlongRouteMeters
            }
            if (camera != null) {
                val distance = camera.alongRouteMeters - navigation.distanceAlongRouteMeters
                if (distance < 1_000) {
                    Spacer(Modifier.height(10.dp))
                    HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.Videocam,
                            contentDescription = null,
                            tint = colorFor(camera.detector.kind),
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "${camera.detector.kind.label} in ${Formatting.distance(distance, units)}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = colorFor(camera.detector.kind),
                        )
                    }
                }
            }
        }
    }
}

/** How close the second manoeuvre has to be before it is worth previewing. */
private const val THEN_PREVIEW_METERS = 400.0

/** The icon for a manoeuvre, so the turn is legible at a glance. */
private fun maneuverIcon(maneuver: Maneuver?): androidx.compose.ui.graphics.vector.ImageVector =
    when (maneuver) {
        Maneuver.LEFT -> Icons.Rounded.TurnLeft
        Maneuver.RIGHT -> Icons.Rounded.TurnRight
        Maneuver.SLIGHT_LEFT -> Icons.Rounded.TurnSlightLeft
        Maneuver.SLIGHT_RIGHT -> Icons.Rounded.TurnSlightRight
        Maneuver.SHARP_LEFT -> Icons.Rounded.TurnSharpLeft
        Maneuver.SHARP_RIGHT -> Icons.Rounded.TurnSharpRight
        Maneuver.U_TURN -> Icons.Rounded.UTurnLeft
        Maneuver.ROUNDABOUT -> Icons.Rounded.RoundaboutLeft
        Maneuver.ARRIVE -> Icons.Rounded.Flag
        else -> Icons.Rounded.ArrowUpward
    }

/** Trip summary and the stop button, pinned to the bottom while driving. */
@Composable
fun NavigationFooter(
    navigation: NavigationState,
    route: Route,
    units: UnitSystem,
    overview: Boolean,
    onToggleOverview: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    Formatting.duration(navigation.secondsRemaining),
                    style = MaterialTheme.typography.titleLarge,
                )
                Text(
                    Formatting.distance(navigation.distanceRemainingMeters, units) +
                        " · " + remainingExposureLabel(navigation),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    "${route.profile.label} route",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            // Looking over the route without abandoning the trip: guidance keeps running,
            // the map just stops chasing the vehicle.
            OutlinedButton(onClick = onToggleOverview, shape = RoundedCornerShape(12.dp)) {
                Icon(
                    if (overview) Icons.Rounded.NearMe else Icons.Rounded.Map,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text(if (overview) "Follow" else "Route")
            }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = onStop, shape = RoundedCornerShape(12.dp)) {
                Text("Stop")
            }
        }
    }
}

private fun remainingExposureLabel(navigation: NavigationState): String {
    val ahead = navigation.detectorsAhead.count { it.alongRouteMeters > navigation.distanceAlongRouteMeters }
    return when (ahead) {
        0 -> "nothing watching ahead"
        1 -> "1 camera ahead"
        else -> "$ahead cameras ahead"
    }
}

/** What to avoid, and how the app should behave while driving. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    cacheBytes: Long,
    savedRegions: List<SavedRegion>,
    regionDownload: RegionDownloadState?,
    canSaveCurrentArea: Boolean,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onClearCache: () -> Unit,
    onSaveCurrentArea: () -> Unit,
    onRefreshRegion: (SavedRegion) -> Unit,
    onDeleteRegion: (SavedRegion) -> Unit,
    onCancelDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OfflineMapsSection(
                regions = savedRegions,
                download = regionDownload,
                canSaveCurrentArea = canSaveCurrentArea,
                onSaveCurrentArea = onSaveCurrentArea,
                onRefresh = onRefreshRegion,
                onDelete = onDeleteRegion,
                onCancelDownload = onCancelDownload,
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))

            Text("Avoid", style = MaterialTheme.typography.titleMedium)
            Text(
                "Routes take detours around whatever is switched on here. Everything else is " +
                    "still drawn on the map, just not routed around.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))

            DetectorKind.entries.forEach { kind ->
                SettingRow(
                    title = kind.label,
                    subtitle = kindExplanation(kind),
                    checked = kind in settings.avoidedKinds,
                    accent = colorFor(kind),
                    onCheckedChange = { enabled ->
                        onUpdate { current ->
                            val kinds = current.avoidedKinds.toMutableSet()
                            if (enabled) kinds.add(kind) else kinds.remove(kind)
                            current.copy(avoidedKinds = kinds)
                        }
                    },
                )
            }

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(16.dp))

            Text("While driving", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            SettingRow(
                title = "Speak directions",
                subtitle = "Read turns aloud.",
                checked = settings.speakDirections,
                onCheckedChange = { on -> onUpdate { it.copy(speakDirections = on) } },
            )
            SettingRow(
                title = "Warn about cameras",
                subtitle = "Call out a device before you reach it.",
                checked = settings.warnAboutCameras,
                onCheckedChange = { on -> onUpdate { it.copy(warnAboutCameras = on) } },
            )
            SettingRow(
                title = "Keep the screen on",
                subtitle = "Stay awake while navigating.",
                checked = settings.keepScreenOnWhileNavigating,
                onCheckedChange = { on -> onUpdate { it.copy(keepScreenOnWhileNavigating = on) } },
            )
            SettingRow(
                title = "Allow for typical traffic",
                subtitle = "Adjust times for the usual congestion at this hour. A model of " +
                    "typical conditions — not a live feed, and it cannot know about a jam.",
                checked = settings.allowForTraffic,
                onCheckedChange = { on -> onUpdate { it.copy(allowForTraffic = on) } },
            )
            SettingRow(
                title = "Avoid heavy traffic",
                subtitle = "Take a calmer road even when it is not the quickest. Costs time " +
                    "by design — turn it off if you would rather just get there.",
                accent = ShadowColors.TrafficHeavy,
                checked = settings.avoidHeavyTraffic,
                onCheckedChange = { on -> onUpdate { it.copy(avoidHeavyTraffic = on) } },
            )
            SettingRow(
                title = "Show coverage on the map",
                subtitle = "Shade what each device can see.",
                checked = settings.showAllDetectors,
                onCheckedChange = { on -> onUpdate { it.copy(showAllDetectors = on) } },
            )
            Spacer(Modifier.height(8.dp))
            Text("Map brightness", style = MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MapTheme.entries.forEach { theme ->
                    val selected = settings.mapTheme == theme
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (selected) {
                            MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
                        } else {
                            MaterialTheme.colorScheme.surfaceVariant
                        },
                        border = if (selected) {
                            androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
                        } else {
                            null
                        },
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onUpdate { it.copy(mapTheme = theme) } },
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    Icons.Rounded.Map,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(theme.label, style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
            }
            Text(
                settings.mapTheme.description,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            Spacer(Modifier.height(10.dp))

            SettingRow(
                title = "Imperial units",
                subtitle = "Miles and feet instead of kilometres and metres.",
                checked = settings.units == UnitSystem.IMPERIAL,
                onCheckedChange = { on ->
                    onUpdate { it.copy(units = if (on) UnitSystem.IMPERIAL else UnitSystem.METRIC) }
                },
            )

            Spacer(Modifier.height(16.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outline)
            Spacer(Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Downloaded map data", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${cacheBytes / (1024 * 1024)} MB stored on this device",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onClearCache) { Text("Clear") }
            }

            Spacer(Modifier.height(12.dp))
            Text(
                "Routing runs on this phone. Your start and destination are never sent to a " +
                    "routing server. Map and camera data come from OpenStreetMap.",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Managing areas kept on the device.
 *
 * Two things earn their place here beyond a list: the size, because these files are tens of
 * megabytes and the user should be able to see what they are spending, and the age, because
 * a saved region freezes camera positions at the moment it was downloaded and plate readers
 * get relocated constantly. A month-old region still routes perfectly well but should not
 * be trusted on coverage, and saying so is more honest than a silent stale map.
 */
@Composable
private fun OfflineMapsSection(
    regions: List<SavedRegion>,
    download: RegionDownloadState?,
    canSaveCurrentArea: Boolean,
    onSaveCurrentArea: () -> Unit,
    onRefresh: (SavedRegion) -> Unit,
    onDelete: (SavedRegion) -> Unit,
    onCancelDownload: () -> Unit,
) {
    Text("Offline maps", style = MaterialTheme.typography.titleMedium)
    Text(
        "Keep an area on this device and trips inside it need no connection at all — no " +
            "downloads, and nothing about the trip leaves the phone.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))

    if (download != null) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Row(
                Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(download.message, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${download.areaKm2.toInt()} km² · this can take a few minutes",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                // "A few minutes" is a promise worth being able to withdraw from.
                TextButton(onClick = onCancelDownload) { Text("Cancel") }
            }
        }
        Spacer(Modifier.height(12.dp))
    }

    if (regions.isEmpty() && download == null) {
        Text(
            "Nothing saved yet.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
    }

    regions.forEach { region ->
        SavedRegionRow(region = region, onRefresh = { onRefresh(region) }, onDelete = { onDelete(region) })
        Spacer(Modifier.height(8.dp))
    }

    Spacer(Modifier.height(4.dp))
    Button(
        onClick = onSaveCurrentArea,
        enabled = canSaveCurrentArea && download == null,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text("Save the area shown on the map")
    }
    if (!canSaveCurrentArea) {
        Text(
            "Move or zoom the map to choose an area first.",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SavedRegionRow(region: SavedRegion, onRefresh: () -> Unit, onDelete: () -> Unit) {
    val stale = region.isStale()

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(region.name, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${region.fileBytes / (1024 * 1024)} MB · " +
                            "${region.roadCount / 2} roads · ${region.detectorCount} cameras",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        when (val days = region.ageDays()) {
                            0L -> "Downloaded today"
                            1L -> "Downloaded yesterday"
                            else -> "Downloaded $days days ago"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = if (stale) ShadowColors.Caution else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onRefresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh ${region.name}")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Rounded.Delete, contentDescription = "Delete ${region.name}")
                }
            }

            if (stale) {
                Spacer(Modifier.height(6.dp))
                Text(
                    "Cameras move. This copy is over a month old — refresh it before " +
                        "relying on its coverage.",
                    style = MaterialTheme.typography.labelMedium,
                    color = ShadowColors.Caution,
                )
            }
        }
    }
}

private fun kindExplanation(kind: DetectorKind): String = when (kind) {
    DetectorKind.ALPR -> "Records your plate, time and place, and keeps it."
    DetectorKind.SPEED_CAMERA -> "Photographs vehicles that are speeding."
    DetectorKind.RED_LIGHT_CAMERA -> "Photographs vehicles that jump the lights."
    DetectorKind.CCTV -> "General traffic cameras watching a road."
    DetectorKind.TOLL_GANTRY -> "Reads plates or transponders to bill you."
}

/**
 * Every instruction on the route, start to finish.
 *
 * A side panel rather than a sheet, because it is meant to be readable *while* driving:
 * it can sit open for the length of a trip beside the map without covering the road ahead
 * or the next-turn card, and it scrolls itself to whichever step is being driven so the
 * driver never has to hunt for their place in it.
 */
@Composable
fun DirectionsPanel(
    steps: List<RouteStep>,
    /** Which step is being driven, or null when nobody is navigating yet. */
    currentStepIndex: Int?,
    congestionSpans: List<CongestionSpan>,
    units: UnitSystem,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Follow the driver down the list, but only far enough to keep the current step in
    // view — yanking it to the top would hide the turns they are about to need.
    LaunchedEffect(currentStepIndex) {
        val index = currentStepIndex ?: return@LaunchedEffect
        if (index in steps.indices) listState.animateScrollToItem(index)
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 4.dp,
        shadowElevation = 12.dp,
    ) {
        Column(Modifier.padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Directions",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Hide directions")
                }
            }

            LazyColumn(state = listState) {
                itemsIndexed(steps) { index, step ->
                    DirectionRow(
                        step = step,
                        driving = index == currentStepIndex,
                        // A step already behind the driver is context, not instruction.
                        passed = currentStepIndex != null && index < currentStepIndex,
                        level = congestionOn(step, congestionSpans),
                        units = units,
                    )
                }
            }
        }
    }
}

@Composable
private fun DirectionRow(
    step: RouteStep,
    driving: Boolean,
    passed: Boolean,
    level: CongestionLevel,
    units: UnitSystem,
) {
    val contentColor = when {
        driving -> MaterialTheme.colorScheme.onSurface
        passed -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                if (driving) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f) else Color.Transparent,
                RoundedCornerShape(8.dp),
            )
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            maneuverIcon(step.maneuver),
            contentDescription = null,
            modifier = Modifier.size(20.dp),
            tint = if (driving) MaterialTheme.colorScheme.primary else contentColor,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                step.instruction,
                style = MaterialTheme.typography.bodyMedium,
                color = contentColor,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    Formatting.distance(step.distanceMeters, units),
                    style = MaterialTheme.typography.labelMedium,
                    color = contentColor,
                )
                if (level.isNotable) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(7.dp).background(colorFor(level), CircleShape))
                    Spacer(Modifier.width(4.dp))
                    Text(
                        level.label.lowercase(),
                        style = MaterialTheme.typography.labelMedium,
                        color = colorFor(level),
                    )
                }
                // Cameras on this leg matter more than the distance does, so they are not
                // buried behind a tap.
                if (step.detectorCount > 0) {
                    Spacer(Modifier.width(8.dp))
                    Icon(
                        Icons.Rounded.Videocam,
                        contentDescription = null,
                        modifier = Modifier.size(12.dp),
                        tint = ShadowColors.Watched,
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        step.detectorCount.toString(),
                        style = MaterialTheme.typography.labelMedium,
                        color = ShadowColors.Watched,
                    )
                }
            }
        }
    }
}

/** The worst band anywhere on the stretch this instruction covers. */
private fun congestionOn(step: RouteStep, spans: List<CongestionSpan>): CongestionLevel {
    val from = step.startAlongRouteMeters
    val to = from + step.distanceMeters
    return spans
        .filter { it.fromMeters < to && it.toMeters > from }
        .maxByOrNull { it.level.ordinal }
        ?.level
        ?: CongestionLevel.FREE
}

/** The tab that brings the directions list back, and the button that opens it. */
@Composable
fun DirectionsToggle(open: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surface,
        tonalElevation = 3.dp,
        shadowElevation = 6.dp,
    ) {
        Row(
            Modifier.clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Rounded.FormatListNumbered,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(Modifier.width(8.dp))
            Text(
                if (open) "Hide steps" else "All steps",
                style = MaterialTheme.typography.labelLarge,
            )
        }
    }
}

@Composable
private fun SettingRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    accent: Color? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (accent != null) {
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
