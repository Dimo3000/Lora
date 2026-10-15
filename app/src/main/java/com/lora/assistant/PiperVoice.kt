package com.lora.assistant

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.File
import java.util.concurrent.LinkedBlockingQueue

/**
 * Живой женский голос (Piper, русская «Ирина»), работает офлайн через sherpa-onnx.
 * Файлы голоса лежат в assets/voice-ru, при первом запуске копируются во внутреннюю память.
 */
class PiperVoice(private val context: Context) {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var tts: OfflineTts? = null
    @Volatile private var session: Speaker? = null

    /** Голос встроен в APK (его кладёт скрипт scripts/fetch-sherpa.sh при сборке). */
    val isBundled: Boolean = runCatching { context.assets.list(ASSET_DIR)?.isNotEmpty() == true }.getOrDefault(false)

    val isReady get() = tts != null

    /** Загружает голос в фоне. onDone(false) — голос не удалось поднять, тогда используем системный. */
    fun load(onDone: (Boolean) -> Unit) {
        if (!isBundled) {
            onDone(false)
            return
        }
        Thread {
            val ok = runCatching {
                val dir = File(context.filesDir, ASSET_DIR)
                val marker = File(dir, ".copied")
                if (!marker.exists()) {
                    dir.deleteRecursively()
                    copyAssetDir(ASSET_DIR, dir)
                    marker.writeText("ok")
                }
                val onnx = dir.listFiles { f -> f.name.endsWith(".onnx") }?.firstOrNull()
                    ?: error("в папке голоса нет файла модели .onnx")
                val config = OfflineTtsConfig(
                    model = OfflineTtsModelConfig(
                        vits = OfflineTtsVitsModelConfig(
                            model = onnx.absolutePath,
                            tokens = File(dir, "tokens.txt").absolutePath,
                            dataDir = File(dir, "espeak-ng-data").absolutePath,
                            noiseScale = 0.75f,
                            lengthScale = 1.08f,
                        ),
                        numThreads = 2,
                        debug = false,
                        provider = "cpu",
                    ),
                )
                tts = OfflineTts(assetManager = null, config = config)
            }.isSuccess
            main.post { onDone(ok) }
        }.start()
    }

    /** Начинает новую реплику. Предыдущая, если ещё звучит, обрывается. */
    fun begin(onFinished: () -> Unit) {
        stop()
        val engine = tts ?: return
        session = Speaker(engine, onFinished).also { it.start() }
    }

    fun add(text: String) {
        session?.queue?.offer(text)
    }

    /** Больше предложений не будет: когда доиграем очередь, вызовем onFinished. */
    fun end() {
        session?.queue?.offer(END)
    }

    fun stop() {
        session?.cancel()
        session = null
    }

    fun release() {
        stop()
        tts?.release()
        tts = null
    }

    private inner class Speaker(private val engine: OfflineTts, private val onFinished: () -> Unit) : Thread("piper") {
        val queue = LinkedBlockingQueue<String>()
        @Volatile private var cancelled = false
        @Volatile private var track: AudioTrack? = null

        fun cancel() {
            cancelled = true
            queue.offer(END)
            runCatching { track?.pause(); track?.flush() }
        }

        override fun run() {
            var frames = 0L
            try {
                while (true) {
                    val text = queue.take()
                    if (text == END || cancelled) break
                    val audio = engine.generate(text, 0, 1.0f)
                    if (cancelled) break
                    if (audio.samples.isEmpty()) continue
                    val t = track ?: createTrack(audio.sampleRate).also { track = it; it.play() }
                    t.write(audio.samples, 0, audio.samples.size, AudioTrack.WRITE_BLOCKING)
                    frames += audio.samples.size
                }
                val t = track
                if (t != null && !cancelled) {
                    // Ждём, пока доиграет последнее предложение.
                    val deadline = System.currentTimeMillis() + 5000
                    while (!cancelled && t.playbackHeadPosition < frames && System.currentTimeMillis() < deadline) sleep(30)
                }
            } catch (_: InterruptedException) {
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                track = null
                if (!cancelled) main.post { onFinished() }
            }
        }

        private fun createTrack(sampleRate: Int): AudioTrack {
            val minBuffer = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    private fun copyAssetDir(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input -> target.outputStream().use { input.copyTo(it) } }
        } else {
            target.mkdirs()
            children.forEach { copyAssetDir("$assetPath/$it", File(target, it)) }
        }
    }

    private companion object {
        const val ASSET_DIR = "voice-ru"
        const val END = "\u0000" // метка конца очереди
    }
}
