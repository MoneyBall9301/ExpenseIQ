package com.expenseiq.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.view.KeyEvent
import android.webkit.*
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File
import java.util.Calendar

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val UPDATE_FILENAME = "expenseiq-update.html"
    private val CHANNEL_ID = "expenseiq_alerts"
    private val PREFS_NAME = "expenseiq_notif"

    // Android 13+ permission launcher
    private val notifPermLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val msg = if (granted) "✅ Notifications enabled!" else "❌ Notification permission denied."
        runOnUiThread { Toast.makeText(this, msg, Toast.LENGTH_SHORT).show() }
        // Tell the JS side what happened
        webView.post {
            webView.evaluateJavascript("if(window.onNotifPermResult)window.onNotifPermResult($granted);", null)
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Edge-to-edge dark status bar matching app theme (#0f172a)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.parseColor("#0f172a")
        window.navigationBarColor = android.graphics.Color.parseColor("#0f172a")
        WindowInsetsControllerCompat(window, window.decorView).isAppearanceLightStatusBars = false

        // ── Create notification channel (required for Android 8+) ──
        createNotificationChannel()

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
        // NOTE: file:///data/user/... paths are blocked by Android (ERR_ACCESS_DENIED).
        // Instead, read the update file content and inject it via loadDataWithBaseURL,
        // using the assets base URL so localStorage keys stay consistent across versions.
        val updateFile = File(filesDir, UPDATE_FILENAME)
        if (updateFile.exists()) {
            val html = updateFile.readText(Charsets.UTF_8)
            webView.loadDataWithBaseURL(
                "file:///android_asset/",   // base URL — keeps localStorage origin stable
                html,
                "text/html",
                "UTF-8",
                null
            )
        } else {
            webView.loadUrl("file:///android_asset/expense-tracker.html")
        }

        // ── Auto-prompt for notification permission on first launch ──
        // Only on Android 13+; ask once after the WebView has loaded
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val askedBefore = prefs.getBoolean("notif_asked", false)
            if (!askedBefore && !NotificationManagerCompat.from(this).areNotificationsEnabled()) {
                prefs.edit().putBoolean("notif_asked", true).apply()
                webView.postDelayed({
                    notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }, 2000) // 2 s delay so the app finishes loading first
            }
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "ExpenseIQ Alerts",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Spend limit alerts, daily summaries, and financial warnings"
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
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
         