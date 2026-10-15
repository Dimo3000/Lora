package com.lora.assistant

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class Role { USER, ASSISTANT, ERROR }

data class ChatMessage(val id: Long, val role: Role, val text: String)

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }

data class UiState(
    val messages: List<ChatMessage> = emptyList(),
    val phase: Phase = Phase.IDLE,
    val partialText: String = "",
    val soundLevel: Float = 0f,
    val settings: AppSettings = AppSettings(),
)

class AssistantViewModel(app: Application) : AndroidViewModel(app), SpeechController.Listener {

    private val store = SettingsStore(app)
    private val answerer = Answerer()
    private val speech = SpeechController(app, this)
    private var askJob: Job? = null
    private var nextId = 0L

    private val _state = MutableStateFlow(UiState(settings = store.load()))
    val state: StateFlow<UiState> = _state.asStateFlow()

    private val settings get() = _state.value.settings

    /** Главная кнопка: начать слушать / закончить / отменить / замолчать — в зависимости от состояния. */
    fun onMicClick() {
        when (_state.value.phase) {
            Phase.IDLE -> startListening()
            Phase.LISTENING -> speech.stopListening()
            Phase.THINKING -> {
                askJob?.cancel()
                setPhase(Phase.IDLE)
            }
            Phase.SPEAKING -> {
                speech.stopSpeaking()
                setPhase(Phase.IDLE)
            }
        }
    }

    fun sendText(text: String) {
        if (text.isBlank()) return
        speech.cancelListening()
        speech.stopSpeaking()
        ask(text.trim())
    }

    fun newConversation() {
        askJob?.cancel()
        speech.cancelListening()
        speech.stopSpeaking()
        _state.update { it.copy(messages = emptyList(), phase = Phase.IDLE, partialText = "") }
    }

    fun saveSettings(newSettings: AppSettings) {
        store.save(newSettings)
        _state.update { it.copy(settings = store.load()) }
    }

    fun permissionDenied() = addMessage(Role.ERROR, "Без доступа к микрофону можно только печатать вопросы.")

    private fun startListening() {
        if (!speech.isRecognitionAvailable()) {
            addMessage(Role.ERROR, "На телефоне нет сервиса распознавания речи. Установите приложение Google.")
            return
        }
        _state.update { it.copy(phase = Phase.LISTENING, partialText = "", soundLevel = 0f) }
        speech.startListening(settings.language)
    }

    private fun ask(question: String) {
        addMessage(Role.USER, question)
        _state.update { it.copy(phase = Phase.THINKING, partialText = "") }

        askJob?.cancel()
        askJob = viewModelScope.launch {
            try {
                val answer = answerer.answer(question, settings.language, settings.city)
                addMessage(Role.ASSISTANT, answer)
                if (speech.speak(answer, settings.language)) setPhase(Phase.SPEAKING) else setPhase(Phase.IDLE)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                addMessage(Role.ERROR, e.message ?: "Неизвестная ошибка.")
                setPhase(Phase.IDLE)
            }
        }
    }

    private fun addMessage(role: Role, text: String) {
        _state.update { it.copy(messages = it.messages + ChatMessage(nextId++, role, text)) }
    }

    private fun setPhase(phase: Phase) = _state.update { it.copy(phase = phase, soundLevel = 0f) }

    // --- SpeechController.Listener ---

    override fun onListeningStarted() = setPhase(Phase.LISTENING)

    override fun onPartialResult(text: String) = _state.update { it.copy(partialText = text) }

    override fun onFinalResult(text: String) = ask(text)

    override fun onListenError(message: String) {
        if (_state.value.phase != Phase.LISTENING) return
        _state.update { it.copy(phase = Phase.IDLE, partialText = "", soundLevel = 0f) }
        addMessage(Role.ERROR, message)
    }

    override fun onSoundLevel(level: Float) = _state.update { it.copy(soundLevel = level) }

    override fun onSpeakingDone() {
        if (_state.value.phase != Phase.SPEAKING) return
        setPhase(Phase.IDLE)
        if (settings.autoListen) startListening()
    }

    override fun onCleared() {
        speech.release()
    }
}
