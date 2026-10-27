package dev.shadowgps.app.data

import dev.shadowgps.core.net.firstSuccessful
import dev.shadowgps.core.osm.parseOverpassResponse
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Raised when every mirror refused or failed, with a message fit to show a driver. */
class OverpassException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** A mirror that was up but too busy to answer: worth saying differently from "offline". */
class OverpassBusyException(message: String) : IOException(message)

/**
 * Talks to the Overpass API, which serves live OpenStreetMap data.
 *
 * Overpass is run by volunteers and queues or refuses work when busy. Three things keep a
 * busy server from freezing the app:
 *
 *  - **Mirrors are hedged, not queued.** If the first has not answered after a short wait,
 *    the next is asked as well, and the first answer wins ([firstSuccessful]). Asking them
 *    strictly in turn gave each up to two minutes of silence before the next was tried,
 *    which is what a driver saw as "stuck on Downloading map data".
 *  - **Every attempt has a deadline.** A server that accepts the request and then goes
 *    quiet is abandoned once its own query time limit has passed, rather than whenever the
 *    socket gives up.
 *  - **Losers are actually stopped.** Calls are cancellable, so the mirrors that lose a race
 *    stop downloading instead of finishing a few megabytes nobody will read.
 *
 * A request runs on this client's own scope, not the caller's, and is shared: if the
 * driver cancels and asks again while it is still in flight, the second ask joins the first
 * rather than starting over. Cancelling the wait does not cancel the download — it finishes
 * into [DiskCache], so the retry is quick either way.
 */
class OverpassClient(
    http: OkHttpClient,
    private val cache: DiskCache,
    private val endpoints: List<String> = DEFAULT_ENDPOINTS,
) {
    // No read timeout of its own: Overpass sends nothing until a query is finished, and each
    // call gets an overall deadline sized to its query instead.
    private val http: OkHttpClient = http.newBuilder().readTimeout(0, TimeUnit.SECONDS).build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val inFlight = ConcurrentHashMap<String, Deferred<String>>()

    /**
     * Runs an Overpass QL query, returning the raw JSON body.
     *
     * @param maxAgeMillis how stale a cached answer may be before it is refetched
     * @param isUsable whether an answer can be believed. One that fails is never cached and
     *   never returned: another mirror is asked instead. This applies to cached answers too,
     *   so one stored before the check existed cannot keep being served.
     */
    suspend fun query(
        overpassQl: String,
        maxAgeMillis: Long,
        isUsable: (String) -> Boolean = { true },
    ): String {
        withContext(Dispatchers.IO) { cache.read(overpassQl, maxAgeMillis)?.takeIf(isUsable) }
            ?.let { return it }
        return shared(overpassQl) { fetch(overpassQl, isUsable) }.await()
    }

    /** One download per query, however many callers are waiting for it. */
    private fun shared(overpassQl: String, download: suspend () -> String): Deferred<String> {
        inFlight[overpassQl]?.let { return it }
        lateinit var started: Deferred<String>
        started = scope.async(start = CoroutineStart.LAZY) {
            try {
                download()
            } finally {
                inFlight.remove(overpassQl, started)
            }
        }
        // Started only once it is registered, so the clean-up above can never run first and
        // leave a finished download registered as in flight.
        val existing = inFlight.putIfAbsent(overpassQl, started)
        return existing ?: started.also { it.start() }
    }

    private suspend fun fetch(overpassQl: String, isUsable: (String) -> Boolean): String {
        val serverSeconds = SERVER_TIMEOUT.find(overpassQl)?.groupValues?.get(1)?.toLongOrNull()
            ?: DEFAULT_SERVER_TIMEOUT_SECONDS
        // A big query is slow even on a healthy server, so it gets longer before a second
        // mirror is bothered with it; a small one is hedged sooner.
        val hedgeAfter = (serverSeconds * 1000 / 4).coerceIn(MIN_HEDGE_MILLIS, MAX_HEDGE_MILLIS)

        return try {
            firstSuccessful(
                sources = endpoints.map { endpoint -> suspend { attempt(endpoint, overpassQl, serverSeconds, isUsable) } },
                hedgeAfterMillis = hedgeAfter,
            )
        } catch (e: IOException) {
            // Nothing fresh could be had. A stale cached copy beats no map at all.
            cache.read(overpassQl, maxAgeMillis = Long.MAX_VALUE)?.takeIf(isUsable)?.let { return it }
            throw if (e is OverpassBusyException) {
                OverpassException(
                    "The map servers are too busy right now. Try again in a minute — or save this " +
                        "area while they are quiet, and trips in it will need no download at all.",
                    e,
                )
            } else {
                OverpassException("Could not download map data. Check your connection and try again.", e)
            }
        }
    }

    private suspend fun attempt(
        endpoint: String,
        overpassQl: String,
        serverSeconds: Long,
        isUsable: (String) -> Boolean,
    ): String {
        val request = Request.Builder()
            .url(endpoint)
            .header("User-Agent", USER_AGENT)
            .post(FormBody.Builder().add("data", overpassQl).build())
            .build()
        val call = http.newCall(request)
        call.timeout().timeout(serverSeconds + DEADLINE_SLACK_SECONDS, TimeUnit.SECONDS)

        val (code, text) = call.awaitBody()
        when {
            code == 429 || code == 504 -> throw OverpassBusyException("Map data server busy ($code)")
            code !in 200..299 || text.isBlank() -> throw OverpassException("Map data server error $code")
        }
        // A server that ran out of time or memory still answers 200 with a valid but empty
        // body. Caching that would poison the area for days and make every route look like
        // empty countryside, so it counts as busy and another mirror is asked.
        failureRemarkIn(text)?.let { throw OverpassBusyException("Map data server overloaded: $it") }
        if (!isUsable(text)) throw OverpassException("Map data server returned nothing for this area")

        cache.write(overpassQl, text)
        return text
    }

    /**
     * Runs the call and reads the whole body, cancellably.
     *
     * The body is read on OkHttp's own thread inside the callback, so cancelling cancels the
     * call outright — mid-download included — rather than leaving a thread reading on.
     */
    private suspend fun Call.awaitBody(): Pair<Int, String> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation { cancel() }
        enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val body = response.use { it.code to it.body?.string().orEmpty() }
                        continuation.resume(body)
                    } catch (e: IOException) {
                        continuation.resumeWithException(e)
                    }
                }
            },
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

        private val SERVER_TIMEOUT = Regex("""\[timeout:(\d+)]""")
        private const val DEFAULT_SERVER_TIMEOUT_SECONDS = 90L

        /** Past the server's own limit, how long to wait for its answer to arrive. */
        private const val DEADLINE_SLACK_SECONDS = 20L

        private const val MIN_HEDGE_MILLIS = 12_000L
        private const val MAX_HEDGE_MILLIS = 45_000L
    }
}
