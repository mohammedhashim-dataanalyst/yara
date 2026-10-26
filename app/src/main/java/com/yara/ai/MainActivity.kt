@file:OptIn(ExperimentalMaterial3Api::class)

package com.yara.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.exifinterface.media.ExifInterface
import java.io.File

private val BG = Color(0xFF07080D)
private val PINK = Color(0xFFFF3B5C)

object Images {
    fun load(ctx: Context, uri: Uri): Bitmap? {
        try {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            var sample = 1
            while (maxOf(opts.outWidth, opts.outHeight) / sample > 1600) sample *= 2
            val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o2) } ?: return null
            val rot = ctx.contentResolver.openInputStream(uri)?.use {
                ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
            } ?: ExifInterface.ORIENTATION_NORMAL
            val deg = when (rot) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
            if (deg == 0f) return bmp
            return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        } catch (e: Exception) {
            return null
        }
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var listener: SpeechListener
    private var attached by mutableStateOf<Bitmap?>(null)
    private var listening by mutableStateOf(false)
    private var photoUri: Uri? = null

    private val micPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) startListening() }
    private val notifPerm = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val takePic = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        if (ok) photoUri?.let { attached = Images.load(this, it) }
    }
    private val pickPic = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) attached = Images.load(this, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        YaraEngine.init(this)
        YaraEngine.activity = this
        listener = SpeechListener(this,
            onText = { t -> sendNow(t) },
            onDone = { listening = false },
            onFail = { m -> listening = false; Toast.makeText(this, m, Toast.LENGTH_SHORT).show() })
        YaraEngine.whenSpoken = { if (YaraEngine.store.handsFree) startListening() }
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = PINK, background = BG, surface = Color(0xFF11131B))) { Root() }
        }
    }

    override fun onDestroy() {
        if (YaraEngine.activity === this) YaraEngine.activity = null
        listener.stop()
        super.onDestroy()
    }

    private fun startListening() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            micPerm.launch(Manifest.permission.RECORD_AUDIO); return
        }
        YaraEngine.voice.stopSpeaking()
        listening = true
        listener.start(YaraEngine.store.speechLang)
    }

    private fun sendNow(text: String) {
        if (YaraEngine.busy) return
        YaraEngine.send(text, attached)
        attached = null
    }

    private fun takePhoto() {
        val dir = File(cacheDir, "shots").apply { mkdirs() }
        val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(this, "$packageName.files", f)
        photoUri = uri
        takePic.launch(uri)
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) { }
    }

    @Composable
    private fun Root() {
        var tab by remember { mutableIntStateOf(0) }
        Column(Modifier.fillMaxSize().background(BG).systemBarsPadding().imePadding().padding(12.dp)) {
            Text("YARA", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
            Text("AI companion • Malayalam + English", color = Color.LightGray, fontSize = 12.sp)
            Spacer(Modifier.height(6.dp))
            TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
                listOf("Chat", "Memory", "Settings").forEachIndexed { i, t ->
                    Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) })
                }
            }
            Spacer(Modifier.height(8.dp))
            when (tab) {
                0 -> ChatTab()
                1 -> MemoryTab()
                else -> SettingsTab()
            }
        }
    }

    @Composable
    private fun ChatTab() {
        val e = YaraEngine
        var input by remember { mutableStateOf("") }
        val state = rememberLazyListState()
        LaunchedEffect(e.messages.size, e.busy) {
            if (e.messages.isNotEmpty()) state.animateScrollToItem(e.messages.size - 1)
        }
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = e.mode == "companion", onClick = { e.mode = "companion" }, label = { Text("💕 Companion") })
                FilterChip(selected = e.mode == "work", onClick = { e.mode = "work" }, label = { Text("📊 Work & code") })
                FilterChip(selected = e.mode == "interview", onClick = { e.mode = "interview" }, label = { Text("🎯 Interview practice") })
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = state, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(e.messages) { m -> Bubble(m) }
                if (e.busy) item { Text("Yara is typing…", color = Color.Gray, modifier = Modifier.padding(8.dp)) }
            }
            if (attached != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📷 Image attached", color = Color.White)
                    TextButton(onClick = { attached = null }) { Text("Remove") }
                }
            }
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { if (listening) { listener.stop(); listening = false } else startListening() }) {
                    Text(if (listening) "⏹ Stop" else "🎤 Talk")
                }
                OutlinedButton(onClick = { takePhoto() }) { Text("📷") }
                OutlinedButton(onClick = { pickPic.launch("image/*") }) { Text("🖼") }
                OutlinedButton(onClick = { e.voice.stopSpeaking() }) { Text("🔇") }
                FilterChip(selected = e.useScreen, onClick = { e.useScreen = !e.useScreen }, label = { Text("👁 Screen") })
            }
            Row(verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = input, onValueChange = { input = it }, modifier = Modifier.weight(1f),
                    placeholder = { Text("Talk to Yara…") }, maxLines = 4
                )
                Spacer(Modifier.padding(horizontal = 4.dp))
                Button(enabled = !e.busy, onClick = {
                    val t = input.trim()
                    if (t.isNotEmpty() || attached != null) {
                        sendNow(if (t.isEmpty()) "What do you see here?" else t)
                        input = ""
                    }
                }) { Text("Send") }
            }
        }
    }

    @Composable
    private fun Bubble(m: Msg) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromYara) Arrangement.Start else Arrangement.End) {
            Surface(
                shape = RoundedCornerShape(18.dp),
                color = if (m.fromYara) Color(0xFF171923) else Color(0xFF3A1620),
                modifier = Modifier.widthIn(max = 320.dp)
            ) {
                Column(Modifier.padding(12.dp)) {
                    Text(m.text, color = Color.White, fontSize = 16.sp)
                    m.links.forEach { l -> TextButton(onClick = { openUrl(l.url) }) { Text(l.title, fontSize = 13.sp) } }
                }
            }
        }
    }

    @Composable
    private fun MemoryTab() {
        val e = YaraEngine
        var list by remember { mutableStateOf(e.store.memories().toList()) }
        var newFact by remember { mutableStateOf("") }
        Column(Modifier.fillMaxSize()) {
            Text("What Yara remembers about you. Delete anything you don't want kept.", color = Color.LightGray, fontSize = 13.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = newFact, onValueChange = { newFact = it }, modifier = Modifier.weight(1f), placeholder = { Text("Add a memory…") }, singleLine = true)
                TextButton(onClick = {
                    if (e.store.addMemory(newFact)) { list = e.store.memories().toList(); newFact = "" }
                }) { Text("Add") }
            }
            if (e.store.backendUrl.isNotBlank()) {
                TextButton(onClick = { e.refreshMemories { list = it } }) { Text("↻ Refresh from backend") }
            }
            LazyColumn(Modifier.weight(1f)) {
                items(list) { f ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("• $f", color = Color.White, modifier = Modifier.weight(1f))
                        TextButton(onClick = { e.deleteMemory(f); list = e.store.memories().toList() }) { Text("✕") }
                    }
                }
            }
        }
    }

    @Composable
    private fun Field(label: String, value: String, secret: Boolean = false, lines: Int = 1, onChange: (String) -> Unit) {
        var v by remember { mutableStateOf(value) }
        OutlinedTextField(
            value = v, onValueChange = { v = it; onChange(it) }, label = { Text(label) },
            singleLine = lines == 1, minLines = lines, modifier = Modifier.fillMaxWidth(),
            visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None
        )
    }

    @Composable
    private fun ToggleRow(label: String, initial: Boolean, onChange: (Boolean) -> Unit) {
        var v by remember { mutableStateOf(initial) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(label, color = Color.White, modifier = Modifier.weight(1f))
            Switch(checked = v, onCheckedChange = { v = it; onChange(it) })
        }
    }

    @Composable
    private fun SettingsTab() {
        val s = YaraEngine.store
        var prov by remember { mutableStateOf(s.provider) }
        var lang by remember { mutableStateOf(s.speechLang) }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("1) Brain - free key", color = PINK, fontWeight = FontWeight.Bold)
            Text("Get a free Gemini key at aistudio.google.com (supports photos) or a free Groq key at console.groq.com (text only). Free limits can change; the key stays on this phone.", color = Color.LightGray, fontSize = 12.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = prov == "gemini", onClick = { prov = "gemini"; s.provider = "gemini" }, label = { Text("Gemini") })
                FilterChip(selected = prov == "openai", onClick = { prov = "openai"; s.provider = "openai" }, label = { Text("Groq / OpenAI-compatible") })
            }
            Field("API key", s.apiKey, secret = true) { s.apiKey = it.trim() }
            androidx.compose.runtime.key(prov) {
                Field("Model name", s.model) { v -> if (prov == "gemini") { s.modelGemini = v.trim() } else { s.modelOpenai = v.trim() } }
                if (prov == "openai") Field("Base URL", s.baseUrl) { s.baseUrl = it.trim() }
            }
            Field("Your name", s.userName) { s.userName = it.trim() }

            Text("2) Voice", color = PINK, fontWeight = FontWeight.Bold)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("ml-IN" to "മലയാളം", "en-IN" to "English", "hi-IN" to "हिन्दी", "ta-IN" to "தமிழ்", "ar-SA" to "العربية").forEach { (code, name) ->
                    FilterChip(selected = lang == code, onClick = { lang = code; s.speechLang = code }, label = { Text(name) })
                }
            }
            ToggleRow("Yara speaks her replies", s.voiceReply) { s.voiceReply = it }
            ToggleRow("Hands-free conversation (listens again after she speaks)", s.handsFree) { s.handsFree = it }

            Text("3) Screen & phone control", color = PINK, fontWeight = FontWeight.Bold)
            Text("Yara reads your screen only when you press 👁 Screen. Screen text is sent to your AI provider.", color = Color.LightGray, fontSize = 12.sp)
            OutlinedButton(onClick = { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("Open Accessibility settings") }
            ToggleRow("Allow screen actions (tap / type / open apps when I ask)", s.allowActions) { s.allowActions = it }
            ToggleRow("Background mode (keeps Yara alive in other apps)", false) { on ->
                val i = Intent(this@MainActivity, YaraForegroundService::class.java)
                if (on) ContextCompat.startForegroundService(this@MainActivity, i) else stopService(i)
            }

            Text("4) Jobs - paste your CV", color = PINK, fontWeight = FontWeight.Bold)
            Field("CV text", s.cv, lines = 6) { s.cv = it }
            Text("Then say: \"find jobs for me\". Yara lists matching jobs with links; you apply yourself.", color = Color.LightGray, fontSize = 12.sp)

            Text("5) Optional: backend (shared memory with Windows)", color = PINK, fontWeight = FontWeight.Bold)
            Field("Backend URL (https://...)", s.backendUrl) { s.backendUrl = it.trim() }
            Field("Sync key (long secret)", s.syncKey, secret = true) { s.syncKey = it.trim() }

            OutlinedButton(onClick = { YaraEngine.clearChat() }) { Text("Clear chat") }
            Spacer(Modifier.height(24.dp))
        }
    }
}
