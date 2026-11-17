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

    // 1.3.1: offline-first with ONE quiet online retry per listening cycle
    private var preferOffline = true      // first attempt of a session: offline preferred
    private var retried = false           // the quiet retry was already used in this cycle
    private var heard = false             // a partial/final arrived in this cycle
    private var startSent = false         // "start" already sent to JS for this session
    private var dirty = false             // recognizer was cancelled: build a fresh one before reuse

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
            preferOffline = true; startSent = false
            newCycle()
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
        dirty = true                                   // late callbacks of a cancel must not reach the next session
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

    private fun newCycle() { retried = false; heard = false }

    private fun freshRecognizer() {
        runCatching { recognizer?.destroy() }
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity).also { it.setRecognitionListener(listener) }
        dirty = false
    }

    private fun listen() {
        val id = curId ?: return
        if (recognizer == null || dirty) freshRecognizer()
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, interim)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        try {
            recognizer?.startListening(intent)
        } catch (t: Throwable) {
            if (quietRetry()) return
            curId = null
            emit(id, "error", err("aborted", SpeechRecognizer.ERROR_CLIENT)); emit(id, "end")
        }
    }

    /**
     * First attempt failed before hearing anything: retry ONCE, silently, with a fresh recognizer
     * and EXTRA_PREFER_OFFLINE=false (same id, lang, interim, continuous). Returns true if retrying.
     */
    private fun quietRetry(): Boolean {
        if (retried || heard || curId == null) return false
        retried = true
        preferOffline = false
        val id = curId
        freshRecognizer()
        main.postDelayed({ if (curId == id) listen() }, 250)
        return true
    }

    private fun sendStartOnce(id: String) {
        if (!startSent) { startSent = true; emit(id, "start") }
    }

    private val listener = object : RecognitionListener {
        // "start" is held back on an offline first attempt until it proves itself (speech/results),
        // so a failed first attempt sends no JS event at all.
        override fun onReadyForSpeech(params: Bundle?) {
            val id = curId ?: return
            if (!preferOffline || retried) sendStartOnce(id)
        }
        override fun onBeginningOfSpeech() { curId?.let { sendStartOnce(it) } }

        override fun onPartialResults(partial: Bundle?) {
            val id = curId ?: return
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) { heard = true; sendStartOnce(id); emit(id, "partial", text(t)) }
        }

        override fun onResults(results: Bundle?) {
            val id = curId ?: return
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            heard = true; sendStartOnce(id)
            emit(id, "final", text(t))
            if (continuous) {
                // continuous: keep listening, no "end" (short pause so the engine is free again)
                main.postDelayed({ if (curId == id) { newCycle(); listen() } }, 150)
            } else {
                curId = null
                emit(id, "end")
            }
        }

        override fun onError(error: Int) {
            val id = curId ?: return                       // cancelled session: already ended
            // nothing heard yet in this cycle -> one quiet retry online (not for a missing permission)
            if (error != SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS && quietRetry()) return
            curId = null
            val name = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> "network"
                else -> "aborted"                          // ERROR_CLIENT, ERROR_RECOGNIZER_BUSY, anything else
            }
            emit(id, "error", err(name, error))
            emit(id, "end")
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun text(t: String) = JSONObject().put("text", t).toString()
    /** {"error":"<web-speech-name>","code":<raw Android error code>} (code omitted for errors raised before Android is asked) */
    private fun err(name: String, code: Int? = null) =
        JSONObject().put("error", name).apply { if (code != null) put("code", code) }.toString()

    private fun emit(id: String, type: String, payload: String = "{}") {
        val js = "window.__lujainSpeechEvent && window.__lujainSpeechEvent(" +
            "${JSONObject.quote(id)},${JSONObject.quote(type)},${JSONObject.quote(payload)})"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
