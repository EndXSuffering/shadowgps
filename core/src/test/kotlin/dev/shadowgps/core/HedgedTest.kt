package dev.shadowgps.core

import dev.shadowgps.core.net.firstSuccessful
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class HedgedTest {

    @Test
    fun `a prompt first answer is the only one asked for`() = runBlocking {
        val asked = AtomicInteger()
        val answer = firstSuccessful(
            listOf(
                suspend { asked.incrementAndGet(); "first" },
                suspend { asked.incrementAndGet(); "second" },
            ),
            hedgeAfterMillis = 500,
        )
        assertEquals("first", answer)
        assertEquals(1, asked.get())
    }

    /** The case that looked frozen: a first server that just sits there. */
    @Test
    fun `a slow first source is overtaken, and then called off`() = runBlocking {
        val firstCalledOff = CompletableDeferred<Boolean>()
        val started = System.nanoTime()
        val answer = firstSuccessful(
            listOf(
                suspend {
                    try {
                        awaitCancellation()
                    } finally {
                        firstCalledOff.complete(true)
                    }
                },
                suspend { "second" },
            ),
            hedgeAfterMillis = 100,
        )
        val tookMillis = (System.nanoTime() - started) / 1_000_000
        assertEquals("second", answer)
        assertTrue(tookMillis < 2_000, "took $tookMillis ms")
        assertTrue(firstCalledOff.await())
    }

    @Test
    fun `a failure moves straight on without waiting out the delay`() = runBlocking {
        val started = System.nanoTime()
        val answer = firstSuccessful(
            listOf(
                suspend { throw IOException("busy (429)") },
                suspend { "second" },
            ),
            hedgeAfterMillis = 10_000,
        )
        assertEquals("second", answer)
        assertTrue((System.nanoTime() - started) / 1_000_000 < 2_000)
    }

    @Test
    fun `a source still running is not abandoned when another fails`() = runBlocking {
        val answer = firstSuccessful(
            listOf(
                suspend { delay(300); "first, eventually" },
                suspend { throw IOException("second is down") },
                suspend { throw IOException("third is down") },
            ),
            hedgeAfterMillis = 50,
        )
        assertEquals("first, eventually", answer)
    }

    @Test
    fun `when everything fails the last failure is reported`() {
        val failure = assertThrows<IOException> {
            runBlocking {
                firstSuccessful<String>(
                    listOf(
                        suspend { throw IOException("first") },
                        suspend { throw IOException("second") },
                    ),
                    hedgeAfterMillis = 50,
                )
            }
        }
        assertEquals("second", failure.message)
    }

    @Test
    fun `sources past the winner are never started`() = runBlocking {
        val thirdStarted = AtomicInteger()
        firstSuccessful(
            listOf(
                suspend { delay(1_000); "first" },
                suspend { "second" },
                suspend { thirdStarted.incrementAndGet(); "third" },
            ),
            hedgeAfterMillis = 50,
        )
        assertFalse(thirdStarted.get() > 0)
    }
}
