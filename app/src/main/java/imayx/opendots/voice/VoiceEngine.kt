package imayx.opendots.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.Locale
import java.util.UUID

private const val TAG = "VoiceChat"

/**
 * Native voice conversation engine.
 *
 * - Speech-to-text via Android [SpeechRecognizer] (on-device or Google
 *   recognition service, depending on the device — no provider realtime
 *   API is assumed).
 * - Text-to-speech via Android [TextToSpeech].
 * - Push-to-talk: [startListening]/[stopListening]; [interrupt] stops TTS
 *   immediately; [speak] reads a response aloud; [replayLast] re-speaks it.
 * - Continuous mode: when enabled, TTS completion automatically restarts
 *   listening (explicit user toggle, cancellable any time).
 *
 * Provider realtime-audio APIs are NOT assumed: capability detection lives
 * with the caller (none of the proxied providers expose realtime audio
 * through this backend), so this engine is always the STT→text→TTS fallback.
 */
class VoiceEngine(context: Context) {
    private val appContext = context.applicationContext

    sealed interface State {
        data object Idle : State
        data object Listening : State
        data class Speaking(val utteranceId: String) : State
    }

    var state: State = State.Idle
        private set

    /** Live partial transcription; the UI observes this. */
    var onPartialResult: ((String) -> Unit)? = null
    /** Final transcription when listening stops. */
    var onFinalResult: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null
    /** Fired when TTS finishes an utterance (used for continuous mode). */
    var onSpeechDone: ((utteranceId: String) -> Unit)? = null

    var continuousMode: Boolean = false

    private var lastSpokenText: String? = null
    private var recognizer: SpeechRecognizer? = null

    private val tts: TextToSpeech = TextToSpeech(appContext) { status ->
        if (status != TextToSpeech.SUCCESS) {
            Log.w(TAG, "TTS init failed: $status")
            onError?.invoke("Text-to-speech is not available on this device.")
        }
    }.apply {
        setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) {}
            override fun onError(utteranceId: String) {
                setState(State.Idle)
            }
            override fun onDone(utteranceId: String) {
                setState(State.Idle)
                onSpeechDone?.invoke(utteranceId)
                if (continuousMode) startListening()
            }
        })
    }

    private fun setState(s: State) {
        state = s
        onStateChanged?.invoke(s)
    }

    fun isRecognitionAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(appContext)

    /** Begin push-to-talk listening. Interrupts any ongoing speech first. */
    fun startListening() {
        interrupt() // stop TTS so the mic doesn't hear the speaker
        if (!isRecognitionAvailable()) {
            onError?.invoke("Speech recognition is not available on this device.")
            return
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        val rec = SpeechRecognizer.createSpeechRecognizer(appContext).also {
            recognizer = it
        }
        rec.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                setState(State.Listening)
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                // ERROR_NO_MATCH / ERROR_SPEECH_TIMEOUT on silence are normal.
                setState(State.Idle)
                destroyRecognizer()
                if (error != SpeechRecognizer.ERROR_NO_MATCH &&
                    error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT &&
                    error != SpeechRecognizer.ERROR_CLIENT
                ) {
                    onError?.invoke("Listening stopped (code $error).")
                } else {
                    onFinalResult?.invoke("")
                }
            }
            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                setState(State.Idle)
                destroyRecognizer()
                onFinalResult?.invoke(text)
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val text = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull() ?: ""
                onPartialResult?.invoke(text)
            }
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            rec.startListening(intent)
        } catch (e: Exception) {
            Log.w(TAG, "startListening failed", e)
            onError?.invoke("Could not start listening: ${e.message}")
            destroyRecognizer()
        }
    }

    fun stopListening() {
        try {
            recognizer?.stopListening()
        } catch (_: Exception) { }
    }

    private fun destroyRecognizer() {
        try {
            recognizer?.destroy()
        } catch (_: Exception) { }
        recognizer = null
    }

    /** Speak text aloud. Interrupts any current speech. */
    fun speak(text: String) {
        interrupt()
        lastSpokenText = text
        val id = UUID.randomUUID().toString()
        setState(State.Speaking(id))
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun replayLast() {
        lastSpokenText?.let { speak(it) }
    }

    /** Immediately stops TTS and listening. */
    fun interrupt() {
        continuousMode = false
        try {
            tts.stop()
        } catch (_: Exception) { }
        try {
            recognizer?.cancel()
        } catch (_: Exception) { }
        destroyRecognizer()
        if (state != State.Idle) setState(State.Idle)
    }

    fun shutdown() {
        interrupt()
        try {
            tts.shutdown()
        } catch (_: Exception) { }
    }
}
