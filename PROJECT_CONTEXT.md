# CircleFTP Browser — Project Context

## What We're Building

A **standalone Android APK** that acts as a native-feeling file browser for a
local-network h5ai FTP HTTP server (CircleFTP). The goal is that it feels
exactly like browsing local files on the device — tap a folder to go in, tap an
episode to stream it, tap "Play All" and MX Player queues the whole season with
native next/previous navigation.

No server, no deployment, no PC running in the background. The APK lives on the
phone. It only needs network access to the FTP server, which is only available
on the local WiFi network anyway.

---

## The FTP Server

| Property       | Value                                                                                   |
|----------------|-----------------------------------------------------------------------------------------|
| Server         | h5ai v0.29.2 (PHP-based HTTP file index)                                                |
| Base URL       | `http://ftp15.circleftp.net/`                                                           |
| Anime root     | `http://ftp15.circleftp.net/FILE/English%20%26%20Foreign%20Anime%20Series/`             |
| Directory API  | `POST http://host/?action=get` with body `items&itemsHref=/path/&itemsWhat=1`           |

h5ai has a built-in JSON API. Every directory responds to a POST request and
returns a clean JSON array with `name`, `href`, `size`, `date`, `managed` fields.
We use this instead of HTML scraping — it's faster, more reliable, and gives
file sizes/dates for free.

### Directory Structure (example)
```
/ (root)
└── FILE/
    └── English & Foreign Anime Series/     ← our configured root
        ├── B The Beginning (2018)/
        │   └── Season 1 1080p/
        │       ├── [DragsterPS] B- The Beginning S01E01 [...].mkv   (1.58 GB)
        │       ├── [DragsterPS] B- The Beginning S01E02 [...].mkv   (1.44 GB)
        │       └── ...
        ├── Another Anime (year)/
        │   └── ...
        └── ...
```

Folders end with `/` in hrefs. Files do not. The h5ai JSON API returns
`managed: true` for real content items.

---

## The App

### Package & Identity
| Property         | Value                          |
|------------------|--------------------------------|
| App name         | CircleFTP Browser              |
| Package name     | `com.circleftp.browser`        |
| Min Android      | API 21 (Android 5.0 Lollipop) |
| Target Android   | API 34 (Android 14)            |
| Language         | Java                           |

### MX Player
| Property         | Value                          |
|------------------|--------------------------------|
| Package          | `com.mxtech.videoplayer.ad`    |
| Note             | User has a self-modified APK   |

---

## Architecture

```
APK
├── MainActivity.java          ← single Activity, hosts the WebView
│   ├── WebView setup          ← setAllowUniversalAccessFromFileURLs(true)
│   │                             so JS can fetch() FTP server with no CORS
│   ├── BrowserBridge.java     ← @JavascriptInterface methods called from JS
│   │   ├── openVideo(url, title)     → Intent to MX Player, streams the file
│   │   └── openPlaylist(m3u)         → writes M3U to cache, opens in MX Player
│   └── Back press handling    ← tells WebView to go back in folder history
│
└── assets/index.html          ← entire UI lives here (HTML + CSS + JS)
    ├── Root view              ← shows configured FTP roots as cards
    ├── Folder browser         ← fetches h5ai JSON API, renders folders/files
    ├── Breadcrumb nav         ← tap any crumb to jump back
    ├── Play All button        ← builds M3U string, calls Android.openPlaylist()
    └── File tap               ← calls Android.openVideo(url, title)
```

### Why WebView, not a native UI?
The entire folder browser UI, h5ai JSON parsing, breadcrumb logic, and M3U
generation is cleanest in JavaScript inside a WebView. The only reason we need
Java at all is to launch MX Player Intents — you can't do that from a browser.
The Java layer is a thin bridge, ~100 lines. The UI layer is pure HTML/CSS/JS.

### Why no Flask / server?
- The FTP is local-network only — a deployed server can't reach it
- Android WebView does not enforce CORS like a browser does
  (`setAllowUniversalAccessFromFileURLs(true)` lets `file://` assets fetch any URL)
- Nothing to run, nothing to maintain, no IP to configure

---

## Key Implementation Details

### h5ai JSON API call (from JavaScript)
```javascript
const response = await fetch(serverBase + '?action=get', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: 'items&itemsHref=' + encodeURIComponent(href) + '&itemsWhat=1'
});
const data = await response.json();
// data.items is an array of { href, managed, ... }
// Filter: managed === true and href !== currentHref (skip self)
// isDir: href ends with '/'
// isVideo: href ends with .mkv / .mp4 / .avi etc.
```

