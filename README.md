# FireFox GX

FireFox GX is a Chromium-based desktop browser (Electron) with an Android WebView port.

## Android APK

Open the `android` folder in Android Studio, or use the Gradle wrapper from that folder:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to `android/app/build/outputs/apk/debug/app-debug.apk`.

Android includes independent tabs, the browser home page, address/search bar, internal Chromium WebView, quick links, themes, file downloads, `.fgxe` extension import/export, and Aurora settings. WebView runs compatible manifest `content_scripts` JavaScript and CSS; it does not provide all Chrome or Firefox extension APIs, background workers, or browser action popups. Android cannot read another browser's private profile folders, so import a ZIP or `.fgxe` file instead.

## Desktop

The Electron source is in the project root. Run `npm install` and `npm start` to launch it. It uses Chromium with independent tabs, built-in download handling, desktop Chrome and Firefox profile scanning, and a glassy themed UI. Extension API compatibility varies; Firefox add-ons and Chrome extensions are not guaranteed to work in Electron.

## Extensions and developer commands

- Enter `://extensions` in the address bar to open the extension library.
- Enter `://devextensions` for the manifest starter and import/publish workflow.
- `.fgxe` is a JSON package containing a manifest and extension files. Packages can be imported and exported locally; this repository does not host an online extension marketplace.
- Before importing or packaging, FireFox GX flags selected risky permissions and patterns such as dynamic code. This heuristic review does not prove a package is safe. Review permissions and source code before installing.

## Aurora and network proxy

Open `://tools` to save a Groq API key and ask Aurora. The desktop app stores the key with Electron safeStorage; Android encrypts it with Android Keystore. The key is sent to Groq only for Aurora requests. A configured HTTP/SOCKS proxy routes FireFox GX browser traffic only; it is not a full device VPN and requires a proxy endpoint from a provider.

## Platform limits

The desktop version is Electron's Chromium runtime; Android uses the device's Chromium-based WebView. Both have matching browser UI and core workflows, while extension execution and proxy capabilities vary by platform. A hosted publishing service, full VPN tunnel/provider, and a native Chromium build are not included.
