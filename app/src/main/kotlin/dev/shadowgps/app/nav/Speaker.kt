package dev.shadowgps.app.nav

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.TextToSpeech.QUEUE_ADD
import android.speech.tts.TextToSpeech.QUEUE_FLUSH
import java.util.Locale

/**
 * Spoken guidance.
 *
 * Instructions queue up behind each other so a turn call is never cut off, but a camera
 * warning jumps the queue — it is time-critical in a way that "continue for 2 km" is not.
 */
class Speaker(context: Context) {

    private var engine: TextToSpeech? = null
    private var ready = false
    private val pending = ArrayDeque<Pair<String, Boolean>>()

    init {
        engine = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                engine?.language = Locale.getDefault()
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
        engine?.speak(text, if (urgent) QUEUE_FLUSH else QUEUE_ADD, null, text.hashCode().toString())
    }

    fun stop() {
        engine?.stop()
        pending.clear()
    }

    fun release() {
        stop()
        engine?.shutdown()
        engine = null
        ready = false
    }
}
