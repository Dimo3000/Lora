package com.lora.assistant

import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit

/**
 * Официальный Yandex SpeechKit (Yandex Cloud): распознавание и синтез речи по API-ключу. Платный сервис.
 * Ключ и идентификатор каталога те же, что для Яндекс Поиска (у ключа должны быть роли SpeechKit).
 */
object SpeechKit {

    private val http = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    val configured get() = SearchConfig.cloudSpeech && SearchConfig.yandexKey.isNotBlank() && SearchConfig.yandexFolder.isNotBlank()

    const val TTS_RATE = 24000

    /** Речь (16 кГц, моно) в текст. null — не получилось. */
    fun recognize(samples: FloatArray): String? = runCatching {
        val seconds = 28
        val used = if (samples.size > 16000 * seconds) samples.copyOf(16000 * seconds) else samples
        val pcm = ByteBuffer.allocate(used.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        used.forEach { pcm.putShort((it.coerceIn(-1f, 1f) * 32767).toInt().toShort()) }
        val url = "https://stt.api.cloud.yandex.net/speech/v1/stt:recognize".toHttpUrl().newBuilder()
            .addQueryParameter("lang", "ru-RU").addQueryParameter("topic", "general")
            .addQueryParameter("format", "lpcm").addQueryParameter("sampleRateHertz", "16000")
            .addQueryParameter("folderId", SearchConfig.yandexFolder)
            .build()
        val request = Request.Builder().url(url)
            .header("Authorization", "Api-Key ${SearchConfig.yandexKey}")
            .post(pcm.array().toRequestBody("application/octet-stream".toMediaType()))
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            JSONObject(r.body?.string().orEmpty()).optString("result").trim().ifBlank { null }
        }
    }.getOrNull()

    /** Текст в речь: сырые 16-битные отсчёты, 24 кГц, моно. null — не получилось. */
    fun synthesize(text: String, speed: Float): ByteArray? = runCatching {
        val body = FormBody.Builder()
            .add("text", text.take(4000)).add("lang", "ru-RU")
            .add("voice", "alena").add("emotion", "good")
            .add("speed", speed.coerceIn(0.5f, 2f).toString())
            .add("format", "lpcm").add("sampleRateHertz", TTS_RATE.toString())
            .add("folderId", SearchConfig.yandexFolder)
            .build()
        val request = Request.Builder().url("https://tts.api.cloud.yandex.net/speech/v1/tts:synthesize")
            .header("Authorization", "Api-Key ${SearchConfig.yandexKey}")
            .post(body)
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) return null
            r.body?.bytes()?.takeIf { it.size > 200 }
        }
    }.getOrNull()
}
