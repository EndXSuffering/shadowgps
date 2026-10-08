package dev.shadowgps.app.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlin.math.abs
import kotlin.math.roundToInt
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import java.util.concurrent.atomic.AtomicBoolean
import dev.shadowgps.app.R
import dev.shadowgps.app.data.MapTheme
import dev.shadowgps.app.data.Place
import dev.shadowgps.app.ui.theme.ShadowColors
import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.coordsCount
import dev.shadowgps.core.geo.coordsToList
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.geo.listToCoords
import dev.shadowgps.core.geo.sliceCoords
import dev.shadowgps.core.geo.haversineMeters
import dev.shadowgps.core.nav.SETTLE_ZOOM
import dev.shadowgps.core.nav.SETTLE_DISTANCE_METERS
import dev.shadowgps.core.nav.SETTLE_DEGREES
import dev.shadowgps.core.geo.interpolateAlongCoords
import dev.shadowgps.core.geo.angularDifference
import dev.shadowgps.core.nav.FollowFraming
import dev.shadowgps.core.nav.HEADING_TIME_CONSTANT_SECONDS
import dev.shadowgps.core.nav.POSITION_TIME_CONSTANT_SECONDS
import dev.shadowgps.core.nav.PositionFix
import dev.shadowgps.core.nav.SNAP_DISTANCE_METERS
import dev.shadowgps.core.nav.ZOOM_TIME_CONSTANT_SECONDS
import dev.shadowgps.core.nav.approachBearing
import dev.shadowgps.core.nav.approachPosition
import dev.shadowgps.core.nav.approachValue
import dev.shadowgps.core.nav.followFraming
import dev.shadowgps.core.nav.smoothingFactor
import dev.shadowgps.core.routing.Route
import dev.shadowgps.core.traffic.CongestionLevel
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.FolderOverlay
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import org.osmdroid.views.overlay.Polyline
import dev.shadowgps.core.geo.BoundingBox as GeoBox
import org.osmdroid.util.BoundingBox as OsmBox

/**
 * The map.
 *
 * osmdroid draws raster OpenStreetMap tiles, which keeps the app free of any map SDK that
 * would phone home. Overlays are split into folders — routes, detectors, markers — so a
 * position update moves one marker instead of rebuilding several hundred shapes.
 */
