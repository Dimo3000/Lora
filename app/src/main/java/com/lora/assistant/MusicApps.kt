package com.lora.assistant

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.provider.MediaStore

/**
 * Просит установленное музыкальное приложение самому включить музыку по запросу. Это штатная команда
 * Android («играть по поиску»), которой пользуются все голосовые помощники. Подписка и вход остаются в приложении.
 */
object MusicApps {

    private val apps = listOf(
        "ru.yandex.music" to "Яндекс Музыке",
        "com.uma.musicvk" to "VK Музыке",
        "com.google.android.apps.youtube.music" to "YouTube Music",
    )

    /** Возвращает название приложения, в котором запустили музыку, или null, если ни одно не подошло. */
    fun play(context: Context, query: String): String? {
        for ((pkg, label) in apps) {
            val intent = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .setPackage(pkg)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(SearchManager.QUERY, query)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val resolvable = context.packageManager.queryIntentActivities(intent, 0).isNotEmpty()
            if (!resolvable) continue
            if (runCatching { context.startActivity(intent) }.isSuccess) return label
        }
        return null
    }
}
