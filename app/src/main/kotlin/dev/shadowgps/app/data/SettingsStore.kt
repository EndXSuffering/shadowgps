package dev.shadowgps.app.data

import android.content.Context
import android.content.SharedPreferences
import dev.shadowgps.core.detect.DetectorKind
import dev.shadowgps.core.format.UnitSystem
import dev.shadowgps.core.routing.AvoidanceSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Everything the user can change. */
data class AppSettings(
    val avoidedKinds: Set<DetectorKind> = setOf(DetectorKind.ALPR),
    val units: UnitSystem = UnitSystem.METRIC,
    val speakDirections: Boolean = true,
    val warnAboutCameras: Boolean = true,
    val keepScreenOnWhileNavigating: Boolean = true,
    /** Draw every known device on the map, not only the ones being avoided. */
    val showAllDetectors: Boolean = true,
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
            .apply()
    }

    private companion object {
        const val KEY_KINDS = "avoided_kinds"
        const val KEY_UNITS = "units"
        const val KEY_SPEAK = "speak_directions"
        const val KEY_WARN = "warn_cameras"
        const val KEY_SCREEN_ON = "keep_screen_on"
        const val KEY_SHOW_ALL = "show_all_detectors"
    }
}
