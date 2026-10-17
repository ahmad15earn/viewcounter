package com.viewcounter.app

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object Store {
    private fun p(c: Context) = c.getSharedPreferences("counts", Context.MODE_PRIVATE)
    private fun day() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    fun inc(c: Context, platform: String, type: String): Int {
        val prefs = p(c)
        val dk = "d|${day()}|$platform|$type"
        val tk = "t|$platform|$type"
        val n = prefs.getInt(dk, 0) + 1
        prefs.edit().putInt(dk, n).putInt(tk, prefs.getInt(tk, 0) + 1).apply()
        return n
    }

    fun today(c: Context, platform: String, type: String) = p(c).getInt("d|${day()}|$platform|$type", 0)
    fun total(c: Context, platform: String, type: String) = p(c).getInt("t|$platform|$type", 0)

    fun getLimit(c: Context) = p(c).getInt("limit", 50)
    fun setLimit(c: Context, v: Int) = p(c).edit().putInt("limit", v).apply()

    fun setDebug(c: Context, s: String) = p(c).edit().putString("debug", s).apply()
    fun getDebug(c: Context): String = p(c).getString("debug", "nothing detected yet") ?: ""

    fun resetAll(c: Context) {
        val l = getLimit(c)
        p(c).edit().clear().putInt("limit", l).apply()
    }
}
