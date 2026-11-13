package dev.shadowgps.app.data

import dev.shadowgps.core.geo.BoundingBox
import dev.shadowgps.core.geo.LatLon
import dev.shadowgps.core.store.RegionFile
import dev.shadowgps.core.store.RegionMetadata
import dev.shadowgps.core.store.RegionPayload
import dev.shadowgps.core.store.RegionSelection
import dev.shadowgps.core.store.StoredArea
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * An area kept on the device: one the user chose to save, or one the app kept by itself.
 */
@Serializable
data class SavedRegion(
    val id: String,
    val name: String,
    val bounds: BoundingBox,
    val downloadedAtMillis: Long,
    val fileBytes: Long,
    val roadCount: Int,
    val detectorCount: Int,
    /**
     * Kept by the app rather than saved by the user: the area around the driver, or what a
     * trip downloaded. These are the app's to replace and to drop when there are too many;
     * a region the user saved is never touched without being asked.
     */
    val automatic: Boolean = false,
    /**
     * Where the area has car parks and driveways, when it does not have them everywhere —
     * see [StoredArea]. Null means everywhere.
     */
    val serviceRoadAreas: List<BoundingBox>? = null,
    /** When the cameras in it were last fetched; the roads may be older. Zero: with the roads. */
    val camerasAtMillis: Long = 0L,
    /** When a trip last used it, for deciding which automatic area to drop first. */
    val lastUsedAtMillis: Long = 0L,
) {
    /** Whole days since the data was downloaded. */
    fun ageDays(nowMillis: Long = System.currentTimeMillis()): Long =
        (nowMillis - downloadedAtMillis) / DAY_MILLIS

    /** When the camera positions were fetched, which is what coverage should be judged on. */
    val camerasFetchedAtMillis: Long get() = if (camerasAtMillis > 0L) camerasAtMillis else downloadedAtMillis

    /**
     * Cameras are relocated often enough that month-old data should not be presented as
     * current. The region still routes fine; it just should not be trusted for coverage.
     */
    fun isStale(nowMillis: Long = System.currentTimeMillis()): Boolean =
        (nowMillis - camerasFetchedAtMillis) / DAY_MILLIS >= STALE_AFTER_DAYS

    fun toStoredArea(): StoredArea = StoredArea(bounds, serviceRoadAreas)

    companion object {
        const val STALE_AFTER_DAYS = 30L
        private const val DAY_MILLIS = 24L * 60 * 60 * 1000
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

    // The index is read, changed and written back as a whole. Areas are now also stored in
    // the background while the driver is doing other things, so two of those at once would
    // each write back a list missing the other's entry.
    private val indexLock = Mutex()

    suspend fun list(): List<SavedRegion> = withContext(Dispatchers.IO) {
        indexLock.withLock { readIndex() }.regions.sortedByDescending { it.downloadedAtMillis }
    }

    /**
     * The best stored area to route a trip in, or null if none covers it.
     *
     * @param trip the area the trip spans; a complete region must contain this to qualify
     * @param preferred the trip plus routing padding; only a tie-breaker. See
     *   [RegionSelection] for why coverage must not be decided on the padded box.
     * @param needed the trip plus the least room the router should have, which an area left
     *   by an earlier trip must contain
     * @param ends where the trip starts and finishes, to prefer an area with their car parks
     */
    suspend fun regionCovering(
        trip: BoundingBox,
        preferred: BoundingBox = trip,
        needed: BoundingBox = preferred,
        ends: List<LatLon> = emptyList(),
    ): SavedRegion? = withContext(Dispatchers.IO) {
        val regions = indexLock.withLock { readIndex() }.regions
        val index = RegionSelection.choose(regions.map { it.toStoredArea() }, trip, preferred, needed, ends)
        regions.getOrNull(index)
    }

    /** Notes that a trip used this area, so it is not the next automatic one to be dropped. */
    suspend fun markUsed(id: String) = withContext(Dispatchers.IO) {
        indexLock.withLock {
            val now = System.currentTimeMillis()
            val regions = readIndex().regions
            // Once a day is plenty for ordering, and saves rewriting the index every trip.
            val current = regions.firstOrNull { it.id == id } ?: return@withLock
            if (now - current.lastUsedAtMillis < USE_NOTE_INTERVAL_MILLIS) return@withLock
            writeIndex(RegionIndex(regions.map { if (it.id == id) it.copy(lastUsedAtMillis = now) else it }))
        }
    }

    /**
     * Keeps the automatic areas within bounds: none past [maxAgeMillis], none that a
     * complete area already covers, and no more than [keep] in all — least recently used
     * first out. Regions the user saved are never touched.
     *
     * @return whether anything was removed
     */
    suspend fun pruneAutomatic(keep: Int, maxAgeMillis: Long): Boolean = withContext(Dispatchers.IO) {
        indexLock.withLock {
            val now = System.currentTimeMillis()
            val regions = readIndex().regions
            val complete = regions.filter { it.serviceRoadAreas == null }
            val doomed = HashSet<String>()

            for (region in regions) {
                if (!region.automatic) continue
                val expired = now - region.downloadedAtMillis > maxAgeMillis
                val redundant = complete.any { other ->
                    other.id != region.id && other.id !in doomed && other.bounds.contains(region.bounds) &&
                        other.downloadedAtMillis >= region.downloadedAtMillis
                }
                if (expired || redundant) doomed += region.id
            }
            regions
                .filter { it.automatic && it.id !in doomed }
                .sortedByDescending { maxOf(it.lastUsedAtMillis, it.downloadedAtMillis) }
                .drop(keep)
                .forEach { doomed += it.id }

            if (doomed.isEmpty()) return@withLock false
            doomed.forEach { fileFor(it).delete() }
            writeIndex(RegionIndex(regions.filterNot { it.id in doomed }))
            true
        }
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
        automatic: Boolean = false,
        serviceRoadAreas: List<BoundingBox>? = null,
        camerasAtMillis: Long = payload.metadata.createdAtMillis,
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
            automatic = automatic,
            serviceRoadAreas = serviceRoadAreas,
            camerasAtMillis = camerasAtMillis,
            lastUsedAtMillis = System.currentTimeMillis(),
        )

        indexLock.withLock {
            writeIndex(RegionIndex(readIndex().regions.filterNot { it.id == id } + saved))
        }
        saved
    }

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        indexLock.withLock {
            fileFor(id).delete()
            writeIndex(RegionIndex(readIndex().regions.filterNot { it.id == id }))
        }
    }

    suspend fun totalBytes(): Long = withContext(Dispatchers.IO) {
        indexLock.withLock { readIndex() }.regions.sumOf { it.fileBytes }
    }

    fun metadataOf(id: String): RegionMetadata? =
        runCatching { fileFor(id).inputStream().use(RegionFile::readMetadata) }.getOrNull()

    private fun fileFor(id: String) = File(directory, "$id.sgr")

    private companion object {
        const val USE_NOTE_INTERVAL_MILLIS = 24L * 60 * 60 * 1000
    }

    private fun readIndex(): RegionIndex {
        if (!indexFile.exists()) return RegionIndex()
        // Explicit serializers rather than the reified helpers: those are extensions that
        // resolve differently depending on which kotlinx.serialization imports are present.
        val stored = runCatching { json.decodeFromString(RegionIndex.serializer(), indexFile.readText()) }
            .getOrElse { return RegionIndex() }

        // Drop entries whose file went missing — a restore, a manual clear, an interrupted
        // write — so the UI never offers a region that cannot be opened.
        val present = stored.regions.filter { fileFor(it.id).exists() }
        if (present.size != stored.regions.size) writeIndex(RegionIndex(present))
        return RegionIndex(present)
    }

    private fun writeIndex(index: RegionIndex) {
        runCatching { indexFile.writeText(json.encodeToString(RegionIndex.serializer(), index)) }
    }
}
