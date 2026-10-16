package com.lora.assistant

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import org.vosk.Recognizer
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Слушает микрофон сам: маленькая модель Vosk показывает слова по ходу речи и даёт отпечаток голоса,
 * детектор Silero находит конец фразы, а итоговый текст распознаёт сильная модель (SpeechKit или GigaAM).
 */
class HybridRecognizer(
    private val listener: SpeechController.Listener,
    private val vosk: VoskRecognizer,
    private val asr: BetterAsr,
) {
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var cancelled = false
    @Volatile private var finishRequested = false
    @Volatile private var worker: Thread? = null

    fun start() {
        val model = vosk.loadedModel
        if (model == null) {
            listener.onListenError("Встроенное распознавание ещё загружается, попробуйте через пару секунд.")
            return
        }
        cancel()
        cancelled = false
        finishRequested = false
        listener.onListeningStarted()
        worker = Thread { run(model) }.also { it.start() }
    }

    fun finish() {
        finishRequested = true
    }

    fun cancel() {
        cancelled = true
        worker = null
    }

    @SuppressLint("MissingPermission")
    private fun run(model: org.vosk.Model) {
        val minBuffer = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(minBuffer, RATE) * 2,
        )
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            main.post { listener.onListenError("Не удалось включить микрофон.") }
            return
        }
        val recognizer = Recognizer(model, RATE.toFloat())
        vosk.speaker?.let { recognizer.setSpeakerModel(it) }
        val vad = asr.newVad()

        val all = FloatArray(RATE * MAX_SECONDS)
        var total = 0
        val chunk = ShortArray(1600)
        val window = FloatArray(512)
        var windowFill = 0
        var segment: FloatArray? = null
        var lastPartial = ""
        val voskTexts = mutableListOf<String>()
        var bestVector: DoubleArray? = null
        var bestFrames = 0
        val started = System.currentTimeMillis()
        var speechSeen = false

        fun takeResult(json: String) {
            val obj = runCatching { JSONObject(json) }.getOrNull() ?: return
            obj.optString("text").trim().takeIf { it.isNotBlank() }?.let { voskTexts += it }
            val frames = obj.optInt("spk_frames", 0)
            val arr = obj.optJSONArray("spk")
            if (arr != null && frames > bestFrames) {
                bestFrames = frames
                bestVector = DoubleArray(arr.length()) { arr.getDouble(it) }
            }
        }

        try {
            record.startRecording()
            while (!cancelled) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) break
                if (recognizer.acceptWaveForm(chunk, n)) takeResult(recognizer.result)
                else {
                    val partial = runCatching { JSONObject(recognizer.partialResult).optString("partial") }.getOrDefault("")
                    if (partial.isNotBlank() && partial != lastPartial) {
                        lastPartial = partial
                        main.post { if (!cancelled) listener.onPartialResult(partial) }
                    }
                }
                var sum = 0.0
                for (i in 0 until n) {
                    val f = chunk[i] / 32768f
                    sum += f * f
                    if (total < all.size) all[total++] = f
                    window[windowFill++] = f
                    if (windowFill == window.size) {
                        vad?.acceptWaveform(window)
                        windowFill = 0
                    }
                }
                val level = (sqrt(sum / n) * 8).toFloat().coerceIn(0f, 1f)
                main.post { listener.onSoundLevel(level) }

                if (vad != null) {
                    if (vad.isSpeechDetected()) speechSeen = true
                    if (!vad.empty()) {
                        segment = vad.front().samples
                        vad.pop()
                        break
                    }
                } else if (lastPartial.isNotBlank() && abs(level) < 0.02f && voskTexts.isNotEmpty()) {
                    break
                }
                if (finishRequested) break
                val elapsed = System.currentTimeMillis() - started
                if (!speechSeen && lastPartial.isBlank() && elapsed > NO_SPEECH_MS) break
                if (total >= all.size) break
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
        if (cancelled) {
            runCatching { recognizer.close() }
            vad?.release()
            return
        }
        takeResult(recognizer.finalResult)
        runCatching { recognizer.close() }
        vad?.release()

        val samples = segment ?: all.copyOf(total)
        val voskText = voskTexts.joinToString(" ").ifBlank { lastPartial }
        val enough = samples.size > RATE / 3 && (segment != null || voskText.isNotBlank())
        val text = if (enough) (asr.decode(samples) ?: voskText) else ""
        main.post {
            if (cancelled) return@post
            if (text.isBlank()) {
                listener.onListenError("Не расслышала, попробуйте ещё раз.")
            } else {
                listener.onSpeakerVector(bestVector, bestFrames)
                listener.onFinalResult(text)
            }
        }
    }

    private companion object {
        const val RATE = 16000
        const val MAX_SECONDS = 28
        const val NO_SPEECH_MS = 8000L
    }
}
