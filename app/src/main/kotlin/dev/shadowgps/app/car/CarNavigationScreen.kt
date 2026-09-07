package dev.shadowgps.app.car

import android.graphics.Canvas
import android.graphics.Rect
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.Distance
import androidx.car.app.model.DateTimeWithZone
import androidx.car.app.model.Template
import androidx.car.app.navigation.NavigationManager
import androidx.car.app.navigation.NavigationManagerCallback
import androidx.car.app.navigation.model.Maneuver
import androidx.car.app.navigation.model.MessageInfo
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.navigation.model.RoutingInfo
import androidx.car.app.navigation.model.Step
import androidx.car.app.navigation.model.TravelEstimate
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import dev.shadowgps.app.nav.CarNavState
import dev.shadowgps.app.nav.NavigationHub
import dev.shadowgps.core.routing.Maneuver as RouteManeuver
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.util.TimeZone
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The trip, on the car screen.
 *
 * Two halves that update on different clocks. The template — the instruction card, the
 * distance, the arrival time — is rebuilt only when [invalidate] is called, because the host
 * rate-limits template updates and rebuilding one per location fix would be throttled and
 * wasteful. The map is drawn straight onto the car's surface on every fix, which the host
 * does not rate-limit because it is the app's own pixels.
 *
 * Starting a trip is deliberately not possible from here. Choosing a destination means a
 * search field, a result list and a lot of looking at a screen, which is the one thing a
 * driving-focused surface should not encourage; the phone does that part, and the car takes
 * over once the car is moving.
 */
class CarNavigationScreen(carContext: CarContext) : Screen(carContext), SurfaceCallback {

    private val renderer = CarMapRenderer()
    private var surface: SurfaceContainer? = null
    /**
     * The part of the surface the host is not covering with its own card.
     *
     * Worth honouring rather than drawing to the whole surface: on a wide head unit the
     * routing card sits over one side, and a vehicle centred on the surface would spend the
     * whole trip hidden underneath it.
     */
    private var visibleArea: Rect? = null
    private var announcedNavigating = false