@Composable
fun MapCanvas(
    modifier: Modifier = Modifier,
    routes: List<Route>,
    selectedRouteIndex: Int,
    detectors: List<Detector>,
    userFix: PositionFix?,
    origin: Place?,
    destination: Place?,
    /** Where a detached route begins, when the driver is not on the network. */
    joinPoint: LatLon?,
    followUser: Boolean,
    showDetectorRanges: Boolean,
    recenterTick: Int,
    mapTheme: MapTheme,
    /**
     * Where to draw the vehicle, and which way it is pointing.
     *
     * While navigating this is the position matched onto the route rather than the raw
     * fix, so the arrow travels along the road instead of wandering off it.
     */
    vehiclePosition: LatLon?,
    vehicleHeadingDegrees: Double?,
    /** Frame the whole route instead of following the driver. */
    overview: Boolean,
    /** Metres to the next manoeuvre, for closing in on it. Null when not navigating. */
    metersToManeuver: Double?,
    zoomForTurns: Boolean,
    /**
     * How far along the chosen route the driver is, while navigating.
     *
     * The arrow is smoothed along the route by this rather than across the map by
     * coordinates, so between fixes it follows the road round a corner instead of cutting
     * across it.
     */
    progressAlongRouteMeters: Double?,
    /** Devices the chosen route goes past, which are the ones that will actually see the car. */
    routeDetectorIds: Set<String>,
    /** Those of them now out of reach behind the driver. */
    passedDetectorIds: Set<String>,
    /** Those close enough ahead to show their coverage for. */
    upcomingDetectorIds: Set<String>,
    onLongPress: (LatLon) -> Unit,
    onDetectorTapped: (Detector) -> Unit,
    onViewportChanged: (GeoBox) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val routeLayer = remember { FolderOverlay() }
    val detectorLayer = remember { FolderOverlay() }
    val pinLayer = remember { FolderOverlay() }
    val vehicleLayer = remember { FolderOverlay() }

    // Set by every map movement and cleared by the sampler further down, which works out
    // the visible area itself. A flag rather than the area: while following, the map moves
    // every frame, and working out an area — a projection and two allocations — sixty
    // times a second for a sampler that looks a little over once a second was all waste.
    val viewportDirty = remember { AtomicBoolean(true) }
    val reportViewport = rememberUpdatedState(onViewportChanged)

    // Set while the frame loop moves the map itself, so the listeners can tell its own
    // movement from somebody's finger. Everything runs on the main thread; this is only a
    // flag that is up for the length of one synchronous call.
    val selfMoving = remember { AtomicBoolean(false) }
    // Bumped when the map is moved by anything other than the frame loop — a pan, a pinch,
    // the overview framing. The frame loop wakes on it, since a map at rest is otherwise
    // never looked at again.
    val outsideMoves = remember { mutableIntStateOf(0) }

    // The view's own height is not observable, and the vehicle's resting place is a
    // fraction of it, so it has to be picked up when the map is first measured.
    val mapHeight = remember { mutableStateOf(0) }

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            setUseDataConnection(true)
            minZoomLevel = 4.0
            maxZoomLevel = 20.0
            controller.setZoom(15.0)

            overlays.add(
                MapEventsOverlay(
                    object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint?): Boolean = false

                        override fun longPressHelper(p: GeoPoint?): Boolean {
                            p ?: return false
                            onLongPress(LatLon(p.latitude, p.longitude))
                            return true
                        }
                    },
                ),
            )
            overlays.add(detectorLayer)
            overlays.add(routeLayer)
            overlays.add(pinLayer)
            overlays.add(vehicleLayer)

            // The viewport is meaningless until the view has been measured, and nothing
            // else reports it until the user pans — which would leave "save this area"
            // with no area for as long as they leave the map alone.
            addOnFirstLayoutListener { _, _, top, _, bottom ->
                viewportDirty.set(true)
                mapHeight.value = bottom - top
            }

            addMapListener(
                object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        movedBySomething()
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        movedBySomething()
                        return false
                    }

                    private fun movedBySomething() {
                        viewportDirty.set(true)
                        if (!selfMoving.get()) outsideMoves.intValue++
                    }
                },
            )
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onDetach()
        }
    }

    // Routes change rarely; rebuilding their lines is cheap when it does happen.
    LaunchedEffect(routes, selectedRouteIndex) {
        routeLayer.items.clear()

        // Draw the alternatives first so the chosen line sits on top of them.
        routes.forEachIndexed { index, route ->
            if (index == selectedRouteIndex) return@forEachIndexed
            routeLayer.items.add(polylineFor(mapView, route, selected = false))
        }
        routes.getOrNull(selectedRouteIndex)?.let { selected ->
            routeLayer.items.add(polylineFor(mapView, selected, selected = true))
            // Traffic sits on top of the chosen line, so the driver can see *where* the
            // modelled delay is rather than only that the estimate went up.
            congestionLines(mapView, selected).forEach { routeLayer.items.add(it) }
        }

        // Frame the whole trip whenever the set of routes changes, unless the driver is
        // being followed — yanking the camera away mid-drive would be hostile.
        val chosen = routes.getOrNull(selectedRouteIndex)
        if (chosen != null && !followUser) {
            val box = GeoBox.of(chosen.geometry).expandMeters(300.0)
            mapView.zoomToBoundingBox(box.toOsm(), true, ROUTE_PADDING_PX)
        }
        mapView.invalidate()
    }

    /*
     * The surveillance layer.
     *
     * With a route chosen, each device is drawn as one of three things. On the route and
     * still able to see the car: full strength and larger, with its coverage drawn once it
     * is close ahead — the shape shows which way it looks and how far, which is the thing
     * worth knowing in the seconds before driving into it. Merely nearby: faded, because it
     * is context rather than a warning. Out of reach behind: faded further still, so the
     * map reads as a record of what has seen the car rather than forgetting it. With no
     * route, every device is drawn plainly.
     *
     * Size and strength carry that, not colour. Colour already says what kind of device it
     * is, and a plate reader on the route and one a block away are both plate readers —
     * recolouring either, as an earlier version did, made an on-route reader look exactly
     * like any other and a passed one exactly like the grey of a CCTV camera.
     *
     * Updated in place. A device whose look has not changed keeps its marker, so passing a
     * camera restyles that one camera instead of rebuilding every shape on the map, and
     * every marker of one kind and size shares a single icon.
     */
    val drawnDetectors = remember { HashMap<String, DrawnDetector>() }
    val detectorIcons = remember { DetectorIcons(context) }
    val tapDetector = rememberUpdatedState(onDetectorTapped)
    LaunchedEffect(detectors, showDetectorRanges, routeDetectorIds, passedDetectorIds, upcomingDetectorIds) {
        val routeChosen = routeDetectorIds.isNotEmpty()
        val wanted = HashSet<String>(detectors.size * 2)
        for (detector in detectors) {
            wanted += detector.id
            val look = when {
                !routeChosen -> DetectorLook.PLAIN
                detector.id in passedDetectorIds -> DetectorLook.PASSED
                detector.id in routeDetectorIds -> DetectorLook.ON_ROUTE
                else -> DetectorLook.NEARBY
            }
            val withCoverage = showDetectorRanges ||
                (look == DetectorLook.ON_ROUTE && detector.id in upcomingDetectorIds)
            val existing = drawnDetectors[detector.id]
            if (existing != null && existing.detector == detector && existing.look == look &&
                existing.withCoverage == withCoverage
            ) {
                continue
            }
            drawnDetectors[detector.id] = DrawnDetector(
                detector = detector,
                look = look,
                withCoverage = withCoverage,
                marker = detectorMarker(mapView, detector, look, detectorIcons) { tapDetector.value(it) },
                coverage = if (withCoverage) coverageShape(mapView, detector, look) else null,
            )
        }
        drawnDetectors.keys.retainAll(wanted)

        // Coverage beneath every marker, and the route's own devices above the rest.
        detectorLayer.items.clear()
        for (drawn in drawnDetectors.values) drawn.coverage?.let { detectorLayer.items.add(it) }
        for (drawn in drawnDetectors.values) if (drawn.look != DetectorLook.ON_ROUTE) detectorLayer.items.add(drawn.marker)
        for (drawn in drawnDetectors.values) if (drawn.look == DetectorLook.ON_ROUTE) detectorLayer.items.add(drawn.marker)
        mapView.invalidate()
    }

    LaunchedEffect(origin, destination, joinPoint) {
        pinLayer.items.clear()
        origin?.let {
            pinLayer.items.add(
                pin(mapView, it.position, R.drawable.ic_marker_origin, it.shortName, centered = true),
            )
        }
        // Where the driver has to get to before guidance can take over.
        joinPoint?.let {
            pinLayer.items.add(
                pin(mapView, it, R.drawable.ic_marker_origin, "Route starts here", centered = true),
            )
        }
        destination?.let {
            pinLayer.items.add(
                pin(mapView, it.position, R.drawable.ic_marker_destination, it.shortName, centered = false),
            )
        }
        mapView.invalidate()
    }

    val vehicle = remember(mapView) { vehicleMarker(mapView) }
    LaunchedEffect(vehicle) {
        vehicleLayer.items.clear()
        vehicleLayer.items.add(vehicle)
    }

    // osmdroid restores whatever centre it last persisted, which on a first run is the
    // middle of the ocean. Move to the driver once, the first time we know where they are.
    val centredOnUser = remember { mutableStateOf(false) }

    // Where the fixes say things are. Read by the frame loop below without restarting it,
    // so a new fix changes where the map is heading rather than interrupting how it gets
    // there.
    val targetPosition = rememberUpdatedState(vehiclePosition ?: userFix?.position)
    // A GPS bearing is meaningless below walking pace and absent on many fixes, which is
    // why the arrow used to spin on the spot at every red light.
    val targetHeading = rememberUpdatedState(
        vehicleHeadingDegrees
            ?: userFix?.bearingDegrees?.takeIf { (userFix.speedMetersPerSecond ?: 0.0) > 1.5 },
    )
    val following = rememberUpdatedState(followUser)
    val turnZoom = rememberUpdatedState(zoomForTurns)
    val maneuverDistance = rememberUpdatedState(metersToManeuver)
    // The chosen route as flat coordinates, worked out once per route rather than per frame.
    val chosenRoute = routes.getOrNull(selectedRouteIndex)
    val routeCoords = rememberUpdatedState(
        remember(chosenRoute) { chosenRoute?.geometry?.takeIf { it.size >= 2 }?.let(::listToCoords) },
    )
    val routeProgress = rememberUpdatedState(progressAlongRouteMeters)

    // The first fix, which is a jump rather than a journey: it brings the driver's own
    // surroundings into view, and the cameras around them with it.
    LaunchedEffect(targetPosition.value != null) {
        val shown = targetPosition.value ?: return@LaunchedEffect
        if (centredOnUser.value) return@LaunchedEffect
        centredOnUser.value = true
        mapView.controller.setZoom(16.0)
        mapView.controller.setCenter(GeoPoint(shown.lat, shown.lon))
        // setCenter does not always emit a scroll event, and this is the jump that first
        // brings the cameras that matter most into range. Flag it directly rather than
        // hoping for a callback.
        viewportDirty.set(true)
    }

    /*
     * The camera, driven one frame at a time.
     *
     * Everything that moves — the vehicle, the map centre, the rotation, the zoom — is
     * stepped here and nowhere else. Fixes land about once a second, so anything driven
     * straight off a fix moves once a second: the arrow hopped a car's length at a time and
     * the map snapped to each new heading. Owning all of it in one place also ends a fight:
     * osmdroid's animator cancels whatever was running when a new animation starts, so a
     * zoom begun on one fix was routinely killed part-way by the pan on the next. Nothing
     * here animates; each frame works out where things belong and puts them there.
     *
     * Three things keep it honest.
     *
     * While navigating, the arrow is smoothed along the route, not across the map. The fix
     * is matched onto the road, but smoothing its coordinates would draw a straight line
     * between two fixes either side of a corner — the arrow would cut across the inside of
     * every turn, metres off the road at exactly the zoom and moment the turn matters.
     *
     * It goes to sleep. Once nothing moved this frame and nothing is still on its way, it
     * stops asking for frames until one of its inputs changes. Otherwise it would wake the
     * display sixty to a hundred and twenty times a second for as long as the map was on
     * screen — parked, browsing, or with no fix at all.
     *
     * It notices the map being moved by anything else. A zoom somebody chose is kept until
     * the situation changes; a map panned away while following comes back to the driver
     * shortly after, rather than staying off to one side until the car moves off.
     */
    LaunchedEffect(mapView) {
        var shownPosition: LatLon? = null
        var shownProgress: Double? = null
        var shownCoords: DoubleArray? = null
        var shownHeading: Double? = null
        var appliedZoom = mapView.zoomLevelDouble
        var shownZoom = appliedZoom
        var zoomTarget = appliedZoom
        var framing = FollowFraming.CRUISING
        var wasFollowing = false
        var hadTurnZoom = turnZoom.value
        var seenOutsideMoves = outsideMoves.intValue
        var recentreAtNanos = 0L
        var lastFrameNanos = 0L

        // Moves the map on the loop's own behalf, so the listeners do not take it for a
        // finger.
        fun moveMap(action: () -> Unit) {
            selfMoving.set(true)
            try {
                action()
            } finally {
                selfMoving.set(false)
            }
        }

        // A zoom too far out to follow a road by is not a choice worth keeping.
        fun usable(zoom: Double): Double = if (zoom < MINIMUM_USEFUL_ZOOM) CRUISING_ZOOM else zoom

        fun inputs(): List<Any?> = listOf(
            targetPosition.value, targetHeading.value, following.value, turnZoom.value,
            maneuverDistance.value, routeCoords.value, routeProgress.value, outsideMoves.intValue,
        )

        while (true) {
            val frameNanos = withFrameNanos { it }
            val elapsed = if (lastFrameNanos == 0L) 0.0 else (frameNanos - lastFrameNanos) / 1e9
            lastFrameNanos = frameNanos
            var moved = false
            var stillGoing = false

            val moves = outsideMoves.intValue
            val movedOutside = moves != seenOutsideMoves
            seenOutsideMoves = moves
            if (movedOutside && following.value) {
                // Long enough not to fight a pan mid-gesture, short enough that a map
                // nudged at a red light does not stay nudged until the car moves off.
                recentreAtNanos = frameNanos + FOLLOW_RESUME_NANOS
            }
            // The live zoom differing from the last one applied here means somebody else
            // set it — a pinch, the overview framing, the recentre button. Carry on from
            // there, so any change from here is a glide rather than a jump back to a value
            // nobody is looking at any more.
            val liveZoom = mapView.zoomLevelDouble
            val zoomedOutside = abs(liveZoom - appliedZoom) > ZOOM_STEP_THRESHOLD
            if (zoomedOutside) {
                appliedZoom = liveZoom
                shownZoom = liveZoom
            }

            var drawn: GeoPoint? = null
            var positionChanged = false
            var rotation: Float? = null
            val wantedPosition = targetPosition.value
            if (wantedPosition != null) {
                // Only when the route and the progress describe the same trip. Straight after
                // a reroute the new line arrives before the first fix measured along it, and
                // for that moment the old progress would place the arrow at some arbitrary
                // point on the new route; the fix's own position is the safer guide.
                val coords = routeCoords.value
                val progress = routeProgress.value?.takeIf { along ->
                    coords != null &&
                        haversineMeters(interpolateAlongCoords(coords, along), wantedPosition) < ROUTE_AGREEMENT_METERS
                }
                val nextPosition = if (coords != null && progress != null) {
                    val along = shownProgress?.takeIf { coords === shownCoords }
                    val nextProgress = if (along == null || abs(progress - along).let { it > SNAP_DISTANCE_METERS || it < SETTLE_DISTANCE_METERS }) {
                        progress
                    } else {
                        approachValue(along, progress, smoothingFactor(elapsed, POSITION_TIME_CONSTANT_SECONDS))
                    }
                    shownProgress = nextProgress
                    shownCoords = coords
                    interpolateAlongCoords(coords, nextProgress)
                } else {
                    shownProgress = null
                    shownCoords = null
                    val here = shownPosition
                    if (here == null || haversineMeters(here, wantedPosition).let { it > SNAP_DISTANCE_METERS || it < SETTLE_DISTANCE_METERS }) {
                        wantedPosition
                    } else {
                        approachPosition(here, wantedPosition, smoothingFactor(elapsed, POSITION_TIME_CONSTANT_SECONDS))
                    }
                }
                shownPosition = nextPosition

                val wantedHeading = targetHeading.value
                val facing = shownHeading
                val nextHeading = when {
                    wantedHeading == null -> facing
                    facing == null || angularDifference(facing, wantedHeading) < SETTLE_DEGREES -> wantedHeading
                    else -> approachBearing(facing, wantedHeading, smoothingFactor(elapsed, HEADING_TIME_CONSTANT_SECONDS))
                }
                shownHeading = nextHeading

                val point = GeoPoint(nextPosition.lat, nextPosition.lon)
                drawn = point
                positionChanged = vehicle.position != point
                if (positionChanged) {
                    vehicle.position = point
                    moved = true
                }
                rotation = nextHeading?.let { -it.toFloat() }
                if (rotation != null && vehicle.rotation != rotation) {
                    vehicle.rotation = rotation
                    moved = true
                }
            }

            if (following.value) {
                if (drawn == null) {
                    recentreAtNanos = 0L
                } else {
                    val centre: GeoPoint = drawn
                    val turnTo: Float? = rotation
                    val startedFollowing = !wasFollowing
                    val recentreDue = recentreAtNanos != 0L && frameNanos >= recentreAtNanos
                    if (positionChanged || startedFollowing || recentreDue) {
                        moveMap { mapView.setExpectedCenter(centre) }
                        recentreAtNanos = 0L
                        moved = true
                    }
                    // Rotating the map to the heading is what makes a turn instruction read
                    // correctly at a junction.
                    if (turnTo != null && mapView.mapOrientation != turnTo) {
                        moveMap { mapView.mapOrientation = turnTo }
                        moved = true
                    }

                    val framingNow = if (turnZoom.value) {
                        followFraming(maneuverDistance.value, framing)
                    } else {
                        FollowFraming.CRUISING
                    }
                    val framingChanged = framingNow != framing
                    framing = framingNow
                    val turnZoomToggled = turnZoom.value != hadTurnZoom
                    hadTurnZoom = turnZoom.value
                    when {
                        startedFollowing || turnZoomToggled ->
                            zoomTarget = if (turnZoom.value) zoomFor(framingNow) else usable(shownZoom)
                        // Somebody chose this zoom on purpose. Keep it, within reason, until
                        // the situation changes.
                        zoomedOutside -> zoomTarget = usable(liveZoom)
                        framingChanged && turnZoom.value -> zoomTarget = zoomFor(framingNow)
                    }
                    zoomTarget = zoomTarget.coerceIn(mapView.minZoomLevel, mapView.maxZoomLevel)

                    shownZoom = if (abs(zoomTarget - shownZoom) < SETTLE_ZOOM) {
                        zoomTarget
                    } else {
                        approachValue(shownZoom, zoomTarget, smoothingFactor(elapsed, ZOOM_TIME_CONSTANT_SECONDS))
                    }
                    // Every zoom change rescales the tile cache, so a step too small to
                    // see is a frame's work thrown away — except the last one, which lands
                    // it exactly.
                    if (shownZoom != appliedZoom &&
                        (abs(shownZoom - appliedZoom) > ZOOM_STEP_THRESHOLD || shownZoom == zoomTarget)
                    ) {
                        moveMap { mapView.controller.setZoom(shownZoom) }
                        appliedZoom = mapView.zoomLevelDouble
                        moved = true
                    }
                    stillGoing = shownZoom != zoomTarget || recentreAtNanos != 0L
                    wasFollowing = true
                }
            } else {
                // Not following — browsing, or stepped back for the overview. Leave the
                // map where the driver put it, and forget the framing so the next trip is
                // not judged against a band left over from the last one.
                wasFollowing = false
                recentreAtNanos = 0L
                framing = FollowFraming.CRUISING
                appliedZoom = liveZoom
                shownZoom = liveZoom
                zoomTarget = liveZoom
                if (mapView.mapOrientation != 0f) {
                    moveMap { mapView.mapOrientation = 0f }
                    moved = true
                }
            }

            if (moved) mapView.invalidate()

            if (!moved && !stillGoing) {
                val atRest = inputs()
                snapshotFlow { inputs() }.first { it != atRest }
                // Time spent asleep is not time to catch up on: measured from here, the
                // first frame after waking would close the whole gap at once.
                lastFrameNanos = 0L
            }
        }
    }

    // Stepping back to see the whole route mid-drive, and returning to the driver after.
    LaunchedEffect(overview, routes, selectedRouteIndex) {
        if (!overview) return@LaunchedEffect
        val route = routes.getOrNull(selectedRouteIndex) ?: return@LaunchedEffect
        mapView.mapOrientation = 0f
        mapView.zoomToBoundingBox(GeoBox.of(route.geometry).expandMeters(300.0).toOsm(), true, ROUTE_PADDING_PX)
    }

    LaunchedEffect(mapTheme) {
        mapView.overlayManager.tilesOverlay.setColorFilter(tileFilterFor(mapTheme))
        mapView.invalidate()
    }

    // Sit the vehicle low on the screen while following, so most of the view is the road
    // ahead rather than the road already driven. osmdroid rotates about the offset centre
    // rather than the middle of the view, so the map still turns around the vehicle itself.
    LaunchedEffect(followUser, mapHeight.value) {
        val height = if (mapHeight.value > 0) mapHeight.value else mapView.height
        val offset = if (followUser) (height * (FOLLOW_SCREEN_POSITION - 0.5f)).toInt() else 0
        if (mapView.mapCenterOffsetY != offset) {
            mapView.setMapCenterOffset(0, offset)
            mapView.invalidate()
        }
    }

    /*
     * Tells the app where the map has got to, so it can load the cameras there.
     *
     * Samples on a timer rather than debouncing the map's own movement events, and that is
     * not a detail. The camera moves every frame while following a driver, so the map emits
     * a scroll event sixty times a second — and a debounce that restarts on every event
     * never fires at all while the car is moving, which is precisely when the layer needs
     * loading. A sampler cannot be starved: it looks at wherever the map has got to, on a
     * cadence of its own, and only when something has moved since it last looked.
     *
     * Deciding whether that means a download is the receiver's business: it knows what it
     * already holds.
     */
    LaunchedEffect(Unit) {
        while (true) {
            delay(VIEWPORT_SAMPLE_MILLIS)
            if (!viewportDirty.getAndSet(false)) continue
            val box = visibleBox(mapView) ?: continue
            reportViewport.value(box)
        }
    }

    // Explicit "take me back to where I am", separate from follow mode so it also works
    // when the driver has panned away while browsing.
    LaunchedEffect(recenterTick) {
        if (recenterTick == 0) return@LaunchedEffect
        val fix = userFix ?: return@LaunchedEffect
        mapView.controller.animateTo(GeoPoint(fix.position.lat, fix.position.lon))
        if (mapView.zoomLevelDouble < 15.0) mapView.controller.setZoom(16.0)
    }

    AndroidView(factory = { mapView }, modifier = modifier)
}

