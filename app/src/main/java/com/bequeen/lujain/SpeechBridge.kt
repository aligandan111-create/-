package com.bequeen.lujain

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognitionService
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
 *   hasPermission(), isAvailable(), openSettings(), engine()
 *
 * 1.2.1 fixes for "the mic keeps saying it needs internet":
 *  - ONE recognizer is kept and reused. Destroying and recreating it for every phrase makes Google's
 *    service drop the connection (error 11 "server disconnected"), which used to show up as "network".
 *  - Google's own recognition service is preferred when it is installed (some phones default to
 *    another engine that only works online or with its own assistant switched on).
 *  - Short failures are retried here, quietly: a dropped service is rebuilt, a network failure is
 *    retried offline (downloaded languages / on-device recognizer on Android 13+).
 *  - Every listening session has a number, so a late callback from an old session can never end
 *    the new one.
 */
class SpeechBridge(private val activity: MainActivity, private val webView: WebView) {

    private var recognizer: SpeechRecognizer? = null
    private var onDevice = false
    private var lang = "en-US"
    private var continuous = false
    private var active = false
    private var session = 0
    private var retries = 0
    private var preferOffline = false
    private var heardSomething = false

    @JavascriptInterface
    fun hasPermission(): Boolean = activity.hasMic()

    @JavascriptInterface
    fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(activity) ||
            (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity))

    /** Which engine is in use, for the in-app mic check. */
    @JavascriptInterface
    fun engine(): String = if (onDevice) "on-device" else (googleService()?.packageName ?: "default")

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
            val my = ++session                       // anything still running belongs to an older session
            if (active) { active = false; runCatching { recognizer?.cancel() } }
            activity.ensureMic { granted ->
                if (my != session) return@ensureMic
                if (!granted) { emit("error", "not-allowed"); emit("end"); return@ensureMic }
                if (!isAvailable()) { emit("error", "service-not-allowed"); emit("end"); return@ensureMic }
                this.lang = lang.ifBlank { "en-US" }; this.continuous = continuous
                active = true; retries = 0; preferOffline = false; heardSomething = false
                listen()
            }
        }
    }

    @JavascriptInterface
    fun stop() { activity.runOnUiThread { if (active) { continuous = false; recognizer?.stopListening() } } }

    @JavascriptInterface
    fun abort() { activity.runOnUiThread { abortFromNative() } }

    fun abortFromNative() {
        session++
        if (!active) return
        active = false
        runCatching { recognizer?.cancel() }
        emit("end")
    }

    fun destroy() { session++; active = false; runCatching { recognizer?.destroy() }; recognizer = null }

    private fun googleService(): ComponentName? {
        val list = runCatching {
            activity.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        }.getOrNull().orEmpty()
        val prefer = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
        for (p in prefer) list.firstOrNull { it.serviceInfo?.packageName == p }?.let {
            return ComponentName(it.serviceInfo.packageName, it.serviceInfo.name)
        }
        return null
    }

    private fun build(useOnDevice: Boolean) {
        runCatching { recognizer?.destroy() }
        onDevice = useOnDevice
        recognizer = when {
            useOnDevice && Build.VERSION.SDK_INT >= 33 -> SpeechRecognizer.createOnDeviceSpeechRecognizer(activity)
            !SpeechRecognizer.isRecognitionAvailable(activity) && Build.VERSION.SDK_INT >= 33 &&
                SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) -> { onDevice = true; SpeechRecognizer.createOnDeviceSpeechRecognizer(activity) }
            else -> googleService()?.let { SpeechRecognizer.createSpeechRecognizer(activity, it) }
                ?: SpeechRecognizer.createSpeechRecognizer(activity)
        }
        val my = session
        recognizer?.setRecognitionListener(Listener(my))
    }

    private fun listen() {
        if (recognizer == null) build(false)
        else recognizer?.setRecognitionListener(Listener(session))
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, activity.packageName)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline)
        try { recognizer?.startListening(intent) }
        catch (t: Throwable) { retryOrFail("aborted", rebuild = true) }
    }

    /** Try again quietly a couple of times before telling the page. */
    private fun retryOrFail(code: String, rebuild: Boolean, offline: Boolean = false) {
        if (!active) return
        if (retries < 2) {
            retries++
            if (offline) {
                preferOffline = true
                if (Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(activity) && !onDevice) build(true)
            } else if (rebuild) build(onDevice)
            val my = session
            webView.postDelayed({ if (active && my == session) listen() }, 400L * retries)
            return
        }
        active = false
        emit("error", code)
        emit("end")
    }

    private fun phraseDone() {
        retries = 0
        if (active && continuous) webView.postDelayed({ if (active) listen() }, 200)
        else { active = false; emit("end") }
    }

    private inner class Listener(private val my: Int) : RecognitionListener {
        private fun stale() = my != session
        override fun onReadyForSpeech(params: Bundle?) { if (!stale()) emit("start") }
        override fun onPartialResults(partial: Bundle?) {
            if (stale()) return
            val t = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) { heardSomething = true; emit("result", t, false) }
        }
        override fun onResults(results: Bundle?) {
            if (stale()) return
            val t = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (t.isNotBlank()) { heardSomething = true; emit("result", t, true) }
            phraseDone()
        }
        override fun onError(error: Int) {
            if (stale() || !active) return
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    if (continuous) { phraseDone(); return }
                    active = false; emit("error", "no-speech"); emit("end")
                }
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> { active = false; emit("error", "not-allowed"); emit("end") }
                SpeechRecognizer.ERROR_SERVER_DISCONNECTED, SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> retryOrFail("busy", rebuild = true)
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                SpeechRecognizer.ERROR_SERVER -> retryOrFail("network", rebuild = false, offline = true)
                SpeechRecognizer.ERROR_AUDIO -> retryOrFail("audio-capture", rebuild = true)
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED, SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    // the offline/on-device engine lacks this language: go back online once
                    if (onDevice || preferOffline) {
                        preferOffline = false
                        if (onDevice) build(false)
                        retryOrFail("language-not-supported", rebuild = false)
                    } else { active = false; emit("error", "language-not-supported"); emit("end") }
                }
                else -> retryOrFail("aborted", rebuild = true)
            }
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
