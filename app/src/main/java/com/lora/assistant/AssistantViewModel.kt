package com.lora.assistant

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Role { USER, ASSISTANT, ERROR }

data class ChatMessage(val id: Long, val role: Role, val text: String)

/** WAKE: ждём, когда назовут имя «Лора». */
enum class Phase { IDLE, WAKE, LISTENING, THINKING, SPEAKING }

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val phase: Phase = Phase.IDLE,
    val partialText: String = "",
    val soundLevel: Float = 0f,
    val settings: AppSettings = AppSettings(),
    val llm: LlmState = LlmState.Missing,
    /** UI должен открыть системное окно распознавания речи. */
    val systemRecognizerRequest: Long = 0L,
    /** Приложение на экране: имя «Лора» слушаем только пока оно открыто. */
    val foreground: Boolean = false,
)

class AssistantViewModel(app: Application) : AndroidViewModel(app), SpeechController.Listener {

    private val store = SettingsStore(app)
    private val answerer = Answerer()
    private val llm = LocalLlm(app)
    private val speech = SpeechController(app, this)
    private var askJob: Job? = null
    private var downloadJob: Job? = null
    private var nextId = 0L

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val settings get() = _state.value.settings

    init {
        llm.city = settings.city
        if (llm.isDownloaded()) loadLlm()
    }

    // ---------- Кнопки интерфейса ----------

    /** Главная кнопка: начать слушать / закончить / отменить / замолчать — в зависимости от состояния. */
    fun onMicClick() {
        when (_state.value.phase) {
            Phase.IDLE, Phase.WAKE -> startListening()
            Phase.LISTENING -> speech.stopListening()
            Phase.THINKING, Phase.SPEAKING -> {
                askJob?.cancel()
                speech.stopSpeaking()
                finishTurn()
            }
        }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        speech.stopWakeWord()
        speech.cancelListening()
        speech.stopSpeaking()
        ask(text.trim())
    }

    fun newConversation() {
        askJob?.cancel()
        speech.cancelListening()
        speech.stopSpeaking()
        llm.resetConversation()
        _state.update { it.copy(messages = emptyList(), phase = Phase.IDLE, partialText = "") }
        resumeWake()
    }

    fun saveSettings(newSettings: AppSettings) {
        store.save(newSettings)
        val saved = store.load()
        llm.city = saved.city
        _state.update { it.copy(settings = saved) }
        if (_state.value.phase == Phase.IDLE || _state.value.phase == Phase.WAKE) {
            speech.stopWakeWord()
            _state.update { it.copy(phase = Phase.IDLE) }
            resumeWake()
        }
    }

    fun setForeground(foreground: Boolean) {
        _state.update { it.copy(foreground = foreground) }
        if (foreground) {
            resumeWake()
        } else if (_state.value.phase == Phase.WAKE) {
            speech.stopWakeWord()
            _state.update { it.copy(phase = Phase.IDLE) }
        }
    }

    /** Пользователь выдал или не выдал доступ к микрофону. */
    fun onMicPermissionResult(granted: Boolean) {
        if (granted) resumeWake() else if (settings.wakeWord) {
            addMessage(Role.ERROR, "Без доступа к микрофону Лора не услышит своё имя.")
        }
    }

    fun permissionDenied() = addMessage(Role.ERROR, "Без доступа к микрофону можно только печатать вопросы.")

    /** Результат системного окна распознавания (null — пользователь закрыл окно). */
    fun onSystemRecognitionResult(text: String?) {
        if (!text.isNullOrBlank()) ask(text.trim()) else finishTurn()
    }

    fun noRecognizerAtAll() = addMessage(
        Role.ERROR,
        "На телефоне не нашлось распознавания речи. Пока можно печатать вопросы.",
    )

    // ---------- Нейросеть на телефоне ----------