/**
 * The area the map is currently showing, or null before it has been laid out.
 *
 * osmdroid can report a degenerate box at extreme zoom or mid-animation, which [GeoBox]
 * rejects; that is a frame to skip, not a crash.
 */
private fun visibleBox(map: MapView): GeoBox? {
    val box = map.boundingBox ?: return null
    return runCatching {
        GeoBox(south = box.latSouth, west = box.lonWest, north = box.latNorth, east = box.lonEast)
    }.getOrNull()
}

/**
 * What each framing is worth in osmdroid zoom levels.
 *
 * A whole level between each, because a level is a doubling of the scale and anything less
 * than that is a change the driver has to go looking for rather than one they see happen.
 * Three fixed steps rather than a continuous ramp: a zoom that creeps constantly is more
 * distracting than one that changes twice and settles.
 */
private fun zoomFor(framing: FollowFraming): Double = when (framing) {
    FollowFraming.CRUISING -> CRUISING_ZOOM
    FollowFraming.APPROACHING -> APPROACH_ZOOM
    FollowFraming.AT_MANEUVER -> MANEUVER_ZOOM
}

private const val CRUISING_ZOOM = 17.0
private const val APPROACH_ZOOM = 18.0
private const val MANEUVER_ZOOM = 19.0

/**
 * Zoom changes smaller than this are not worth applying.
 *
 * Setting a zoom level rescales the tile cache, which is real work, and the frame loop
 * offers one a frame. Below this the result would not differ by a pixel, so the steady
 * state between manoeuvres costs nothing at all.
 */
