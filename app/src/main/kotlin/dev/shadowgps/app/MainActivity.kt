package dev.shadowgps.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.shadowgps.app.data.Place
import dev.shadowgps.app.ui.MainViewModel
import dev.shadowgps.app.ui.MapScreen
import dev.shadowgps.app.ui.Phase
import dev.shadowgps.app.ui.theme.ShadowGpsTheme
import dev.shadowgps.core.geo.LatLon

class MainActivity : ComponentActivity() {

    private val model: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        setContent {
            ShadowGpsTheme {
                val state by model.state.collectAsStateWithLifecycle()

                // Honour geo: links from other apps, so ShadowGPS can be the thing that
                // opens when you tap an address elsewhere on the phone.
                LaunchedEffect(intent) {
                    parseGeoIntent(intent)?.let { place -> model.chooseDestination(place) }
                }

                LaunchedEffect(state.phase, state.settings.keepScreenOnWhileNavigating) {
                    val keepOn = state.phase == Phase.NAVIGATING && state.settings.keepScreenOnWhileNavigating
                    if (keepOn) {
                        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    } else {
                        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    }
                }

                MapScreen(viewModel = model)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        parseGeoIntent(intent)?.let { place -> model.chooseDestination(place) }
    }

    override fun onResume() {
        super.onResume()
        // Location permission and the system location toggle can both change while the
        // app is in the background.
        model.refreshPermissionState()
    }
}

/**
 * Pulls a destination out of a `geo:` URI.
 *
 * Handles both forms in the wild: `geo:lat,lon` and `geo:0,0?q=lat,lon(label)`.
 */
internal fun parseGeoIntent(intent: Intent?): Place? {
    val uri: Uri = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.data ?: return null
    if (uri.scheme != "geo") return null

    val query = uri.query?.substringAfter("q=", "")?.substringBefore("&").orEmpty()
    val label = query.substringAfter("(", "").substringBefore(")").takeIf { it.isNotBlank() }

    val coordinates = query.substringBefore("(").takeIf { it.contains(",") }
        ?: uri.schemeSpecificPart.substringBefore("?")

    val parts = coordinates.split(",")
    if (parts.size < 2) return null
    val lat = parts[0].trim().toDoubleOrNull() ?: return null
    val lon = parts[1].trim().toDoubleOrNull() ?: return null
    if (lat == 0.0 && lon == 0.0) return null

    return Place(
        name = label ?: "Shared location",
        position = LatLon(lat, lon),
    )
}
