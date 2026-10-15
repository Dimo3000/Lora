package com.lora.assistant

/** Богатое содержимое ответа, которое рисуется в чате под текстом. */
sealed interface Card

data class DayForecast(val label: String, val emoji: String, val min: Int, val max: Int, val rain: Int)

data class WeatherCard(
    val city: String,
    val emoji: String,
    val temperature: Int?, // сейчас; null, если спрашивали про завтра
    val description: String,
    val feelsLike: Int?,
    val wind: Int?,
    val days: List<DayForecast>,
) : Card

data class Tile(val title: String, val subtitle: String, val imageUrl: String?, val link: String)

/** Ряд плиток с картинками: фото, афиши фильмов, превью видео. */
data class GalleryCard(val title: String, val tiles: List<Tile>) : Card

data class AudioCard(val title: String, val subtitle: String, val url: String) : Card

/** Одна кнопка-ссылка, например «Искать в YouTube». */
data class LinkCard(val title: String, val subtitle: String, val link: String) : Card

/** Готовый рецепт прямо в чате. */
data class RecipeCard(val title: String, val ingredients: List<String>, val steps: List<String>, val source: String) : Card