private const val ZOOM_STEP_THRESHOLD = 0.01

/**
 * How long a map moved by hand while following stays where it was put.
 *
 * About the gap between two fixes, which is how long the old once-per-fix recentring
 * left it — long enough not to fight a pan mid-gesture.
 */
private const val FOLLOW_RESUME_NANOS = 1_200_000_000L

/**
 * How far apart the route-matched fix and the route's own point for that progress may be
 * before they are taken to describe different trips. The two come from the same projection,
 * so in agreement they coincide; this only has to absorb rounding.
 */
private const val ROUTE_AGREEMENT_METERS = 5.0

/** Below this the map is too far out to follow a road by, whatever the driver chose. */
private const val MINIMUM_USEFUL_ZOOM = 16.0

/** How often the map is asked where it has got to, for loading the camera layer. */
private const val VIEWPORT_SAMPLE_MILLIS = 700L

/**
 * Where down the screen the vehicle sits while being followed, as a fraction of the height.
 *
 * Centred, half the map is road already driven, which is of no use to anybody. Pushing the
 * vehicle down the screen spends that space on the road ahead instead — far enough to see
 * the next turn coming, not so far that the arrow is lost behind the bottom panel.
 */
private const val FOLLOW_SCREEN_POSITION = 0.70f

/**
 * Recolours map tiles as they are drawn.
 *
 * Filtering at draw time rather than switching tile source means no extra downloads, no
 * second tile cache, and it works identically on a saved offline map.
 */
