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

import org.json.JSONArray;

import java.util.ArrayList;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        makeWindowDark();

        webView = new WebView(this);
        webView.setBackgroundColor(Color.BLACK);
        setContentView(webView);

        configureWebView();

        webView.addJavascriptInterface(new BrowserBridge(), "Android");
        webView.loadUrl("file:///android_asset/index.html");
    }

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
        s.setAllowFileAccess(true);
        s.setAllowContentAccess(true);
        s.setAllowFileAccessFromFileURLs(true);
        // Allows file:///android_asset/ to fetch() the FTP HTTP server — no CORS
        s.setAllowUniversalAccessFromFileURLs(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        s.setSaveFormData(false);

        webView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
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

    // ─── Bridge ───────────────────────────────────────────────────────────────

    private class BrowserBridge {

        /**
         * Open a video and pass the FULL episode list to MX Player so
         * prev / next navigation works like a local folder.
         *
         * MX Player reads the "video_list" parcelable ArrayList<Uri> extra
         * and "video_list.name" ArrayList<String> extra natively.
         *
         * @param urlsJson   JSON array of all video URLs in the folder, e.g. ["http://...ep1","http://...ep2",...]
         * @param titlesJson JSON array of matching display names
         * @param startIndex Which item in the list to start playing (0-based)
         */
        @JavascriptInterface
        public void openVideoWithPlaylist(String urlsJson, String titlesJson, int startIndex) {
            runOnUiThread(() -> {
                try {
                    JSONArray urlsArr   = new JSONArray(urlsJson);
                    JSONArray titlesArr = new JSONArray(titlesJson);

                    ArrayList<Uri>   uriList  = new ArrayList<>();
                    ArrayList<String> nameList = new ArrayList<>();

                    for (int i = 0; i < urlsArr.length(); i++) {
                        uriList.add(Uri.parse(urlsArr.getString(i)));
                        nameList.add(titlesArr.getString(i));
                    }

                    // Clamp startIndex just in case
                    int idx = Math.max(0, Math.min(startIndex, uriList.size() - 1));

                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setPackage("com.mxtech.videoplayer.ad");
                    // setData points to the starting episode
                    intent.setDataAndType(uriList.get(idx), "video/*");
                    // video_list = full playlist → MX Player enables prev/next
                    intent.putParcelableArrayListExtra("video_list", uriList);
                    intent.putStringArrayListExtra("video_list.name", nameList);
                    startActivity(intent);

                } catch (Exception e) {
                    // Fallback: play single video without playlist
                    try {
                        JSONArray urlsArr = new JSONArray(urlsJson);
                        int idx = Math.max(0, Math.min(startIndex, urlsArr.length() - 1));
                        Intent fallback = new Intent(Intent.ACTION_VIEW);
                        fallback.setDataAndType(Uri.parse(urlsArr.getString(idx)), "video/*");
                        fallback.setPackage("com.mxtech.videoplayer.ad");
                        startActivity(fallback);
                    } catch (Exception ignored) {
                        showToast("Playback error: " + e.getMessage());
                    }
                }
            });
        }

        /**
         * Called by JS when the user presses back at the root view.
         */
        @JavascriptInterface
        public void exitApp() {
            runOnUiThread(() -> finish());
        }

        /**
         * Brief Android Toast for error feedback.
         */
        @JavascriptInterface
        public void showToast(String message) {
            runOnUiThread(() ->
                Toast.makeText(MainActivity.this, message, Toast.LENGTH_SHORT).show()
            );
        }
    }
}
