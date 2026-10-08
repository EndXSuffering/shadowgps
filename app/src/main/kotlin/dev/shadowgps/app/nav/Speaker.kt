package dev.shadowgps.app.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_ADD
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

/**
 * Spoken guidance.
 *
 * Instructions queue up behind each other, and a camera warning may jump the queue — but
 * only past other warnings, never past a turn. Jumping means discarding what is queued, and
 * every prompt is spoken once: a turn instruction flushed to make room for a camera warning
 * was simply never heard. That happened whenever both fell on the same fix, since the
 * engine emits the turn first and the warning straight after it. So the speaker keeps count
 * of turn instructions still queued or playing, and while there are any, a warning waits
 * its turn like everything else.
 */
class Speaker(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<Pair<String, Boolean>>()

    /**
     * Instructions queued or playing that must not be discarded. Touched from the engine's
     * callback thread as well as the main one. If a callback ever goes missing this only
     * errs high, which costs a warning its place in the queue — never an instruction.
     */
    private val protectedUtterances = AtomicInteger(0)
    private var utteranceCounter = 0L

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.getDefault()
                engine?.setOnUtteranceProgressListener(
                    object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) = Unit
                        override fun onDone(utteranceId: String?) = finished(utteranceId)
                        override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)

                        @Deprecated("Superseded by the overload with an error code.")
                        override fun onError(utteranceId: String?) = finished(utteranceId)
                        override fun onError(utteranceId: String?, errorCode: Int) = finished(utteranceId)
                    },
                )
                while (pending.isNotEmpty()) {
                    val (text, urgent) = pending.removeFirst()
                    speakNow(text, urgent)
                }
            } else {
                pending.clear()
            }
        }
    }

    fun speak(text: String, urgent: Boolean = false) {
        if (!ready) {
            // Hold only a couple of lines: anything older than that is stale by the time
            // the engine finishes starting up.
            if (pending.size >= 2) pending.removeFirst()
            pending.addLast(text to urgent)
            return
        }
        speakNow(text, urgent)
    }

    private fun speakNow(text: String, urgent: Boolean) {
        val engine = engine ?: return
        val id = "${if (urgent) WARNING_PREFIX else INSTRUCTION_PREFIX}${utteranceCounter++}"
        val mode = if (urgent && protectedUtterances.get() == 0) QUEUE_FLUSH else QUEUE_ADD
        if (!urgent) protectedUtterances.incrementAndGet()
        if (engine.speak(text, mode, null, id) != TextToSpeech.SUCCESS && !urgent) {
            // Rejected outright, so no callback will ever arrive for it.
            protectedUtterances.decrementAndGet()
        }
    }

    private fun finished(utteranceId: String?) {
        if (utteranceId?.startsWith(INSTRUCTION_PREFIX) == true) {
            protectedUtterances.updateAndGet { (it - 1).coerceAtLeast(0) }
        }
    }

    fun stop() {
        engine?.stop()
        pending.clear()
        // Everything queued has just been dropped, whether or not each drop is reported.
        protectedUtterances.set(0)
    }

    private companion object {
        const val INSTRUCTION_PREFIX = "say:"
        const val WARNING_PREFIX = "warn:"
    }

    fun release() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}
