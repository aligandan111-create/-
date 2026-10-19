# Keep the JavaScript bridge methods (called from the WebView by name)
-keepclassmembers class com.bequeen.lujain.** {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
