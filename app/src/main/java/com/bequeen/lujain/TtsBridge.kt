package com.bequeen.lujain

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject
import java.util.Locale

/**
 * Text-to-speech for the WebView (exposed to JS as "LujainTTS").
 * bridge.js turns it into a standard window.speechSynthesis.
 * English is spoken with a US voice, Arabic with the phone's Arabic voice.
 */
class TtsBridge(context: Context, private val webView: WebView) : TextToSpeech.OnInitListener {

    private val tts = TextToSpeech(context.applicationContext, this)
    @Volatile private var ready = false
    private val queue = mutableListOf<Array<Any>>()   // requests that arrive before the engine is ready

    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(id: String) = emit("start", id)
            override fun onDone(id: String) = emit("end", id)
            @Deprecated("Deprecated in Java") override fun onError(id: String) = emit("error", id)
            override fun onError(id: String, errorCode: Int) = emit("error", id)
            override fun onStop(id: String, interrupted: Boolean) = emit("end", id)
        })
        synchronized(queue) { queue.forEach { speakNow(it[0] as String, it[1] as String, it[2] as String, it[3] as Float) }; queue.clear() }
    }

    @JavascriptInterface
    fun speak(id: String, text: String, lang: String, rate: Double) {
        if (!ready) { synchronized(queue) { queue.add(arrayOf(id, text, lang, rate.toFloat())) }; return }
        speakNow(id, text, lang, rate.toFloat())
    }

    private fun speakNow(id: String, text: String, lang: String, rate: Float) {
        val locale = if (lang.lowercase().startsWith("ar")) Locale.forLanguageTag("ar") else Locale.US
        val res = tts.setLanguage(locale)
        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) { emit("error", id); return }
        tts.setSpeechRate(rate.coerceIn(0.3f, 2f))
        tts.speak(text, TextToSpeech.QUEUE_ADD, null, id)
    }

    @JavascriptInterface fun stop() { stopFromNative() }
    @JavascriptInterface fun isSpeaking(): Boolean = ready && tts.isSpeaking
    @JavascriptInterface fun hasArabic(): Boolean =
        ready && tts.isLanguageAvailable(Locale.forLanguageTag("ar")) >= TextToSpeech.LANG_AVAILABLE

    fun stopFromNative() { if (ready) tts.stop() }
    fun destroy() { tts.stop(); tts.shutdown() }

    private fun emit(type: String, id: String) {
        val js = "window.__lujainTTS && window.__lujainTTS(${JSONObject.quote(type)},${JSONObject.quote(id)})"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
