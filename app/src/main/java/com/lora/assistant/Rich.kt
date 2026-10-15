package com.lora.assistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Находит картинки, афиши фильмов, видео и свободную музыку для карточек в чате.
 * Источники без ключей: Википедия и Викисклад (Wikimedia Commons).
 */
class Rich {

    enum class Kind { NONE, AUDIO, PICTURES, VIDEO, MOVIES }

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build()

    fun kindOf(question: String): Kind {
        val q = question.lowercase(Locale.ROOT)
        val command = q.containsAny("включи", "поставь", "сыграй", "запусти", "проиграй", "послушать", "послушаем", "play ")
        val music = q.containsAny("симфон", "музык", "песн", "мелоди", "сонат", "концерт", "вальс", "опер", "классик", "реквием",
            "бах", "моцарт", "бетховен", "чайковск", "шопен", "вивальди", "григ", "шуберт", "вагнер", "рахманинов", "music", "song")
        return when {
            command && music -> Kind.AUDIO
            q.containsAny("видео", "ролик", "трейлер", "клип") -> Kind.VIDEO
            q.containsAny("фильм", "сериал", "мультфильм", "кинофильм") &&
                q.containsAny("посоветуй", "подборк", "что посмотреть", "найди", "покажи", "порекомендуй", "лучшие", "топ ", "про ") -> Kind.MOVIES
            q.containsAny("картинк", "фото", "изображен", "покажи ") -> Kind.PICTURES
            else -> Kind.NONE
        }
    }

    /** Свободная запись для запроса «включи симфонию». null — подходящей нет. */
    fun audio(question: String): AudioCard? {
        val query = englishTerms(subject(question, AUDIO_WORDS))
        val files = commons("$query filetype:audio", limit = 12)
        val pick = files.filter { it.mime.startsWith("audio") || it.mime == "application/ogg" }.let { list ->
            list.firstOrNull() ?: return null
        }
        return AudioCard(pick.title, pick.credit, pick.url)
    }

    fun pictures(question: String): GalleryCard? {
        val subject = subject(question, PICTURE_WORDS)
        if (subject.isBlank()) return null
        val tiles = commons("$subject filetype:bitmap", limit = 8)
            .filter { it.thumb != null }
            .take(6)
            .map { Tile(it.title, it.credit, it.thumb, it.page) }
        return tiles.takeIf { it.isNotEmpty() }?.let { GalleryCard("Фото: $subject", it) }
    }

    /** Превью роликов и кнопка поиска в YouTube (само видео открывается в плеере телефона). */
    fun video(question: String): List<Card> {
        val subject = subject(question, VIDEO_WORDS).ifBlank { question }
        val cards = mutableListOf<Card>()
        val clips = commons("$subject filetype:video", limit = 6).filter { it.thumb != null }.take(4)
        if (clips.isNotEmpty()) {
            cards += GalleryCard("Видео: $subject", clips.map { Tile(it.title, it.credit, it.thumb, it.url) })
        }
        cards += youtube(subject, "Смотреть на YouTube")
        return cards
    }