private fun tileFilterFor(theme: MapTheme): ColorFilter? = when (theme) {
    MapTheme.DAY -> null

    // Straight multiplicative dim: the same map, turned down.
    MapTheme.DIM -> ColorMatrixColorFilter(
        ColorMatrix(
            floatArrayOf(
                0.62f, 0f, 0f, 0f, 0f,
                0f, 0.62f, 0f, 0f, 0f,
                0f, 0f, 0.62f, 0f, 0f,
                0f, 0f, 0f, 1f, 0f,
            ),
        ),
    )

    // Invert, then rotate the hue back so the result reads as a dark map rather than a
    // photographic negative: roads stay pale on dark ground and greens stay green.
    MapTheme.NIGHT -> ColorMatrixColorFilter(
        ColorMatrix().apply {
            set(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    ),
                ),
            )
            postConcat(ColorMatrix().apply { setSaturation(0.4f) })
        },
    )
}

private const val ROUTE_PADDING_PX = 140

private fun GeoBox.toOsm(): OsmBox = OsmBox(north, east, south, west)

private fun polylineFor(map: MapView, route: Route, selected: Boolean): Polyline =
    Polyline(map).apply {
        setPoints(route.geometry.map { GeoPoint(it.lat, it.lon) })
        outlinePaint.apply {
            color = if (selected) ShadowColors.RouteSelected.toArgb() else ShadowColors.RouteAlternate.toArgb()
            strokeWidth = if (selected) 16f else 9f
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
            isAntiAlias = true
            alpha = if (selected) 255 else 150
        }
        infoWindow = null
    }

