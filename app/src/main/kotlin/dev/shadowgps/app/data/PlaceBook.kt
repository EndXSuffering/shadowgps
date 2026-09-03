package dev.shadowgps.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** An address the user keeps, or one they used recently. */
@Serializable
data class SavedPlace(
    val place: Place,
    /** What the user calls it, when they have renamed it. */
    val label: String? = null,
    val savedAtMillis: Long = 0L,
    val lastUsedMillis: Long = 0L,
    /** Starred entries are kept; unstarred ones are recents and age out. */
    val starred: Boolean = false,
) {
    val title: String get() = label ?: place.shortName

    /** Identity is the position, so the same address saved twice does not appear twice. */
    val key: String get() = "%.5f,%.5f".format(place.position.lat, place.position.lon)
}

@Serializable
private data class PlaceBookContents(val places: List<SavedPlace> = emptyList())

/**
 * Saved addresses and recent destinations.
 *
 * Kept in app storage rather than anywhere remote: a list of the places someone drives to
 * is exactly the record this app exists to avoid creating, so it never leaves the device
 * and is excluded from backups.
 */
class PlaceBook(root: File) {

    private val file = File(root, "places.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val _places = MutableStateFlow(read())
    val places: StateFlow<List<SavedPlace>> = _places.asStateFlow()

    /** Starred addresses, most recently saved first. */
    val saved: List<SavedPlace> get() = _places.value.filter { it.starred }.sortedByDescending { it.savedAtMillis }

    /** Unstarred destinations, most recently used first. */
    val recents: List<SavedPlace> get() = _places.value.filterNot { it.starred }.sortedByDescending { it.lastUsedMillis }

    fun isStarred(place: Place): Boolean = find(place)?.starred == true

    /** Records a destination the user actually travelled to. */
    fun remember(place: Place) {
        val now = System.currentTimeMillis()
        val existing = find(place)
        val updated = existing?.copy(lastUsedMillis = now)
            ?: SavedPlace(place = place, savedAtMillis = now, lastUsedMillis = now)
        write(_places.value.filterNot { it.key == updated.key } + updated)
    }

    /** Stars or unstars an address. Unstarring keeps it as a recent rather than erasing it. */
    fun setStarred(place: Place, starred: Boolean, label: String? = null) {
        val now = System.currentTimeMillis()
        val existing = find(place)
        val updated = (existing ?: SavedPlace(place = place, lastUsedMillis = now)).copy(
            starred = starred,
            savedAtMillis = if (starred) now else existing?.savedAtMillis ?: now,
            label = label ?: existing?.label,
        )
        write(_places.value.filterNot { it.key == updated.key } + updated)
    }

    fun forget(saved: SavedPlace) {
        write(_places.value.filterNot { it.key == saved.key })
    }

    fun clearRecents() {
        write(_places.value.filter { it.starred })
    }

    private fun find(place: Place): SavedPlace? {
        val key = SavedPlace(place).key
        return _places.value.firstOrNull { it.key == key }
    }

    private fun read(): List<SavedPlace> {
        if (!file.exists()) return emptyList()
        return runCatching {
            json.decodeFromString(PlaceBookContents.serializer(), file.readText()).places
        }.getOrDefault(emptyList())
    }

    private fun write(places: List<SavedPlace>) {
        // Recents are a convenience, not a history to accumulate indefinitely.
        val trimmed = places.filter { it.starred } +
            places.filterNot { it.starred }.sortedByDescending { it.lastUsedMillis }.take(MAX_RECENTS)

        _places.value = trimmed
        runCatching {
            file.writeText(json.encodeToString(PlaceBookContents.serializer(), PlaceBookContents(trimmed)))
        }
    }

    private companion object {
        const val MAX_RECENTS = 12
    }
}
