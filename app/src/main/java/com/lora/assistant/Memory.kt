package com.lora.assistant

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Всё, что Лора помнит между запусками: факты о людях, переписка и история разговора с нейросетью. */
class Memory(context: Context) {

    private val prefs = context.getSharedPreferences("memory", Context.MODE_PRIVATE)

    // ---------- Факты ----------

    data class Fact(val owner: String?, val text: String)

    fun facts(): List<Fact> {
        val arr = runCatching { JSONArray(prefs.getString("facts", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Fact(o.optString("owner").ifBlank { null }, o.getString("text"))
        }
    }

    fun addFact(owner: String?, text: String) {
        val all = (facts() + Fact(owner, text.trim())).distinct().takeLast(MAX_FACTS)
        prefs.edit().putString("facts", JSONArray(all.map { JSONObject().put("owner", it.owner ?: "").put("text", it.text) }).toString()).apply()
    }

    fun clearFacts() = prefs.edit().remove("facts").apply()

    /** Текст для системной подсказки нейросети. */
    fun factsForPrompt(): String {
        val facts = facts()
        if (facts.isEmpty()) return ""
        return "Что ты помнишь о собеседниках (используй это естественно, не пересказывай списком):\n" +
            facts.joinToString("\n") { "- " + (it.owner?.let { o -> "$o: " } ?: "") + it.text }
    }

    // ---------- История разговора с нейросетью ----------

    fun loadHistory(): List<Pair<Boolean, String>> {
        val arr = runCatching { JSONArray(prefs.getString("history", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).map { val o = arr.getJSONObject(it); o.getBoolean("u") to o.getString("t") }
    }

    fun saveHistory(history: List<Pair<Boolean, String>>) {
        val arr = JSONArray(history.takeLast(MAX_HISTORY).map { JSONObject().put("u", it.first).put("t", it.second) })
        prefs.edit().putString("history", arr.toString()).apply()
    }

    // ---------- Чат на экране ----------

    fun loadChat(): List<ChatMessage> {
        val arr = runCatching { JSONArray(prefs.getString("chat", "[]")) }.getOrDefault(JSONArray())
        return (0 until arr.length()).mapNotNull {
            val o = arr.getJSONObject(it)
            val role = runCatching { Role.valueOf(o.getString("r")) }.getOrNull() ?: return@mapNotNull null
            ChatMessage(it.toLong(), role, o.getString("t"))
        }
    }

    fun saveChat(messages: List<ChatMessage>) {
        val arr = JSONArray(messages.filter { it.text.isNotBlank() && it.text != "…" }.takeLast(MAX_CHAT)
            .map { JSONObject().put("r", it.role.name).put("t", it.text) })
        prefs.edit().putString("chat", arr.toString()).apply()
    }

    fun clearChat() = prefs.edit().remove("chat").remove("history").apply()

    private companion object {
        const val MAX_FACTS = 60
        const val MAX_HISTORY = 30
        const val MAX_CHAT = 80
    }
}
