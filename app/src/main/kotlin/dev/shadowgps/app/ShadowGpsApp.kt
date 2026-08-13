package dev.shadowgps.app

import android.app.Application
import android.content.Context
import org.osmdroid.config.Configuration
import java.io.File

class ShadowGpsApp : Application() {

    override fun onCreate() {
        super.onCreate()
        configureOsmdroid()
    }

    /**
     * Points osmdroid at app-private storage and gives it an identifying User-Agent.
     *
     * Both matter: the default cache location needs external storage permissions this app
     * has no business asking for, and OSM's tile servers block clients that do not identify
     * themselves.
     */
    private fun configureOsmdroid() {
        val config = Configuration.getInstance()
        config.load(this, getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
        config.userAgentValue = "ShadowGPS/${BuildConfig.VERSION_NAME}"
        config.osmdroidBasePath = File(cacheDir, "osmdroid").apply { mkdirs() }
        config.osmdroidTileCache = File(config.osmdroidBasePath, "tiles").apply { mkdirs() }
        // Tiles are the bulkiest thing the app stores; cap it rather than let it grow.
        config.tileFileSystemCacheMaxBytes = 200L * 1024 * 1024
        config.tileFileSystemCacheTrimBytes = 160L * 1024 * 1024
    }
}