/**
 * Coloured overlays for the stretches the model expects to be moving badly.
 *
 * Drawn over the route's own line rather than replacing it: spans are measured in metres
 * along the route and slicing them back out is subject to rounding, so anything that falls
 * between two spans shows the route colour underneath instead of a hole in the line.
 *
 * Only notable bands are drawn. Painting the clear stretches too would mean colouring the
 * whole route on any city trip, at which point the colour stops telling the driver anything.
 */
private fun congestionLines(map: MapView, route: Route): List<Polyline> {
    val notable = route.congestionSpans.filter { it.level.isNotable && it.lengthMeters > 1.0 }
    if (notable.isEmpty()) return emptyList()

    val coords = listToCoords(route.geometry)
    return notable.mapNotNull { span ->
        val piece = sliceCoords(coords, span.fromMeters, span.toMeters)
        if (coordsCount(piece) < 2) return@mapNotNull null
        Polyline(map).apply {
            setPoints(coordsToList(piece).map { GeoPoint(it.lat, it.lon) })
            outlinePaint.apply {
                color = colorFor(span.level).toArgb()
                // Matches the selected route's width so the coloured stretch reads as part
                // of the same line, and butt caps keep it from bleeding past its span.
                strokeWidth = 16f
                strokeCap = Paint.Cap.BUTT
                strokeJoin = Paint.Join.ROUND
                isAntiAlias = true
            }
            infoWindow = null
        }
    }
}

