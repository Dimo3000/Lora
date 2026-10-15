package com.lora.assistant

import android.content.Context

data class Option(val id: String, val title: String)

data class AppSettings(
    val city: String = "Москва",
    val language: String = LANGUAGES.first().id,
    val autoListen: Boolean = false,
    /** Откликаться на имя «Лора», пока приложение открыто. */
    val wakeWord: Boolean = false,
    /** Яндекс Поиск по API (необязательно): ключ и идентификатор каталога из Yandex Cloud. */
    val yandexKey: String = "",
    val yandexFolder: String = "",
    /** Скорость речи: 0.8 медленнее, 1.2 быстрее. */
    val speechRate: Float = 1.0f,
) {
    companion object {
        val LANGUAGES = listOf(
            Option("ru-RU", "Русский"),
            Option("en-US", "English"),
        )
    }
}

/** Настройки хранятся локально на телефоне (SharedPreferences). */
class SettingsStore(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    fun load() = AppSettings(
        city = prefs.getString("city", null)?.ifBlank { null } ?: AppSettings().city,
        language = prefs.getString("language", null) ?: AppSettings.LANGUAGES.first().id,
        autoListen = prefs.getBoolean("auto_listen", false),
        wakeWord = prefs.getBoolean("wake_word", false),
        yandexKey = prefs.getString("yandex_key", "") ?: "",
        yandexFolder = prefs.getString("yandex_folder", "") ?: "",
        speechRate = prefs.getFloat("speech_rate", 1.0f),
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("city", settings.city.trim())
            .putString("language", settings.language)
            .putBoolean("auto_listen", settings.autoListen)
            .putBoolean("wake_word", settings.wakeWord)
            .putString("yandex_key", settings.yandexKey.trim())
            .putString("yandex_folder", settings.yandexFolder.trim())
            .putFloat("speech_rate", settings.speechRate)
            .apply()
    }
}
