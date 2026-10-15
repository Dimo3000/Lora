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

data class ChatMessage(val id: Long, val role: Role, val text: String, val cards: List<Card> = emptyList())

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
    /** Запомненные голоса семьи. */
    val voices: List<String> = emptyList(),
)

private class Enroll(val name: String, var done: Int = 0, var fails: Int = 0)

class AssistantViewModel(app: Application) : AndroidViewModel(app), SpeechController.Listener {

    private val store = SettingsStore(app)
    private val rich = Rich()
    private val answerer = Answerer()
    private val llm = LocalLlm(app)
    private val speech = SpeechController(app, this)
    private var askJob: Job? = null
    private var downloadJob: Job? = null
    private var nextId = 0L
    private val profiles = VoiceProfiles(app)
    private var pendingVector: DoubleArray? = null
    private var pendingFrames = 0
    private var speaker: String? = null
    private var enroll: Enroll? = null

    private val _state = MutableStateFlow(UiState(settings = store.load(), voices = profiles.names()))
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
                val kind = rich.kindOf(question)
                if (kind == Rich.Kind.AUDIO) {
                    playMusic(question)
                    return@launch
                }
                val cards = withContext(Dispatchers.IO) {
                    when (kind) {
                        Rich.Kind.PICTURES -> listOfNotNull(rich.pictures(question))
                        Rich.Kind.VIDEO -> rich.video(question)
                        Rich.Kind.MOVIES -> rich.movies(question)
                        else -> if (answerer.needsWeb(question)) listOf(rich.yandex(question)) else emptyList()
                    }
                }
                val live = answerer.answerLive(question, settings.language, settings.city)
                when {
                    live != null -> deliver(live, listOfNotNull(answerer.takeCard()))
                    cards.isNotEmpty() && _state.value.llm != LlmState.Ready -> deliver(introFor(kind), cards)
                    _state.value.llm == LlmState.Ready -> chatWithLlm(question, cards)
                    else -> deliver(greetByName(question, answerer.answer(question, settings.language, settings.city)))
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
    private fun deliver(answer: String, cards: List<Card> = emptyList()) {
        addMessage(Role.ASSISTANT, answer, cards)
        if (speech.speak(answer, settings.language)) setPhase(Phase.SPEAKING) else finishTurn()
    }

    /** Нейросеть отвечает потоком: текст появляется на экране, а готовые предложения сразу озвучиваются. */
    /** Без нейросети обращаемся по имени в ответ на приветствие. */
    private fun greetByName(question: String, answer: String): String {
        val name = speaker ?: return answer
        val q = question.lowercase()
        return if (q.startsWith("привет") || q.startsWith("здравствуй") || q.startsWith("добрый") || q.startsWith("доброе")) {
            "$name, ${answer.replaceFirstChar { it.lowercase() }}"
        } else answer
    }

    private fun introFor(kind: Rich.Kind) = when (kind) {
        Rich.Kind.PICTURES -> "Вот что нашла."
        Rich.Kind.VIDEO -> "Нашла видео, нажмите на превью."
        Rich.Kind.MOVIES -> "Вот несколько фильмов, нажмите на афишу, чтобы узнать подробнее."
        else -> "Вот что нашла."
    }

    /** «Включи симфонию»: ищем свободную запись и сразу запускаем, без голоса поверх музыки. */
    private suspend fun playMusic(question: String) {
        val free = rich.isFreeMusicRequest(question)
        val track = if (free) withContext(Dispatchers.IO) { runCatching { rich.audio(question) }.getOrNull() } else null
        if (track != null) {
            addMessage(Role.ASSISTANT, "Включаю: ${track.title}", listOf(track))
            AudioPlayer.play(track.url)
            finishTurn()
            return
        }
        val query = rich.musicQuery(question)
        val app = MusicApps.play(getApplication(), query)
        if (app != null) {
            addMessage(Role.ASSISTANT, "Включаю в $app: $query", rich.musicLinks(query))
            finishTurn()
        } else {
            deliver(
                "Современную музыку сама проиграть не могу, у неё авторские права. " +
                    "Выберите, где открыть: нажмите на сервис, поиск уже введён.",
                rich.musicLinks(query),
            )
        }
    }

    private suspend fun chatWithLlm(question: String, cards: List<Card> = emptyList()) {
        val web = if (answerer.needsWeb(question)) runCatching { answerer.webContext(question) }.getOrNull() else null
        val id = nextId++
        var shown = ""
        _state.update { it.copy(messages = it.messages + ChatMessage(id, Role.ASSISTANT, "…", cards)) }
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
            llm.chat(question, web, speaker).collect { chunk ->
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
        if (enroll != null) {
            startListening()
            return
        }
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

    private fun addMessage(role: Role, text: String, cards: List<Card> = emptyList()) {
        _state.update { it.copy(messages = it.messages + ChatMessage(nextId++, role, text, cards)) }
    }

    private fun attachCards(id: Long, cards: List<Card>) {
        if (cards.isEmpty()) return
        _state.update { st -> st.copy(messages = st.messages.map { if (it.id == id) it.copy(cards = cards) else it }) }
    }

    private fun updateMessage(id: Long, text: String) {
        _state.update { st -> st.copy(messages = st.messages.map { if (it.id == id) it.copy(text = text) else it }) }
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase, soundLevel = 0f) }

    // ---------- SpeechController.Listener ----------

    override fun onListeningStarted() = setPhase(Phase.LISTENING)

    override fun onPartialResult(text: String) = _state.update { it.copy(partialText = text) }

    override fun onSpeakerVector(vector: DoubleArray?, frames: Int) {
        pendingVector = vector
        pendingFrames = frames
    }

    override fun onFinalResult(text: String) {
        val vector = pendingVector?.takeIf { pendingFrames >= VoiceProfiles.MIN_FRAMES }
        pendingVector = null
        if (enroll != null) {
            handleEnrollment(vector)
            return
        }
        speaker = vector?.let { profiles.identify(it) } ?: speaker.takeIf { vector == null }
        ask(text)
    }

    /** Запоминание голоса: просим три фразы подряд и сохраняем отпечаток каждой. */
    fun startEnroll(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        if (!settings.language.startsWith("ru") || !speech.vosk.isBundled) {
            addMessage(Role.ERROR, "Для запоминания голоса нужно встроенное русское распознавание.")
            return
        }
        speech.stopSpeaking()
        profiles.remove(clean)
        enroll = Enroll(clean)
        val text = "Запоминаю голос: $clean. Скажите любую фразу подлиннее, всего нужно три."
        addMessage(Role.ASSISTANT, text)
        if (speech.speak(text, settings.language)) setPhase(Phase.SPEAKING) else finishTurn()
    }

    fun removeVoice(name: String) {
        profiles.remove(name)
        if (speaker == name) speaker = null
        _state.update { it.copy(voices = profiles.names()) }
    }

    private fun handleEnrollment(vector: DoubleArray?) {
        val e = enroll ?: return
        val text: String
        if (vector == null) {
            e.fails++
            if (e.fails >= 4) {
                enroll = null
                addMessage(Role.ERROR, "Не получилось записать голос. Скажите фразу подлиннее и в тишине, потом попробуйте снова.")
                finishTurn()
                return
            }
            text = "Не получилось, скажите фразу подлиннее."
        } else {
            profiles.add(e.name, vector)
            e.done++
            if (e.done >= VoiceProfiles.SAMPLES_NEEDED) {
                enroll = null
                _state.update { it.copy(voices = profiles.names()) }
                val done = "Готово, теперь я узнаю ${e.name} по голосу."
                addMessage(Role.ASSISTANT, done)
                if (speech.speak(done, settings.language)) setPhase(Phase.SPEAKING) else finishTurn()
                return
            }
            text = "Записала ${e.done} из ${VoiceProfiles.SAMPLES_NEEDED}. Теперь ещё фразу."
        }
        addMessage(Role.ASSISTANT, text)
        if (speech.speak(text, settings.language)) setPhase(Phase.SPEAKING) else finishTurn()
    }

    override fun onListenError(message: String) {
        if (_state.value.phase != Phase.LISTENING) return
        enroll?.let { e ->
            e.fails++
            if (e.fails >= 4) {
                enroll = null
                _state.update { it.copy(phase = Phase.IDLE, partialText = "", soundLevel = 0f) }
                addMessage(Role.ERROR, "Не получилось записать голос: $message")
                finishTurn()
                return
            }
        }
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
