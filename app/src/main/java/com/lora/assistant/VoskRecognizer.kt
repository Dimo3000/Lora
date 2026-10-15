package com.lora.assistant

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

/**
 * Встроенное офлайн-распознавание русской речи (Vosk). Модель лежит в assets/model-ru
 * и при первом запуске распаковывается во внутреннюю память. Не нужен ни Google, ни интернет.
 */
class VoskRecognizer(private val context: Context, private val listener: SpeechController.Listener) {

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var service: SpeechService? = null
    private var lastPartial = ""
    private var delivered = false

    /** Модель включена в APK (её кладёт скрипт scripts/download-vosk-model.sh при сборке). */
    val isBundled: Boolean = runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)

    val isReady get() = model != null

    /** Загруженная модель; её же использует детектор имени «Лора». */
    val loadedModel: Model? get() = model

    init {
        if (isBundled) {
            StorageService.unpack(
                context, ASSET_DIR, "model",
                { m -> main.post { model = m } },
                { e -> main.post { listener.onListenError("Не удалось загрузить встроенное распознавание: ${e.message}") } },
            )
        }
    }

    fun start() {
        val m = model ?: run {
            listener.onListenError("Встроенное распознавание ещё загружается, попробуйте через пару секунд.")
            return
        }
        stop()
        lastPartial = ""
        delivered = false
        try {
            val s = SpeechService(Recognizer(m, SAMPLE_RATE), SAMPLE_RATE)
            service = s
            s.startListening(voskListener, TIMEOUT_MS)
            listener.onListeningStarted()
        } catch (e: Exception) {
            listener.onListenError("Не удалось включить микрофон: ${e.message}")
        }
    }

    /** Закончить слушать: распознаём то, что уже сказано. */
    fun finish() {
        if (lastPartial.isNotBlank()) deliver(lastPartial) else cancel()
    }

    fun cancel() {
        delivered = true
        stop()
    }

    fun release() {
        stop()
        model?.close()
        model = null
    }

    private fun stop() {
        service?.stop()
        service?.shutdown()
        service = null
    }

    private fun deliver(text: String) {
        if (delivered) return
        delivered = true
        main.post { stop() }
        if (text.isBlank()) listener.onListenError("Не расслышала, попробуйте ещё раз.")
        else listener.onFinalResult(text)
    }

    private val voskListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) {
            val text = field(hypothesis, "partial")
            if (text.isNotBlank() && !delivered) {
                lastPartial = text
                listener.onPartialResult(text)
                listener.onSoundLevel(0.6f)
            }
        }

        // Vosk сам находит паузу после фразы и присылает результат.
        override fun onResult(hypothesis: String?) {
            val text = field(hypothesis, "text")
            if (text.isNotBlank()) deliver(text)
        }

        override fun onFinalResult(hypothesis: String?) {
            deliver(field(hypothesis, "text").ifBlank { lastPartial })
        }

        override fun onError(exception: Exception?) {
            if (delivered) return
            delivered = true
            main.post { stop() }
            listener.onListenError("Ошибка распознавания: ${exception?.message}")
        }

        override fun onTimeout() {
            deliver(lastPartial)
        }
    }

    private fun field(json: String?, name: String): String =
        runCatching { JSONObject(json ?: "").optString(name) }.getOrDefault("").trim()

    private companion object {
        const val ASSET_DIR = "model-ru"
        const val SAMPLE_RATE = 16000.0f
        const val TIMEOUT_MS = 10000
    }
}