    fun movies(question: String): List<Card> {
        val subject = subject(question, MOVIE_WORDS).ifBlank { "фильмы" }
        val url = "https://ru.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", "$subject фильм")
            .addQueryParameter("gsrlimit", "8")
            .addQueryParameter("prop", "pageimages|extracts").addQueryParameter("piprop", "thumbnail")
            .addQueryParameter("pithumbsize", "360").addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1").addQueryParameter("exsentences", "2").addQueryParameter("exlimit", "8")
            .build()
        val pages = runCatching { getJson(url.toString()).optJSONObject("query")?.optJSONObject("pages") }.getOrNull()
        val tiles = pages?.let { p ->
            p.keys().asSequence().map { p.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }
                .filter { it.has("thumbnail") }
                .take(6)
                .map { page ->
                    val title = page.getString("title")
                    Tile(
                        title, page.optString("extract").take(140),
                        page.getJSONObject("thumbnail").getString("source"),
                        "https://ru.wikipedia.org/wiki/" + URLEncoder.encode(title.replace(' ', '_'), "UTF-8"),
                    )
                }.toList()
        }.orEmpty()
        val cards = mutableListOf<Card>()
        if (tiles.isNotEmpty()) cards += GalleryCard("Фильмы: $subject", tiles)
        cards += youtube("$subject трейлер", "Трейлеры на YouTube")
        return cards
    }

    fun youtube(query: String, title: String = "Искать в YouTube") =
        LinkCard(title, query, "https://www.youtube.com/results?search_query=" + URLEncoder.encode(query, "UTF-8"))

    // ---------- Викисклад ----------

    private data class CommonsFile(val title: String, val url: String, val thumb: String?, val mime: String, val credit: String, val page: String)

    private fun commons(search: String, limit: Int): List<CommonsFile> = runCatching {
        val url = "https://commons.wikimedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query").addQueryParameter("format", "json")
            .addQueryParameter("generator", "search").addQueryParameter("gsrsearch", search)
            .addQueryParameter("gsrnamespace", "6").addQueryParameter("gsrlimit", limit.toString())
            .addQueryParameter("prop", "imageinfo").addQueryParameter("iiprop", "url|mime|extmetadata")
            .addQueryParameter("iiurlwidth", "400")
            .build()
        val pages = getJson(url.toString()).optJSONObject("query")?.optJSONObject("pages") ?: return emptyList()
        pages.keys().asSequence().map { pages.getJSONObject(it) }.sortedBy { it.optInt("index", 99) }.mapNotNull { page ->
            val info = page.optJSONArray("imageinfo")?.optJSONObject(0) ?: return@mapNotNull null
            val meta = info.optJSONObject("extmetadata")
            val name = page.getString("title").removePrefix("File:").substringBeforeLast('.')
            val license = meta?.optJSONObject("LicenseShortName")?.optString("value").orEmpty()
            CommonsFile(
                title = name.replace('_', ' '),
                url = info.getString("url"),
                thumb = info.optString("thumburl").ifBlank { null },
                mime = info.optString("mime"),
                credit = listOf("Wikimedia Commons", license).filter { it.isNotBlank() }.joinToString(", "),
                page = info.optString("descriptionurl").ifBlank { info.getString("url") },
            )
        }.toList()
    }.getOrDefault(emptyList())

    private fun getJson(url: String): JSONObject {
        val request = Request.Builder().url(url).header("User-Agent", "Lora/1.5 (Android voice assistant)").build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            return JSONObject(r.body?.string().orEmpty())
        }
    }

    // ---------- Разбор запроса ----------

    private fun subject(question: String, stop: Set<String>): String =
        question.lowercase(Locale.ROOT).replace(Regex("[?!.,«»\"]"), " ").split(" ")
            .filter { it.isNotBlank() && it !in stop && it !in COMMON_STOP }
            .joinToString(" ")

    private fun englishTerms(subject: String): String {
        var s = subject
        for ((ru, en) in RU_TO_EN) s = s.replace(ru, en)
        return s.trim().ifBlank { "classical music" }
    }

    private fun String.containsAny(vararg parts: String) = parts.any { contains(it) }

    private companion object {
        val COMMON_STOP = setOf("лора", "мне", "пожалуйста", "какой-нибудь", "какую-нибудь", "что-нибудь", "нибудь", "а", "и", "про", "о", "об", "на", "в", "с")
        val AUDIO_WORDS = setOf("включи", "поставь", "сыграй", "запусти", "проиграй", "послушать", "послушаем", "хочу", "музыку", "музыка", "песню", "песня")
        val PICTURE_WORDS = setOf("покажи", "картинку", "картинки", "фото", "фотографию", "фотографии", "изображение", "изображения", "найди", "как", "выглядит")
        val VIDEO_WORDS = setOf("покажи", "видео", "ролик", "трейлер", "клип", "найди", "включи", "поставь")
        val MOVIE_WORDS = setOf("посоветуй", "порекомендуй", "подборку", "подборка", "найди", "покажи", "что", "посмотреть", "хороший", "фильм", "фильмы", "сериал", "сериалы", "мультфильм", "мультфильмы", "кино", "лучшие", "топ", "про")
        // Названия на Викискладе в основном английские.
        val RU_TO_EN = listOf(
            "симфонию" to "symphony", "симфония" to "symphony", "симфонии" to "symphony", "сонату" to "sonata", "соната" to "sonata",
            "концерт" to "concerto", "вальс" to "waltz", "классическую" to "classical", "классику" to "classical", "классика" to "classical",
            "бетховена" to "Beethoven", "бетховен" to "Beethoven", "моцарта" to "Mozart", "моцарт" to "Mozart", "баха" to "Bach", "бах" to "Bach",
            "чайковского" to "Tchaikovsky", "чайковский" to "Tchaikovsky", "шопена" to "Chopin", "шопен" to "Chopin",
            "вивальди" to "Vivaldi", "грига" to "Grieg", "григ" to "Grieg", "шуберта" to "Schubert", "шуберт" to "Schubert",
            "вагнера" to "Wagner", "вагнер" to "Wagner", "рахманинова" to "Rachmaninoff", "рахманинов" to "Rachmaninoff",
            "лунную" to "Moonlight", "времена года" to "Four Seasons", "оперу" to "opera", "опера" to "opera", "реквием" to "requiem",
            "мелодию" to "melody", "музыку" to "music", "музыка" to "music",
        )
    }
}
