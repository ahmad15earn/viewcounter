package com.viewcounter.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/** Home screen widget: today's number, the brain's mood and one of your own messages. */
class WidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        for (id in appWidgetIds) appWidgetManager.updateAppWidget(id, build(context))
    }

    companion object {
        fun build(c: Context): RemoteViews {
            val total = Store.sumToday(c, "*", "*")
            val limit = Store.getLimit(c)
            val ratio = if (limit > 0) Store.maxCountToday(c).toFloat() / limit else 0f
            val msgs = Store.widgetMsgs(c)
            val text = if (total == 0 || msgs.isEmpty()) {
                "আজ এখনো স্ক্রল শুরু করোনি। দারুণ, এভাবেই চলো!"
            } else {
                val slot = (System.currentTimeMillis() / 1_800_000L + total / 15).toInt()
                Store.fillTemplate(c, msgs[Math.floorMod(slot, msgs.size)])
            }
            val v = RemoteViews(c.packageName, R.layout.widget_view)
            v.setImageViewResource(R.id.w_img, Moods.res(Moods.forRatio(ratio)))
            v.setTextViewText(R.id.w_count, "$total scrolls today")
            v.setTextViewText(R.id.w_msg, text)
            val open = PendingIntent.getActivity(
                c, 1, Intent(c, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            v.setOnClickPendingIntent(R.id.w_root, open)
            return v
        }

        fun refresh(c: Context) {
            try {
                val mgr = AppWidgetManager.getInstance(c)
                val ids = mgr.getAppWidgetIds(ComponentName(c, WidgetProvider::class.java))
                if (ids.isEmpty()) return
                val v = build(c)
                for (id in ids) mgr.updateAppWidget(id, v)
            } catch (_: Exception) {
            }
        }
    }
}
