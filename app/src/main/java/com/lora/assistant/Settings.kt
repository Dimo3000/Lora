package com.lora.assistant

import android.content.Context

data class Option(val id: String, val title: String)

data class AppSettings(
    val city: String = "Москва",
    val language: String = LANGUAGES.first().id,
    val autoListen: Boolean = false,
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
    )

    fun save(settings: AppSettings) {
        prefs.edit()
            .putString("city", settings.city.trim())
            .putString("language", settings.language)
            .putBoolean("auto_listen", settings.autoListen)
            .apply()
    }
}
