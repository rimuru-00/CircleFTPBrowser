package com.circleftp.browser;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.view.Window;
import android.view.WindowManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileWriter;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Full-screen dark window — no white flash during load
        makeWindowDark();

        // Use WebView directly as the root view; no XML layout needed
        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        configureWebView();

        // Register the Java → JS bridge (callable as window.Android.xxx in JS)
        webView.addJavascriptInterface(new BrowserBridge(), "Android");

        // Load the UI from the bundled asset
        webView.loadUrl("file:///android_asset/index.html");
    }

    /**
     * Hardware back button: ask the JS layer to handle navigation.
     * JS calls Android.exitApp() when it is already at the root view.
     */
    @SuppressWarnings("deprecation")
    @Override
    public void onBackPressed() {
        webView.evaluateJavascript("goBack()", null);
    }

    // ─── WebView configuration ────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    private void configureWebView() {
        WebSettings s = webView.getSettings();

        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);

        // These two are the key settings:
        // setAllowUniversalAccessFromFileURLs lets our file:///android_asset/index.html
        // make fetch() requests to http://ftp15.circleftp.net/ without CORS errors.
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        s.setAllowUniversalAccessFromFileURLs(true);

        // Allow HTTP content (the FTP server is plain HTTP, not HTTPS)
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);

        // Don't save form data or passwords
        s.setSaveFormData(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                // We never want the WebView to navigate away from index.html.
                // All navigation is handled inside JS via fetch(); page loads
                // should not happen except the initial file:// load.
                return false;
            }
        });
    }

    private void makeWindowDark() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(Color.BLACK);
            w.setNavigationBarColor(Color.BLACK);
        }
    }

    // ─── Bridge (called from JavaScript as window.Android.method()) ───────────

    private class BrowserBridge {

        /**
         * Stream a single video file in MX Player.
         * Called when the user taps an episode in the list.
         *
         * @param url   Direct HTTP URL to the .mkv/.mp4/etc file on the FTP server
         * @param title Display name shown as the video title inside MX Player
         */
        @JavascriptInterface
        public void openVideo(String url, String title) {
            runOnUiThread(() -> {
                try {
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(Uri.parse(url), "video/*");
                    intent.setPackage("com.mxtech.videoplayer.ad");
                    intent.putExtra("title", title);
                    startActivity(intent);
                } catch (Exception primary) {
                    // MX Player not found or failed — let the user pick any player
                    try {
                        Intent fallback = new Intent(Intent.ACTION_VIEW);
                        fallback.setDataAndType(Uri.parse(url), "video/*");
                        startActivity(Intent.createChooser(fallback, "Open video with…"));
                    } catch (Exception ignored) {
                        showToast("No video player found");
                    }
                }
            });
        }

        /**
         * Open a full season as an M3U playlist in MX Player.
         * MX Player treats this exactly like a local folder — the user gets
         * native previous/next episode navigation.
         *
         * Flow:
         *   JS builds the #EXTM3U string → passes it here →
         *   we write it to cache → FileProvider shares it with MX Player.
         *
         * We must use FileProvider because MX Player cannot read files
         * from our private getCacheDir() directly on Android 7+.
         *
         * @param m3uContent Full M3U playlist text (built by JS)
         */
        @JavascriptInterface
        public void openPlaylist(String m3uContent) {
            runOnUiThread(() -> {
                try {
                    // Write M3U to cache
                    File m3uFile = new File(getCacheDir(), "circleftp_playlist.m3u");
                    FileWriter fw = new FileWriter(m3uFile, false); // overwrite
                    fw.write(m3uContent);
                    fw.close();

                    // Get a shareable URI via FileProvider
                    Uri uri = FileProvider.getUriForFile(
                        MainActivity.this,
                        getPackageName() + ".provider",
                        m3uFile
                    );

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setDataAndType(uri, "audio/x-mpegurl");
                    intent.setPackage("com.mxtech.videoplayer.ad");
                    // CRITICAL: grant read permission for this one-time URI
                    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    startActivity(intent);

                } catch (Exception e) {
                    showToast("Playlist error: " + e.getMessage());
                }
            });
        }

        /**
         * Called by JS when the user presses back at the root view.
         * Exits the app cleanly.
         */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> finish());
        }

        /**
         * Show a brief Android Toast (used for error feedback).
         */
        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()
            );
        }
    }
}
