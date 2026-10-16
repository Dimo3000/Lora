package com.lora.assistant

import android.content.Context
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineNemoEncDecCtcModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.TimeUnit

/**
 * Мощное офлайн-распознавание русской речи (GigaAM v2 через sherpa-onnx). Модель около 230 МБ скачивается
 * один раз в настройках. Заметно точнее встроенной маленькой модели Vosk.
 */
class GigaAm(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private val dir = File(context.filesDir, "asr-giga")
    private val modelFile = File(dir, MODEL_FILE)
    private val tokensFile = File(dir, TOKENS_FILE)
    private val partFile = File(dir, "$MODEL_FILE.part")

    @Volatile private var recognizer: OfflineRecognizer? = null

    val isReady get() = recognizer != null
    fun isDownloaded() = modelFile.exists() && tokensFile.exists()
    fun partialBytes() = if (partFile.exists()) partFile.length() else 0L

    suspend fun download(onProgress: (done: Long, total: Long) -> Unit) = withContext(Dispatchers.IO) {
        dir.mkdirs()
        if (!tokensFile.exists()) {
            val tmp = File(dir, "$TOKENS_FILE.part")
            http.newCall(Request.Builder().url(BASE_URL + TOKENS_FILE).build()).execute().use { r ->
                if (!r.isSuccessful) throw IOException("Сервер ответил ошибкой ${r.code}.")
                tmp.writeBytes(r.body?.bytes() ?: throw IOException("Пустой ответ сервера."))
            }
            tmp.renameTo(tokensFile)
        }
        if (modelFile.exists()) return@withContext
        val have = partFile.length()
        if (dir.usableSpace < MODEL_BYTES_APPROX - have + 100_000_000L) throw IOException("Не хватает места: нужно около 300 МБ.")
        val request = Request.Builder().url(BASE_URL + MODEL_FILE)
            .apply { if (have > 0) header("Range", "bytes=$have-") }
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 416) { partFile.renameTo(modelFile); return@use }
            if (!response.isSuccessful) throw IOException("Сервер ответил ошибкой ${response.code}.")
            val resumed = response.code == 206
            val offset = if (resumed) have else 0L
            val body = response.body ?: throw IOException("Пустой ответ сервера.")
            val total = offset + body.contentLength().coerceAtLeast(0L)
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
                        if (now - lastReport > 300_000_000L) { lastReport = now; onProgress(done, total) }
                    }
                }
                onProgress(done, total)
                if (total > 0 && done < total) throw IOException("Загрузка оборвалась, нажмите «Скачать» ещё раз, она продолжится.")
            }
            if (!partFile.renameTo(modelFile)) throw IOException("Не удалось сохранить модель.")
        }
    }

    suspend fun load() = withContext(Dispatchers.Default) {
        if (recognizer != null) return@withContext
        val config = OfflineRecognizerConfig(
            featConfig = FeatureConfig(sampleRate = 16000, featureDim = 80),
            modelConfig = OfflineModelConfig(
                nemo = OfflineNemoEncDecCtcModelConfig(model = modelFile.absolutePath),
                tokens = tokensFile.absolutePath,
                numThreads = 3,
                debug = false,
                provider = "cpu",
            ),
        )
        recognizer = OfflineRecognizer(assetManager = null, config = config)
    }

    fun delete() {
        release()
        modelFile.delete(); partFile.delete(); tokensFile.delete()
    }

    fun release() {
        recognizer?.release()
        recognizer = null
    }

    /** Распознаёт фразу (16 кГц, моно). null — модель не загружена или ничего не услышала. */
    @Synchronized
    fun decode(samples: FloatArray): String? {
        val r = recognizer ?: return null
        val stream = r.createStream()
        return try {
            stream.acceptWaveform(samples, 16000)
            r.decode(stream)
            r.getResult(stream).text.trim().ifBlank { null }
        } catch (_: Exception) {
            null
        } finally {
            stream.release()
        }
    }

    companion object {
        const val MODEL_FILE = "model.int8.onnx"
        const val TOKENS_FILE = "tokens.txt"
        const val BASE_URL = "https://huggingface.co/csukuangfj/sherpa-onnx-nemo-ctc-giga-am-v2-russian-2025-04-19/resolve/main/"
        const val MODEL_BYTES_APPROX = 230_000_000L
    }
}

/** Выбор лучшего доступного распознавания и детектор конца фразы (Silero VAD). */
class BetterAsr(private val context: Context) {

    val giga = GigaAm(context)

    private val vadFile = File(context.filesDir, "silero_vad.onnx")

    private val vadBundled = runCatching { context.assets.list("vad")?.contains("silero_vad.onnx") == true }.getOrDefault(false)

    private fun ensureVad(): File? {
        if (vadFile.exists() && vadFile.length() > 0) return vadFile
        if (!vadBundled) return null
        return runCatching {
            context.assets.open("vad/silero_vad.onnx").use { input -> vadFile.outputStream().use { input.copyTo(it) } }
            vadFile
        }.getOrNull()
    }

    /** Есть ли что-то лучше встроенной маленькой модели. */
    fun available(): Boolean = (giga.isReady || SpeechKit.configured) && vadBundled

    fun newVad(): Vad? {
        val file = ensureVad() ?: return null
        return runCatching {
            Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = file.absolutePath,
                        threshold = 0.5f,
                        minSilenceDuration = 0.7f,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 20f,
                    ),
                    sampleRate = 16000,
                    numThreads = 1,
                    provider = "cpu",
                ),
            )
        }.getOrNull()
    }

    /** Сначала облачное Яндекс SpeechKit (если включено), затем офлайн GigaAM. */
    fun decode(samples: FloatArray): String? {
        if (SpeechKit.configured) SpeechKit.recognize(samples)?.let { return it }
        return giga.decode(samples)
    }
}
