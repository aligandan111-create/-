package com.bequeen.lujain

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import androidx.core.content.ContextCompat
import org.json.JSONObject

/**
 * Speech recognition for the WebView, exposed to JS as "LujainSpeech" (API A).
 * assets/www/bridge.js turns it into a standard window.SpeechRecognition.
 *
 *   startListen(id, lang, interim, continuous)
 *   stopListen(id)
 *   -> window.__lujainSpeechEvent(id, type, payloadJson)   type: start | partial | final | error | end
 *      partial/final payload {"text":"..."}, error payload {"error":"<name>"}
 *
 * The SpeechRecognizer is created and used ONLY on the main thread (JS calls arrive on a
 * background thread and are posted to the main looper).
 *
 * Extra helpers (not part of the contract, used by the page's mic check / error sheet):
 *   hasPermission(), isAvailable(), openSettings()
 */
class SpeechBridge(private val activity: MainActivity, private val webView: WebView) {

    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var curId: String? = null          // the session events belong to; null = nothing running
    private var lang = "en-US"
    private var interim = true
    private var continuous = false

    @JavascriptInterface
    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @JavascriptInterface
    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(activity)

    @JavascriptInterface
    fun openSettings() {
        main.post {
            runCatching {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null)))
            }
        }
    }

    @JavascriptInterface
    fun startListen(id: String, lang: String, interim: Boolean, continuous: Boolean) {
        main.post {
            curId?.let { old -> cancelSession(old) }          // a new session replaces any running one
            if (!hasPermission()) { emit(id, "error", err("not-allowed")); emit(id, "end"); return@post }
            if (!isAvailable()) { emit(id, "error", err("service-not-allowed")); emit(id, "end"); return@post }
            curId = id
            this.lang = lang.ifBlank { "en-US" }
            this.interim = interim
            this.continuous = continuous
            listen()
        }
    }

    @JavascriptInterface
    fun stopListen(id: String) {
        main.post { if (curId == id) cancelSession(id) }
    }

    /** Cancel and send "end" exactly once; later callbacks for this id are ignored. */
    private fun cancelSession(id: String) {
        curId = null
        runCatching { recognizer?.cancel() }
        emit(id, "end")
    }

    /** Called from the Activity (onPause). */
    fun stopFromNative() { main.post { curId?.let { cancelSession(it) } } }

    /** Called from the Activity (onDestroy). */
    fun destroy() {                                    // onDestroy runs on the main thread
        curId = null
        main.removeCallbacksAndMessages(null)
        runCatching { recognizer?.destroy() }
        recognizer = null
    }

    private fun listen() {
        val id = curId ?: return
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(activity).also { it.setRecognitionListener(listener) }
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            curId = null
            emit(id, "error", err("aborted")); emit(id, "end")
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { curId?.let { emit(it, "start") } }

        override fun onPartialResults(partial: Bundle?) {
            val id = curId ?: return
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) emit(id, "partial", text(t))
        }

        override fun onResults(results: Bundle?) {
            val id = curId ?: return
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            emit(id, "final", text(t))
            if (continuous) {
                // continuous: keep listening, no "end" (short pause so the engine is free again)
                main.postDelayed({ if (curId == id) listen() }, 150)
            } else {
                curId = null
                emit(id, "end")
            }
        }

        override fun onError(error: Int) {
            val id = curId ?: return                       // cancelled session: already ended
            curId = null
            val name = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> "network"
                else -> "aborted"                          // ERROR_CLIENT, ERROR_RECOGNIZER_BUSY, anything else
            }
            emit(id, "error", err(name))
            emit(id, "end")
        }

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun text(t: String) = JSONObject().put("text", t).toString()
    private fun err(name: String) = JSONObject().put("error", name).toString()

    private fun emit(id: String, type: String, payload: String = "{}") {
        val js = "window.__lujainSpeechEvent && window.__lujainSpeechEvent(" +
            "${JSONObject.quote(id)},${JSONObject.quote(type)},${JSONObject.quote(payload)})"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
