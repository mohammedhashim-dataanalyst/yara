package com.yara.ai

import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** App-wide brain: chat state, LLM calls, tool handling. Survives the activity going to the background. */
object YaraEngine {
    private var inited = false
    lateinit var appCtx: Context
    lateinit var store: Store
    lateinit var voice: Voice
    var activity: MainActivity? = null
    var whenSpoken: (() -> Unit)? = null

    val messages = mutableStateListOf<Msg>()
    var busy by mutableStateOf(false)
    var mode by mutableStateOf("companion")
    var useScreen by mutableStateOf(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lastUser = ""
    private val risky = Regex("(pay|transfer|purchase|buy now|place order|send money|delete|remove account|confirm payment)", RegexOption.IGNORE_CASE)

    fun init(ctx: Context) {
        if (inited) return
        inited = true
        appCtx = ctx.applicationContext
        store = Store(appCtx)
        voice = Voice(appCtx)
        voice.whenDone = { scope.launch { whenSpoken?.invoke() } }
        messages.addAll(store.loadHistory())
        if (messages.isEmpty()) messages.add(Msg(true, "Hi ❤️ ഞാൻ Yara ആണ്. എന്താണ് വിശേഷം? Tell me anything."))
    }

    private fun toast(t: String) { Toast.makeText(appCtx, t, Toast.LENGTH_SHORT).show() }

    fun clearChat() {
        messages.clear()
        messages.add(Msg(true, "Fresh start ❤️ ഞാൻ ഇവിടെയുണ്ട്."))
        store.saveHistory(messages.toList())
    }

    fun send(text: String, image: Bitmap?) {
        val screen = useScreen
        useScreen = false
        lastUser = text
        messages.add(Msg(false, if (image != null) "📷 $text" else text))
        busy = true
        scope.launch {
            try {
                val screenText = if (screen) grabScreen() else null
                val hist = messages.dropLast(1).takeLast(14)
                val reply = withContext(Dispatchers.IO) { Llm.chat(store, hist, text, image, screenText, mode) }
                handle(reply, speakAnyway = screen)
            } catch (e: Exception) {
                messages.add(Msg(true, "⚠️ " + Llm.friendly(e)))
            } finally {
                busy = false
                store.saveHistory(messages.toList())
            }
        }
    }

    /** Sends Yara to the background, waits, then reads the text of whatever app is on screen. */
    private suspend fun grabScreen(): String? {
        val svc = YaraAccessibilityService.instance
        if (svc == null) {
            messages.add(Msg(true, "ℹ️ First turn on \"Yara AI\" in Accessibility settings (Settings tab → Open Accessibility settings)."))
            return null
        }
        toast("Yara will read your screen in 4 seconds…")
        activity?.moveTaskToBack(true)
        delay(4000)
        return svc.readScreen().ifBlank { null }
    }

    private suspend fun handle(r: Reply, speakAnyway: Boolean) {
        r.memories?.let { store.setMemories(it) }
        val links = mutableListOf<Link>()
        val acts = mutableListOf<Tag>()
        val notes = mutableListOf<String>()
        for (t in r.tags) {
            when (t.name) {
                "remember" -> if (r.memories == null) {
                    val f = t.args.firstOrNull()
                    if (f != null && store.addMemory(f)) notes.add("🧠 Saved: $f")
                }
                "remind" -> {
                    val mins = t.args.getOrNull(0)?.toIntOrNull()
                    if (mins != null && mins > 0) notes.add(Tools.remind(appCtx, mins, t.args.getOrNull(1) ?: "Reminder"))
                }
                "jobs" -> {
                    val found = withContext(Dispatchers.IO) { Tools.searchJobs(t.args.firstOrNull().orEmpty()) }
                    links.addAll(found)
                    notes.add(if (found.isEmpty()) "🔎 No jobs found for that search." else "🔎 Jobs found - tap a link to open and apply:")
                }
                "act" -> acts.add(t)
            }
        }
        val body = r.text.ifBlank { if (acts.isNotEmpty()) "Okay ❤️" else "…" }
        messages.add(Msg(true, body + notes.joinToString("") { "\n$it" }, links))
        if (store.voiceReply || speakAnyway) voice.speak(r.text)
        if (acts.isNotEmpty()) {
            if (store.allowActions) runActions(acts)
            else messages.add(Msg(true, "ℹ️ I can do that on your screen, but \"Allow screen actions\" is off in Settings."))
        }
    }

    private suspend fun runActions(acts: List<Tag>) {
        var backgrounded = false
        for (a in acts.take(5)) {
            val kind = a.args.getOrNull(0)?.lowercase() ?: continue
            val arg = a.args.getOrNull(1).orEmpty()
            if (kind == "open") {
                toast("Yara: opening $arg")
                val ok = Tools.openApp(activity ?: appCtx, arg)
                if (!ok) messages.add(Msg(true, "ℹ️ I couldn't open \"$arg\"."))
                backgrounded = true
                delay(2000)
                continue
            }
            val svc = YaraAccessibilityService.instance
            if (svc == null) {
                messages.add(Msg(true, "ℹ️ Turn on \"Yara AI\" in Accessibility settings so I can act on your screen."))
                return
            }
            if (!backgrounded) {
                activity?.moveTaskToBack(true)
                backgrounded = true
                delay(1500)
            }
            if (kind == "click" && risky.containsMatchIn(arg) && !risky.containsMatchIn(lastUser)) {
                toast("Yara skipped a risky tap: $arg")
                continue
            }
            val ok = when (kind) {
                "click" -> { toast("Yara: tapping \"$arg\""); svc.click(arg) }
                "type" -> { toast("Yara: typing"); svc.typeText(arg) }
                "scroll" -> svc.scroll(arg.lowercase() != "up")
                "back", "home", "recents" -> svc.global(kind)
                else -> false
            }
            if (!ok) toast("Yara couldn't do: $kind $arg")
            delay(1200)
        }
    }

    fun deleteMemory(fact: String) {
        store.deleteMemory(fact)
        if (store.backendUrl.isNotBlank()) scope.launch { withContext(Dispatchers.IO) { try { Llm.backendDeleteMemory(store, fact) } catch (e: Exception) { } } }
    }

    fun refreshMemories(onDone: (List<String>) -> Unit) {
        scope.launch {
            try {
                val list = withContext(Dispatchers.IO) { Llm.backendMemories(store) }
                store.setMemories(list)
                onDone(list)
            } catch (e: Exception) {
                toast(Llm.friendly(e))
            }
        }
    }
}
