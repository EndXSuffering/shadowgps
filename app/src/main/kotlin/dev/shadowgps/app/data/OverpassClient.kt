package dev.shadowgps.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/** Raised when every mirror refused or failed, with a message fit to show a driver. */
class OverpassException(message: String, cause: Throwable? = null) : IOException(message, cause)

/**
 * Talks to the Overpass API, which serves live OpenStreetMap data.
 *
 * Overpass is run by volunteers and rate-limits hard when busy, so this client rotates
 * across public mirrors, backs off on 429/504, and leans on [DiskCache] to avoid asking
 * twice for the same thing.
 */
class OverpassClient(
    private val http: OkHttpClient,
    private val cache: DiskCache,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
) {

    /**
     * Runs an Overpass QL query, returning the raw JSON body.
     *
     * @param maxAgeMillis how stale a cached answer may be before it is refetched
     */
    suspend fun query(overpassQl: String, maxAgeMillis: Long): String = withContext(Dispatchers.IO) {
        cache.read(overpassQl, maxAgeMillis)?.let { return@withContext it }

        var lastError: Exception? = null

        for ((attempt, endpoint) in endpoints.withIndex()) {
            try {
                val body = FormBody.Builder().add("data", overpassQl).build()
                val request = Request.Builder()
                    .url(endpoint)
                    .header("User-Agent", USER_AGENT)
                    .post(body)
                    .build()

                http.newCall(request).execute().use { response ->
                    val text = response.body?.string().orEmpty()
                    when {
                        response.isSuccessful && text.isNotBlank() -> {
                            cache.write(overpassQl, text)
                            return@withContext text
                        }
                        // Busy or timed out: this mirror may recover, the next may not be
                        // any better, so pause before moving on.
                        response.code == 429 || response.code == 504 -> {
                            lastError = OverpassException("Map data server busy (${response.code})")
                            delay(BACKOFF_MILLIS * (attempt + 1))
                        }
                        else -> lastError = OverpassException("Map data server error ${response.code}")
                    }
                }
            } catch (e: IOException) {
                lastError = e
            }
        }

        // Nothing fresh could be had. A stale cached copy beats no map at all.
        cache.read(overpassQl, maxAgeMillis = Long.MAX_VALUE)?.let { return@withContext it }

        throw OverpassException(
            "Could not download map data. Check your connection and try again.",
            lastError,
        )
    }

    companion object {
        const val USER_AGENT = "ShadowGPS/0.1 (OpenStreetMap-based navigation)"

        /** Public Overpass instances, tried in order. */
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.osm.ch/api/interpreter",
        )

        private const val BACKOFF_MILLIS = 800L
    }
}
