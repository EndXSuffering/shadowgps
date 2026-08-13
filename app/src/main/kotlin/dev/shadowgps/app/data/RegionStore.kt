package dev.shadowgps.app.data

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.store.RegionFile
import dev.shadowgps.core.store.RegionMetadata
import dev.shadowgps.core.store.RegionPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/** A region the user has chosen to keep on the device. */
@Serializable
data class SavedRegion(
    val id: String,
    val name: String,
    val bounds: BoundingBox,
    val downloadedAtMillis: Long,
    val fileBytes: Long,
    val roadCount: Int,
    val detectorCount: Int,
) {
    /** Whole days since the data was downloaded. */
    fun ageDays(nowMillis: Long = System.currentTimeMillis()): Long =
        (nowMillis - downloadedAtMillis) / (24L * 60 * 60 * 1000)

    /**
     * Cameras are relocated often enough that month-old data should not be presented as
     * current. The region still routes fine; it just should not be trusted for coverage.
     */
    fun isStale(nowMillis: Long = System.currentTimeMillis()): Boolean = ageDays(nowMillis) >= STALE_AFTER_DAYS

    companion object {
        const val STALE_AFTER_DAYS = 30L
    }
}

@Serializable
private data class RegionIndex(val regions: List<SavedRegion> = emptyList())

/**
 * Saved offline regions.
 *
 * Each region is one binary file holding a built road graph and its cameras; a small JSON
 * index alongside them lets the settings screen list what is stored without opening any of
 * them. Files live in `filesDir` rather than `cacheDir` on purpose — the whole point is
 * that Android must not reclaim them when storage runs low.
 */
class RegionStore(root: File) {

    private val directory = File(root, "regions").apply { mkdirs() }
    private val indexFile = File(directory, "index.json")
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    suspend fun list(): List<SavedRegion> = withContext(Dispatchers.IO) {
        readIndex().regions.sortedByDescending { it.downloadedAtMillis }
    }

    /** The smallest saved region that fully covers [box], or null if none does. */
    suspend fun regionCovering(box: BoundingBox): SavedRegion? = withContext(Dispatchers.IO) {
        readIndex().regions
            .filter { it.bounds.contains(box) }
            .minByOrNull { it.bounds.areaKm2 }
    }

    suspend fun load(region: SavedRegion): RegionPayload = withContext(Dispatchers.IO) {
        fileFor(region.id).inputStream().use(RegionFile::read)
    }

    /** Writes a region and records it in the index, replacing any region with the same id. */
    suspend fun save(
        id: String,
        name: String,
        bounds: BoundingBox,
        payload: RegionPayload,
    ): SavedRegion = withContext(Dispatchers.IO) {
        val target = fileFor(id)
        // Write beside the target and rename, so an interrupted save cannot leave a
        // half-written region that the index claims is usable.
        val temp = File(directory, "$id.part")
        temp.outputStream().use { RegionFile.write(it, payload) }
        if (target.exists()) target.delete()
        if (!temp.renameTo(target)) {
            temp.delete()
            error("Could not store the downloaded region")
        }

        val saved = SavedRegion(
            id = id,
            name = name,
            bounds = bounds,
            downloadedAtMillis = payload.metadata.createdAtMillis,
            fileBytes = target.length(),
            roadCount = payload.graph.edgeCount,
            detectorCount = payload.detectors.size,
        )

        writeIndex(RegionIndex(readIndex().regions.filterNot { it.id == id } + saved))
        saved
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        fileFor(id).delete()
        writeIndex(RegionIndex(readIndex().regions.filterNot { it.id == id }))
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        readIndex().regions.sumOf { it.fileBytes }
    }

    fun metadataOf(id: String): RegionMetadata? =
        runCatching { fileFor(id).inputStream().use(RegionFile::readMetadata) }.getOrNull()

    private fun fileFor(id: String) = File(directory, "$id.sgr")

    private fun readIndex(): RegionIndex {
        if (!indexFile.exists()) return RegionIndex()
        val stored = runCatching { json.decodeFromString<RegionIndex>(indexFile.readText()) }
            .getOrElse { return RegionIndex() }

        // Drop entries whose file went missing — a restore, a manual clear, an interrupted
        // write — so the UI never offers a region that cannot be opened.
        val present = stored.regions.filter { fileFor(it.id).exists() }
        if (present.size != stored.regions.size) writeIndex(RegionIndex(present))
        return RegionIndex(present)
    }

    private fun writeIndex(index: RegionIndex) {
        runCatching { indexFile.writeText(json.encodeToString(index)) }
    }
}
