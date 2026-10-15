package com.lora.assistant

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Распознавание речи (SpeechRecognizer) и озвучка ответов (TextToSpeech).
 * Все методы вызываются с главного потока; колбэки тоже приходят на главный поток.
 */
class SpeechController(private val context: Context, private val listener: Listener) {

    interface Listener {
        fun onListeningStarted()
        fun onPartialResult(text: String)
        fun onFinalResult(text: String)
        fun onListenError(message: String)
        fun onSoundLevel(level: Float)
        fun onSpeakingDone()
        fun onWakeWord()

        /** Отпечаток голоса последней фразы (только встроенное распознавание). null — не удалось получить. */
        fun onSpeakerVector(vector: DoubleArray?, frames: Int) {}
    }

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    val vosk = VoskRecognizer(context, listener)
    private val piper = PiperVoice(context)

    /** Скорость речи из настроек. */
    var speechRate: Float
        get() = piper.rate
        set(value) { piper.rate = value }
    private val wake = WakeWordDetector { listener.onWakeWord() }
    @Volatile private var systemSession = 0
    private var pendingUtterances = 0
    private var speechEnded = false
    private var piperActive = false
    private var activeEngine = Engine.NONE
    private var ttsReady = false
    private var utteranceCounter = 0

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status ->
        ttsReady = status == TextToSpeech.SUCCESS
    }

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) = Unit

            override fun onDone(utteranceId: String?) = finished(utteranceId)

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) = finished(utteranceId)

            override fun onStop(utteranceId: String?, interrupted: Boolean) = Unit
        })
    }

    private fun finished(utteranceId: String?) {
        val session = utteranceId?.removePrefix("s")?.substringBefore('-')?.toIntOrNull()
        main.post {
            if (session != systemSession) return@post
            pendingUtterances--
            if (speechEnded && pendingUtterances <= 0) listener.onSpeakingDone()
        }
    }

    init {
        piper.load { }
    }

    /**
     * Через какой движок можно распознавать речь в фоне, прямо внутри приложения.
     * NONE значит, что остаётся только системное окно распознавания (см. recognizeIntent).
     */
    fun backgroundEngine(language: String): Engine = when {
        // Для русского сначала встроенный офлайн-движок: он есть в APK и не зависит от Google.
        language.startsWith("ru") && vosk.isBundled -> Engine.VOSK
        SpeechRecognizer.isRecognitionAvailable(context) -> Engine.SERVICE
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context) -> Engine.ON_DEVICE
        else -> Engine.NONE
    }

    enum class Engine { VOSK, SERVICE, ON_DEVICE, NONE }

    fun startListening(language: String) {
        stopSpeaking()
        activeEngine = backgroundEngine(language)
        if (activeEngine == Engine.VOSK) {
            vosk.start()
            return
        }
        val r = recognizer ?: createRecognizer(activeEngine).also {
            it.setRecognitionListener(recognitionListener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
        }
        r.startListening(intent)
    }

    private fun createRecognizer(engine: Engine): SpeechRecognizer =
        if (engine == Engine.ON_DEVICE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
        } else {
            SpeechRecognizer.createSpeechRecognizer(context)
        }

    /** Закончить слушать и распознать то, что уже сказано. */
    fun stopListening() {
        if (activeEngine == Engine.VOSK) vosk.finish() else recognizer?.stopListening()
    }

    fun cancelListening() {
        vosk.cancel()
        recognizer?.cancel()
    }

    // ---------- Озвучка ----------

    /** Какой голос используется: встроенный женский (Piper) или системный Android. */
    val usesBuiltInVoice get() = piper.isReady

    /**
     * Начинает озвучку по предложениям: beginSpeech() → speakChunk() для каждого предложения → endSpeech().
     * Когда всё договорено, вызывается listener.onSpeakingDone().
     */
    fun beginSpeech(language: String): Boolean {
        stopSpeaking()
        speechEnded = false
        pendingUtterances = 0
        val session = ++systemSession
        piperActive = piper.isReady && language.startsWith("ru")
        if (piperActive) {
            piper.begin { if (session == systemSession) listener.onSpeakingDone() }
            return true
        }
        if (!ttsReady) return false
        val result = tts.setLanguage(Locale.forLanguageTag(language))
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            tts.setLanguage(Locale.getDefault())
        }
        pickFemaleVoice(language)
        return true
    }

    fun speakChunk(text: String) {
        val clean = cleanForSpeech(text)
        if (clean.isBlank()) return
        if (piperActive) {
            piper.add(clean)
            return
        }
        for (chunk in splitForTts(clean, TextToSpeech.getMaxSpeechInputLength() - 100)) {
            pendingUtterances++
            tts.speak(chunk, TextToSpeech.QUEUE_ADD, null, "s$systemSession-${utteranceCounter++}")
        }
    }

    fun endSpeech() {
        speechEnded = true
        if (piperActive) {
            piper.end()
        } else if (pendingUtterances == 0) {
            main.post { listener.onSpeakingDone() }
        }
    }

    /** Озвучивает целый текст. Возвращает false, если синтез речи недоступен. */
    fun speak(text: String, language: String): Boolean {
        if (!beginSpeech(language)) return false
        speakChunk(text)
        endSpeech()
        return true
    }

    fun stopSpeaking() {
        systemSession++
        piper.stop()
        if (ttsReady) tts.stop()
        pendingUtterances = 0
    }

    /** Выбирает женский голос системного синтезатора, если их несколько. */
    private fun pickFemaleVoice(language: String) {
        val wanted = Locale.forLanguageTag(language).language
        val voices = runCatching { tts.voices }.getOrNull().orEmpty()
            .filter { it.locale.language == wanted && !it.isNetworkConnectionRequired }
        val female = voices.firstOrNull { v ->
            val n = v.name.lowercase()
            n.contains("female") || n.contains("-f-") || n.contains("irina") || n.contains("anna") ||
                n.contains("alena") || n.contains("ru-ru-x-ruf") || n.contains("ruc-") || n.contains("rud-")
        }
        if (female != null) runCatching { tts.voice = female }
    }

    // ---------- Имя «Лора» ----------

    /** Начинает ждать слово «Лора». Нужна загруженная модель Vosk. */
    fun startWakeWord(): Boolean {
        val model = vosk.loadedModel ?: return false
        return wake.start(model)
    }

    fun stopWakeWord() = wake.stop()

    val wakeWordAvailable get() = vosk.isReady

    fun release() {
        wake.stop()
        vosk.release()
        piper.release()
        recognizer?.destroy()
        recognizer = null
        tts.stop()
        tts.shutdown()
    }

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = listener.onListeningStarted()
        override fun onBeginningOfSpeech() = Unit
        override fun onRmsChanged(rmsdB: Float) = listener.onSoundLevel(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        override fun onBufferReceived(buffer: ByteArray?) = Unit
        override fun onEndOfSpeech() = Unit
        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onPartialResults(partialResults: Bundle?) {
            firstResult(partialResults)?.let(listener::onPartialResult)
        }

        override fun onResults(results: Bundle?) {
            val text = firstResult(results)
            if (text.isNullOrBlank()) listener.onListenError("Не расслышала, попробуйте ещё раз.")
            else listener.onFinalResult(text)
        }

        override fun onError(error: Int) {
            listener.onListenError(
                when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                        "Не расслышала, попробуйте ещё раз."
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Нет доступа к микрофону."
                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                        "Распознаванию речи нужен интернет."
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Распознавание занято, попробуйте ещё раз."
                    SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE ->
                        "Этот язык не поддерживается распознаванием на телефоне."
                    else -> "Ошибка распознавания ($error)."
                },
            )
        }
    }

    private fun firstResult(bundle: Bundle?): String? =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()?.trim()

    companion object {
        /** Интент для системного окна распознавания (запасной путь, если фонового сервиса нет). */
        fun recognizeIntent(language: String) = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            putExtra(RecognizerIntent.EXTRA_PROMPT, if (language.startsWith("en")) "Ask a question" else "Задайте вопрос")
        }

        /** Убирает разметку, которую синтезатор прочитал бы вслух. */
        fun cleanForSpeech(text: String): String = text
            .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1") // [текст](ссылка) -> текст
            .replace(Regex("https?://\\S+"), "")
            .replace(Regex("[*_`#>|]+"), "")
            .replace(Regex("(?m)^\\s*[-•]\\s+"), "")
            .replace("т.д.", "так далее").replace("т.е.", "то есть").replace("т.п.", "тому подобное")
            .replace("°C", " градусов").replace("°", " градусов").replace("%", " процентов").replace("м/с", "метров в секунду")
            .replace(Regex("\\s*[—–]\\s*"), ", ")
            .replace(Regex("[()«»\"]"), "")
            .replace(Regex("[\\p{So}\\p{Cs}\\uFE0F]"), "") // эмодзи
            .replace(Regex("[ \\t]+"), " ")
            .trim()

        fun splitForTts(text: String, maxLen: Int): List<String> {
            if (text.isBlank()) return emptyList()
            val result = mutableListOf<String>()
            val current = StringBuilder()
            for (sentence in text.split(Regex("(?<=[.!?…\\n])\\s+"))) {
                if (current.length + sentence.length + 1 > maxLen && current.isNotEmpty()) {
                    result += current.toString()
                    current.clear()
                }
                if (sentence.length > maxLen) {
                    sentence.chunked(maxLen).forEach { result += it }
                } else {
                    if (current.isNotEmpty()) current.append(' ')
                    current.append(sentence)
                }
            }
            if (current.isNotEmpty()) result += current.toString()
            return result
        }
    }
}
