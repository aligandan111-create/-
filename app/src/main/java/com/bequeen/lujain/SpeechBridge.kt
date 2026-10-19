package com.bequeen.lujain

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONObject

/**
 * Speech recognition for the WebView (exposed to JS as "LujainSpeech").
 * bridge.js turns it into a standard window.SpeechRecognition, so the web code is unchanged.
 *
 *   start(lang, continuous, interim) / stop() / abort()  -> window.__lujainSR(type, text, isFinal)
 *   hasPermission(), isAvailable(), openSettings()        -> used by the page's "Mic check" and error sheet
 *
 * Everything runs on the main thread (JS calls arrive on a background thread), the mic
 * permission is asked at runtime, and on Android 13+ the on-device recognizer is used
 * when no online recognition service is installed.
 */
class SpeechBridge(private val activity: MainActivity, private val webView: WebView) {

    private var recognizer: SpeechRecognizer? = null
    private var lang = "en-US"
    private var continuous = false
    private var active = false

    @JavascriptInterface
    fun hasPermission(): Boolean = activity.hasMic()

    @JavascriptInterface
    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(activity) ||
            (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity))

    @JavascriptInterface
    fun openSettings() {
        activity.runOnUiThread {
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", activity.packageName, null))
                )
            }
        }
    }

    @JavascriptInterface
    fun start(lang: String, continuous: Boolean, interim: Boolean) {
        activity.runOnUiThread {
            if (active) { active = false; runCatching { recognizer?.cancel() }; emit("end") }
            activity.ensureMic { granted ->
                if (!granted) { emit("error", "not-allowed"); emit("end"); return@ensureMic }
                if (!isAvailable()) { emit("error", "service-not-allowed"); emit("end"); return@ensureMic }
                this.lang = lang.ifBlank { "en-US" }; this.continuous = continuous; active = true
                listen()
            }
        }
    }

    @JavascriptInterface
    fun stop() { activity.runOnUiThread { active = false; recognizer?.stopListening() } }

    @JavascriptInterface
    fun abort() { activity.runOnUiThread { abortFromNative() } }

    fun abortFromNative() {
        if (!active && recognizer == null) return
        val wasActive = active
        active = false
        runCatching { recognizer?.cancel() }
        if (wasActive) emit("end")
    }

    fun destroy() { recognizer?.destroy(); recognizer = null }

    private fun createRecognizer(): SpeechRecognizer =
        if (!SpeechRecognizer.isRecognitionAvailable(activity) && Build.VERSION.SDK_INT >= 33 &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(activity)
        ) SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
        else SpeechRecognizer.createSpeechRecognizer(activity)

    private fun listen() {
        recognizer?.destroy()
        recognizer = createRecognizer().also { it.setRecognitionListener(listener) }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
        try { recognizer?.startListening(intent) }
        catch (t: Throwable) { active = false; emit("error", "aborted"); emit("end") }
    }

    private fun restartOrEnd() {
        if (active && continuous) webView.postDelayed({ if (active) listen() }, 150)
        else { active = false; emit("end") }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) { emit("start") }
        override fun onPartialResults(partial: Bundle?) {
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) emit("result", t, false)
        }
        override fun onResults(results: Bundle?) {
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) emit("result", t, true)
            restartOrEnd()
        }
        override fun onError(error: Int) {
            val code = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "no-speech"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "not-allowed"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER, SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "network"
                SpeechRecognizer.ERROR_AUDIO -> "audio-capture"
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> "language-not-supported"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "busy"
                SpeechRecognizer.ERROR_CLIENT -> return          // follows cancel(); "end" was already sent
                else -> "aborted"
            }
            if (code == "no-speech" && active && continuous) { restartOrEnd(); return }
            active = false
            emit("error", code)
            emit("end")
        }
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun emit(type: String, text: String = "", isFinal: Boolean = false) {
        val js = "window.__lujainSR && window.__lujainSR(${JSONObject.quote(type)},${JSONObject.quote(text)},$isFinal)"
        webView.post { webView.evaluateJavascript(js, null) }
    }
}
