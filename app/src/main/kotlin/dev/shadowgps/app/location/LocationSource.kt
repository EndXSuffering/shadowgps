package dev.shadowgps.app.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.nav.PositionFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Device location, straight from the platform.
 *
 * Deliberately built on [LocationManager] rather than Play Services' fused provider: fused
 * location is a Google binary that reports to Google, which is a strange dependency for an
 * app whose purpose is not being tracked. The platform API also keeps the build free of
 * proprietary components, so it runs on de-Googled Android.
 */
class LocationSource(private val context: Context) {

    private val manager: LocationManager? =
        ContextCompat.getSystemService(context, LocationManager::class.java)

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Whether the user has location switched on at all. */
    fun isEnabled(): Boolean {
        val manager = manager ?: return false
        return manager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    @SuppressLint("MissingPermission")
    fun lastKnown(): PositionFix? {
        if (!hasPermission()) return null
        val manager = manager ?: return null
        val candidates = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            .mapNotNull { provider ->
                runCatching { manager.getLastKnownLocation(provider) }.getOrNull()
            }
        return candidates.maxByOrNull { it.time }?.toFix()
    }

    /**
     * A stream of position fixes.
     *
     * Both providers are requested: GPS for accuracy once it has a fix, network for
     * something usable in the seconds before that.
     */
    @SuppressLint("MissingPermission")
    fun updates(
        minIntervalMillis: Long = 1_000L,
        minDistanceMeters: Float = 2f,
    ): Flow<PositionFix> = callbackFlow {
        val manager = manager
        if (manager == null || !hasPermission()) {
            close()
            return@callbackFlow
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(location.toFix())
            }

            // Required on API < 30; the default implementations are final on newer levels.
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Kept for API levels below 29, which still call it.")
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
        }

        val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
        var registered = 0
        for (provider in providers) {
            if (!manager.allProviders.contains(provider)) continue
            runCatching {
                manager.requestLocationUpdates(
                    provider,
                    minIntervalMillis,
                    minDistanceMeters,
                    listener,
                    Looper.getMainLooper(),
                )
                registered++
            }
        }
        if (registered == 0) close()

        lastKnown()?.let { trySend(it) }

        awaitClose { runCatching { manager.removeUpdates(listener) } }
    }

    private fun Location.toFix(): PositionFix = PositionFix(
        position = LatLon(latitude, longitude),
        bearingDegrees = if (hasBearing()) bearing.toDouble() else null,
        speedMetersPerSecond = if (hasSpeed()) speed.toDouble() else null,
        accuracyMeters = if (hasAccuracy()) accuracy.toDouble() else null,
        timestampMillis = time,
    )
}
