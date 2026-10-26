package com.yara.ai

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Text-to-speech. Picks Malayalam / Hindi / Tamil / Arabic / English voice from the script of the text. */
class Voice(context: Context) {
    private var tts: TextToSpeech? = null
    private var ready = false
    var whenDone: (() -> Unit)? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) { whenDone?.invoke() }
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) {}
            })
        }
    }

    private fun localeFor(t: String): Locale {
        var ml = 0; var hi = 0; var ta = 0; var ar = 0
        for (c in t) when (c.code) {
            in 0x0D00..0x0D7F -> ml++
            in 0x0900..0x097F -> hi++
            in 0x0B80..0x0BFF -> ta++
            in 0x0600..0x06FF -> ar++
        }
        val best = maxOf(ml, hi, ta, ar)
        return when {
            best < 3 -> Locale("en", "IN")
            best == ml -> Locale("ml", "IN")
            best == hi -> Locale("hi", "IN")
            best == ta -> Locale("ta", "IN")
            else -> Locale("ar")
        }
    }

    private fun clean(raw: String): String = raw
        .replace(Regex("https?://\\S+"), "")
        .replace(Regex("```[\\s\\S]*?```"), " code omitted ")
        .replace(Regex("[*_#`>~]"), "")
        .replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "")
        .trim()

    fun speak(raw: String) {
        val t0 = tts ?: return
        if (!ready) return
        val t = clean(raw).take(3500)
        if (t.isBlank()) return
        val loc = localeFor(t)
        if (t0.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) t0.language = loc
        t0.speak(t, TextToSpeech.QUEUE_FLUSH, null, "yara")
    }

    fun stopSpeaking() { tts?.stop() }
}

/** Speech-to-text using the phone's recognizer (Google). Must be used from the main thread. */
class SpeechListener(
    private val ctx: Context,
    private val onText: (String) -> Unit,
    private val onDone: () -> Unit,
    private val onFail: (String) -> Unit
) {
    private var sr: SpeechRecognizer? = null

    fun start(lang: String) {
        stop()
        if (!SpeechRecognizer.isRecognitionAvailable(ctx)) { onFail("Speech recognition is not available on this phone."); return }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}
            override fun onError(error: Int) {
                onDone()
                if (error != SpeechRecognizer.ERROR_NO_MATCH && error != SpeechRecognizer.ERROR_SPEECH_TIMEOUT) onFail("Voice error $error")
            }
            override fun onResults(results: Bundle?) {
                val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
                onDone()
                if (t.isNotBlank()) onText(t)
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
        sr = r
        r.startListening(i)
    }

    fun stop() {
        sr?.let { it.stopListening(); it.destroy() }
        sr = null
    }
}
