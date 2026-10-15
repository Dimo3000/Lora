package com.lora.assistant

import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * Слушает микрофон и ждёт имя «Лора». Использует ту же офлайн-модель Vosk, но с крошечной
 * грамматикой из одного слова, поэтому работает быстро и почти не нагружает телефон.
 */
class WakeWordDetector(private val onWake: () -> Unit) {

    private var service: SpeechService? = null
    private var fired = false

    fun start(model: Model): Boolean {
        stop()
        fired = false
        return try {
            val recognizer = Recognizer(model, SAMPLE_RATE, GRAMMAR)
            val s = SpeechService(recognizer, SAMPLE_RATE)
            service = s
            s.startListening(listener)
            true
        } catch (_: Exception) {
            service = null
            false
        }
    }

    fun stop() {
        service?.stop()
        service?.shutdown()
        service = null
    }

    private fun check(json: String?, key: String) {
        if (fired) return
        val text = runCatching { JSONObject(json ?: "").optString(key) }.getOrDefault("")
        if (WAKE_WORDS.any { text.contains(it) }) {
            fired = true
            onWake() // Vosk присылает события на главном потоке
        }
    }

    private val listener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
        override fun onError(exception: Exception?) = Unit
        override fun onTimeout() = Unit
    }

    private companion object {
        const val SAMPLE_RATE = 16000.0f
        val WAKE_WORDS = listOf("лора", "лара")
        // «лара» добавлена, потому что распознавание иногда путает эти два имени.
        const val GRAMMAR = "[\"лора\", \"лара\", \"[unk]\"]"
    }
}
