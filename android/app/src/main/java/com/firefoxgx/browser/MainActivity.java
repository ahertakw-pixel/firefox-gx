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
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class MainActivity extends Activity {
    private static final int PICK_EXTENSION_ZIP = 3901;
    private static final long MAX_UNPACKED_BYTES = 64L * 1024L * 1024L;

    private FrameLayout root;
    private WebView uiView;
    private WebView browserView;
    private float density;
    private File pendingExtensionRoot;
    private File pendingExtractionDir;

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

        browserView = new WebView(this);
        browserView.setBackgroundColor(Color.WHITE);
        browserView.getSettings().setJavaScriptEnabled(true);
        browserView.getSettings().setDomStorageEnabled(true);
        browserView.getSettings().setDatabaseEnabled(true);
        browserView.getSettings().setSupportMultipleWindows(false);
        browserView.getSettings().setJavaScriptCanOpenWindowsAutomatically(false);
        browserView.setVisibility(View.GONE);
        browserView.setWebViewClient(new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String scheme = request.getUrl().getScheme();
                return !("http".equals(scheme) || "https".equals(scheme));
            }

            @Override
            public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
                super.onPageStarted(view, url, favicon);
                publishBrowserState(true);
            }

            @Override
            public void onPageFinished(WebView view, String url) {
                super.onPageFinished(view, url);
                injectPageExtensions(url);
                publishBrowserState(false);
            }
        });
        browserView.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog, boolean isUserGesture, Message resultMsg) {
                WebView.WebViewTransport transport = (WebView.WebViewTransport) resultMsg.obj;
                transport.setWebView(browserView);
                resultMsg.sendToTarget();
                return true;
            }
        });
        root.addView(browserView, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.LEFT));

        uiView.loadUrl("file:///android_asset/web/index.html");
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

    private void publishBrowserState(boolean loading) {
        if (uiView == null || browserView == null) return;
        JSONObject state = new JSONObject();
        try {
            state.put("url", browserView.getUrl() == null ? "" : browserView.getUrl());
            state.put("title", browserView.getTitle() == null ? "" : browserView.getTitle());
            state.put("canGoBack", browserView.canGoBack());
            state.put("canGoForward", browserView.canGoForward());
            state.put("loading", loading);
            state.put("active", browserView.getVisibility() == View.VISIBLE);
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

    private void showExtensionConsent(File extensionRoot, JSONObject details) {
        StringBuilder message = new StringBuilder("This extension can add code and styles to web pages that match its rules.\n\nRequested permissions:\n");
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

    private void injectPageExtensions(String url) {
        if (browserView == null || !url.startsWith("http://") && !url.startsWith("https://")) return;
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
                    if (css != null) for (int j = 0; j < css.length(); j++) injectFile(directory, css.optString(j), true);
                    JSONArray js = contentScript.optJSONArray("js");
                    if (js != null) for (int j = 0; j < js.length(); j++) injectFile(directory, js.optString(j), false);
                }
            } catch (Exception ignored) {}
        }
    }

    private boolean matchesAny(JSONArray patterns, Uri page) {
        if (patterns == null) return false;
        for (int i = 0; i < patterns.length(); i++) if (matches(patterns.optString(i, ""), page)) return true;
        return false;
    }

    private void injectFile(File rootDir, String relativePath, boolean css) {
        try {
            File file = extensionFile(rootDir, relativePath);
            if (file == null) return;
            String source = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            String safeSource = JSONObject.quote(source);
            String js = css
                    ? "(()=>{const s=document.createElement('style');s.textContent=" + safeSource + ";(document.head||document.documentElement).appendChild(s)})()"
                    : "(()=>{try{" + source + "\n}catch(e){console.error('FireFox GX extension script failed',e)}})()";
            browserView.evaluateJavascript(js, null);
        } catch (Exception ignored) {}
    }

    public class AndroidBridge {
        @JavascriptInterface
        public void navigate(String url) {
            if (url == null || !(url.startsWith("https://") || url.startsWith("http://"))) return;
            runOnUi(() -> {
                browserView.setVisibility(View.VISIBLE);
                browserView.loadUrl(url);
            });
        }

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
                FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(Math.max(1, (int) Math.round(width * density)), Math.max(1, (int) Math.round(height * density)), Gravity.TOP | Gravity.LEFT);
                params.leftMargin = Math.max(0, (int) Math.round(x * density));
                params.topMargin = Math.max(0, (int) Math.round(y * density));
                browserView.setLayoutParams(params);
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

        @JavascriptInterface
        public void reportUiReady() { runOnUi(() -> { publishExtensions(); publishBrowserState(false); }); }
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
        if (requestCode != PICK_EXTENSION_ZIP) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finishImport(importResult(false, null, true));
            return;
        }
        try {
            pendingExtensionRoot = extractExtensionZip(data.getData());
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
