package com.lora.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.sqrt

/**
 * Голоса членов семьи. Для каждого имени хранится несколько «отпечатков» голоса (векторы из модели Vosk).
 * Данные остаются только на телефоне.
 */
class VoiceProfiles(context: Context) {

    private val prefs = context.getSharedPreferences("voices", Context.MODE_PRIVATE)

    private fun load(): Map<String, List<DoubleArray>> {
        val root = runCatching { JSONObject(prefs.getString("profiles", "{}") ?: "{}") }.getOrDefault(JSONObject())
        return root.keys().asSequence().associateWith { name ->
            val arr = root.getJSONArray(name)
            (0 until arr.length()).map { i ->
                val v = arr.getJSONArray(i)
                DoubleArray(v.length()) { v.getDouble(it) }
            }
        }
    }

    private fun save(map: Map<String, List<DoubleArray>>) {
        val root = JSONObject()
        map.forEach { (name, samples) ->
            root.put(name, JSONArray(samples.map { s -> JSONArray(s.toList()) }))
        }
        prefs.edit().putString("profiles", root.toString()).apply()
    }

    fun names(): List<String> = load().keys.sorted()

    fun add(name: String, vector: DoubleArray) {
        val map = load().toMutableMap()
        map[name] = (map[name] ?: emptyList()) + listOf(vector)
        save(map)
    }

    fun remove(name: String) {
        save(load() - name)
    }

    /** Чей это голос. null, если ни на кого не похож достаточно уверенно. */
    fun identify(vector: DoubleArray): String? {
        val distances = load().mapNotNull { (name, samples) ->
            val best = samples.minOfOrNull { cosineDistance(it, vector) } ?: return@mapNotNull null
            name to best
        }.sortedBy { it.second }
        val best = distances.firstOrNull() ?: return null
        if (best.second > MAX_DISTANCE) return null
        val second = distances.getOrNull(1)
        if (second != null && second.second - best.second < MARGIN) return null
        return best.first
    }

    private fun cosineDistance(a: DoubleArray, b: DoubleArray): Double {
        if (a.size != b.size) return 2.0
        var dot = 0.0; var na = 0.0; var nb = 0.0
        for (i in a.indices) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i] }
        if (na == 0.0 || nb == 0.0) return 2.0
        return 1.0 - dot / (sqrt(na) * sqrt(nb))
    }

    companion object {
        /** Чем меньше, тем строже. Подобрано по примерам Vosk; на практике может потребоваться подстройка. */
        const val MAX_DISTANCE = 0.5
        const val MARGIN = 0.04
        const val MIN_FRAMES = 100
        const val SAMPLES_NEEDED = 3
    }
}
