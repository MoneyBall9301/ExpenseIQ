package com.expenseiq.app

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * Receives daily alarm broadcasts and shows the ExpenseIQ daily summary notification.
 * Also handles boot-completed to re-register the alarm after device restart.
 */
class NotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val prefs = context.getSharedPreferences("expenseiq_notif", Context.MODE_PRIVATE)

        // On reboot, re-schedule the alarm if it was enabled
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            if (prefs.getBoolean("daily_enabled", false)) {
                reschedule(context, prefs)
            }
            return
        }

        // Show the notification
        val warnCount = prefs.getInt("warn_count", 0)
        val topWarn = prefs.getString("top_warn", "Tap to review your finances")
            ?: "Tap to review your finances"

        val title = when {
            warnCount > 1 -> "ExpenseIQ: $warnCount alerts need attention"
            warnCount == 1 -> "ExpenseIQ: 1 alert"
            else -> "ExpenseIQ Daily Check-in"
        }
        val body = topWarn

        val openIntent = Intent(context, MainActivity::class.java)
        val pi = PendingIntent.getActivity(
            context, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val heatScore = prefs.getInt("heat_score", 0)
        val heatLabel = prefs.getString("heat_label", "") ?: ""
        val heatEmoji = when {
            heatScore <= 15 -> "❄️"
            heatScore <= 30 -> "🌿"
            heatScore <= 50 -> "🌡️"
            heatScore <= 70 -> "🔥"
            else            -> "🔥🔥"
        }
        val fullBody = if (heatLabel.isNotEmpty())
            "$body\n$heatEmoji Spending Heat: $heatLabel ($heatScore/100)"
        else body

        val heatColor = when {
            heatScore <= 15 -> android.graphics.Color.parseColor("#22c55e")
            heatScore <= 30 -> android.graphics.Color.parseColor("#4ade80")
            heatScore <= 50 -> android.graphics.Color.parseColor("#facc15")
            heatScore <= 70 -> android.graphics.Color.parseColor("#fb923c")
            heatScore <= 85 -> android.graphics.Color.parseColor("#ef4444")
            else            -> android.graphics.Color.parseColor("#dc2626")
        }
        val notif = NotificationCompat.Builder(context, "expenseiq_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fullBody))
            .setProgress(100, heatScore, false)
            .setColor(heatColor)
            .setColorized(true)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1001, notif)
        } catch (e: Exce