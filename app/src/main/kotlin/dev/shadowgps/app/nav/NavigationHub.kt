package dev.shadowgps.app.nav

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
 * The single line of communication between the view model and the foreground service.
 *
 * The view model owns navigation; the service exists to keep the process alive and put the
 * next instruction in the notification shade. A process-wide object is the simplest thing
 * that lets those two talk without binding, and there is only ever one trip in progress.
 */
object NavigationHub {

    private val _banner = MutableStateFlow<NavigationBanner?>(null)
    val banner: StateFlow<NavigationBanner?> = _banner.asStateFlow()

    private val _stopRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    /** Emits when the user taps "Stop" on the notification. */
    val stopRequests: SharedFlow<Unit> = _stopRequests.asSharedFlow()

    fun publish(banner: NavigationBanner?) {
        _banner.value = banner
    }

    fun requestStop() {
        _stopRequests.tryEmit(Unit)
    }
}
