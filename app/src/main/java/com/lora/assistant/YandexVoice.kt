package com.lora.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import java.util.concurrent.LinkedBlockingQueue

/**
 * Голос Яндекс SpeechKit (нужен ключ и интернет). Если сервис не ответил, сообщаем, сколько предложений уже
 * прозвучало, и остаток озвучивается офлайн-голосом (см. SpeechController).
 */
class YandexVoice {

    private val main = Handler(Looper.getMainLooper())
    @Volatile private var session: Speaker? = null
    @Volatile var rate: Float = 1.0f

    fun begin(onFinished: () -> Unit, onFailed: (spoken: Int) -> Unit) {
        stop()
        session = Speaker(onFinished, onFailed).also { it.start() }
    }

    fun add(text: String) {
        session?.texts?.offer(text)
    }

    fun end() {
        session?.texts?.offer(END)
    }

    fun stop() {
        session?.cancel()
        session = null
    }

    private inner class Speaker(private val onFinished: () -> Unit, private val onFailed: (Int) -> Unit) : Thread("yandex-tts") {
        val texts = LinkedBlockingQueue<String>()
        private val audio = LinkedBlockingQueue<Any>() // ByteArray, FAIL или END
        @Volatile private var cancelled = false
        @Volatile private var track: AudioTrack? = null

        fun cancel() {
            cancelled = true
            texts.offer(END)
            audio.offer(END)
            runCatching { track?.pause(); track?.flush() }
        }

        override fun run() {
            // Синтезатор готовит следующее предложение, пока проигрывается предыдущее.
            val synth = Thread {
                try {
                    while (!cancelled) {
                        val text = texts.take()
                        if (text == END || cancelled) break
                        val bytes = SpeechKit.synthesize(text, rate)
                        if (bytes == null) {
                            audio.offer(FAIL)
                            return@Thread
                        }
                        audio.offer(bytes)
                    }
                } catch (_: InterruptedException) {
                }
                audio.offer(END)
            }
            synth.start()

            var spoken = 0
            var failed = false
            var framesWritten = 0L
            try {
                while (!cancelled) {
                    val item = audio.take()
                    if (item === FAIL) { failed = true; break }
                    if (item !is ByteArray) break
                    val t = track ?: createTrack().also { track = it; it.play() }
                    t.write(item, 0, item.size, AudioTrack.WRITE_BLOCKING)
                    framesWritten += item.size / 2
                    spoken++
                }
                val t = track
                if (t != null && !cancelled) {
                    val deadline = System.currentTimeMillis() + 8000
                    while (!cancelled && t.playbackHeadPosition < framesWritten && System.currentTimeMillis() < deadline) sleep(30)
                }
            } catch (_: InterruptedException) {
            } finally {
                runCatching { track?.stop() }
                runCatching { track?.release() }
                track = null
                synth.interrupt()
                if (!cancelled) main.post { if (failed) onFailed(spoken) else onFinished() }
            }
        }

        private fun createTrack(): AudioTrack {
            val minBuffer = AudioTrack.getMinBufferSize(SpeechKit.TTS_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
            return AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SpeechKit.TTS_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build(),
                )
                .setBufferSizeInBytes(minBuffer * 4)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }
    }

    private companion object {
        const val END = "\u0000"
        val FAIL = Any()
    }
}
