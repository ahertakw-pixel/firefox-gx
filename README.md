# FireFox GX

FireFox GX is a Chromium-based desktop browser (Electron) with an Android WebView port.

## Android APK

Open the `android` folder in Android Studio, or use the Gradle wrapper from that folder:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

Android includes the browser home page, search/address bar, internal website view, navigation controls, quick links, sidebar, themes, and an extension manager. Android imports ZIP archives of unpacked Chrome extensions and injects compatible manifest `content_scripts` JavaScript and CSS on matching sites. It does not install `.crx` packages or provide all Chrome extension APIs; service workers, browser toolbar actions, and many extensions are unsupported. Desktop Chrome profile scanning is available only in the Windows build.

## Desktop

The Electron source is in the project root. Run `npm install` and `npm start` to launch it. Electron's extension loader supports a subset of Chrome extension APIs and accepts unpacked extension directories.
