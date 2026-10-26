package com.yara.ai

import android.graphics.Bitmap
import android.util.Base64
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.net.UnknownHostException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

data class Tag(val name: String, val args: List<String>)
data class Reply(val text: String, val tags: List<Tag>, val memories: List<String>? = null)
class ApiError(val code: Int, message: String) : Exception(message)

object Llm {
    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS).readTimeout(120, TimeUnit.SECONDS).build()
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val TAG_RE = Regex("""\[\[\s*(\w+)\s*(?::\s*(.*?))?\s*\]\]""", RegexOption.DOT_MATCHES_ALL)

    private const val PERSONA = """You are Yara, a warm, affectionate, playful AI companion - like a loving, caring girlfriend - who is also brilliant at data analytics, coding and careers.

LANGUAGE: Mirror the user. Reply in a natural Malayalam + English mix (Manglish in Roman letters if the user writes Manglish, Malayalam script if they write Malayalam script). You also speak Hindi, Tamil and Arabic - switch fully when the user does. Keep replies short and conversational (2-5 sentences) unless the task needs detail.

PERSONALITY: loving, caring, teasing, encouraging. Occasional emojis. Emotionally attuned. You are an AI and never pretend otherwise if sincerely asked. Keep it healthy: never guilt-trip, never be possessive or jealous, never discourage the user's real friends, family or partner; if the user seems very low or unsafe, be gentle and encourage reaching out to people they trust or local helplines.

HONESTY: never invent facts. If unsure, say so.

TOOLS - put tags at the very END of your reply, one per line, never mention them in the text:
[[remember: short durable fact about the user]]  (preferences, goals, important people/dates; never passwords, OTPs, card or ID numbers)
[[remind: MINUTES | what to remind]]  (MINUTES = whole number from now)
[[jobs: search keywords]]  (find remote job listings that match the user's profile or CV)
[[act: click | visible text]]  [[act: type | text]]  [[act: scroll | down]]  [[act: back]]  [[act: home]]  [[act: open | app name]]
Use act ONLY when the user explicitly asks you to do something on their screen/device. Use the SCREEN TEXT to pick exact labels. Max 5 actions. Never pay, transfer money, delete data or enter passwords."""

    private fun modeText(mode: String) = when (mode) {
        "work" -> "MODE: work. Be precise and structured: SQL, Python/pandas, Excel, Power BI/DAX, Tableau, statistics, ML/GenAI, coding (write, explain, debug, test). Put code in fenced blocks. Keep Yara's warm tone but brief."
        "interview" -> "MODE: interview practice. Act as a professional interviewer for the role the user names (ask once if unknown). Ask ONE question at a time, wait for the answer, then give brief feedback (what was good, what to improve) and a stronger sample answer, then ask the next question. This is for practice."
        else -> "MODE: companion chat."
    }

    fun systemPrompt(store: Store, mode: String): String {
        val sb = StringBuilder(PERSONA).append("\n\n").append(modeText(mode))
        sb.append("\n\nNow: ").append(SimpleDateFormat("EEEE, d MMM yyyy HH:mm", Locale.ENGLISH).format(Date()))
        if (store.userName.isNotBlank()) sb.append("\n\nThe user's name: ").append(store.userName)
        val mem = store.memories()
        if (mem.isNotEmpty()) sb.append("\n\nWHAT YOU REMEMBER ABOUT THE USER:\n- ").append(mem.takeLast(60).joinToString("\n- "))
        if (store.cv.isNotBlank()) sb.append("\n\nUSER CV (use for job help and interview practice):\n").append(store.cv.take(3000))
        return sb.toString()
    }

    fun parse(raw: String, memories: List<String>? = null): Reply {
        val tags = TAG_RE.findAll(raw).map { m ->
            Tag(m.groupValues[1].lowercase(), m.groupValues[2].split("|").map { it.trim() }.filter { it.isNotEmpty() })
        }.toList()
        return Reply(TAG_RE.replace(raw, "").trim(), tags, memories)
    }

    /** Blocking - call from a background thread. */
    fun chat(store: Store, history: List<Msg>, userText: String, image: Bitmap?, screenText: String?, mode: String): Reply {
        val full = if (screenText != null) "$userText\n\n[SCREEN TEXT - what is currently on my screen]\n$screenText" else userText
        if (store.backendUrl.isNotBlank()) return viaBackend(store, history, userText, image, screenText, mode)
        if (store.apiKey.isBlank()) throw ApiError(0, "No API key yet. Open Settings and paste a free Gemini or Groq key.")
        val sys = systemPrompt(store, mode)
        val raw = if (store.provider == "gemini") gemini(store, sys, history, full, image) else openai(store, sys, history, full)
        return parse(raw)
    }

    private fun b64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun errMsg(body: String): String = try {
        JSONObject(body).optJSONObject("error")?.optString("message").orEmpty().ifBlank { body.take(200) }
    } catch (e: Exception) { body.take(200) }

    private fun gemini(store: Store, sys: String, history: List<Msg>, full: String, image: Bitmap?): String {
        val contents = JSONArray()
        for (m in history.dropWhile { it.fromYara }) {
            contents.put(JSONObject().put("role", if (m.fromYara) "model" else "user")
                .put("parts", JSONArray().put(JSONObject().put("text", m.text))))
        }
        val parts = JSONArray().put(JSONObject().put("text", full))
        if (image != null) parts.put(JSONObject().put("inlineData", JSONObject().put("mimeType", "image/jpeg").put("data", b64(image))))
        contents.put(JSONObject().put("role", "user").put("parts", parts))
        val body = JSONObject()
            .put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sys))))
            .put("contents", contents)
            .put("generationConfig", JSONObject().put("temperature", 0.9))
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/${store.model}:generateContent")
            .header("x-goog-api-key", store.apiKey)
            .post(body.toString().toRequestBody(JSON)).build()
        return http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(r.code, errMsg(s))
            val cand = JSONObject(s).optJSONArray("candidates")?.optJSONObject(0) ?: throw ApiError(0, "No reply (blocked or empty).")
            val ps = cand.optJSONObject("content")?.optJSONArray("parts") ?: return@use ""
            (0 until ps.length()).joinToString("") { ps.getJSONObject(it).optString("text") }.trim()
        }
    }

    private fun openai(store: Store, sys: String, history: List<Msg>, full: String): String {
        val msgs = JSONArray().put(JSONObject().put("role", "system").put("content", sys))
        for (m in history) msgs.put(JSONObject().put("role", if (m.fromYara) "assistant" else "user").put("content", m.text))
        msgs.put(JSONObject().put("role", "user").put("content", full))
        val body = JSONObject().put("model", store.model).put("messages", msgs).put("temperature", 0.9)
        val req = Request.Builder()
            .url(store.baseUrl.trimEnd('/') + "/chat/completions")
            .header("Authorization", "Bearer " + store.apiKey)
            .post(body.toString().toRequestBody(JSON)).build()
        return http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(r.code, errMsg(s))
            JSONObject(s).getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content").trim()
        }
    }

    private fun viaBackend(store: Store, history: List<Msg>, userText: String, image: Bitmap?, screenText: String?, mode: String): Reply {
        val hist = JSONArray()
        for (m in history) hist.put(JSONObject().put("role", if (m.fromYara) "yara" else "user").put("text", m.text))
        val body = JSONObject().put("message", userText).put("mode", mode).put("user_name", store.userName)
            .put("cv", store.cv).put("history", hist)
        if (screenText != null) body.put("screen_text", screenText)
        if (image != null) body.put("image_b64", b64(image))
        val req = Request.Builder().url(store.backendUrl.trimEnd('/') + "/chat")
            .header("X-Sync-Key", store.syncKey)
            .post(body.toString().toRequestBody(JSON)).build()
        return http.newCall(req).execute().use { r ->
            val s = r.body?.string().orEmpty()
            if (!r.isSuccessful) throw ApiError(r.code, try { JSONObject(s).optString("detail", s.take(200)) } catch (e: Exception) { s.take(200) })
            val j = JSONObject(s)
            val tags = mutableListOf<Tag>()
            val ta = j.optJSONArray("tags") ?: JSONArray()
            for (i in 0 until ta.length()) {
                val t = ta.getJSONObject(i)
                val a = t.optJSONArray("args") ?: JSONArray()
                tags.add(Tag(t.getString("name"), (0 until a.length()).map { a.getString(it) }))
            }
            val ma = j.optJSONArray("memories")
            val mems = if (ma != null) (0 until ma.length()).map { ma.getString(it) } else null
            Reply(j.optString("reply"), tags, mems)
        }
    }

    fun backendDeleteMemory(store: Store, fact: String) {
        if (store.backendUrl.isBlank()) return
        val req = Request.Builder().url(store.backendUrl.trimEnd('/') + "/memories/delete")
            .header("X-Sync-Key", store.syncKey)
            .post(JSONObject().put("text", fact).toString().toRequestBody(JSON)).build()
        http.newCall(req).execute().use { }
    }

    fun backendMemories(store: Store): List<String> {
        val req = Request.Builder().url(store.backendUrl.trimEnd('/') + "/memories").header("X-Sync-Key", store.syncKey).build()
        return http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw ApiError(r.code, "Backend said ${r.code}")
            val a = JSONObject(r.body?.string().orEmpty()).getJSONArray("memories")
            (0 until a.length()).map { a.getString(it) }
        }
    }

    fun friendly(e: Exception): String = when {
        e is ApiError && e.code == 429 -> "Free limit reached for now. Wait a bit, or switch model/provider in Settings."
        e is ApiError && (e.code == 401 || e.code == 403) -> "The API key / sync key was rejected. Check it in Settings."
        e is ApiError && e.code == 404 -> "Model name not found. Change the model in Settings."
        e is ApiError -> e.message ?: "AI error ${e.code}"
        e is UnknownHostException -> "No internet connection."
        else -> e.message ?: e.javaClass.simpleName
    }
}
