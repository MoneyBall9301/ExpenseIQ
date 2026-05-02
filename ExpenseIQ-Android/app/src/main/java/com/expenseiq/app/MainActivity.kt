package com.expenseiq.app

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.webkit.*
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val UPDATE_FILENAME = "expenseiq-update.html"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge dark status bar matching app theme (#0f172a)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.parseColor("#0f172a")
        window.navigationBarColor = android.graphics.Color.parseColor("#0f172a")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        webView = WebView(this)
        setContentView(webView)

        // ── WebView settings ──────────────────────────────────────
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true               // localStorage (all app data lives here)
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            cacheMode = WebSettings.LOAD_DEFAULT
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
            mediaPlaybackRequiresUserGesture = false
            useWideViewPort = true
            loadWithOverviewMode = true
        }

        // ── JavaScript interface ──────────────────────────────────
        webView.addJavascriptInterface(AndroidBridge(this), "Android")

        // ── Handle JS alert / confirm dialogs ─────────────────────
        webView.webChromeClient = object : WebChromeClient() {
            override fun onJsAlert(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }

            override fun onJsConfirm(view: WebView, url: String, message: String, result: JsResult): Boolean {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage(message)
                    .setPositiveButton("OK") { _, _ -> result.confirm() }
                    .setNegativeButton("Cancel") { _, _ -> result.cancel() }
                    .setOnCancelListener { result.cancel() }
                    .show()
                return true
            }
        }

        // ── Keep all navigation inside the WebView ────────────────
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val scheme = request.url.scheme ?: ""
                return !(scheme == "file" || scheme == "blob" || scheme == "javascript")
            }
        }

        // ── Handle CSV / JSON downloads (blob: URLs) ─────────────
        webView.setDownloadListener { url, _, contentDisposition, mimetype, _ ->
            if (url.startsWith("blob:")) {
                val filename = URLUtil.guessFileName(url, contentDisposition, mimetype)
                val js = """
                    (function() {
                        var xhr = new XMLHttpRequest();
                        xhr.open('GET', '$url', true);
                        xhr.responseType = 'blob';
                        xhr.onload = function() {
                            var reader = new FileReader();
                            reader.onload = function(e) {
                                var b64 = e.target.result.split(',')[1];
                                Android.saveFile('$filename', b64, '$mimetype');
                            };
                            reader.readAsDataURL(xhr.response);
                        };
                        xhr.send();
                    })();
                """.trimIndent()
                webView.evaluateJavascript(js, null)
            }
        }

        // ── Load app: prefer downloaded update over bundled assets ─
        val updateFile = File(filesDir, UPDATE_FILENAME)
        if (updateFile.exists()) {
            webView.loadUrl("file://${updateFile.absolutePath}")
        } else {
            webView.loadUrl("file:///android_asset/expense-tracker.html")
        }
    }

    // ── Back button navigates within WebView ──────────────────────
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK && webView.canGoBack()) {
            webView.goBack()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ── JavaScript ↔ Kotlin bridge ────────────────────────────────
    inner class AndroidBridge(private val ctx: Context) {

        /** Save CSV / JSON downloads to the Downloads folder */
        @JavascriptInterface
        fun saveFile(filename: String, base64Data: String, mimeType: String) {
            try {
                val bytes = Base64.decode(base64Data, Base64.DEFAULT)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = ctx.contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { stream -> stream.write(bytes) }
                    values.clear()
                    values.put(MediaStore.Downloads.IS_PENDING, 0)
                    resolver.update(it, values, null, null)
                    runOnUiThread {
                        Toast.makeText(ctx, "✅ Saved to Downloads: $filename", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(ctx, "❌ Save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** Write a full HTML update to internal storage */
        @JavascriptInterface
        fun writeUpdateHtml(content: String) {
            try {
                val file = File(ctx.filesDir, UPDATE_FILENAME)
                file.writeText(content, Charsets.UTF_8)
                runOnUiThread {
                    Toast.makeText(ctx, "✅ Update saved! Close & reopen app to apply.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(ctx, "❌ Update save failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** Check whether a downloaded update is active */
        @JavascriptInterface
        fun hasUpdate(): Boolean = File(ctx.filesDir, UPDATE_FILENAME).exists()

        /** Return ISO timestamp of the saved update, or "" if none */
        @JavascriptInterface
        fun updateTimestamp(): String {
            val file = File(ctx.filesDir, UPDATE_FILENAME)
            return if (file.exists()) java.util.Date(file.lastModified()).toString() else ""
        }

        /** Delete the stored update — reverts to bundled version on next launch */
        @JavascriptInterface
        fun clearUpdate() {
            val file = File(ctx.filesDir, UPDATE_FILENAME)
            val deleted = file.delete()
            runOnUiThread {
                Toast.makeText(
                    ctx,
                    if (deleted) "✅ Update cleared. Bundled version loads on next restart."
                    else "ℹ️ No update file to clear.",
                    Toast.LENGTH_LONG
                ).show()
            }
        }
    }
}
