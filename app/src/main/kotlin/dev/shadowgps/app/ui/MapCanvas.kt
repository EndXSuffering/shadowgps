package dev.shadowgps.app.ui

import android.graphics.Paint
import android.graphics.drawable.Drawable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import dev.shadowgps.app.R
import dev.shadowgps.app.data.Place
import dev.shadowgps.app.ui.theme.ShadowColors
import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.geo.destinationPoint
import dev.shadowgps.core.nav.PositionFix
import dev.shadowgps.core.routing.Route
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

    // Every map movement writes here; a debounce below decides when to act on it. A
    // leading-edge throttle used to drop whatever arrived inside its window, which meant
    // the end of a pan — the position the user actually stopped at — was routinely lost
    // and its cameras never loaded.
    val viewportRequest = remember { mutableStateOf<GeoBox?>(null) }
    val reportViewport = rememberUpdatedState(onViewportChanged)

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
            addOnFirstLayoutListener { _, _, _, _, _ ->
                viewportRequest.value = visibleBox(this@apply)
            }

            addMapListener(
                object : MapListener {
                    override fun onScroll(event: ScrollEvent?): Boolean {
                        viewportRequest.value = visibleBox(this@apply)
                        return false
                    }

                    override fun onZoom(event: ZoomEvent?): Boolean {
                        viewportRequest.value = visibleBox(this@apply)
                        return false
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

    LaunchedEffect(detectors, showDetectorRanges) {
        detectorLayer.items.clear()
        for (detector in detectors) {
            if (showDetectorRanges) {
                detectorLayer.items.add(coverageShape(mapView, detector))
            }
            detectorLayer.items.add(detectorMarker(mapView, detector, onDetectorTapped))
        }
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

    // The hot path: one marker moves, nothing is rebuilt.
    LaunchedEffect(userFix, followUser) {
        val fix = userFix ?: return@LaunchedEffect

        if (!centredOnUser.value) {
            centredOnUser.value = true
            mapView.controller.setZoom(16.0)
            mapView.controller.setCenter(GeoPoint(fix.position.lat, fix.position.lon))
            // setCenter does not always emit a scroll event, and this is the jump that
            // first brings the driver's own surroundings into view — the cameras that
            // matter most. Report it directly rather than hoping for a callback.
            viewportRequest.value = visibleBox(mapView)
        }

        vehicle.position = GeoPoint(fix.position.lat, fix.position.lon)
        vehicle.rotation = -(fix.bearingDegrees?.toFloat() ?: 0f)

        if (followUser) {
            mapView.controller.animateTo(vehicle.position)
            // Rotating the map to the heading is what makes a turn instruction read
            // correctly at a junction.
            fix.bearingDegrees?.let { mapView.mapOrientation = -it.toFloat() }
            if (mapView.zoomLevelDouble < 16.0) mapView.controller.setZoom(17.0)
        } else if (mapView.mapOrientation != 0f) {
            mapView.mapOrientation = 0f
        }
        mapView.invalidate()
    }

    // Acts on where the map settled rather than where it passed through, and skips a
    // reload when the new view is inside one already fetched — panning within an area
    // whose cameras are on screen needs nothing.
    var lastLoaded by remember { mutableStateOf<GeoBox?>(null) }
    LaunchedEffect(viewportRequest.value) {
        val box = viewportRequest.value ?: return@LaunchedEffect
        delay(VIEWPORT_DEBOUNCE_MILLIS)
        if (lastLoaded?.contains(box) == true) return@LaunchedEffect
        lastLoaded = box
        reportViewport.value(box)
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

/** Settling time after the last map movement before the camera layer is fetched. */
private const val VIEWPORT_DEBOUNCE_MILLIS = 400L

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
 * The area a device can see.
 *
 * Cameras with a mapped facing direction get a wedge showing where they actually look;
 * ones without get a full circle, matching how the router treats them.
 */
private fun coverageShape(map: MapView, detector: Detector): Polygon {
    val tint = colorFor(detector.kind)
    return Polygon(map).apply {
        points = when (val heading = detector.headingDegrees) {
            null -> Polygon.pointsAsCircle(
                GeoPoint(detector.position.lat, detector.position.lon),
                detector.rangeMeters,
            )

            else -> wedge(detector.position, heading, detector.fovDegrees, detector.rangeMeters)
        }
        // Translucent enough to read the road underneath, since overlapping cameras are
        // common and their shapes stack.
        fillPaint.color = tint.copy(alpha = 0.20f).toArgb()
        outlinePaint.color = tint.copy(alpha = 0.55f).toArgb()
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

private fun detectorMarker(map: MapView, detector: Detector, onTap: (Detector) -> Unit): Marker =
    Marker(map).apply {
        position = GeoPoint(detector.position.lat, detector.position.lon)
        setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
        icon = tinted(map, R.drawable.ic_detector, colorFor(detector.kind).toArgb())
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

private fun tinted(map: MapView, iconRes: Int, color: Int): Drawable? {
    val drawable = ContextCompat.getDrawable(map.context, iconRes)?.mutate() ?: return null
    DrawableCompat.setTint(DrawableCompat.wrap(drawable), color)
    return drawable
}

/** Colour scale shared with the route cards: red is watched, grey is background noise. */
fun colorFor(kind: DetectorKind): androidx.compose.ui.graphics.Color = when (kind) {
    DetectorKind.ALPR -> ShadowColors.Watched
    DetectorKind.TOLL_GANTRY -> ShadowColors.Caution
    DetectorKind.SPEED_CAMERA -> ShadowColors.Caution
    DetectorKind.RED_LIGHT_CAMERA -> ShadowColors.Caution
    DetectorKind.CCTV -> ShadowColors.TextSecondary
}
