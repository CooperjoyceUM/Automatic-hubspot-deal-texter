package com.utemaster.dealtexter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.utemaster.dealtexter.core.Notifier

/**
 * Posts notifications. Problems with the same key are shown at most once every
 * [REPEAT_AFTER_MS], so an ongoing HubSpot outage doesn't buzz every 15 minutes.
 */
class AppNotifier(private val context: Context) : Notifier {

    private val throttle = context.getSharedPreferences("notify_throttle", Context.MODE_PRIVATE)

    override fun problem(key: String, title: String, text: String) {
        val now = System.currentTimeMillis()
        val last = throttle.getLong(key, 0L)
        if (now - last < REPEAT_AFTER_MS) return
        throttle.edit { putLong(key, now) }
        post(CHANNEL_PROBLEMS, key.hashCode(), title, text)
    }

    override fun sent(dealId: String, title: String, text: String) {
        post(CHANNEL_SENT, "sent-$dealId".hashCode(), title, text)
    }

    private fun post(channel: String, id: Int, title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = Notification.Builder(context, channel)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java).notify(id, n) }
    }

    companion object {
        const val CHANNEL_PROBLEMS = "problems"
        const val CHANNEL_SENT = "sent"
        private const val REPEAT_AFTER_MS = 6 * 60 * 60 * 1000L

        fun createChannels(context: Context) {
            val nm = context.getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_PROBLEMS, "Problems needing attention", NotificationManager.IMPORTANCE_HIGH),
            )
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_SENT, "Texts sent", NotificationManager.IMPORTANCE_LOW),
            )
        }
    }
}
