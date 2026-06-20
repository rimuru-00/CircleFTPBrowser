# CircleFTP Browser

A native Android file browser for local-network h5ai FTP servers.
Tap a folder to navigate. Tap an episode to stream in MX Player.
Tap **Play All** to queue an entire season — prev/next works like local files.

---

## Requirements

| Tool             | Version           |
|------------------|-------------------|
| Android Studio   | Giraffe or newer  |
| Android SDK      | API 34 (compile)  |
| JDK              | 17 (bundled with Android Studio) |
| MX Player        | `com.mxtech.videoplayer.ad` installed on device |
| Network          | Device must be on the same WiFi as the FTP server |

---

## Build Steps

### 1 — Open in Android Studio

1. Launch Android Studio
2. **File → Open** → select the `circleftp-browser/` folder (this folder)
3. Wait for Gradle sync to finish (first sync downloads dependencies, ~1 min)

### 2 — Set SDK path (if prompted)

Android Studio may ask you to set the SDK location.
If it doesn't auto-detect, go to **File → Project Structure → SDK Location**
and point it at your Android SDK folder.

### 3 — Build the APK

**Option A — Android Studio UI:**
- Menu: **Build → Build Bundle(s) / APK(s) → Build APK(s)**
- Output: `app/build/outputs/apk/debug/app-debug.apk`

**Option B — Command line:**
```bash
# On Linux/Mac
chmod +x gradlew
./gradlew assembleDebug

# On Windows
gradlew.bat assembleDebug
```
Output is at: `app/build/outputs/apk/debug/app-debug.apk`

### 4 — Install on device

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```
Or just copy the APK to your phone and tap to install
(enable "Install unknown apps" in Settings for your file manager).

---

## Configuration

All configuration is inside `app/src/main/assets/index.html`, at the top:

```javascript
const CONFIG = {
    roots: [
        {
            name : "Anime Series",
            icon : "🎌",
            url  : "http://ftp15.circleftp.net/FILE/English%20%26%20Foreign%20Anime%20Series/",
            desc : "ftp15.circleftp.net"
        }
        // Add more roots here
    ],
    videoExts: ['.mkv', '.mp4', '.avi', '.m4v', '.webm', '.ts',
                '.flv', '.wmv', '.m2ts', '.mov', '.rmvb']
};
```

Edit `url` to change the FTP server or root directory.
Add more objects to `roots` to show multiple servers/folders on the home screen.

---

## How It Works

```
Phone (APK)
│
├─ WebView (CORS-free, can fetch any URL)
│   └─ index.html  →  fetch("http://ftp15.circleftp.net/anime/...")
│                       └─ parses h5ai HTML listing
│                       └─ renders folder / episode list
│
├─ BrowserBridge (Java ↔ JavaScript)
│   ├─ openVideo(url, title)
│   │   └─ Intent → MX Player  (streams the .mkv directly)
│   └─ openPlaylist(m3uContent)
│       └─ writes .m3u to cache
│       └─ FileProvider URI → MX Player  (native prev/next)
│
└─ Back button → goBack() in JS → folder navigation or app exit
```

---

## File Structure

```
circleftp-browser/
├── README.md
├── PROJECT_CONTEXT.md
├── build.gradle                        root Gradle config
├── settings.gradle
├── gradle.properties
├── gradlew / gradlew.bat
├── gradle/wrapper/
│   └── gradle-wrapper.properties
└── app/
    ├── build.gradle                    app module config
    ├── proguard-rules.pro
    └── src/main/
        ├── AndroidManifest.xml         INTERNET permission, FileProvider
        ├── assets/
        │   └── index.html              ★ entire UI + logic lives here
        ├── java/com/circleftp/browser/
        │   └── MainActivity.java       WebView host + MX Player bridge
        └── res/
            ├── values/
            │   └── strings.xml
            └── xml/
                ├── network_security_config.xml   allows plain HTTP
                └── provider_paths.xml            FileProvider cache path
```

---

## Troubleshooting

| Problem | Fix |
|---------|-----|
| Blank screen on open | Check WiFi — device must be on local network |
| "Could not load folder" | Verify FTP URL in CONFIG matches your server |
| MX Player doesn't open | Confirm `com.mxtech.videoplayer.ad` is installed |
| Play All does nothing | Make sure the folder contains `.mkv`/`.mp4` files |
| HTTP blocked on Android 9+ | Already handled via `network_security_config.xml` |