    init {
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(this)

        carContext.getCarService(NavigationManager::class.java).setNavigationManagerCallback(
            object : NavigationManagerCallback {
                override fun onStopNavigation() {
                    // The host asks for this when another navigation app takes over, and it
                    // is not a suggestion.
                    NavigationHub.requestStop()
                }
            },
        )

        lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onDestroy(owner: LifecycleOwner) {
                    endNavigationIfStarted()
                }
            },
        )

        lifecycleScope.launch {
            NavigationHub.car.collectLatest { state ->
                syncNavigationManager(state)
                drawMap(state)
                invalidate()
            }
        }
    }

    override fun onGetTemplate(): Template {
        val state = NavigationHub.car.value
        val builder = NavigationTemplate.Builder()
            .setBackgroundColor(CarColor.SECONDARY)
            .setActionStrip(actionStrip(state))

        val navigation = state.navigation
        val step = navigation?.let { it.nextStep ?: it.currentStep }

        // A routing card has to carry either a step or a spinner; it cannot be built empty.
        // Anything without a step to show is a message instead.
        if (navigation == null || state.route == null || step == null) {
            return builder.setNavigationInfo(idleMessage()).build()
        }

        val routing = if (navigation.isOffRoute) {
            RoutingInfo.Builder().setLoading(true).build()
        } else {
            RoutingInfo.Builder()
                .setCurrentStep(
                    carStep(step),
                    distance(navigation.distanceToManeuverMeters, state.imperial),
                )
                .apply {
                    navigation.followingStep?.let { setNextStep(carStep(it)) }
                }
                .build()
        }

        return builder
            .setNavigationInfo(routing)
            .setDestinationTravelEstimate(travelEstimate(state))
            .build()
    }

    private fun idleMessage(): MessageInfo = MessageInfo.Builder("Start a trip on your phone")
        .setText("Routes are planned on the phone, so nobody has to read a search box at the wheel.")
        .build()

    // ------------------------------------------------------------------ surface

    override fun onSurfaceAvailable(container: SurfaceContainer) {
        surface = container
        drawMap(NavigationHub.car.value)
    }

    override fun onSurfaceDestroyed(container: SurfaceContainer) {
        surface = null
    }

    override fun onVisibleAreaChanged(area: Rect) {
        visibleArea = area
        drawMap(NavigationHub.car.value)
    }

    /**
     * Paints one frame.
     *
     * Everything here is defensive on purpose: the host can take the surface away between
     * one fix and the next, and a crash on a car screen is worse than a stale one.
     */
    private fun drawMap(state: CarNavState) {
        val container = surface ?: return
        val nativeSurface = container.surface ?: return
        if (!nativeSurface.isValid) return

        var canvas: Canvas? = null
        try {
            canvas = nativeSurface.lockCanvas(null) ?: return
            renderer.draw(canvas, state, container.width, container.height, container.dpi, visibleArea)
        } catch (e: IllegalArgumentException) {
            // The surface went away mid-frame. The next fix will draw again.
        } catch (e: IllegalStateException) {
            // Same.
        } finally {
            if (canvas != null) {
                runCatching { nativeSurface.unlockCanvasAndPost(canvas) }
            }
        }
    }

    // ------------------------------------------------------------------ host state

    /**
     * Tells the host when a trip starts and stops.
     *
     * The car needs to know, not merely be shown: it is what lets the head unit hand the
     * screen over from whatever was there before, and what stops two navigation apps
     * talking over each other.
     */
    private fun syncNavigationManager(state: CarNavState) {
        val manager = carContext.getCarService(NavigationManager::class.java)
        if (state.isNavigating && !announcedNavigating) {
            manager.navigationStarted()
            announcedNavigating = true
        } else if (!state.isNavigating && announcedNavigating) {
            manager.navigationEnded()
            announcedNavigating = false
        }
    }

    private fun endNavigationIfStarted() {
        if (!announcedNavigating) return
        runCatching { carContext.getCarService(NavigationManager::class.java).navigationEnded() }
        announcedNavigating = false
    }

    // ------------------------------------------------------------------ model

    /**
     * The buttons along the edge of the car screen.
     *
     * Text rather than icons throughout, and only two of them. A car screen is read in
     * glances, and the camera count is the one number this app exists to show — putting it
     * where the driver already looks for the trip controls beats hiding it behind a tap.
     */
    private fun actionStrip(state: CarNavState): ActionStrip {
        val builder = ActionStrip.Builder()
            .addAction(
                Action.Builder()
                    .setTitle(camerasAheadLabel(state))
                    .setOnClickListener { invalidate() }
                    .build(),
            )
        if (state.isNavigating) {
            builder.addAction(
                Action.Builder()
                    .setTitle("Stop")
                    .setOnClickListener { NavigationHub.requestStop() }
                    .build(),
            )
        }
        return builder.build()
    }

    private fun camerasAheadLabel(state: CarNavState): String {
        val navigation = state.navigation ?: return "ShadowGPS"
        val ahead = navigation.detectorsAhead.count {
            it.alongRouteMeters > navigation.distanceAlongRouteMeters
        }
        return when (ahead) {
            0 -> "Unseen"
            1 -> "1 camera"
            else -> "$ahead cameras"
        }
    }

    private fun carStep(step: dev.shadowgps.core.routing.RouteStep): Step {
        val builder = Step.Builder(step.instruction)
        step.roadName?.let { builder.setRoad(it) }
        builder.setManeuver(maneuver(step))
        return builder.build()
    }

    private fun maneuver(step: dev.shadowgps.core.routing.RouteStep): Maneuver {
        val type = when (step.maneuver) {
            RouteManeuver.DEPART -> Maneuver.TYPE_DEPART
            RouteManeuver.CONTINUE -> Maneuver.TYPE_STRAIGHT
            RouteManeuver.SLIGHT_LEFT -> Maneuver.TYPE_TURN_SLIGHT_LEFT
            RouteManeuver.LEFT -> Maneuver.TYPE_TURN_NORMAL_LEFT
            RouteManeuver.SHARP_LEFT -> Maneuver.TYPE_TURN_SHARP_LEFT
            RouteManeuver.SLIGHT_RIGHT -> Maneuver.TYPE_TURN_SLIGHT_RIGHT
            RouteManeuver.RIGHT -> Maneuver.TYPE_TURN_NORMAL_RIGHT
            RouteManeuver.SHARP_RIGHT -> Maneuver.TYPE_TURN_SHARP_RIGHT
            RouteManeuver.U_TURN -> Maneuver.TYPE_U_TURN_LEFT
            RouteManeuver.ROUNDABOUT -> Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW
            RouteManeuver.ARRIVE -> Maneuver.TYPE_DESTINATION
        }
        val builder = Maneuver.Builder(type)
        // The host rejects a roundabout with no exit number, so a missing one becomes the
        // first exit rather than a crash.
        if (type == Maneuver.TYPE_ROUNDABOUT_ENTER_AND_EXIT_CW) {
            builder.setRoundaboutExitNumber((step.roundaboutExit ?: 1).coerceAtLeast(1))
        }
        return builder.build()
    }

    private fun travelEstimate(state: CarNavState): TravelEstimate {
        val navigation = state.navigation
        val remainingSeconds = navigation?.secondsRemaining ?: 0.0
        val arrival = System.currentTimeMillis() + (remainingSeconds * 1000).roundToLong()

        return TravelEstimate.Builder(
            distance(navigation?.distanceRemainingMeters ?: 0.0, state.imperial),
            DateTimeWithZone.create(arrival, TimeZone.getDefault()),
        )
            .setRemainingTimeSeconds(remainingSeconds.roundToLong().coerceAtLeast(0))
            .setRemainingTimeColor(CarColor.DEFAULT)
            .build()
    }

    /**
     * Metres into something the car will print.
     *
     * The unit is chosen by size as well as by the driver's preference, because "0.06 miles"
     * is not how anybody describes a turn coming up.
     */
    private fun distance(meters: Double, imperial: Boolean): Distance {
        val safe = meters.coerceAtLeast(0.0)
        return if (imperial) {
            val feet = safe * 3.28084
            if (feet < 1000) {
                Distance.create((feet / 10).roundToInt() * 10.0, Distance.UNIT_FEET)
            } else {
                Distance.create(safe / 1609.344, Distance.UNIT_MILES)
            }
        } else {
            if (safe < 1000) {
                Distance.create((safe / 10).roundToInt() * 10.0, Distance.UNIT_METERS)
            } else {
                Distance.create(safe / 1000.0, Distance.UNIT_KILOMETERS)
            }
        }
    }
}
