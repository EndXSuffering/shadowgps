package dev.shadowgps.core.net

import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.cancellation.CancellationException

/**
 * Asks [sources] for the same thing, starting the next one whenever the ones already asked
 * are taking too long, and returns the first answer that comes back.
 *
 * This is how the map download stops looking frozen. The public map servers are run by
 * volunteers and queue requests when busy, and asking them strictly one after another meant
 * a busy first server could hold the driver for minutes before anyone else was tried. Now a
 * second is asked once the first has had [hedgeAfterMillis] without answering, a third once
 * both have, and whichever answers first wins; the others are called off.
 *
 * A source that fails is not waited out: the next one starts at once if nothing else is
 * still running. The extra load stays modest — a server that answers promptly is the only
 * one ever asked — which matters for services nobody is paid to run.
 *
 * @throws Throwable the last failure, when every source has failed
 */
suspend fun <T> firstSuccessful(
    sources: List<suspend () -> T>,
    hedgeAfterMillis: Long,
): T {
    require(sources.isNotEmpty()) { "nothing to ask" }
    return coroutineScope {
        val outcomes = Channel<Result<T>>(Channel.UNLIMITED)
        var started = 0
        var finished = 0
        var lastFailure: Throwable? = null

        fun startNext() {
            val source = sources[started++]
            launch {
                val outcome = try {
                    Result.success(source())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    Result.failure(e)
                }
                outcomes.send(outcome)
            }
        }

        startNext()
        while (true) {
            val outcome = if (started < sources.size) {
                withTimeoutOrNull(hedgeAfterMillis) { outcomes.receive() }
            } else {
                outcomes.receive()
            }
            if (outcome == null) {
                // Nobody has answered in time; ask the next one as well, without giving up
                // on those already asked.
                startNext()
                continue
            }
            finished++
            if (outcome.isSuccess) {
                coroutineContext.cancelChildren()
                @Suppress("UNCHECKED_CAST")
                return@coroutineScope outcome.getOrNull() as T
            }
            lastFailure = outcome.exceptionOrNull()
            if (finished == started) {
                // Everything asked so far has failed and nothing is still running.
                if (started < sources.size) startNext() else break
            }
        }
        throw lastFailure ?: IllegalStateException("every source failed")
    }
}
