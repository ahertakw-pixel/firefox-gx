package com.firefoxgx.browser;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Message;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.webkit.DownloadListener;
import android.webkit.URLUtil;
import androidx.webkit.ProxyConfig;
import androidx.webkit.ProxyController;
import androidx.webkit.WebViewFeature;
import android.app.DownloadManager;
import android.os.Environment;
import android.widget.FrameLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int PICK_EXTENSION_ZIP = 3901;
    private static final int SAVE_FGXE = 3902;
    private static final long MAX_UNPACKED_BYTES = 64L * 1024L * 1024L;

    private FrameLayout root;
    private WebView uiView;
    private WebView browserView;
    private final LinkedHashMap<String, WebView> browserTabs = new LinkedHashMap<>();
    private String activeTabId = "";
    private int nextTab = 1;
    private int browserLeft, browserTop, browserWidth = 1, browserHeight = 1;
    private float density;
    private File pendingExtensionRoot;
    private File pendingExtractionDir;
    private String pendingSafetyFindings = "";
    private String pendingPackageJson = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setStatusBarColor(Color.rgb(10, 12, 19));
        getWindow().setNavigationBarColor(Color.rgb(10, 12, 19));
        getWindow().getDecorView().setSystemUiVisibility(0);
        density = getResources().getDisplayMetrics().density;

        root = new FrameLayout(this);
        root.setBackgroundColor(Color.rgb(10, 12, 19));
        setContentView(root);

        uiView = new WebView(this);
        uiView.setBackgroundColor(Color.rgb(10, 12, 19));
        uiView.getSettings().setJavaScriptEnabled(true);
        uiView.getSettings().setDomStorageEnabled(true);
        uiView.getSettings().setAllowFileAccess(true);
        uiView.getSettings().setAllowContentAccess(false);
        uiView.addJavascriptInterface(new AndroidBridge(), "AndroidBridge");
        uiView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !"file".equals(scheme) && !"about".equals(scheme);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                reportUiReady();
            }
        });
        root.addView(uiView, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        createBrowserTab("");

        uiView.loadUrl("file:///android_asset/web/index.html");
    }

    private String createBrowserTab(String url) {
        String id = "mobile-" + (nextTab++);
        WebView view = new WebView(this);
        view.setBackgroundColor(Color.WHITE);
        view.getSettings().setJavaScriptEnabled(true);
        view.getSettings().setDomStorageEnabled(true);
        view.getSettings().setDatabaseEnabled(true);
        view.getSettings().setSupportMultipleWindows(false);
        view.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        view.setVisibility(View.GONE);
        view.setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView w, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme(); return !("http".equals(scheme) || "https".equals(scheme));
            }
            @Override public void onPageStarted(WebView w, String page, android.graphics.Bitmap favicon) { publishBrowserState(w, true); }
            @Override public void onPageFinished(WebView w, String page) { injectPageExtensions(w, page); publishBrowserState(w, false); }
        });
        view.setWebChromeClient(new WebChromeClient() {
            @Override public boolean onCreateWindow(WebView w, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                String newId = createBrowserTab(""); WebView next = browserTabs.get(newId); transport.setWebView(next); resultMsg.sendToTarget(); return true;
            }
        });
        view.setDownloadListener((downloadUrl, userAgent, contentDisposition, mimeType, contentLength) -> {
            try {
                DownloadManager.Request request = new DownloadManager.Request(Uri.parse(downloadUrl));
                request.setMimeType(mimeType);
                request.addRequestHeader("User-Agent", userAgent);
                request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
                request.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, URLUtil.guessFileName(downloadUrl, contentDisposition, mimeType));
                ((DownloadManager) getSystemService(DOWNLOAD_SERVICE)).enqueue(request);
                runOnUi(() -> android.widget.Toast.makeText(this, "Download started", android.widget.Toast.LENGTH_SHORT).show());
            } catch (Exception ignored) {}
        });
        root.addView(view, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));
        browserTabs.put(id, view); activateBrowserTab(id);
        if (url != null && !url.isEmpty()) view.loadUrl(url);
        return id;
    }

    private void activateBrowserTab(String id) {
        WebView next = browserTabs.get(id); if (next == null) return;
        for (Map.Entry<String, WebView> entry : browserTabs.entrySet()) { String url = entry.getValue().getUrl(); entry.getValue().setVisibility(entry.getKey().equals(id) && url != null && !url.isEmpty() ? View.VISIBLE : View.GONE); }
        activeTabId = id; browserView = next;
        next.setLayoutParams(new FrameLayout.LayoutParams(Math.max(1, browserWidth), Math.max(1, browserHeight), Gravity.TOP | Gravity.LEFT));
        FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) next.getLayoutParams(); layout.leftMargin = browserLeft; layout.topMargin = browserTop; next.setLayoutParams(layout);
        publishBrowserTabs(); publishBrowserState(false);
    }

    private void publishBrowserTabs() {
        if (uiView == null) return;
        JSONArray result = new JSONArray();
        for (Map.Entry<String, WebView> entry : browserTabs.entrySet()) {
            JSONObject item = new JSONObject(); try { item.put("id", entry.getKey()).put("title", entry.getValue().getTitle() == null ? "New tab" : entry.getValue().getTitle()).put("url", entry.getValue().getUrl() == null ? "" : entry.getValue().getUrl()).put("active", entry.getKey().equals(activeTabId)); result.put(item); } catch (JSONException ignored) {}
        }
        uiView.evaluateJavascript("window.__firefoxGXTabs && window.__firefoxGXTabs(JSON.parse(" + JSONObject.quote(result.toString()) + "))", null);
    }

    @Override
    public void onBackPressed() {
        if (browserView.getVisibility() == View.VISIBLE && browserView.canGoBack()) {
            browserView.goBack();
            return;
        }
        if (browserView.getVisibility() == View.VISIBLE) {
            browserView.setVisibility(View.GONE);
            publishBrowserState(false);
            return;
        }
        super.onBackPressed();
    }

    private void runOnUi(Runnable runnable) {
        runOnUiThread(runnable);
    }

    private void reportUiReady() {
        if (uiView == null) return;
        uiView.evaluateJavascript("window.__firefoxGXReady && window.__firefoxGXReady()", null);
    }

    private void publishBrowserState(boolean loading) { publishBrowserState(browserView, loading); }

    private void publishBrowserState(WebView view, boolean loading) {
        if (uiView == null || view == null || view != browserView) return;
        JSONObject state = new JSONObject();
        try {
            state.put("url", view.getUrl() == null ? "" : view.getUrl());
            state.put("title", view.getTitle() == null ? "" : view.getTitle());
            state.put("canGoBack", view.canGoBack());
            state.put("canGoForward", view.canGoForward());
            state.put("loading", loading);
            state.put("active", view.getVisibility() == View.VISIBLE);
            state.put("tabId", activeTabId);
        } catch (JSONException ignored) {}
        uiView.evaluateJavascript("window.__firefoxGXState && window.__firefoxGXState(JSON.parse(" + JSONObject.quote(state.toString()) + "))", null);
    }

    private JSONObject extensionDetails(File rootDir) throws Exception {
        File manifestFile = new File(rootDir, "manifest.json");
        if (!manifestFile.isFile()) throw new Exception("This ZIP has no manifest.json at the extension root.");
        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(manifestFile.toPath()), StandardCharsets.UTF_8));
        int manifestVersion = manifest.optInt("manifest_version", 0);
        if (manifestVersion != 2 && manifestVersion != 3) throw new Exception("Only Manifest V2 and V3 extensions are supported.");
        JSONArray permissions = new JSONArray();
        appendPermissions(permissions, manifest.optJSONArray("permissions"));
        appendPermissions(permissions, manifest.optJSONArray("host_permissions"));
        return new JSONObject()
                .put("name", manifest.optString("name", "Imported extension"))
                .put("version", manifest.optString("version", "unknown"))
                .put("manifestVersion", manifestVersion)
                .put("description", manifest.optString("description", ""))
                .put("permissions", permissions);
    }

    private void appendPermissions(JSONArray destination, JSONArray source) throws JSONException {
        if (source == null) return;
        for (int i = 0; i < source.length(); i++) {
            String value = source.optString(i, "");
            if (!value.isEmpty() && !destination.toString().contains(JSONObject.quote(value))) destination.put(value);
        }
    }

    private File extractExtensionZip(Uri uri) throws Exception {
        File temp = new File(getCacheDir(), "firefoxgx-import-" + System.nanoTime());
        if (!temp.mkdirs()) throw new Exception("Could not prepare a temporary import folder.");
        pendingExtractionDir = temp;
        long totalBytes = 0;
        InputStream stream = getContentResolver().openInputStream(uri);
        if (stream == null) throw new Exception("Could not open the selected ZIP file.");
        try (InputStream raw = stream; ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            byte[] buffer = new byte[16 * 1024];
            String rootCanonical = temp.getCanonicalPath() + File.separator;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/');
                if (name.startsWith("/") || name.contains(":") || name.equals("..") || name.startsWith("../") || name.contains("/../")) {
                    throw new Exception("The ZIP contains an unsafe file path.");
                }
                File output = new File(temp, name);
                String outputCanonical = output.getCanonicalPath();
                if (!outputCanonical.startsWith(rootCanonical) && !outputCanonical.equals(temp.getCanonicalPath())) {
                    throw new Exception("The ZIP contains an unsafe file path.");
                }
                if (entry.isDirectory()) {
                    if (!output.mkdirs() && !output.isDirectory()) throw new Exception("Could not unpack the ZIP file.");
                } else {
                    File parent = output.getParentFile();
                    if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new Exception("Could not unpack the ZIP file.");
                    try (OutputStream out = new FileOutputStream(output)) {
                        int read;
                        while ((read = zip.read(buffer)) != -1) {
                            totalBytes += read;
                            if (totalBytes > MAX_UNPACKED_BYTES) throw new Exception("The extension ZIP is larger than 64 MB when unpacked.");
                            out.write(buffer, 0, read);
                        }
                    }
                }
                zip.closeEntry();
            }
        } catch (Exception error) {
            deleteTree(temp);
            throw error;
        }
        File rootDir = findExtensionRoot(temp, 0);
        if (rootDir == null) {
            deleteTree(temp);
            throw new Exception("Choose a ZIP with manifest.json at its root, or one folder below the root.");
        }
        return rootDir;
    }

    private File extractExtensionFile(Uri uri) throws Exception {
        byte[] bytes;
        try (InputStream input = getContentResolver().openInputStream(uri); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            if (input == null) throw new Exception("Could not open the selected file."); byte[] buffer = new byte[8192]; int read; while ((read = input.read(buffer)) != -1) { output.write(buffer, 0, read); if (output.size() > MAX_UNPACKED_BYTES) throw new Exception("The package is larger than 64 MB."); } bytes = output.toByteArray();
        }
        String head = new String(bytes, 0, Math.min(bytes.length, 32), StandardCharsets.UTF_8).trim();
        if (!head.startsWith("{")) { File archive = new File(getCacheDir(), "firefoxgx-archive-" + System.nanoTime() + ".zip"); try (OutputStream out = new FileOutputStream(archive)) { out.write(bytes); } try { return extractExtensionZip(Uri.fromFile(archive)); } finally { archive.delete(); } }
        File temp = new File(getCacheDir(), "firefoxgx-fgxe-" + System.nanoTime()); if (!temp.mkdirs()) throw new Exception("Could not prepare a package folder."); pendingExtractionDir = temp;
        try {
            JSONObject pack = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
            if (!"FGXE".equals(pack.optString("format")) || pack.optInt("version") != 1) throw new Exception("Unsupported .fgxe package format.");
            JSONObject manifest = pack.optJSONObject("manifest"), files = pack.optJSONObject("files"); if (manifest == null || files == null) throw new Exception("Package is missing its manifest or files.");
            JSONArray permissions = manifest.optJSONArray("permissions"); if (permissions != null) for (int i = 0; i < permissions.length(); i++) { String p = permissions.optString(i); if ("<all_urls>".equals(p) || "debugger".equals(p) || "nativeMessaging".equals(p) || "management".equals(p)) pendingSafetyFindings += "Broad/privileged permission: " + p + "\n"; }
            String rootCanonical = temp.getCanonicalPath() + File.separator;
            for (java.util.Iterator<String> it = files.keys(); it.hasNext();) {
                String name = it.next(); if (name.startsWith("/") || name.contains("..") || name.contains("\\") || name.contains(":")) throw new Exception("Package contains an unsafe file path.");
                String source = files.optString(name, ""); if (source.matches("(?is).*(\\beval\\s*\\(|new\\s+Function\\s*\\(|https?://[^\\s\\\"']+\\.js\\b).*")) pendingSafetyFindings += "Potential dynamic/remote code in: " + name + "\n";
                File output = new File(temp, name); if (!output.getCanonicalPath().startsWith(rootCanonical)) throw new Exception("Package contains an unsafe path."); File parent = output.getParentFile(); if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new Exception("Could not unpack package files.");
                String prefix = "data:application/octet-stream;base64,"; byte[] fileBytes = source.startsWith(prefix) ? Base64.decode(source.substring(prefix.length()), Base64.DEFAULT) : source.getBytes(StandardCharsets.UTF_8); try (OutputStream out = new FileOutputStream(output)) { out.write(fileBytes); }
            }
            if (!new File(temp, "manifest.json").isFile()) throw new Exception("Package has no root manifest.json."); return temp;
        } catch (Exception error) { deleteTree(temp); throw error; }
    }

    private File findExtensionRoot(File folder, int depth) {
        if (new File(folder, "manifest.json").isFile()) return folder;
        if (depth >= 2) return null;
        File[] children = folder.listFiles(File::isDirectory);
        if (children == null) return null;
        for (File child : children) {
            File found = findExtensionRoot(child, depth + 1);
            if (found != null) return found;
        }
        return null;
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder output = new StringBuilder();
        for (byte b : digest) output.append(String.format(Locale.ROOT, "%02x", b));
        return output.substring(0, 20);
    }

    private void finishImport(JSONObject result) {
        try { deleteTree(pendingExtractionDir); } catch (Exception ignored) {}
        pendingExtensionRoot = null;
        pendingExtractionDir = null;
        String json = result.toString();
        runOnUi(() -> {
            if (uiView != null) {
                uiView.evaluateJavascript("window.__firefoxGXImportResult && window.__firefoxGXImportResult(JSON.parse(" + JSONObject.quote(json) + "))", null);
                publishExtensions();
            }
        });
    }

    private SecretKey auroraSecretKey() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        String alias = "firefoxgx-aurora-key";
        if (store.containsAlias(alias)) return ((KeyStore.SecretKeyEntry) store.getEntry(alias, null)).getSecretKey();
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }

    private boolean encryptAndStoreKey(String value) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE, auroraSecretKey());
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] combined = new byte[cipher.getIV().length + encrypted.length]; System.arraycopy(cipher.getIV(), 0, combined, 0, cipher.getIV().length); System.arraycopy(encrypted, 0, combined, cipher.getIV().length, encrypted.length);
        getSharedPreferences("aurora", MODE_PRIVATE).edit().putString("key", Base64.encodeToString(combined, Base64.NO_WRAP)).apply(); return true;
    }

    private String decryptAuroraKey() throws Exception {
        String saved = getSharedPreferences("aurora", MODE_PRIVATE).getString("key", ""); if (saved.isEmpty()) throw new Exception("Add your Groq API key in Browser Tools first.");
        byte[] combined = Base64.decode(saved, Base64.NO_WRAP); byte[] iv = new byte[12]; byte[] data = new byte[combined.length - 12]; System.arraycopy(combined, 0, iv, 0, 12); System.arraycopy(combined, 12, data, 0, data.length);
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.DECRYPT_MODE, auroraSecretKey(), new GCMParameterSpec(128, iv)); return new String(cipher.doFinal(data), StandardCharsets.UTF_8);
    }

    private String askGroq(String prompt) throws Exception {
        JSONObject message = new JSONObject().put("role", "user").put("content", prompt);
        JSONObject body = new JSONObject().put("model", "openai/gpt-oss-120b").put("messages", new JSONArray().put(message)).put("max_tokens", 600);
        HttpURLConnection connection = (HttpURLConnection) new URL("https://api.groq.com/openai/v1/chat/completions").openConnection();
        connection.setRequestMethod("POST"); connection.setConnectTimeout(15000); connection.setReadTimeout(30000); connection.setDoOutput(true); connection.setRequestProperty("Authorization", "Bearer " + decryptAuroraKey()); connection.setRequestProperty("Content-Type", "application/json");
        try (OutputStream output = connection.getOutputStream()) { output.write(body.toString().getBytes(StandardCharsets.UTF_8)); }
        int status = connection.getResponseCode(); InputStream stream = status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int read; while ((read = stream.read(buffer)) != -1) bytes.write(buffer, 0, read); connection.disconnect();
        JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8)); if (status < 200 || status >= 300) throw new Exception("Groq returned HTTP " + status + ".");
        return response.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content", "No response returned.");
    }

    private void installImportedExtension(File extensionRoot, JSONObject details) {
        try {
            String id = sha256(details.optString("name") + "|" + details.optString("version") + "|" + System.nanoTime());
            File destination = new File(new File(getFilesDir(), "extensions"), id);
            if (!destination.getParentFile().mkdirs() && !destination.getParentFile().isDirectory()) throw new Exception("Could not create the extension library.");
            copyTree(extensionRoot, destination);
            pendingExtensionRoot = extensionRoot;
            JSONObject result = new JSONObject()
                    .put("ok", true)
                    .put("id", id)
                    .put("name", details.optString("name"))
                    .put("version", details.optString("version"))
                    .put("manifestVersion", details.optInt("manifestVersion"))
                    .put("permissions", details.optJSONArray("permissions"));
            finishImport(result);
        } catch (Exception error) {
            finishImport(importResult(false, error.getMessage(), false));
        }
    }

    private JSONObject buildFgxePackage(File rootDir) throws Exception {
        JSONObject manifest = new JSONObject(new String(Files.readAllBytes(new File(rootDir, "manifest.json").toPath()), StandardCharsets.UTF_8));
        JSONObject files = new JSONObject();
        collectPackageFiles(rootDir, rootDir, files);
        return new JSONObject().put("format", "FGXE").put("version", 1).put("manifest", manifest).put("files", files);
    }

    private void collectPackageFiles(File rootDir, File directory, JSONObject files) throws Exception {
        File[] children = directory.listFiles(); if (children == null) return;
        for (File child : children) {
            if (child.isDirectory()) collectPackageFiles(rootDir, child, files);
            else {
                String relative = rootDir.toPath().relativize(child.toPath()).toString().replace(File.separatorChar, '/');
                byte[] content = Files.readAllBytes(child.toPath());
                String value;
                if (relative.matches("(?i).+\\.(js|css|json|html|txt|svg)")) value = new String(content, StandardCharsets.UTF_8);
                else value = "data:application/octet-stream;base64," + Base64.encodeToString(content, Base64.NO_WRAP);
                files.put(relative, value);
            }
        }
    }

    private void promptPublishFgxe() {
        File library = new File(getFilesDir(), "extensions"); File[] dirs = library.listFiles(File::isDirectory);
        if (dirs == null || dirs.length == 0) { android.widget.Toast.makeText(this, "Import an extension before publishing a package.", android.widget.Toast.LENGTH_SHORT).show(); return; }
        String[] names = new String[dirs.length]; for (int i = 0; i < dirs.length; i++) { try { names[i] = extensionDetails(dirs[i]).optString("name", dirs[i].getName()); } catch (Exception e) { names[i] = dirs[i].getName(); } }
        new AlertDialog.Builder(this).setTitle("Choose extension to package").setItems(names, (dialog, which) -> {
            try {
                JSONObject pack = buildFgxePackage(dirs[which]);
                JSONArray permissions = pack.getJSONObject("manifest").optJSONArray("permissions");
                StringBuilder findings = new StringBuilder(); if (permissions != null) for (int i = 0; i < permissions.length(); i++) { String p = permissions.optString(i); if ("<all_urls>".equals(p) || "debugger".equals(p) || "nativeMessaging".equals(p) || "management".equals(p)) findings.append("Broad/privileged permission: ").append(p).append('\n'); }
                String packed = pack.getJSONObject("files").toString(); if (packed.matches("(?is).*(\\beval\\s*\\(|new\\s+Function\\s*\\(|https?://[^\\s\\\"']+\\.js\\b).*")) findings.append("Potential dynamic or remote script pattern.\n");
                if (findings.length() > 0) new AlertDialog.Builder(this).setTitle("Safety review findings").setMessage(findings + "This is a heuristic review, not a guarantee. Only publish code you trust.").setNegativeButton("CANCEL", null).setPositiveButton("CONTINUE", (d, w) -> openFgxeSaveDialogSafe(pack)).show();
                else openFgxeSaveDialog(pack);
            } catch (Exception e) { android.widget.Toast.makeText(this, "Could not package extension: " + e.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
        }).show();
    }

    private void openFgxeSaveDialog(JSONObject pack) throws Exception {
        pendingPackageJson = pack.toString(); String name = pack.getJSONObject("manifest").optString("name", "extension").replaceAll("[^a-zA-Z0-9_-]", "-");
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("application/json"); intent.putExtra(Intent.EXTRA_TITLE, name + ".fgxe"); startActivityForResult(intent, SAVE_FGXE);
    }

    private void openFgxeSaveDialogSafe(JSONObject pack) { try { openFgxeSaveDialog(pack); } catch (Exception error) { android.widget.Toast.makeText(this, "Could not create package: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show(); } }

    private void showExtensionConsent(File extensionRoot, JSONObject details) {
        StringBuilder message = new StringBuilder("This extension can add code and styles to web pages that match its rules.\n\nRequested permissions:\n");
        if (!pendingSafetyFindings.isEmpty()) message.append("\nSAFETY REVIEW FINDINGS:\n").append(pendingSafetyFindings).append("\nReview the code and only proceed if you trust this extension.\n");
        JSONArray permissions = details.optJSONArray("permissions");
        if (permissions == null || permissions.length() == 0) message.append("None declared");
        else for (int i = 0; i < permissions.length(); i++) message.append("• ").append(permissions.optString(i)).append('\n');
        new AlertDialog.Builder(this)
                .setTitle("Add " + details.optString("name") + " to FireFox GX?")
                .setMessage(message.toString())
                .setNegativeButton("CANCEL", (dialog, which) -> {
                    finishImport(importResult(false, null, true));
                })
                .setPositiveButton("ADD EXTENSION", (dialog, which) -> installImportedExtension(extensionRoot, details))
                .show();
    }

    private void copyTree(File source, File destination) throws Exception {
        if (source.isDirectory()) {
            if (!destination.mkdirs() && !destination.isDirectory()) throw new Exception("Could not copy the extension files.");
            File[] children = source.listFiles();
            if (children != null) for (File child : children) copyTree(child, new File(destination, child.getName()));
        } else {
            File parent = destination.getParentFile();
            if (parent != null && !parent.mkdirs() && !parent.isDirectory()) throw new Exception("Could not copy the extension files.");
            Files.copy(source.toPath(), destination.toPath());
        }
    }

    private void deleteTree(File file) {
        if (file == null || !file.exists()) return;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) for (File child : children) deleteTree(child);
        }
        // These directories are created only in this app's private cache for ZIP extraction.
        file.delete();
    }

    private JSONArray getInstalledExtensions() {
        JSONArray items = new JSONArray();
        File library = new File(getFilesDir(), "extensions");
        File[] directories = library.listFiles(File::isDirectory);
        if (directories == null) return items;
        for (File directory : directories) {
            try {
                JSONObject details = extensionDetails(directory)
                        .put("id", directory.getName())
                        .put("path", directory.getAbsolutePath())
                        .put("popup", "");
                items.put(details);
            } catch (Exception ignored) {}
        }
        return items;
    }

    private void publishExtensions() {
        if (uiView == null) return;
        String json = getInstalledExtensions().toString();
        uiView.evaluateJavascript("window.__firefoxGXExtensions && window.__firefoxGXExtensions(JSON.parse(" + JSONObject.quote(json) + "))", null);
    }

    private boolean matches(String pattern, Uri uri) {
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if ("<all_urls>".equals(pattern)) return scheme.equals("http") || scheme.equals("https");
        int marker = pattern.indexOf("://");
        if (marker < 0) return false;
        String wantedScheme = pattern.substring(0, marker).toLowerCase(Locale.ROOT);
        if (!wantedScheme.equals("*") && !wantedScheme.equals(scheme)) return false;
        if (wantedScheme.equals("*") && !(scheme.equals("http") || scheme.equals("https"))) return false;
        String remainder = pattern.substring(marker + 3);
        int slash = remainder.indexOf('/');
        String hostPattern = (slash < 0 ? remainder : remainder.substring(0, slash)).toLowerCase(Locale.ROOT);
        String pathPattern = slash < 0 ? "/*" : remainder.substring(slash);
        boolean hostMatches;
        if (hostPattern.equals("*")) hostMatches = true;
        else if (hostPattern.startsWith("*.")) {
            String suffix = hostPattern.substring(1);
            hostMatches = host.endsWith(suffix) || host.equals(suffix.substring(1));
        } else hostMatches = host.equals(hostPattern);
        if (!hostMatches) return false;
        String requestPath = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();
        return wildcardMatch(pathPattern, requestPath);
    }

    private boolean wildcardMatch(String pattern, String value) {
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < pattern.length(); i++) {
            char character = pattern.charAt(i);
            if (character == '*') regex.append(".*");
            else if (character == '?') regex.append('.');
            else {
                if ("\\.[]{}()+-^$|".indexOf(character) >= 0) regex.append('\\');
                regex.append(character);
            }
        }
        return value.matches(regex.append('$').toString());
    }

    private File extensionFile(File rootDir, String relative) throws Exception {
        File rootCanonical = rootDir.getCanonicalFile();
        File file = new File(rootDir, relative).getCanonicalFile();
        String prefix = rootCanonical.getPath() + File.separator;
        if (!file.getPath().startsWith(prefix) || !file.isFile()) return null;
        return file;
    }

    private void injectPageExtensions(WebView targetView, String url) {
        if (targetView == null || !url.startsWith("http://") && !url.startsWith("https://")) return;
        Uri page = Uri.parse(url);
        File library = new File(getFilesDir(), "extensions");
        File[] directories = library.listFiles(File::isDirectory);
        if (directories == null) return;
        for (File directory : directories) {
            try {
                JSONObject manifest = new JSONObject(new String(Files.readAllBytes(new File(directory, "manifest.json").toPath()), StandardCharsets.UTF_8));
                JSONArray scripts = manifest.optJSONArray("content_scripts");
                if (scripts == null) continue;
                for (int i = 0; i < scripts.length(); i++) {
                    JSONObject contentScript = scripts.optJSONObject(i);
                    if (contentScript == null || !matchesAny(contentScript.optJSONArray("matches"), page)) continue;
                    JSONArray css = contentScript.optJSONArray("css");
                    if (css != null) for (int j = 0; j < css.length(); j++) injectFile(targetView, directory, css.optString(j), true);
                    JSONArray js = contentScript.optJSONArray("js");
                    if (js != null) for (int j = 0; j < js.length(); j++) injectFile(targetView, directory, js.optString(j), false);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean matchesAny(JSONArray patterns, Uri page) {
        if (patterns == null) return false;
        for (int i = 0; i < patterns.length(); i++) if (matches(patterns.optString(i, ""), page)) return true;
        return false;
    }

    private void injectFile(WebView targetView, File rootDir, String relativePath, boolean css) {
        try {
            File file = extensionFile(rootDir, relativePath);
            if (file == null) return;
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String safeSource = JSONObject.quote(source);
            String js = css
                    ? "(()=>{const s=document.createElement('style');s.textContent=" + safeSource + ";(document.head||document.documentElement).appendChild(s)})()"
                    : "(()=>{try{" + source + "\n}catch(e){console.error('FireFox GX extension script failed',e)}})()";
            targetView.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void navigate(String url) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
            runOnUi(() -> {
                if (browserView == null) createBrowserTab("");
                browserView.setVisibility(View.VISIBLE);
                browserView.loadUrl(url);
                publishBrowserTabs();
            });
        }

        @JavascriptInterface public String newTab(String url) {
            final String[] result = {""};
            runOnUi(() -> { result[0] = createBrowserTab(url); publishBrowserTabs(); });
            return result[0];
        }

        @JavascriptInterface public boolean activateTab(String id) {
            runOnUi(() -> activateBrowserTab(id)); return true;
        }

        @JavascriptInterface public boolean closeTab(String id) {
            runOnUi(() -> {
                WebView removed = browserTabs.remove(id); if (removed != null) { root.removeView(removed); removed.destroy(); }
                if (browserTabs.isEmpty()) createBrowserTab("");
                else if (id.equals(activeTabId)) activateBrowserTab(browserTabs.keySet().iterator().next());
                publishBrowserTabs();
            }); return true;
        }

        @JavascriptInterface public boolean download() { return true; }
        @JavascriptInterface public boolean openDownloads() {
            runOnUi(() -> { try { Intent intent = new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS); intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(intent); } catch (Exception ignored) {} }); return true;
        }
        @JavascriptInterface public boolean setProxy(String value) {
            try {
                if (!WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) return false;
                ProxyController controller = ProxyController.getInstance();
                if (value == null || value.trim().isEmpty()) { controller.clearProxyOverride(Runnable::run, () -> {}); return true; }
                Uri uri = Uri.parse(value.trim()); String scheme = uri.getScheme() == null ? "http" : uri.getScheme().toLowerCase(Locale.ROOT);
                if ("socks5".equals(scheme) || "socks4".equals(scheme)) scheme = "socks";
                if (!("http".equals(scheme) || "https".equals(scheme) || "socks".equals(scheme)) || uri.getHost() == null || uri.getUserInfo() != null) return false;
                int port = uri.getPort(); String rule = scheme + "://" + uri.getHost() + (port > 0 ? ":" + port : "");
                controller.setProxyOverride(new ProxyConfig.Builder().addProxyRule(rule).build(), Runnable::run, () -> {}); return true;
            } catch (Exception error) { return false; }
        }
        @JavascriptInterface public boolean saveAuroraKey(String key) { try { return encryptAndStoreKey(key); } catch (Exception error) { return false; } }
        @JavascriptInterface public boolean hasAuroraKey() { return !getSharedPreferences("aurora", MODE_PRIVATE).getString("key", "").isEmpty(); }
        @JavascriptInterface public String askAurora(String prompt) { try { return askGroq(prompt); } catch (Exception error) { return "Aurora: " + error.getMessage(); } }
        @JavascriptInterface public boolean publishFgxe() { runOnUi(MainActivity.this::promptPublishFgxe); return true; }

        @JavascriptInterface
        public void history(String direction) {
            runOnUi(() -> {
                if ("back".equals(direction) && browserView.canGoBack()) browserView.goBack();
                else if ("forward".equals(direction) && browserView.canGoForward()) browserView.goForward();
                publishBrowserState(false);
            });
        }

        @JavascriptInterface
        public void reload() { runOnUi(() -> browserView.reload()); }

        @JavascriptInterface
        public void home() {
            runOnUi(() -> {
                browserView.setVisibility(View.GONE);
                publishBrowserState(false);
            });
        }

        @JavascriptInterface
        public void setBounds(double x, double y, double width, double height) {
            runOnUi(() -> {
                browserWidth = Math.max(1, (int) Math.round(width * density)); browserHeight = Math.max(1, (int) Math.round(height * density));
                browserLeft = Math.max(0, (int) Math.round(x * density)); browserTop = Math.max(0, (int) Math.round(y * density));
                if (browserView != null) { FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(browserWidth, browserHeight, Gravity.TOP | Gravity.LEFT); params.leftMargin = browserLeft; params.topMargin = browserTop; browserView.setLayoutParams(params); }
            });
        }

        @JavascriptInterface
        public String getExtensionsJson() { return getInstalledExtensions().toString(); }

        @JavascriptInterface
        public boolean removeExtension(String id) {
            if (id == null || !id.matches("[a-f0-9]{20}")) return false;
            File destination = new File(new File(getFilesDir(), "extensions"), id);
            boolean removed = deleteManagedExtension(destination);
            if (removed) runOnUi(MainActivity.this::publishExtensions);
            return removed;
        }

        @JavascriptInterface
        public void importExtensionZip() {
            runOnUi(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                intent.addCategory(Intent.CATEGORY_OPENABLE);
                intent.setType("application/zip");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
                startActivityForResult(intent, PICK_EXTENSION_ZIP);
            });
        }

        @JavascriptInterface public void importFgxe() {
            runOnUi(() -> {
                Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT); intent.addCategory(Intent.CATEGORY_OPENABLE); intent.setType("*/*");
                intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream", "application/json", "text/plain"});
                startActivityForResult(intent, PICK_EXTENSION_ZIP);
            });
        }

        @JavascriptInterface
        public void reportUiReady() { runOnUi(() -> { publishExtensions(); publishBrowserState(false); publishBrowserTabs(); }); }
    }

    private boolean deleteManagedExtension(File directory) {
        File library = new File(getFilesDir(), "extensions");
        try {
            String base = library.getCanonicalPath() + File.separator;
            if (!directory.getCanonicalPath().startsWith(base)) return false;
            deleteTree(directory);
            return true;
        } catch (Exception error) { return false; }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == SAVE_FGXE) {
            if (resultCode == RESULT_OK && data != null && data.getData() != null) {
                try (OutputStream output = getContentResolver().openOutputStream(data.getData())) { if (output == null) throw new Exception("Could not write package."); output.write(pendingPackageJson.getBytes(StandardCharsets.UTF_8)); android.widget.Toast.makeText(this, "Published .fgxe package saved.", android.widget.Toast.LENGTH_SHORT).show(); }
                catch (Exception error) { android.widget.Toast.makeText(this, "Could not save .fgxe package: " + error.getMessage(), android.widget.Toast.LENGTH_LONG).show(); }
            }
            pendingPackageJson = ""; return;
        }
        if (requestCode != PICK_EXTENSION_ZIP) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finishImport(importResult(false, null, true));
            return;
        }
        try {
            pendingSafetyFindings = "";
            pendingExtensionRoot = extractExtensionFile(data.getData());
            JSONObject details = extensionDetails(pendingExtensionRoot);
            showExtensionConsent(pendingExtensionRoot, details);
        } catch (Exception error) {
            finishImport(importResult(false, error.getMessage(), false));
        }
    }

    private JSONObject importResult(boolean ok, String error, boolean cancelled) {
        JSONObject result = new JSONObject();
        try {
            result.put("ok", ok);
            if (error != null) result.put("error", error);
            if (cancelled) result.put("cancelled", true);
        } catch (JSONException ignored) {}
        return result;
    }
}
