package com.lora.assistant

import android.media.AudioAttributes
import android.media.MediaPlayer
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Один общий плеер для карточек с музыкой: одновременно играет только одна дорожка. */
object AudioPlayer {
    var currentUrl by mutableStateOf<String?>(null)
        private set
    var playing by mutableStateOf(false)
        private set
    var preparing by mutableStateOf(false)
        private set

    private var player: MediaPlayer? = null

    fun isCurrent(url: String) = currentUrl == url

    fun play(url: String) {
        if (currentUrl == url && player != null) {
            player?.start()
            playing = true
            return
        }
        stop()
        currentUrl = url
        preparing = true
        val p = MediaPlayer()
        player = p
        try {
            p.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            p.setDataSource(url)
            p.setOnPreparedListener {
                if (player === p) {
                    preparing = false
                    playing = true
                    it.start()
                }
            }
            p.setOnCompletionListener { if (player === p) stop() }
            p.setOnErrorListener { _, _, _ -> if (player === p) stop(); true }
            p.prepareAsync()
        } catch (_: Exception) {
            stop()
        }
    }

    fun pause() {
        player?.takeIf { it.isPlaying }?.pause()
        playing = false
    }

    fun toggle(url: String) {
        if (isCurrent(url) && playing) pause() else play(url)
    }

    fun stop() {
        val p = player
        player = null
        runCatching { p?.stop() }
        runCatching { p?.release() }
        currentUrl = null
        playing = false
        preparing = false
    }
}