    fun downloadModel() {
        if (downloadJob?.isActive == true) return
        downloadJob = viewModelScope.launch {
            _state.update { it.copy(llm = LlmState.Downloading(llm.partialBytes(), LocalLlm.MODEL_BYTES_APPROX)) }
            try {
                llm.download { done, total ->
                    _state.update { it.copy(llm = LlmState.Downloading(done, if (total > 0) total else LocalLlm.MODEL_BYTES_APPROX)) }
                }
                loadLlm()
            } catch (e: CancellationException) {
                _state.update { it.copy(llm = LlmState.Missing) }
                throw e
            } catch (e: Exception) {
                _state.update { it.copy(llm = LlmState.Failed(e.message ?: "Не удалось скачать модель.")) }
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun deleteModel() {
        downloadJob?.cancel()
        llm.deleteModel()
        _state.update { it.copy(llm = LlmState.Missing) }
    }

    private fun loadLlm() {
        _state.update { it.copy(llm = LlmState.Loading) }
        viewModelScope.launch {
            try {
                llm.load()
                _state.update { it.copy(llm = LlmState.Ready) }
            } catch (e: Exception) {
                _state.update {
                    it.copy(llm = LlmState.Failed("Не удалось запустить нейросеть: ${e.message}. Возможно, не хватает памяти телефона."))
                }
            }
        }
    }

    // ---------- Слушаем и отвечаем ----------

    private fun startListening() {
        speech.stopWakeWord()
        if (speech.backgroundEngine(settings.language) == SpeechController.Engine.NONE) {
            // Фонового сервиса нет: просим UI открыть системное окно распознавания.
            speech.stopSpeaking()
            _state.update { it.copy(systemRecognizerRequest = System.nanoTime(), phase = Phase.IDLE) }
            return
        }
        _state.update { it.copy(phase = Phase.LISTENING, partialText = "", soundLevel = 0f) }
        speech.startListening(settings.language)
    }

    private fun ask(rawQuestion: String) {
        val question = stripName(rawQuestion)
        addMessage(Role.USER, question)
        _state.update { it.copy(phase = Phase.THINKING, partialText = "") }

        askJob?.cancel()
        askJob = viewModelScope.launch {
            try {
                val live = answerer.answerLive(question, settings.language, settings.city)
                when {
                    live != null -> deliver(live)
                    _state.value.llm == LlmState.Ready -> chatWithLlm(question)
                    else -> deliver(answerer.answer(question, settings.language, settings.city))
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addMessage(Role.ERROR, e.message ?: "Неизвестная ошибка.")
                finishTurn()
            }
        }
    }

    /** Показывает готовый ответ и озвучивает его целиком. */
    private fun deliver(answer: String) {
        addMessage(Role.ASSISTANT, answer)
        if (speech.speak(answer, settings.language)) setPhase(Phase.SPEAKING) else finishTurn()
    }

    /** Нейросеть отвечает потоком: текст появляется на экране, а готовые предложения сразу озвучиваются. */
    private suspend fun chatWithLlm(question: String) {
        val web = if (answerer.needsWeb(question)) runCatching { answerer.webContext(question) }.getOrNull() else null
        val id = nextId++
        var shown = ""
        _state.update { it.copy(messages = it.messages + ChatMessage(id, Role.ASSISTANT, "…")) }
        val speaking = speech.beginSpeech(settings.language)
        val pending = StringBuilder()
        var started = false

        fun flushSentences(final: Boolean) {
            while (true) {
                val m = SENTENCE_END.find(pending) ?: break
                val end = m.range.last + 1
                val sentence = pending.substring(0, end).trim()
                pending.delete(0, end)
                if (sentence.isNotBlank() && speaking) {
                    speech.speakChunk(sentence)
                    if (!started) {
                        started = true
                        setPhase(Phase.SPEAKING)
                    }
                }
            }
            if (final && pending.isNotBlank() && speaking) {
                speech.speakChunk(pending.toString())
                started = true
                pending.clear()
            }
        }

        try {
            llm.chat(question, web).collect { chunk ->
                shown += chunk
                pending.append(chunk)
                _state.update { st ->
                    st.copy(messages = st.messages.map { if (it.id == id) it.copy(text = shown.trim()) else it })
                }
                flushSentences(final = false)
            }
            flushSentences(final = true)
            if (shown.isBlank()) {
                updateMessage(id, "Не знаю, что ответить.")
            }
            if (speaking && started) speech.endSpeech() else finishTurn()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            speech.stopSpeaking()
            // Нейросеть сломалась: отвечаем простым способом, чтобы не оставить человека без ответа.
            _state.update { st -> st.copy(messages = st.messages.filterNot { it.id == id }) }
            val fallback = withContext(Dispatchers.IO) { runCatching { answerer.answer(question, settings.language, settings.city) } }
            fallback.onSuccess { deliver(it) }.onFailure {
                addMessage(Role.ERROR, e.message ?: "Нейросеть не смогла ответить.")
                finishTurn()
            }
        }
    }

    /** Убирает обращение «Лора, …» в начале фразы. */
    private fun stripName(text: String): String {
        val cleaned = text.trim().replace(Regex("^(?i)(эй|ну|привет)?[,\\s]*(лора|лара)[,!.\\s]+"), "").trim()
        return cleaned.ifBlank { text.trim() }
    }

    /** Ход закончен: возвращаемся в ожидание имени или в покой. */
    private fun finishTurn() {
        setPhase(Phase.IDLE)
        if (settings.autoListen && !settings.wakeWord) startListening() else resumeWake()
    }

    private fun resumeWake() {
        val st = _state.value
        if (!settings.wakeWord || !st.foreground || st.phase != Phase.IDLE) return
        val granted = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted || !settings.language.startsWith("ru")) return
        if (speech.startWakeWord()) setPhase(Phase.WAKE)
    }

    private fun addMessage(role: Role, text: String) {
        _state.update { it.copy(messages = it.messages + ChatMessage(nextId++, role, text)) }
    }

    private fun updateMessage(id: Long, text: String) {
        _state.update { st -> st.copy(messages = st.messages.map { if (it.id == id) it.copy(text = text) else it }) }
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase, soundLevel = 0f) }

    // ---------- SpeechController.Listener ----------

    override fun onListeningStarted() = setPhase(Phase.LISTENING)

    override fun onPartialResult(text: String) = _state.update { it.copy(partialText = text) }

    override fun onFinalResult(text: String) = ask(text)

    override fun onListenError(message: String) {
        if (_state.value.phase != Phase.LISTENING) return
        _state.update { it.copy(phase = Phase.IDLE, partialText = "", soundLevel = 0f) }
        // В режиме имени не засоряем чат ошибками «не расслышала»: просто ждём дальше.
        if (!settings.wakeWord) addMessage(Role.ERROR, message)
        finishTurn()
    }

    override fun onSoundLevel(level: Float) = _state.update { it.copy(soundLevel = level) }

    override fun onSpeakingDone() {
        if (_state.value.phase != Phase.SPEAKING) return
        finishTurn()
    }

    /** Услышали «Лора»: коротким сигналом даём понять, что слушаем, и начинаем слушать вопрос. */
    override fun onWakeWord() {
        if (_state.value.phase != Phase.WAKE) return
        speech.stopWakeWord()
        runCatching {
            ToneGenerator(AudioManager.STREAM_MUSIC, 70).startTone(ToneGenerator.TONE_PROP_BEEP, 120)
        }
        // Небольшая пауза, чтобы сигнал не попал в запись.
        viewModelScope.launch {
            kotlinx.coroutines.delay(250)
            if (_state.value.phase == Phase.WAKE) startListening()
        }
    }

    override fun onCleared() {
        askJob?.cancel()
        speech.release()
        llm.close()
    }

    private companion object {
        /** Конец предложения: знак препинания и пробел/перенос после него. */
        val SENTENCE_END = Regex("[.!?…]+[\\s\\n]+|\\n+")
    }
}
