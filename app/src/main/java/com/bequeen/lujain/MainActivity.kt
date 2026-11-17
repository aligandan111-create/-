package com.bequeen.lujain

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.webkit.PermissionRequest
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/**
 * Lujain — one full-screen WebView showing file:///android_asset/www/index.html, plus two native bridges:
 *   LujainSpeech  – Android SpeechRecognizer (API A)
 *   LujainTTS     – Android TextToSpeech
 */
class MainActivity : ComponentActivity() {

    private lateinit var root: FrameLayout
    private lateinit var webView: WebView
    private lateinit var speech: SpeechBridge
    private lateinit var tts: TtsBridge

    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    private fun hasMic(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        // Full-screen WebView inside a frame. The frame is padded for the status bar, navigation
        // buttons, camera cutout and keyboard, so the page never runs under them.
        webView = WebView(this)
        root = FrameLayout(this)
        root.setBackgroundColor(Color.parseColor("#0F1428"))
        root.addView(webView, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            v.setPadding(b.left, b.top, b.right, maxOf(b.bottom, ime.bottom))
            WindowInsetsCompat.CONSUMED
        }

        with(webView.settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            mediaPlaybackRequiresUserGesture = false
            textZoom = 100
        }

        webView.webChromeClient = object : WebChromeClient() {
            override fun onPermissionRequest(request: PermissionRequest) {
                runOnUiThread {
                    if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in request.resources && hasMic())
                        request.grant(arrayOf(PermissionRequest.RESOURCE_AUDIO_CAPTURE))
                    else request.deny()
                }
            }
        }

        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String?) = matchBarsToPage()

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url
                if (url.scheme == "file") return false
                // Any outside link opens in the browser.
                runCatching { startActivity(Intent(Intent.ACTION_VIEW, url)) }
                return true
            }
        }

        speech = SpeechBridge(this, webView)
        tts = TtsBridge(this, webView)
        webView.addJavascriptInterface(speech, "LujainSpeech")
        webView.addJavascriptInterface(tts, "LujainTTS")

        webView.loadUrl("file:///android_asset/www/index.html")

        // Microphone permission at startup.
        if (!hasMic()) micPermission.launch(Manifest.permission.RECORD_AUDIO)

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

    /** Paint the strips behind the clock and the navigation buttons in the page's own background color. */
    private fun matchBarsToPage() {
        webView.evaluateJavascript("getComputedStyle(document.body).backgroundColor") { v ->
            val m = Regex("(\\d+)\\D+(\\d+)\\D+(\\d+)").find(v ?: "") ?: return@evaluateJavascript
            val (r, g, b) = m.destructured.toList().map { it.toInt() }
            root.setBackgroundColor(Color.rgb(r, g, b))
            val light = (0.299 * r + 0.587 * g + 0.114 * b) > 150
            WindowCompat.getInsetsController(window, root).apply {
                isAppearanceLightStatusBars = light
                isAppearanceLightNavigationBars = light
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::webView.isInitialized) matchBarsToPage()
    }

    override fun onPause() {
        super.onPause()
        speech.stopFromNative()
        tts.stopFromNative()
    }

    override fun onDestroy() {
        speech.destroy()
        tts.destroy()
        webView.destroy()
        super.onDestroy()
    }
}
