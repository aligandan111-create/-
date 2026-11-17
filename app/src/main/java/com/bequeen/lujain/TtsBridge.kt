package com.bequeen.lujain

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

/**
 * Text-to-speech for the WebView, exposed to JS as "LujainTTS".
 * assets/www/bridge.js turns it into a standard window.speechSynthesis.
 *
 *   speak(id, text, lang, rate)   QUEUE_ADD, utteranceId = id, rate clamped 0.25–4
 *   stop()                        stops speaking and flushes everything pending
 *   isSpeaking()
 *   -> window.__lujainTTS('start' | 'end' | 'error', id)
 */
class TtsBridge(context: Context, private val webView: WebView) : TextToSpeech.OnInitListener {

    private enum class State { INIT, READY, FAILED }

    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var state = State.INIT
    private val queue = mutableListOf<Req>()            // speak() calls that arrive before init finishes
    private data class Req(val id: String, val text: String, val lang: String, val rate: Float)

    override fun onInit(status: Int) {
        val pending: List<Req>
        synchronized(queue) {
            state = if (status == TextToSpeech.SUCCESS) State.READY else State.FAILED
            pending = queue.toList(); queue.clear()
        }
        if (state == State.READY) {
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(id: String) = emit("start", id)
                override fun onDone(id: String) = emit("end", id)
                @Deprecated("Deprecated in Java") override fun onError(id: String) = emit("error", id)
                override fun onError(id: String, errorCode: Int) = emit("error", id)
                override fun onStop(id: String, interrupted: Boolean) = emit("error", id)
            })
            pending.forEach { speakNow(it) }
        } else {
            pending.forEach { emit("error", it.id) }   // init failed: every queued utterance fails
        }
    }

    @JavascriptInterface
    fun speak(id: String, text: String, lang: String, rate: Double) {
        val req = Req(id, text, lang, rate.toFloat())
        synchronized(queue) {
            when (state) {
                State.INIT -> { queue.add(req); return }
                State.FAILED -> { emit("error", id); return }
                State.READY -> {}
            }
        }
        speakNow(req)
    }

    private fun speakNow(r: Req) {
        setLanguageLeniently(r.lang)
        tts.setSpeechRate(r.rate.coerceIn(0.25f, 4f))
        if (tts.speak(r.text, TextToSpeech.QUEUE_ADD, null, r.id) != TextToSpeech.SUCCESS) emit("error", r.id)
    }

    /** Exact locale if supported, else the language-only locale, else keep the current voice. Never fails the utterance. */
    private fun setLanguageLeniently(tag: String) {
        val exact = Locale.forLanguageTag(tag.ifBlank { "en-US" }.replace('_', '-'))
        if (ok(tts.isLanguageAvailable(exact))) { tts.language = exact; return }
        val base = Locale(exact.language)
        if (exact.language.isNotEmpty() && ok(tts.isLanguageAvailable(base))) tts.language = base
    }
    private fun ok(r: Int) = r >= TextToSpeech.LANG_AVAILABLE

    @JavascriptInterface
    fun stop() {
        synchronized(queue) {
            queue.forEach { emit("error", it.id) }
            queue.clear()
        }
        if (state == State.READY) tts.stop()          // onStop fires 'error' for the utterances it cut off
    }

    @JavascriptInterface
    fun isSpeaking(): Boolean = state == State.READY && tts.isSpeaking

    fun stopFromNative() = stop()
    fun destroy() { runCatching { tts.stop() }; runCatching { tts.shutdown() } }

    private fun emit(type: String, id: String) {
        val js = "window.__lujainTTS && window.__lujainTTS(${JSONObject.quote(type)},${JSONObject.quote(id)})"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
