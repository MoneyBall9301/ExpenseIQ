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

        val notif = NotificationCompat.Builder(context, "expenseiq_alerts")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(1001, notif)
        } catch (e: Exception) { /* silently ignore if permission revoked */ }

        // Re-schedule for tomorrow
        reschedule(context, prefs)
    }

    private fun reschedule(context: Context, prefs: android.content.SharedPreferences) {
        val hour = prefs.getInt("notif_hour", 20)
        val minute = prefs.getInt("notif_minute", 0)
        val cal = java.util.Calendar.getInstance().apply {
            add(java.util.Calendar.DAY_OF_YEAR, 1)
            set(java.util.Calendar.HOUR_OF_DAY, hour)
            set(java.util.Calendar.MINUTE, minute)
            set(java.util.Calendar.SECOND, 0)
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, NotificationReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        try {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } catch (e: Exception) {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }
    }
}