/**
 * The area a device can see.
 *
 * Cameras with a mapped facing direction get a wedge showing where they actually look;
 * ones without get a full circle, matching how the router treats them. Translucent enough
 * to read the road underneath, since overlapping cameras are common and their shapes stack,
 * and more so the less the device matters to this trip.
 */
private fun coverageShape(map: MapView, detector: Detector, look: DetectorLook): Polygon {
    val tint = colorFor(detector.kind)
    return Polygon(map).apply {
        points = when (val heading = detector.headingDegrees) {
            null -> Polygon.pointsAsCircle(
                GeoPoint(detector.position.lat, detector.position.lon),
                detector.rangeMeters,
            )

            else -> wedge(detector.position, heading, detector.fovDegrees, detector.rangeMeters)
        }
        fillPaint.color = tint.copy(alpha = look.coverageFill).toArgb()
        outlinePaint.color = tint.copy(alpha = look.coverageOutline).toArgb()
        outlinePaint.strokeWidth = 2f
        infoWindow = null
    }
}

private fun wedge(center: LatLon, heading: Double, fovDegrees: Double, rangeMeters: Double): List<GeoPoint> {
    val half = (fovDegrees / 2).coerceAtMost(180.0)
    val points = ArrayList<GeoPoint>()
    points.add(GeoPoint(center.lat, center.lon))
    var angle = heading - half
    val step = (half * 2) / WEDGE_SEGMENTS
    repeat(WEDGE_SEGMENTS + 1) {
        val edge = destinationPoint(center, angle, rangeMeters)
        points.add(GeoPoint(edge.lat, edge.lon))
        angle += step
    }
    points.add(GeoPoint(center.lat, center.lon))
    return points
}

private const val WEDGE_SEGMENTS = 16

