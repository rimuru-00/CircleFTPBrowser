package com.circleftp.browser;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
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
import org.json.JSONObject;

public class MainActivity extends AppCompatActivity {

    private WebView webView;

    // MX Player free-edition package + activity class, per the official intent spec
    // (sites.google.com/site/mxvpen/api). Needed verbatim — by name — only to receive
    // a playback result; everyday launching still works via setPackage() alone.
    private static final String MX_PACKAGE      = "com.mxtech.videoplayer.ad";
    private static final String MX_ACTIVITY     = "com.mxtech.videoplayer.ad.ActivityScreen";
    private static final int    REQ_MX_PLAYBACK = 4242;

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

    /**
     * Receives MX Player's playback result — IF the installed build still
     * honors "return_result" the way stock MX Player does (see
     * openVideoWithPlaylist below for why that's not guaranteed on a
     * modified APK). When it does fire, we forward position/duration/end_by
     * straight into the JS layer; window.onPlaybackResult() in index.html
     * decides what to do with it. If it never fires, nothing breaks — the
     * app simply keeps the simple tap-to-mark-watched behavior it already had.
     */
    @SuppressWarnings("deprecation")
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_MX_PLAYBACK) return;

        try {
            JSONObject result = new JSONObject();
            result.put("ok", resultCode == RESULT_OK);
            if (data != null) {
                Uri lastUri = data.getData();
                result.put("uri", lastUri != null ? lastUri.toString() : JSONObject.NULL);
                if (data.hasExtra("position")) result.put("position", data.getIntExtra("position", -1));
                if (data.hasExtra("duration")) result.put("duration", data.getIntExtra("duration", -1));
                String endBy = data.getStringExtra("end_by");
                result.put("end_by", endBy != null ? endBy : JSONObject.NULL);
            }
            final String js = "window.onPlaybackResult && window.onPlaybackResult(" + result.toString() + ")";
            runOnUiThread(() -> webView.evaluateJavascript(js, null));
        } catch (Exception ignored) {
            // A malformed/unexpected result should never crash the app —
            // worst case, this one playback session just doesn't get tracked.
        }
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
         * Per MX Player's official intent spec (sites.google.com/site/mxvpen/api),
         * "video_list" MUST be a Parcelable[] (Uri[]) and "video_list.name" MUST be
         * a String[] — NOT ArrayLists. putParcelableArrayListExtra/putStringArrayListExtra
         * store ArrayLists under the hood; MX Player calls getParcelableArrayExtra()
         * on its side, which silently fails to cast an ArrayList to an array (catches
         * the ClassCastException internally and returns null) instead of crashing.
         * That's why it was falling back to single-video playback with no prev/next.
         *
         * Position/duration tracking: per the same spec, http/https sources only
         * return a result (position, duration, end_by) if the intent targets MX
         * Player's activity by explicit class name, not just by package. We try
         * that first. If this MX Player build has been modified enough that its
         * internal class name no longer matches the stock free-edition name, the
         * explicit-component intent fails to resolve immediately (caught below)
         * and we fall straight back to the exact intent that has always worked
         * here — so this change can add tracking, but can never break playback.
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

                    int count = urlsArr.length();
                    Uri[]    uriArr  = new Uri[count];
                    String[] nameArr = new String[count];

                    for (int i = 0; i < count; i++) {
                        uriArr[i]  = Uri.parse(urlsArr.getString(i));
                        nameArr[i] = titlesArr.getString(i);
                    }

                    // Clamp startIndex just in case
                    int idx = Math.max(0, Math.min(startIndex, count - 1));

                    // ── Attempt 1: explicit component + return_result, so we can
                    // capture real playback position/duration on exit.
                    try {
                        Intent rich = new Intent(Intent.ACTION_VIEW);
                        rich.setClassName(MX_PACKAGE, MX_ACTIVITY);
                        rich.setDataAndType(uriArr[idx], "video/*");
                        rich.putExtra("video_list", uriArr);
                        rich.putExtra("video_list.name", nameArr);
                        rich.putExtra("video_list_is_explicit", true);
                        rich.putExtra("return_result", true);
                        startActivityForResult(rich, REQ_MX_PLAYBACK);
                        return;
                    } catch (ActivityNotFoundException notFound) {
                        // This build's ActivityScreen class name doesn't match —
                        // fall through to the known-good path below.
                    }

                    // ── Attempt 2: same playback, no result. This is exactly
                    // what's always worked on this device.
                    Intent intent = new Intent(Intent.ACTION_VIEW);
                    intent.setPackage(MX_PACKAGE);
                    intent.setDataAndType(uriArr[idx], "video/*");
                    intent.putExtra("video_list", uriArr);
                    intent.putExtra("video_list.name", nameArr);
                    intent.putExtra("video_list_is_explicit", true);
                    startActivity(intent);

                } catch (Exception e) {
                    // Fallback: play single video without playlist
                    try {
                        JSONArray urlsArr = new JSONArray(urlsJson);
                        int idx = Math.max(0, Math.min(startIndex, urlsArr.length() - 1));
                        Intent fallback = new Intent(Intent.ACTION_VIEW);
                        fallback.setDataAndType(Uri.parse(urlsArr.getString(idx)), "video/*");
                        fallback.setPackage(MX_PACKAGE);
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
         * Opens an external URL with the system's default handler — e.g. a
         * t.me link opens straight into the Telegram app if installed,
         * otherwise falls back to the browser. Needed because
         * shouldOverrideUrlLoading() returns false above, which means a
         * plain <a href> click would otherwise navigate the WebView itself
         * away from index.html instead of leaving the app.
         */
        @JavascriptInterface
        public void openExternalLink(String url) {
            runOnUiThread(() -> {
                try {
                    startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
                } catch (Exception e) {
                    showToast("Couldn't open link");
                }
            });
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