### Launching MX Player (Java bridge)
```java
// Single episode
@JavascriptInterface
public void openVideo(String url, String title) {
    runOnUiThread(() -> {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(url), "video/*");
        intent.setPackage("com.mxtech.videoplayer.ad");
        intent.putExtra("title", title);
        startActivity(intent);
    });
}

// Full season playlist — MX Player handles prev/next natively
@JavascriptInterface
public void openPlaylist(String m3uContent) {
    runOnUiThread(() -> {
        File f = new File(getCacheDir(), "circleftp.m3u");
        // write m3uContent to f
        // share via FileProvider
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uri, "audio/x-mpegurl");
        intent.setPackage("com.mxtech.videoplayer.ad");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(intent);
    });
}
```

### M3U format (built in JavaScript)
```
#EXTM3U
#EXTINF:-1,[DragsterPS] B- The Beginning S01E01 [1080p] [Multi-Audio] [Multi-Subs] [AA262A35].mkv
http://ftp15.circleftp.net/FILE/.../S01E01.mkv
#EXTINF:-1,[DragsterPS] B- The Beginning S01E02 [...]
http://ftp15.circleftp.net/FILE/.../S01E02.mkv
...
```
MX Player opens this as a native playlist — full prev/next, exactly like local files.

---

## File Structure
```
circleftp-browser/
├── PROJECT_CONTEXT.md             ← this file
├── build.gradle                   ← root Gradle (classpath, repos)
├── settings.gradle                ← project name, module include
├── gradle.properties              ← AndroidX, JVM args
└── app/
    ├── build.gradle               ← compileSdk 34, minSdk 21, deps
    └── src/main/
        ├── AndroidManifest.xml    ← INTERNET permission, FileProvider, single Activity
        ├── res/
        │   ├── values/
        │   │   └── strings.xml    ← app name
        │   └── xml/
        │       └── provider_paths.xml   ← FileProvider path for cache M3U
        ├── java/com/circleftp/browser/
        │   └── MainActivity.java  ← WebView setup + BrowserBridge inner class
        └── assets/
            └── index.html         ← full UI (HTML + CSS + JS, ~500 lines)
```

---

## Build Status

| File                          | Status         |
|-------------------------------|----------------|
| `build.gradle` (root)         | ✅ Done        |
| `settings.gradle`             | ✅ Done        |
| `gradle.properties`           | ✅ Done        |
| `app/build.gradle`            | ✅ Done        |
| `AndroidManifest.xml`         | ⏳ Pending     |
| `res/xml/provider_paths.xml`  | ⏳ Pending     |
| `res/values/strings.xml`      | ⏳ Pending     |
| `MainActivity.java`           | ⏳ Pending     |
| `assets/index.html`           | ⏳ Pending     |

---

## What Still Needs Building
1. `AndroidManifest.xml` — INTERNET permission, FileProvider declaration, Activity config (fullscreen, no title bar)
2. `provider_paths.xml` — exposes `getCacheDir()` so FileProvider can share the M3U file with MX Player
3. `strings.xml` — just the app name
4. `MainActivity.java` — WebView configuration + BrowserBridge inner class
5. `assets/index.html` — the full UI: dark theme file manager, h5ai JSON API fetching, breadcrumb nav, Play All → M3U

---

## UI Design Goals
- **Dark theme** — black/dark grey background, matches MX Player's own UI
- **Folder cards** on root view (one per configured FTP root)
- **List view** inside folders — folder icon (orange) / video icon (blue), filename, file size
- **Breadcrumb bar** — tap any segment to jump back up
- **Action bar** (appears when folder has videos) — "▶ Play All" and "⬇ M3U" buttons
- **Back button** — hardware back navigates folder history, exits app at root
- **No loading flicker** — skeleton/spinner while h5ai API responds

---

## Configurable Constants (in `index.html`)
```javascript
const CONFIG = {
    roots: [
        {
            name: "Anime Series",
            icon: "🎌",
            url: "http://ftp15.circleftp.net/FILE/English%20%26%20Foreign%20Anime%20Series/",
            desc: "ftp15.circleftp.net"
        }
        // Add more FTP roots / mirrors here
    ],
    videoExts: ['.mkv', '.mp4', '.avi', '.m4v', '.webm', '.ts', '.flv', '.wmv']
};
```

---

## Notes
- The h5ai JSON API sometimes returns the current directory itself as an item —
  filter it out by comparing `item.href === currentHref`
- File sizes from h5ai are in bytes — convert to GB/MB in JS for display
- The modified MX Player package name is `com.mxtech.videoplayer.ad` (confirmed)
- If MX Player is not found, fall back to `intent.setPackage(null)` +
  `createChooser()` so any video player can handle it
