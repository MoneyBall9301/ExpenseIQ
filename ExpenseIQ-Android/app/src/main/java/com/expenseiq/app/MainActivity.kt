package com.expenseiq.app

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
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

    // File chooser support for <input type="file">
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        val cb = fileChooserCallback
        fileChooserCallback = null
        if (uri != null) cb?.onReceiveValue(arrayOf(uri))
        else cb?.onReceiveValue(null)
    }

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

            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                // Cancel any previous pending callback
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = callback
                fileChooserLauncher.launch("*/*")
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

        // ── Notification bridge methods ───────────────────────────

        /** Check if notification permission is granted */
        @JavascriptInterface
        fun hasNotificationPermission(): Boolean =
            NotificationManagerCompat.from(ctx).areNotificationsEnabled()

        /** Request notification permission (Android 13+); on older versions auto-granted */
        @JavascriptInterface
        fun requestNotificationPermission() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                runOnUiThread {
                    notifPermLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                }
            } else {
                // Pre-Android 13: permission is granted at install time
                webView.post {
                    webView.evaluateJavascript("if(window.onNotifPermResult)window.onNotifPermResult(true);", null)
                }
            }
        }

        /** Show an immediate notification — called from JS for critical warnings */
        @JavascriptInterface
        fun showNotification(title: String, body: String, notifId: Int) {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val heatScore = prefs.getInt("heat_score", 0)
            val heatLabel = prefs.getString("heat_label", "") ?: ""
            val openIntent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                ctx, notifId, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val fullBody = if (heatLabel.isNotEmpty()) "$body\n🌡️ Spending Heat: $heatLabel ($heatScore/100)" else body
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
                .setProgress(100, heatScore, false)
                .setColor(heatColor(heatScore))
                .setColorized(true)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(notifId, notif)
            } catch (e: SecurityException) {
                // Permission not granted — silently ignore
            }
        }

        /** Store the current warning summary + heat score so daily alarm can show it */
        @JavascriptInterface
        fun updateNotificationData(warnCount: Int, topWarning: String) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("warn_count", warnCount)
                .putString("top_warn", topWarning)
                .apply()
        }

        /** Store heat score separately — called from renderHeatBar */
        @JavascriptInterface
        fun updateHeatScore(score: Int, label: String) {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putInt("heat_score", score)
                .putString("heat_label", label)
                .apply()
        }

        /**
         * Show/update the persistent pinned notification — cannot be swiped away.
         * Called from renderHeatBar every time the dashboard loads.
         */
        @JavascriptInterface
        fun showPersistentStatus(title: String, body: String, heatScore: Int) {
            if (!NotificationManagerCompat.from(ctx).areNotificationsEnabled()) return
            val color = heatColor(heatScore)
            val openIntent = Intent(ctx, MainActivity::class.java)
            val pi = PendingIntent.getActivity(
                ctx, 999, openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notif = NotificationCompat.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setProgress(100, heatScore, false)
                .setColor(color)           // tints icon & accent strip to heat colour
                .setColorized(true)        // applies colour to notification background
                .setContentIntent(pi)
                .setSilent(true)           // no sound/vibration on update
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(1000, notif)
            } catch (e: SecurityException) { /* silently ignore */ }
        }

        /** Map heat score 0–100 to a colour int (green → yellow → orange → red) */
        private fun heatColor(score: Int): Int = when {
            score <= 15 -> android.graphics.Color.parseColor("#22c55e") // green
            score <= 30 -> android.graphics.Color.parseColor("#4ade80") // light green
            score <= 50 -> android.graphics.Color.parseColor("#facc15") // yellow
            score <= 70 -> android.graphics.Color.parseColor("#fb923c") // orange
            score <= 85 -> android.graphics.Color.parseColor("#ef4444") // red
            else        -> android.graphics.Color.parseColor("#dc2626") // deep red
        }

        /**
         * Schedule a daily reminder notification at the given hour:minute (24h).
         * Persists across reboots via the alarm being rescheduled by NotificationReceiver.
         */
        @JavascriptInterface
        fun scheduleDailyReminder(hour: Int, minute: Int) {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putInt("notif_hour", hour).putInt("notif_minute", minute)
                .putBoolean("daily_enabled", true).apply()

            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour)
                set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0)
                if (timeInMillis <= System.currentTimeMillis())
                    add(Calendar.DAY_OF_YEAR, 1)  // schedule for tomorrow if time already passed
            }
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, NotificationReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
                runOnUiThread {
                    Toast.makeText(ctx, "🔔 Daily reminder set for ${hour.toString().padStart(2,'0')}:${minute.toString().padStart(2,'0')}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
            }
        }

        /** Cancel the daily reminder */
        @JavascriptInterface
        fun cancelDailyReminder() {
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                .putBoolean("daily_enabled", false).apply()
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, 0,
                Intent(ctx, NotificationReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.cancel(pi)
            runOnUiThread {
                Toast.makeText(ctx, "🔕 Daily reminder cancelled.", Toast.LENGTH_SHORT).show()
            }
        }

        /** Returns true if the daily reminder is currently scheduled */
        @JavascriptInterface
        fun isDailyReminderEnabled(): Boolean =
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getBoolean("daily_enabled", false)

        /** Returns "HH:MM" of the scheduled time, or "" if not set */
        @JavascriptInterface
        fun getDailyReminderTime(): String {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            if (!prefs.getBoolean("daily_enabled", false)) return ""
            val h = prefs.getInt("notif_hour", 20).toString().padStart(2, '0')
            val m = prefs.getInt("notif_minute", 0).toString().padStart(2, '0')
            return "$h:$m"
        }
    }
}