/**
 * How a device is drawn, given what it means to the trip on screen.
 *
 * @property markerAlpha opacity of the marker itself
 * @property iconScale size of the marker relative to the plain icon
 */
private enum class DetectorLook(
    val markerAlpha: Float,
    val iconScale: Float,
    val coverageFill: Float,
    val coverageOutline: Float,
) {
    /** No route chosen: every device is equally relevant. */
    PLAIN(markerAlpha = 1f, iconScale = 1f, coverageFill = 0.20f, coverageOutline = 0.55f),

    /** On the route and still able to see the car. */
    ON_ROUTE(markerAlpha = 1f, iconScale = 1.4f, coverageFill = 0.24f, coverageOutline = 0.70f),

    /** Near the route but not on it. */
    NEARBY(markerAlpha = 0.55f, iconScale = 1f, coverageFill = 0.10f, coverageOutline = 0.30f),

    /** On the route, and now out of reach behind the driver. */
    PASSED(markerAlpha = 0.28f, iconScale = 1f, coverageFill = 0.06f, coverageOutline = 0.20f),
}

/** One device as currently on the map, kept so an unchanged device is not redrawn. */
private class DrawnDetector(
    val detector: Detector,
    val look: DetectorLook,
    val withCoverage: Boolean,
    val marker: Marker,
    val coverage: Polygon?,
)

/**
 * One icon per kind of device and size, shared by every marker drawn with it.
 *
 * A marker applies its own opacity and bounds to its icon each time it draws, so one icon
 * can stand in for any number of markers. Building a fresh tinted copy for every marker,
 * as before, meant a few hundred drawables on a city route, all identical.
 */
private class DetectorIcons(private val context: Context) {
    private val icons = HashMap<Pair<DetectorKind, Float>, Drawable?>()

    fun forKind(kind: DetectorKind, scale: Float): Drawable? =
        icons.getOrPut(kind to scale) { build(kind, scale) }

    private fun build(kind: DetectorKind, scale: Float): Drawable? {
        val base = ContextCompat.getDrawable(context, R.drawable.ic_detector)?.mutate() ?: return null
        base.setTint(colorFor(kind).toArgb())
        if (scale == 1f || base.intrinsicWidth <= 0 || base.intrinsicHeight <= 0) return base
        // Rendered once at the larger size rather than scaled at draw time, which a marker
        // has no way to ask for.
        val width = (base.intrinsicWidth * scale).roundToInt()
        val height = (base.intrinsicHeight * scale).roundToInt()
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        base.setBounds(0, 0, width, height)
        base.draw(Canvas(bitmap))
        return BitmapDrawable(context.resources, bitmap)
    }
}

/** One device on the map. */
private fun detectorMarker(
    map: MapView,
    detector: Detector,
    look: DetectorLook,
    icons: DetectorIcons,
    onTap: (Detector) -> Unit,
): Marker =
    Marker(map).apply {
        position = GeoPoint(detector.position.lat, detector.position.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = icons.forKind(detector.kind, look.iconScale)
        setAlpha(look.markerAlpha)
        title = detector.describe()
        infoWindow = null
        setOnMarkerClickListener { _, _ ->
            onTap(detector)
            true
        }
    }

private fun pin(map: MapView, position: LatLon, iconRes: Int, label: String, centered: Boolean): Marker =
    Marker(map).apply {
        this.position = GeoPoint(position.lat, position.lon)
        setAnchor(Marker.ANCHOR_CENTER, if (centered) Marker.ANCHOR_CENTER else Marker.ANCHOR_BOTTOM)
        icon = ContextCompat.getDrawable(map.context, iconRes)
        title = label
        infoWindow = null
    }

private fun vehicleMarker(map: MapView): Marker =
    Marker(map).apply {
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = ContextCompat.getDrawable(map.context, R.drawable.ic_vehicle)
        isFlat = true
        infoWindow = null
    }

/** Traffic scale, shared by the map line and the directions list. */
fun colorFor(level: CongestionLevel): androidx.compose.ui.graphics.Color = when (level) {
    CongestionLevel.FREE -> ShadowColors.RouteSelected
    CongestionLevel.LIGHT -> ShadowColors.TrafficLight
    CongestionLevel.HEAVY -> ShadowColors.TrafficHeavy
    CongestionLevel.SEVERE -> ShadowColors.TrafficSevere
}

/** Colour scale shared with the route cards: red is watched, grey is background noise. */
fun colorFor(kind: DetectorKind): androidx.compose.ui.graphics.Color = when (kind) {
    DetectorKind.ALPR -> ShadowColors.Watched
    DetectorKind.TOLL_GANTRY -> ShadowColors.Caution
    DetectorKind.SPEED_CAMERA -> ShadowColors.Caution
    DetectorKind.RED_LIGHT_CAMERA -> ShadowColors.Caution
    DetectorKind.CCTV -> ShadowColors.TextSecondary
}
