package com.lora.assistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Ключ Яндекс Поиска из настроек (общий для всех поисков в приложении). */
object SearchConfig {
    @Volatile var yandexKey: String = ""
    @Volatile var yandexFolder: String = ""
    /** Использовать Yandex SpeechKit для распознавания и голоса (если задан ключ). */
    @Volatile var cloudSpeech: Boolean = true
}

/** Обычный поиск в интернете без ключей: результаты DuckDuckGo (запасной вариант Bing) и тексты страниц. */
class WebSearch {

    data class Hit(val title: String, val snippet: String, val url: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun search(query: String, limit: Int = 5): List<Hit> {
        val hits = runCatching { yandex(query) }.getOrDefault(emptyList())
            .ifEmpty { runCatching { duckDuckGo(query) }.getOrDefault(emptyList()) }
            .ifEmpty { runCatching { bing(query) }.getOrDefault(emptyList()) }
        return hits.take(limit)
    }

    /** Официальный Yandex Search API (Yandex Cloud): нужен ключ и идентификатор каталога. */
    private fun yandex(query: String): List<Hit> {
        val key = SearchConfig.yandexKey
        val folder = SearchConfig.yandexFolder
        if (key.isBlank() || folder.isBlank()) return emptyList()
        val body = org.json.JSONObject()
            .put("query", org.json.JSONObject().put("searchType", "SEARCH_TYPE_RU").put("queryText", query))
            .put("folderId", folder)
            .put("responseFormat", "FORMAT_XML")
            .put("userAgent", "Lora/2.0")
            .toString()
        val request = Request.Builder().url("https://searchapi.api.cloud.yandex.net/v2/web/search")
            .header("Authorization", "Api-Key $key")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val raw = http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("Yandex ${r.code}")
            org.json.JSONObject(r.body?.string().orEmpty()).optString("rawData")
        }
        val xml = String(android.util.Base64.decode(raw, android.util.Base64.DEFAULT), Charsets.UTF_8)
        return Regex("<doc[ >].*?</doc>", RegexOption.DOT_MATCHES_ALL).findAll(xml).mapNotNull { m ->
            val doc = m.value
            val url = Regex("<url>(.*?)</url>", RegexOption.DOT_MATCHES_ALL).find(doc)?.groupValues?.get(1)?.let(::clean) ?: return@mapNotNull null
            val title = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(doc)?.groupValues?.get(1)?.let(::clean).orEmpty()
            val snippet = Regex("<passage>(.*?)</passage>", RegexOption.DOT_MATCHES_ALL).find(doc)?.groupValues?.get(1)?.let(::clean)
                ?: Regex("<headline>(.*?)</headline>", RegexOption.DOT_MATCHES_ALL).find(doc)?.groupValues?.get(1)?.let(::clean).orEmpty()
            Hit(title.ifBlank { url }, snippet, url)
        }.toList()
    }

