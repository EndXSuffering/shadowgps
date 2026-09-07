package dev.shadowgps.app.nav

import dev.shadowgps.core.detect.Detector
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.graph.RoadGraph
import dev.shadowgps.core.nav.NavigationState
import dev.shadowgps.core.routing.Route
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow

/** What the guidance notification shows. */
data class NavigationBanner(
    val instruction: String,
    val detail: String,
)

/**
 * Everything the car screen needs to draw a trip.
 *
 * A snapshot rather than a reference to the view model, because the car app is a separate
 * component: it has no activity, no view model store, and no business reaching into either.
 * The road graph is carried by reference — it is large, immutable once built, and the car
 * renderer draws the surrounding streets straight out of it, which is what gives the car
 * screen a real map without fetching a single tile.
 */
data class CarNavState(
    val graph: RoadGraph? = null,
    val route: Route? = null,
    val detectors: List<Detector> = emptyList(),
    val vehiclePosition: LatLon? = null,
    val vehicleHeadingDegrees: Double? = null,
    val navigation: NavigationState? = null,
    val destinationName: String? = null,
    val imperial: Boolean = false,
) {
    val isNavigating: Boolean get() = route != null && navigation != null
}

/**
 * The single line of communication between the view model and the foreground service.
 *
 * The view model owns navigation; the service exists to keep the process alive and put the
 * next instruction in the notification shade. A process-wide object is the simplest thing
 * that lets those two talk without binding, and there is only ever one trip in progress.
 */
object NavigationHub {

    private val _banner = MutableStateFlow<NavigationBanner?>(null)
    val banner: StateFlow<NavigationBanner?> = _banner.asStateFlow()

    private val _car = MutableStateFlow(CarNavState())

    /** What the Android Auto screen draws, published by the view model on every fix. */
    val car: StateFlow<CarNavState> = _car.asStateFlow()

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the user taps "Stop" on the notification. */
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    fun publish(banner: NavigationBanner?) {
        _banner.value = banner
    }

    fun publishCar(state: CarNavState) {
        _car.value = state
    }

    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }
}
