package com.viewcounter.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.AudioAttributes
import android.net.Uri

object Notifier {
    private const val CHANNEL = "alerts_v2"

    fun ensureChannel(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        try {
            nm.deleteNotificationChannel("alerts_v1")
        } catch (_: Exception) {
        }
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Scroll messages", NotificationManager.IMPORTANCE_HIGH)
        ch.description = "Your custom messages when you hit a scroll number"
        val uri = Uri.parse("android.resource://${c.packageName}/${R.raw.alert}")
        ch.setSound(
            uri,
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        ch.enableVibration(true)
        nm.createNotificationChannel(ch)
    }

    fun post(c: Context, id: Int, title: String, text: String, mood: Int) {
        if (!Store.flag(c, "notify")) return
        try {
            ensureChannel(c)
            val nm = c.getSystemService(NotificationManager::class.java) ?: return
            val open = PendingIntent.getActivity(
                c, 0,
                Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val bmp = BitmapFactory.decodeResource(c.resources, Moods.res(mood))
            val n = Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_eye)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setLargeIcon(bmp)
                .setColor(0xFF6C4DFF.toInt())
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            nm.notify(id, n)
        } catch (_: Exception) {
        }
    }
}
