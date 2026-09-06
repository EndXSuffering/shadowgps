package dev.shadowgps.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.format.UnitSystem
import dev.shadowgps.core.routing.AvoidanceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * How bright the map is drawn.
 *
 * Raster OpenStreetMap tiles are designed for daylight on a desk, which is painful on a
 * windscreen at night. Rather than swapping tile providers, the tiles are filtered as they
 * are drawn, so this costs no extra downloads and works on a saved map.
 */
enum class MapTheme(val label: String, val description: String) {
    DAY("Daylight", "Standard map colours."),
    DIM("Dimmed", "The same map, darkened for evening driving."),
    NIGHT("Night", "Inverted to dark, for driving after dark."),
    ;

    companion object {
        fun fromName(name: String?): MapTheme = entries.firstOrNull { it.name == name } ?: DAY
    }
}

/** Everything the user can change. */
data class AppSettings(
    val avoidedKinds: Set<DetectorKind> = setOf(DetectorKind.ALPR),
    val units: UnitSystem = UnitSystem.METRIC,
    val speakDirections: Boolean = true,
    val warnAboutCameras: Boolean = true,
    val keepScreenOnWhileNavigating: Boolean = true,
    /** Draw every known device on the map, not only the ones being avoided. */
    val showAllDetectors: Boolean = true,
    val mapTheme: MapTheme = MapTheme.DAY,
    /**
     * Allow for typical congestion at the departure time.
     *
     * A model, not a feed. On by default because ETAs that ignore rush hour are wrong far
     * more often than the model is, but it is a switch because a prior that disagrees with
     * what you can see out of the windscreen should be dismissable.
     */
    val allowForTraffic: Boolean = true,
    /**
     * Prefer calmer roads even when they are not quicker.
     *
     * Distinct from [allowForTraffic], which only makes the estimate honest. This says the
     * driver would rather keep moving than shave a minute, and it costs time by design.
     */
    val avoidHeavyTraffic: Boolean = false,
    /**
     * Close in on the map as a turn approaches.
     *
     * A view wide enough to see the road ahead is too wide to see which of three lanes
     * peels off at an exit. Zooming for the last couple of hundred metres buys that detail
     * back exactly when it is wanted, and gives it up again afterwards.
     */
    val zoomForTurns: Boolean = true,
    /**
     * Show current speed, and the posted limit where the map knows one.
     *
     * The limit comes from the same OpenStreetMap data as everything else, and is shown only
     * where a road actually carries a `maxspeed` tag — never guessed from the road class.
     */
    val showSpeedometer: Boolean = true,
) {
    fun toAvoidanceSettings(): AvoidanceSettings = AvoidanceSettings(enabledKinds = avoidedKinds)
}

/**
 * Persisted preferences.
 *
 * Plain [SharedPreferences] behind a [StateFlow]: there are a handful of switches here and
 * nothing that needs a database.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("shadowgps_settings", Context.MODE_PRIVATE)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) {
        val updated = transform(_settings.value)
        write(updated)
        _settings.value = updated
    }

    private fun read(): AppSettings {
        val defaults = AppSettings()
        val storedKinds = prefs.getStringSet(KEY_KINDS, null)
        return AppSettings(
            avoidedKinds = storedKinds
                ?.mapNotNull { name -> DetectorKind.entries.firstOrNull { it.name == name } }
                ?.toSet()
                ?: defaults.avoidedKinds,
            units = runCatching { UnitSystem.valueOf(prefs.getString(KEY_UNITS, null) ?: "") }
                .getOrDefault(defaults.units),
            speakDirections = prefs.getBoolean(KEY_SPEAK, defaults.speakDirections),
            warnAboutCameras = prefs.getBoolean(KEY_WARN, defaults.warnAboutCameras),
            keepScreenOnWhileNavigating = prefs.getBoolean(KEY_SCREEN_ON, defaults.keepScreenOnWhileNavigating),
            showAllDetectors = prefs.getBoolean(KEY_SHOW_ALL, defaults.showAllDetectors),
            mapTheme = MapTheme.fromName(prefs.getString(KEY_MAP_THEME, null)),
            allowForTraffic = prefs.getBoolean(KEY_TRAFFIC, defaults.allowForTraffic),
            avoidHeavyTraffic = prefs.getBoolean(KEY_AVOID_TRAFFIC, defaults.avoidHeavyTraffic),
            zoomForTurns = prefs.getBoolean(KEY_ZOOM_TURNS, defaults.zoomForTurns),
            showSpeedometer = prefs.getBoolean(KEY_SPEEDOMETER, defaults.showSpeedometer),
        )
    }

    private fun write(settings: AppSettings) {
        prefs.edit()
            .putStringSet(KEY_KINDS, settings.avoidedKinds.map { it.name }.toSet())
            .putString(KEY_UNITS, settings.units.name)
            .putBoolean(KEY_SPEAK, settings.speakDirections)
            .putBoolean(KEY_WARN, settings.warnAboutCameras)
            .putBoolean(KEY_SCREEN_ON, settings.keepScreenOnWhileNavigating)
            .putBoolean(KEY_SHOW_ALL, settings.showAllDetectors)
            .putString(KEY_MAP_THEME, settings.mapTheme.name)
            .putBoolean(KEY_TRAFFIC, settings.allowForTraffic)
            .putBoolean(KEY_AVOID_TRAFFIC, settings.avoidHeavyTraffic)
            .putBoolean(KEY_ZOOM_TURNS, settings.zoomForTurns)
            .putBoolean(KEY_SPEEDOMETER, settings.showSpeedometer)
            .apply()
    }

    private companion object {
        const val KEY_KINDS = "avoided_kinds"
        const val KEY_UNITS = "units"
        const val KEY_SPEAK = "speak_directions"
        const val KEY_WARN = "warn_cameras"
        const val KEY_SCREEN_ON = "keep_screen_on"
        const val KEY_SHOW_ALL = "show_all_detectors"
        const val KEY_MAP_THEME = "map_theme"
        const val KEY_TRAFFIC = "allow_for_traffic"
        const val KEY_AVOID_TRAFFIC = "avoid_heavy_traffic"
        const val KEY_ZOOM_TURNS = "zoom_for_turns"
        const val KEY_SPEEDOMETER = "show_speedometer"
    }
}
