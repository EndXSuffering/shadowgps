package dev.shadowgps.app.data

import java.io.File
import java.security.MessageDigest

/**
 * A plain file cache for downloaded map data.
 *
 * Road networks and camera positions change on the scale of weeks, and the OSM mirrors
 * this app queries are donated infrastructure. Caching aggressively is both faster for the
 * driver and the polite thing to do.
 */
class DiskCache(private val root: File) {

    init {
        if (!root.exists()) root.mkdirs()
    }

    /** Cached text for [key] if it exists and is younger than [maxAgeMillis]. */
    fun read(key: String, maxAgeMillis: Long): String? {
        val file = fileFor(key)
        if (!file.exists()) return null
        if (System.currentTimeMillis() - file.lastModified() > maxAgeMillis) return null
        return runCatching { file.readText() }.getOrNull()
    }

    fun write(key: String, value: String) {
        val file = fileFor(key)
        runCatching {
            // Write beside the target and rename, so a kill mid-write cannot leave a
            // truncated file that later reads as valid cached data.
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.writeText(value)
            if (file.exists()) file.delete()
            temp.renameTo(file)
        }
    }

    /** Deletes everything, for the settings screen's "clear downloaded data". */
    fun clear() {
        root.listFiles()?.forEach { it.delete() }
    }

    /** Total bytes held, for showing the user what clearing would free. */
    fun sizeBytes(): Long = root.listFiles()?.sumOf { it.length() } ?: 0L

    private fun fileFor(key: String) = File(root, hash(key))

    private fun hash(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(40)
}
