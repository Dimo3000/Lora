package com.lora.assistant

import android.content.Context
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.SamplerConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.time.LocalDate
import java.util.concurrent.TimeUnit

/** Состояние нейросети, которое показываем в интерфейсе. */
sealed interface LlmState {
    data object Missing : LlmState
    data class Downloading(val doneBytes: Long, val totalBytes: Long) : LlmState
    data object Loading : LlmState
    data object Ready : LlmState
    data class Failed(val message: String) : LlmState
}

/**
 * Нейросеть, которая работает прямо на телефоне (Gemma 4 через LiteRT-LM): без ключей и без интернета.
 * Модель (около 2,6 ГБ) скачивается один раз по кнопке в настройках.
 */
class LocalLlm(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val modelDir = File(context.filesDir, "models")
    private val modelFile = File(modelDir, MODEL_FILE)
    private val partFile = File(modelDir, "$MODEL_FILE.part")

    /** Город пользователя: попадает в системную подсказку. */
    @Volatile var city: String = ""

    private var engine: Engine? = null
    private var conversation: Conversation? = null

    /** Наша копия диалога: нужна, чтобы пересоздать разговор, когда он станет слишком длинным. */
    private val memory = Memory(context)
    private val history = memory.loadHistory().toMutableList() // (это реплика пользователя?, текст)

    fun isDownloaded() = modelFile.exists()

    fun partialBytes() = if (partFile.exists()) partFile.length() else 0L

    /** Докачивает модель с места обрыва. onProgress вызывается из фонового потока. */
    suspend fun download(onProgress: (done: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        modelDir.mkdirs()
        if (modelFile.exists()) return@withContext
        val have = partFile.length()
        if (modelDir.usableSpace < MODEL_BYTES_APPROX - have + 300_000_000L) {
            throw IOException("Не хватает места: нужно около 3 ГБ свободной памяти телефона.")
        }
        val request = Request.Builder().url(MODEL_URL)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 416) { // файл уже скачан целиком
                partFile.renameTo(modelFile)
                return@use
            }
            if (!response.isSuccessful) throw IOException("Сервер ответил ошибкой ${response.code}.")
            val resumed = response.code == 206
            val offset = if (resumed) have else 0L
            val total = offset + (response.body?.contentLength() ?: -1L).coerceAtLeast(0L)
            val body = response.body ?: throw IOException("Пустой ответ сервера.")
            RandomAccessFile(partFile, "rw").use { out ->
                if (resumed) out.seek(offset) else out.setLength(0)
                var done = offset
                var lastReport = 0L
                val buffer = ByteArray(256 * 1024)
                body.byteStream().use { input ->
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        val now = System.nanoTime()
                        if (now - lastReport > 300_000_000L) {
                            lastReport = now
                            onProgress(done, total)
                        }
                    }
                }
                onProgress(done, total)
                if (total > 0 && done < total) throw IOException("Загрузка оборвалась, нажмите «Скачать» ещё раз, она продолжится.")
            }
            if (!partFile.renameTo(modelFile)) throw IOException("Не удалось сохранить модель.")
        }
    }

    fun deleteModel() {
        close()
        modelFile.delete()
        partFile.delete()
    }

    /** Загружает модель в память (до ~10–30 секунд). */
    suspend fun load() = withContext(Dispatchers.Default) {
        if (engine != null) return@withContext
        val e = Engine(
            EngineConfig(
                modelPath = modelFile.absolutePath,
                backend = Backend.CPU(),
                cacheDir = context.cacheDir.absolutePath,
            ),
        )
        e.initialize()
        engine = e
        conversation = newConversation(history.map(::toMessage))
    }

    /** Отвечает потоком кусочков текста. Контекст разговора сохраняется между вопросами. */
    fun chat(userText: String, webContext: String? = null, speaker: String? = null): Flow<String> = flow {
        if (engine == null) throw IOException("Нейросеть ещё не загружена.")
        if (history.sumOf { it.second.length } > MAX_HISTORY_CHARS) {
            // Разговор стал длинным: начинаем заново, оставив только последние реплики.
            val tail = history.takeLast(6)
            history.clear()
            history.addAll(tail)
            conversation?.close()
            conversation = newConversation(tail.map(::toMessage))
        }
        val conv = conversation ?: newConversation(history.map(::toMessage)).also { conversation = it }
        val answer = StringBuilder()
        val prompt = if (webContext == null) userText else
            "$webContext\nИспользуй эти данные, если они подходят. Для подборки назови 3–5 вариантов с короткими пояснениями простыми предложениями, без списков и ссылок.\n\nВопрос: $userText"
        val who = speaker?.let { "(Сейчас с тобой говорит $it. Иногда называй собеседника по имени, но не в каждой фразе.)\n" }.orEmpty()
        conv.sendMessageAsync(who + prompt).collect { chunk ->
            val text = chunk.toString()
            answer.append(text)
            emit(text)
        }
        history += true to userText
        history += false to answer.toString()
        memory.saveHistory(history)
    }.flowOn(Dispatchers.Default)

    /** Пересоздаёт разговор (например, после нового факта в памяти), сохранив историю. */
    fun refreshMemory() {
        if (engine == null) return
        conversation?.close()
        conversation = newConversation(history.map(::toMessage))
    }

    fun resetConversation() {
        history.clear()
        memory.saveHistory(history)
        conversation?.close()
        conversation = engine?.let { newConversation(emptyList()) }
    }

    fun close() {
        conversation?.close()
        conversation = null
        engine?.close()
        engine = null
    }

    private fun toMessage(turn: Pair<Boolean, String>): Message =
        if (turn.first) Message.user(turn.second) else Message.model(turn.second)

    private fun newConversation(initial: List<Message>): Conversation =
        checkNotNull(engine).createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(systemPrompt()),
                initialMessages = initial,
                samplerConfig = SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8),
            ),
        )

    private fun systemPrompt() = """
        Ты Лора, голосовой помощник и добрая собеседница. Ты женщина: говоришь о себе в женском роде.
        Твои ответы зачитывает синтезатор речи, поэтому:
        - отвечай по-человечески, тепло и живо, как в разговоре с близким человеком;
        - сразу отвечай по сути, не рассуждай вслух и не пересказывай вопрос;
        - обычно 1–3 коротких предложения; длиннее только если человек просит подробности;
        - никакого markdown, списков, таблиц, эмодзи и ссылок, только обычные предложения;
        - числа и единицы пиши так, чтобы их легко произнести вслух.
        Поддерживай разговор на любую тему, помни, о чём говорили раньше, при уместности задавай встречный вопрос.
        Отвечай на языке собеседника. Если чего-то не знаешь, честно скажи об этом, не выдумывай.
        Сама ты не ходишь в интернет: когда нужны свежие данные, приложение даёт тебе результаты поиска, и ты опираешься на них, а не на память.
        Сегодня ${LocalDate.now()}.${if (city.isNotBlank()) " Город собеседника: $city." else ""}
        ${memory.factsForPrompt()}
    """.trimIndent()

    companion object {
        const val MODEL_FILE = "gemma-4-E2B-it.litertlm"
        const val MODEL_URL =
            "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/$MODEL_FILE"
        const val MODEL_BYTES_APPROX = 2_600_000_000L
        private const val MAX_HISTORY_CHARS = 6000
    }
}
