package com.yara.ai

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Link(val title: String, val url: String)
data class Msg(val fromYara: Boolean, val text: String, val links: List<Link> = emptyList())

/** All settings, memories and chat history live on the phone (SharedPreferences). */
class Store(context: Context) {
    private val sp = context.applicationContext.getSharedPreferences("yara", Context.MODE_PRIVATE)

    private fun s(k: String, d: String = ""): String = sp.getString(k, d) ?: d
    private fun put(k: String, v: String) { sp.edit().putString(k, v).apply() }
    private fun b(k: String, d: Boolean): Boolean = sp.getBoolean(k, d)
    private fun putB(k: String, v: Boolean) { sp.edit().putBoolean(k, v).apply() }

    var userName: String
        get() = s("userName")
        set(v) = put("userName", v)
    var provider: String // "gemini" or "openai"
        get() = s("provider", "gemini")
        set(v) = put("provider", v)
    var apiKey: String
        get() = s("apiKey")
        set(v) = put("apiKey", v)
    var modelGemini: String
        get() = s("modelGemini", "gemini-flash-latest")
        set(v) = put("modelGemini", v)
    var modelOpenai: String
        get() = s("modelOpenai", "llama-3.3-70b-versatile")
        set(v) = put("modelOpenai", v)
    var baseUrl: String
        get() = s("baseUrl", "https://api.groq.com/openai/v1")
        set(v) = put("baseUrl", v)
    val model: String get() = if (provider == "gemini") modelGemini else modelOpenai

    var backendUrl: String
        get() = s("backendUrl")
        set(v) = put("backendUrl", v)
    var syncKey: String
        get() = s("syncKey")
        set(v) = put("syncKey", v)
    var cv: String
        get() = s("cv")
        set(v) = put("cv", v)
    var speechLang: String
        get() = s("speechLang", "ml-IN")
        set(v) = put("speechLang", v)
    var voiceReply: Boolean
        get() = b("voiceReply", true)
        set(v) = putB("voiceReply", v)
    var handsFree: Boolean
        get() = b("handsFree", false)
        set(v) = putB("handsFree", v)
    var allowActions: Boolean
        get() = b("allowActions", false)
        set(v) = putB("allowActions", v)

    fun memories(): MutableList<String> {
        val a = JSONArray(s("memories", "[]"))
        return MutableList(a.length()) { a.getString(it) }
    }

    fun setMemories(list: List<String>) = put("memories", JSONArray(list).toString())

    fun addMemory(fact: String): Boolean {
        val f = fact.trim().take(200)
        val bad = Regex("(password|passcode|otp|cvv|card number|aadhaar|passport|\\d{9,})", RegexOption.IGNORE_CASE)
        if (f.isEmpty() || bad.containsMatchIn(f)) return false
        val list = memories()
        if (list.any { it.equals(f, ignoreCase = true) }) return false
        list.add(f)
        setMemories(list.takeLast(200))
        return true
    }

    fun deleteMemory(fact: String) = setMemories(memories().filter { it != fact })

    fun loadHistory(): List<Msg> {
        val a = JSONArray(s("history", "[]"))
        return (0 until a.length()).map {
            val o = a.getJSONObject(it)
            Msg(o.getBoolean("y"), o.getString("t"))
        }
    }

    fun saveHistory(list: List<Msg>) {
        val a = JSONArray()
        list.takeLast(60).forEach { a.put(JSONObject().put("y", it.fromYara).put("t", it.text)) }
        put("history", a.toString())
    }
}
