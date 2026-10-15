package com.lora.assistant

import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

/** Обычный поиск в интернете без ключей: результаты DuckDuckGo (запасной вариант Bing) и тексты страниц. */
class WebSearch {

    data class Hit(val title: String, val snippet: String, val url: String)

    private val http = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun search(query: String, limit: Int = 5): List<Hit> {
        val hits = runCatching { duckDuckGo(query) }.getOrDefault(emptyList())
            .ifEmpty { runCatching { bing(query) }.getOrDefault(emptyList()) }
        return hits.take(limit)
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
