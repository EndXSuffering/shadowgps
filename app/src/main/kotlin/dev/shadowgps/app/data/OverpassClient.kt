package dev.shadowgps.app.data

import dev.shadowgps.core.osm.parseOverpassResponse
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
     * @param isUsable whether an answer can be believed. One that fails is never cached and
     *   never returned: the next mirror is asked instead. This applies to cached answers too,
     *   so one stored before the check existed cannot keep being served.
     */
    suspend fun query(
        overpassQl: String,
        maxAgeMillis: Long,
        isUsable: (String) -> Boolean = { true },
    ): String = withContext(Dispatchers.IO) {
        cache.read(overpassQl, maxAgeMillis)?.takeIf(isUsable)?.let { return@withContext it }

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
                            // A server that ran out of time or memory still answers 200
                            // with a valid but empty body. Caching that would poison the
                            // area for days and make every route look like empty
                            // countryside, so treat it as a failure and try the next
                            // mirror instead.
                            val remark = failureRemarkIn(text)
                            if (remark != null) {
                                lastError = OverpassException("Map data server overloaded: $remark")
                                delay(BACKOFF_MILLIS * (attempt + 1))
                                return@use
                            }
                            if (!isUsable(text)) {
                                lastError = OverpassException("Map data server returned nothing for this area")
                                return@use
                            }
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
        cache.read(overpassQl, maxAgeMillis = Long.MAX_VALUE)?.takeIf(isUsable)?.let { return@withContext it }

        throw OverpassException(
            "Could not download map data. Check your connection and try again.",
            lastError,
        )
    }

    /**
     * Looks for an Overpass failure remark without parsing the whole payload.
     *
     * A road download is megabytes of JSON and this runs on every response, so the cheap
     * substring check gates the real parse.
     */
    private fun failureRemarkIn(body: String): String? {
        if (!body.contains("\"remark\"")) return null
        return runCatching { parseOverpassResponse(body).errorRemark }.getOrNull()
    }

    companion object {
        const val USER_AGENT = "ShadowGPS/0.1 (OpenStreetMap-based navigation)"

        /**
         * Public Overpass instances with worldwide data, tried in order.
         *
         * Worldwide matters. The third mirror used to be overpass.osm.ch, which holds
         * Switzerland only: when the first two were busy, it answered a trip in Texas with a
         * well-formed, empty map, which was then cached for two weeks as "no roads here".
         */
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass-api.de/api/interpreter",
            "https://overpass.kumi.systems/api/interpreter",
            "https://overpass.private.coffee/api/interpreter",
        )

        private const val BACKOFF_MILLIS = 800L
    }
}
