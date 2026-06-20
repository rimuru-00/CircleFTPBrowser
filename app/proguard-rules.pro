# ProGuard rules for CircleFTP Browser

# Keep the JavascriptInterface bridge methods — they are called by name
# from JavaScript and would be stripped by ProGuard without this rule.
-keepclassmembers class com.circleftp.browser.MainActivity$BrowserBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# Keep the outer activity (needed to call runOnUiThread, etc.)
-keep class com.circleftp.browser.MainActivity { *; }

# AndroidX FileProvider
-keep class androidx.core.content.FileProvider { *; }
