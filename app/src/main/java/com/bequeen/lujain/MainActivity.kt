package com.bequeen.lujain

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

/**
 * Lujain — one WebView that shows the app (assets/www/index.html) plus two native bridges:
 *   LujainSpeech  – Android speech recognition (the WebView has none)
 *   LujainTTS     – Android text-to-speech (the WebView has none)
 */
class MainActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var speech: SpeechBridge
    private lateinit var tts: TtsBridge

    private var micCallback: ((Boolean) -> Unit)? = null
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        micCallback?.invoke(granted); micCallback = null
    }

    fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Used by the mic buttons: asks again if the user said no the first time. */
    fun ensureMic(cb: (Boolean) -> Unit) {
        if (hasMic()) cb(true)
        else { micCallback = cb; micPermission.launch(Manifest.permission.RECORD_AUDIO) }
    }

    /** First launch after install: ask for the microphone once, without waiting for a mic tap. */
    private fun askMicOnFirstLaunch() {
        val prefs = getSharedPreferences("lujain", MODE_PRIVATE)
        if (prefs.getBoolean("mic_asked", false) || hasMic()) return
        prefs.edit().putBoolean("mic_asked", true).apply()
        micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // Keep content clear of the status bar, navigation bar and keyboard (Android 15+ is edge-to-edge).
        ViewCompat.setOnApplyWindowInsetsListener(webView) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            WindowInsetsCompat.CONSUMED
        }

        // Serve the app from https://appassets.androidplatform.net so it runs as a secure origin.
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            allowFileAccess = false
            allowContentAccess = false
            textZoom = 100
        }
        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                assetLoader.shouldInterceptRequest(request.url)

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.host == "appassets.androidplatform.net") return false
                // Any outside link opens in the browser.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }

        speech = SpeechBridge(this, webView)
        tts = TtsBridge(this, webView)
        webView.addJavascriptInterface(speech, "LujainSpeech")
        webView.addJavascriptInterface(tts, "LujainTTS")

        webView.loadUrl("https://appassets.androidplatform.net/assets/www/index.html")
        askMicOnFirstLaunch()

        // Back button: close an open sheet/drawer first, otherwise leave the app.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){var m=document.querySelector('.modal.open');if(m){m.classList.remove('open');return true;}" +
                        "var d=document.getElementById('trDrawer');if(d&&d.open){d.open=false;return true;}return false;})()"
                ) { handled ->
                    if (handled != "true") { isEnabled = false; onBackPressedDispatcher.onBackPressed() }
                }
            }
        })
    }

    override fun onPause() {
        super.onPause()
        speech.abortFromNative()
        tts.stopFromNative()
    }

    override fun onDestroy() {
        speech.destroy()
        tts.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