    /** Ищет на страницах результатов готовый рецепт (разметка schema.org/Recipe, она есть почти на всех кулинарных сайтах). */
    fun recipe(hits: List<Hit>): RecipeCard? {
        for (hit in hits.take(5)) {
            val html = runCatching { get(hit.url, maxBytes = 800_000) }.getOrNull() ?: continue
            for (m in Regex("<script[^>]+application/ld\\+json[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL).findAll(html)) {
                val json = runCatching { org.json.JSONTokener(m.groupValues[1].trim()).nextValue() }.getOrNull() ?: continue
                val node = findRecipe(json) ?: continue
                val ingredients = node.optJSONArray("recipeIngredient")?.let { a -> (0 until a.length()).map { clean(a.optString(it)) } }.orEmpty()
                val steps = steps(node.opt("recipeInstructions"))
                if (ingredients.isNotEmpty() && steps.isNotEmpty()) {
                    return RecipeCard(clean(node.optString("name")).ifBlank { hit.title }, ingredients, steps, hit.url)
                }
            }
        }
        return null
    }

    private fun findRecipe(node: Any?): org.json.JSONObject? = when (node) {
        is org.json.JSONArray -> (0 until node.length()).firstNotNullOfOrNull { findRecipe(node.opt(it)) }
        is org.json.JSONObject -> {
            val type = node.opt("@type")
            val isRecipe = (type is String && type == "Recipe") ||
                (type is org.json.JSONArray && (0 until type.length()).any { type.optString(it) == "Recipe" })
            if (isRecipe) node else findRecipe(node.opt("@graph"))
        }
        else -> null
    }

    private fun steps(node: Any?): List<String> = when (node) {
        is String -> node.split(Regex("\\n+|(?<=\\.)\\s+(?=[А-ЯA-Z])")).map(::clean).filter { it.isNotBlank() }
        is org.json.JSONArray -> (0 until node.length()).flatMap { steps(node.opt(it)) }
        is org.json.JSONObject -> when {
            node.has("itemListElement") -> steps(node.opt("itemListElement"))
            node.has("text") -> listOf(clean(node.optString("text")))
            else -> emptyList()
        }
        else -> emptyList()
    }

    /** Случайный свежий анекдот с anekdot.ru. */
    fun joke(): String? = runCatching {
        val xml = get("https://www.anekdot.ru/rss/export_j.xml")
        Regex("<description>(.*?)</description>", RegexOption.DOT_MATCHES_ALL).findAll(xml)
            .map { clean(it.groupValues[1].replace("<![CDATA[", "").replace("]]>", "").replace("<br>", " ").replace("<br/>", " ")) }
            .filter { it.length in 40..700 }
            .toList()
            .randomOrNull()
    }.getOrNull()

    /** Основной текст страницы (обрезанный), чтобы нейросеть могла составить подборку. */
    fun pageText(url: String, maxChars: Int = 2000): String? = runCatching {
        val html = get(url, maxBytes = 400_000)
        val body = html
            .replace(Regex("(?is)<(script|style|nav|footer|header|noscript|svg)[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<!--.*?-->"), " ")
        clean(body).take(maxChars).ifBlank { null }
    }.getOrNull()

    private fun duckDuckGo(query: String): List<Hit> {
        val url = "https://html.duckduckgo.com/html/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("kl", "ru-ru").build()
        val html = get(url.toString())
        val links = Regex("class=\"result__a\"[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        val snippets = Regex("class=\"result__snippet\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).findAll(html).toList()
        return links.mapIndexedNotNull { i, m ->
            val title = clean(m.groupValues[2])
            if (title.isBlank()) return@mapIndexedNotNull null
            Hit(title, snippets.getOrNull(i)?.let { clean(it.groupValues[1]) }.orEmpty(), realUrl(m.groupValues[1]))
        }.filter { !it.url.contains("duckduckgo.com/y.js") }
    }

    private fun bing(query: String): List<Hit> {
        val url = "https://www.bing.com/search".toHttpUrl().newBuilder()
            .addQueryParameter("q", query).addQueryParameter("setlang", "ru").build()
        val html = get(url.toString())
        return Regex("<li class=\"b_algo\".*?</li>", RegexOption.DOT_MATCHES_ALL).findAll(html).mapNotNull { block ->
            val text = block.value
            val link = Regex("<h2[^>]*><a[^>]*href=\"([^\"]+)\"[^>]*>(.*?)</a>", RegexOption.DOT_MATCHES_ALL).find(text) ?: return@mapNotNull null
            val snippet = Regex("<p[^>]*>(.*?)</p>", RegexOption.DOT_MATCHES_ALL).find(text)?.let { clean(it.groupValues[1]) }.orEmpty()
            Hit(clean(link.groupValues[2]), snippet, link.groupValues[1])
        }.toList()
    }

    private fun realUrl(href: String): String {
        val target = Regex("[?&]uddg=([^&]+)").find(href)?.groupValues?.get(1) ?: return href
        return runCatching { URLDecoder.decode(target, "UTF-8") }.getOrDefault(href)
    }

    private fun get(url: String, maxBytes: Long = 1_000_000): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Lora/1.4 (Android voice assistant)")
            .header("Accept-Language", "ru-RU,ru;q=0.9,en;q=0.5")
            .build()
        http.newCall(request).execute().use { r ->
            if (!r.isSuccessful) error("HTTP ${r.code}")
            val source = r.body?.source() ?: return ""
            source.request(maxBytes)
            return source.buffer.readUtf8(minOf(source.buffer.size, maxBytes))
        }
    }

    private fun clean(html: String): String = html
        .replace(Regex("(?s)<[^>]+>"), " ")
        .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#x27;", "'").replace("&#39;", "'")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&laquo;", "«").replace("&raquo;", "»").replace("&mdash;", "—")
        .replace(Regex("\\s+"), " ").trim()
}
