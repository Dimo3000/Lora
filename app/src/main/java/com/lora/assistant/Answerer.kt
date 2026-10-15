package com.lora.assistant

import android.util.Xml
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class AnswerException(message: String) : Exception(message)

/**
 * Отвечает на вопросы без ключей API и без нейросетей:
 * погода (Open-Meteo), курсы валют (ЦБ РФ), новости (Google News RSS),
 * время и дата (телефон), всё остальное — Википедия, затем DuckDuckGo.
 */
class Answerer(
    private val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build(),
) {
    /** Город из последнего вопроса о погоде, чтобы работало «а завтра?». */
    private var lastCity: String? = null

    suspend fun answer(question: String, language: String, defaultCity: String): String =
        withContext(Dispatchers.IO) {
            val en = language.startsWith("en")
            val q = question.lowercase(Locale.ROOT).trim()
            try {
                when {
                    q.containsAny("погод", "температур", "градус", "дожд", "снег", "weather", "temperature", "rain") ||
                        (lastCity != null && q.containsAny("а завтра", "а послезавтра", "а сегодня", "and tomorrow")) ->
                        weather(q, en, defaultCity)
                    q.containsAny("курс", "доллар", "евро", "юан", "валют", "exchange rate", "dollar", "euro") ->
                        currency(q, en)
                    q.containsAny("новост", "news", "что происходит", "что нового") -> news(q, en)
                    q.containsAny("который час", "сколько времени", "what time", "время сейчас") -> time(en)
                    q.containsAny("какое сегодня число", "какой сегодня день", "какое число", "какая дата",
                        "what day", "what's the date", "what is the date", "today's date") -> date(en)
                    else -> facts(question, en)
                }
            } catch (e: IOException) {
                throw AnswerException(if (en) "No internet connection." else "Нет связи с интернетом.")
            }
        }

    // ---------- Погода ----------

    private fun weather(q: String, en: Boolean, defaultCity: String): String {
        val cityWord = extractCity(q)
        val place = cityWord?.let { geocode(it, en) }
            ?: lastCity?.let { geocode(it, en) }
            ?: geocode(defaultCity, en)
            ?: throw AnswerException(
                if (en) "I couldn't find that city." else "Не нашла такой город. Попробуйте сказать, например: погода в Казани.",
            )
        lastCity = place.name

        val url = "https://api.open-meteo.com/v1/forecast".toHttpUrl().newBuilder()
            .addQueryParameter("latitude", place.lat.toString())
            .addQueryParameter("longitude", place.lon.toString())
            .addQueryParameter("current", "temperature_2m,apparent_temperature,weather_code,wind_speed_10m")
            .addQueryParameter("daily", "temperature_2m_max,temperature_2m_min,weather_code,precipitation_probability_max")
            .addQueryParameter("wind_speed_unit", "ms")
            .addQueryParameter("timezone", "auto")
            .addQueryParameter("forecast_days", "3")
            .build()
        val json = getJson(url)
        val daily = json.getJSONObject("daily")
        val day = when {
            q.contains("послезавтра") || q.contains("day after tomorrow") -> 2
            q.contains("завтра") || q.contains("tomorrow") -> 1
            else -> 0
        }
        val max = daily.getJSONArray("temperature_2m_max").getDouble(day).roundToInt()
        val min = daily.getJSONArray("temperature_2m_min").getDouble(day).roundToInt()
        val code = daily.getJSONArray("weather_code").getInt(day)
        val rain = daily.optJSONArray("precipitation_probability_max")?.optInt(day, -1) ?: -1

        if (day == 0) {
            val cur = json.getJSONObject("current")
            val t = cur.getDouble("temperature_2m").roundToInt()
            val feels = cur.getDouble("apparent_temperature").roundToInt()
            val wind = cur.getDouble("wind_speed_10m").roundToInt()
            val now = weatherText(cur.getInt("weather_code"), en)
            return if (en) {
                "Right now in ${place.name} it's ${t}°, feels like ${feels}°, $now, wind $wind m/s. " +
                    "Today from $min to $max degrees" + rainText(rain, en) + "."
            } else {
                "Сейчас в городе ${place.name} ${degrees(t)}, ощущается как ${degrees(feels)}, $now, ветер $wind м/с. " +
                    "Днём от $min до ${degrees(max)}" + rainText(rain, en) + "."
            }
        }
        val whenText = if (en) (if (day == 1) "Tomorrow" else "The day after tomorrow")
        else (if (day == 1) "Завтра" else "Послезавтра")
        return if (en) {
            "$whenText in ${place.name}: ${weatherText(code, en)}, from $min to $max degrees" + rainText(rain, en) + "."
        } else {
            "$whenText в городе ${place.name}: ${weatherText(code, en)}, от $min до ${degrees(max)}" + rainText(rain, en) + "."
        }
    }

    private data class Place(val name: String, val lat: Double, val lon: Double)

    private fun geocode(name: String, en: Boolean): Place? {
        for (candidate in cityCandidates(name)) {
            val url = "https://geocoding-api.open-meteo.com/v1/search".toHttpUrl().newBuilder()
                .addQueryParameter("name", candidate)
                .addQueryParameter("count", "1")
                .addQueryParameter("language", if (en) "en" else "ru")
                .build()
            val r = getJson(url).optJSONArray("results")?.optJSONObject(0) ?: continue
            return Place(r.getString("name"), r.getDouble("latitude"), r.getDouble("longitude"))
        }
        return null
    }

    /** «погода в Нижнем Новгороде завтра» -> «нижнем новгороде». */
    private fun extractCity(q: String): String? {
        val m = Regex("(?:\\bв|\\bво|\\bin)\\s+([\\p{L}\\- ]+)").find(q) ?: return null
        val stop = setOf("сегодня", "завтра", "послезавтра", "сейчас", "будет", "какая", "какой", "today", "tomorrow", "now", "the")
        val words = m.groupValues[1].trim().split(" ").filter { it.isNotBlank() }.takeWhile { it !in stop }
        return words.take(3).joinToString(" ").ifBlank { null }
    }

    /** Названия городов в вопросе стоят в предложном падеже: «в Москве», «в Казани». Перебираем формы. */
    private fun cityCandidates(name: String): List<String> {
        val words = name.trim().split(" ")
        fun variants(w: String): List<String> = buildList {
            add(w)
            when {
                w.endsWith("ом") || w.endsWith("ем") -> add(w.dropLast(2))
                w.endsWith("е") -> {
                    add(w.dropLast(1) + "а"); add(w.dropLast(1)); add(w.dropLast(1) + "я"); add(w.dropLast(1) + "о")
                }
                w.endsWith("и") -> { add(w.dropLast(1) + "ь"); add(w.dropLast(1) + "и"); add(w.dropLast(1) + "а") }
                w.endsWith("у") -> add(w.dropLast(1))
            }
        }
        val last = words.last()
        val adjectiveFix = words.dropLast(1).map {
            when {
                it.endsWith("ом") -> it.dropLast(2) + "ий"
                it.endsWith("ой") -> it.dropLast(2) + "ая"
                it.endsWith("ем") -> it.dropLast(2) + "ий"
                else -> it
            }
        }
        return (variants(last).map { (adjectiveFix + it).joinToString(" ") } + name).distinct()
    }

    private fun weatherText(code: Int, en: Boolean): String = when (code) {
        0 -> if (en) "clear sky" else "ясно"
        1 -> if (en) "mostly clear" else "преимущественно ясно"
        2 -> if (en) "partly cloudy" else "переменная облачность"
        3 -> if (en) "overcast" else "пасмурно"
        45, 48 -> if (en) "fog" else "туман"
        51, 53, 55, 56, 57 -> if (en) "drizzle" else "морось"
        61, 63, 66 -> if (en) "rain" else "дождь"
        65, 67 -> if (en) "heavy rain" else "сильный дождь"
        71, 73, 77 -> if (en) "snow" else "снег"
        75 -> if (en) "heavy snow" else "сильный снег"
        80, 81 -> if (en) "showers" else "ливень"
        82 -> if (en) "violent showers" else "сильный ливень"
        85, 86 -> if (en) "snow showers" else "снегопад"
        95, 96, 99 -> if (en) "thunderstorm" else "гроза"
        else -> if (en) "unsettled weather" else "переменчивая погода"
    }

    private fun rainText(p: Int, en: Boolean) = when {
        p < 0 -> ""
        en -> ", chance of precipitation $p%"
        else -> ", вероятность осадков $p ${plural(p, "процент", "процента", "процентов")}"
    }

    private fun degrees(t: Int) = "$t ${plural(t, "градус", "градуса", "градусов")}"

    // ---------- Курсы валют ----------

    private fun currency(q: String, en: Boolean): String {
        val json = getJson("https://www.cbr-xml-daily.ru/daily_json.js".toHttpUrl())
        val valutes = json.getJSONObject("Valute")
        val wanted = buildList {
            if (q.containsAny("доллар", "dollar", "usd")) add("USD")
            if (q.containsAny("евро", "euro", "eur")) add("EUR")
            if (q.containsAny("юан", "yuan", "cny")) add("CNY")
            if (q.containsAny("фунт", "pound", "gbp")) add("GBP")
            if (q.containsAny("тенге", "tenge")) add("KZT")
            if (q.containsAny("лир", "lira")) add("TRY")
            if (q.containsAny("бел", "byn")) add("BYN")
            if (isEmpty()) { add("USD"); add("EUR"); add("CNY") }
        }
        val parts = wanted.mapNotNull { code ->
            val v = valutes.optJSONObject(code) ?: return@mapNotNull null
            val nominal = v.getInt("Nominal")
            val rub = String.format(Locale.ROOT, "%.2f", v.getDouble("Value")).replace('.', ',')
            if (en) "$nominal $code is $rub rubles" else "$nominal ${v.getString("Name").lowercase()} стоит $rub руб."
        }
        if (parts.isEmpty()) throw AnswerException(if (en) "I don't know that currency." else "Не знаю такой валюты.")
        return if (en) "Central Bank of Russia rate: " + parts.joinToString("; ") + "."
        else "Курс ЦБ на сегодня: " + parts.joinToString("; ") + "."
    }

    // ---------- Новости ----------

    private fun news(q: String, en: Boolean): String {
        val topic = q.replace(Regex("[?!.,]"), " ")
            .split(" ")
            .filter { it.isNotBlank() && it !in NEWS_STOP_WORDS }
            .joinToString(" ")
        val base = if (topic.isBlank()) "https://news.google.com/rss" else "https://news.google.com/rss/search"
        val builder = base.toHttpUrl().newBuilder()
        if (topic.isNotBlank()) builder.addQueryParameter("q", topic)
        if (en) builder.addQueryParameter("hl", "en-US").addQueryParameter("gl", "US").addQueryParameter("ceid", "US:en")
        else builder.addQueryParameter("hl", "ru").addQueryParameter("gl", "RU").addQueryParameter("ceid", "RU:ru")

        val titles = rssTitles(getText(builder.build())).take(4)
        if (titles.isEmpty()) throw AnswerException(if (en) "I found no news." else "Новостей не нашла.")
        val intro = when {
            en -> if (topic.isBlank()) "Top news:" else "News about $topic:"
            topic.isBlank() -> "Главные новости:"
            else -> "Новости по теме «$topic»:"
        }
        return intro + "\n" + titles.joinToString("\n") { "$it." }
    }

    private fun rssTitles(xml: String): List<String> {
        val parser = Xml.newPullParser()
        parser.setInput(xml.reader())
        val titles = mutableListOf<String>()
        var inItem = false
        while (parser.next() != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG) {
                when (parser.name) {
                    "item" -> inItem = true
                    "title" -> if (inItem) {
                        // В Google News заголовок выглядит как «Текст - Источник», источник не читаем.
                        titles += parser.nextText().substringBeforeLast(" - ").trim()
                    }
                }
            } else if (parser.eventType == XmlPullParser.END_TAG && parser.name == "item") {
                inItem = false
            }
        }
        return titles.filter { it.isNotBlank() }
    }

    // ---------- Время и дата ----------

    private fun time(en: Boolean): String {
        val now = LocalTime.now()
        return if (en) "It's ${now.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))}."
        else "Сейчас ${now.hour} ${plural(now.hour, "час", "часа", "часов")} " +
            "${now.minute} ${plural(now.minute, "минута", "минуты", "минут")}."
    }

    private fun date(en: Boolean): String {
        val today = LocalDate.now()
        return if (en) "Today is ${today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy", Locale.US))}."
        else "Сегодня ${today.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy 'года'", Locale.forLanguageTag("ru")))}."
    }

    // ---------- Факты: Википедия, затем DuckDuckGo ----------

    private fun facts(question: String, en: Boolean): String {
        val query = cleanQuestion(question)
        wikipedia(query, if (en) "en" else "ru")?.let { return it }
        if (!en) wikipedia(query, "en")?.let { return "В русской Википедии не нашла, вот из английской: $it" }
        duckDuckGo(query)?.let { return it }
        throw AnswerException(
            if (en) "Sorry, I couldn't find an answer. Try asking differently."
            else "Не нашла ответа. Попробуйте спросить по-другому, например: кто такой Гагарин или что такое фотосинтез.",
        )
    }

    private fun wikipedia(query: String, lang: String): String? {
        if (query.isBlank()) return null
        val url = "https://$lang.wikipedia.org/w/api.php".toHttpUrl().newBuilder()
            .addQueryParameter("action", "query")
            .addQueryParameter("format", "json")
            .addQueryParameter("generator", "search")
            .addQueryParameter("gsrsearch", query)
            .addQueryParameter("gsrlimit", "1")
            .addQueryParameter("prop", "extracts")
            .addQueryParameter("exintro", "1")
            .addQueryParameter("explaintext", "1")
            .addQueryParameter("exsentences", "3")
            .addQueryParameter("redirects", "1")
            .build()
        val pages = getJson(url).optJSONObject("query")?.optJSONObject("pages") ?: return null
        val key = pages.keys().asSequence().firstOrNull() ?: return null
        val extract = pages.getJSONObject(key).optString("extract")
            .replace(Regex("\\s*\\([^()]*\\)"), "") // убираем скобки с транскрипциями и датами-пояснениями
            .replace("\u0301", "") // знаки ударения
            .replace(Regex("\\s+"), " ")
            .trim()
        return extract.ifBlank { null }
    }

    private fun duckDuckGo(query: String): String? {
        val url = "https://api.duckduckgo.com/".toHttpUrl().newBuilder()
            .addQueryParameter("q", query)
            .addQueryParameter("format", "json")
            .addQueryParameter("no_html", "1")
            .addQueryParameter("skip_disambig", "1")
            .build()
        val json = getJson(url)
        return listOf("Answer", "AbstractText", "Definition")
            .map { json.optString(it) }
            .firstOrNull { it.isNotBlank() }
            ?: json.optJSONArray("RelatedTopics")?.optJSONObject(0)?.optString("Text")?.ifBlank { null }
    }

    /** «Скажи, пожалуйста, кто такой Юрий Гагарин?» -> «Юрий Гагарин». */
    private fun cleanQuestion(question: String): String {
        var s = question.trim().trimEnd('?', '!', '.')
        for (prefix in QUESTION_PREFIXES) {
            s = s.replace(Regex("(?i)^\\s*$prefix\\b[,]?\\s*"), "")
        }
        return s.trim().ifBlank { question }
    }

    // ---------- HTTP ----------

    private fun getText(url: HttpUrl): String {
        val request = Request.Builder().url(url)
            .header("User-Agent", "LoraVoiceAssistant/1.0 (Android; personal use)")
            .build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw AnswerException("Сервис ответил ошибкой ${response.code}.")
            return response.body?.string().orEmpty()
        }
    }

    private fun getJson(url: HttpUrl) = JSONObject(getText(url))

    private companion object {
        val NEWS_STOP_WORDS = setOf(
            "новости", "новость", "новостей", "последние", "свежие", "главные", "какие", "расскажи", "скажи",
            "покажи", "что", "нового", "сегодня", "про", "о", "об", "по", "теме", "в", "мире", "происходит",
            "есть", "пожалуйста", "мне", "news", "latest", "the", "about", "what", "is", "are", "today", "tell", "me",
        )
        val QUESTION_PREFIXES = listOf(
            "скажи", "пожалуйста", "расскажи", "подскажи", "лора", "а", "ну",
            "кто такой", "кто такая", "кто такие", "кто это", "кто был", "кто была", "кто",
            "что такое", "что это такое", "что это", "что значит", "что означает", "что",
            "мне", "про", "о", "об",
            "who is", "who was", "what is", "what are", "what's", "tell me about", "tell me", "please",
        )
    }
}

private fun String.containsAny(vararg parts: String) = parts.any { contains(it) }

/** Русское склонение после числа: 1 градус, 2 градуса, 5 градусов. */
internal fun plural(n: Int, one: String, few: String, many: String): String {
    val a = kotlin.math.abs(n) % 100
    val b = a % 10
    return when {
        a in 11..14 -> many
        b == 1 -> one
        b in 2..4 -> few
        else -> many
    }
}
