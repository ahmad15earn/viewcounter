package com.viewcounter.app

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** A custom message: "after [threshold] [type] on [platform], show [message] with [mood] face". */
class Rule(
    val id: String,
    val platform: String,   // "*" = all apps, built-in key, or "app:<package>"
    val type: String,       // "*" = any, or stories / posts / reels / messages
    val threshold: Int,
    val message: String,
    val mood: Int,          // 1..8
    val enabled: Boolean
)

object Store {
    val PACKAGES = mapOf(
        "com.instagram.android" to "instagram",
        "com.facebook.katana" to "facebook",
        "com.facebook.orca" to "facebook",
        "com.twitter.android" to "twitter",
        "com.whatsapp" to "whatsapp"
    )
    private val COUNT_TYPES = setOf("stories", "posts", "reels", "messages", "scrolls")

    fun prefs(c: Context): SharedPreferences =
        c.applicationContext.getSharedPreferences("counts", Context.MODE_PRIVATE)

    private fun fmt(cal: Calendar) = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
    fun day(): String = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
    fun dayAgo(n: Int): String {
        val cal = Calendar.getInstance()
        cal.add(Calendar.DAY_OF_YEAR, -n)
        return fmt(cal)
    }

    // ---------- counters ----------
    fun inc(c: Context, platform: String, type: String, by: Int = 1): Int {
        val p = prefs(c)
        val dk = "d|${day()}|$platform|$type"
        val tk = "t|$platform|$type"
        val n = p.getInt(dk, 0) + by
        p.edit().putInt(dk, n).putInt(tk, p.getInt(tk, 0) + by).apply()
        return n
    }

    fun today(c: Context, platform: String, type: String) = prefs(c).getInt("d|${day()}|$platform|$type", 0)
    fun total(c: Context, platform: String, type: String) = prefs(c).getInt("t|$platform|$type", 0)

    /** Sum of today's counters, with "*" wildcards. Time counters are not included. */
    fun sumToday(c: Context, platform: String, type: String): Int {
        var s = 0
        val prefix = "d|${day()}|"
        for ((k, v) in prefs(c).all) {
            if (v !is Int || !k.startsWith(prefix)) continue
            val parts = k.split("|")
            if (parts.size != 4 || parts[3] !in COUNT_TYPES) continue
            if (platform != "*" && parts[2] != platform) continue
            if (type != "*" && parts[3] != type) continue
            s += v
        }
        return s
    }

    fun dayTotal(c: Context, date: String): Int {
        var s = 0
        val prefix = "d|$date|"
        for ((k, v) in prefs(c).all) {
            if (v !is Int || !k.startsWith(prefix)) continue
            val parts = k.split("|")
            if (parts.size == 4 && parts[3] in COUNT_TYPES) s += v
        }
        return s
    }

    fun resetAll(c: Context) {
        val e = prefs(c).edit()
        for (k in prefs(c).all.keys) {
            if (k.startsWith("d|") || k.startsWith("t|") || k.startsWith("f|")) e.remove(k)
        }
        e.apply()
    }

    // ---------- settings ----------
    fun getLimit(c: Context) = prefs(c).getInt("limit", 50)
    fun setLimit(c: Context, v: Int) = prefs(c).edit().putInt("limit", v).apply()

    fun flag(c: Context, key: String, def: Boolean = true) = prefs(c).getBoolean("s_$key", def)
    fun setFlag(c: Context, key: String, v: Boolean) = prefs(c).edit().putBoolean("s_$key", v).apply()

    fun setupShown(c: Context) = prefs(c).getBoolean("setup_shown", false)
    fun markSetupShown(c: Context) = prefs(c).edit().putBoolean("setup_shown", true).apply()
    fun askedNotif(c: Context) = prefs(c).getBoolean("asked_notif", false)
    fun markAskedNotif(c: Context) = prefs(c).edit().putBoolean("asked_notif", true).apply()

    fun setDebug(c: Context, s: String) = prefs(c).edit().putString("debug", s).apply()
    fun getDebug(c: Context): String = prefs(c).getString("debug", "nothing detected yet") ?: ""

    // ---------- tracked apps ----------
    fun tracked(c: Context): List<String> =
        (prefs(c).getString("tracked", "") ?: "").split(",").filter { it.isNotBlank() }

    fun setTracked(c: Context, list: List<String>) =
        prefs(c).edit().putString("tracked", list.distinct().joinToString(",")).apply()

    fun watched(c: Context): Set<String> = PACKAGES.keys + tracked(c)

    // ---------- rules ----------
    private fun defaultRules(): MutableList<Rule> = mutableListOf(
        Rule("r1", "*", "reels", 50, "৫০টা রিল হয়ে গেছে ভাই! মাথাটা একটু জিরিয়ে নাও।", 6, true),
        Rule("r2", "*", "reels", 100, "১০০ রিল! তোমার ব্রেইন বলছে: আর না প্লিজ!", 7, true),
        Rule("r3", "*", "reels", 150, "১৫০ রিল পার! ফোন রেখে একটু বাইরে হেঁটে আসো।", 8, true),
        Rule("r4", "*", "*", 300, "আজ ৩০০টার বেশি কনটেন্ট স্ক্রল হয়েছে। আজকের জন্য যথেষ্ট!", 5, true)
    )

    fun rules(c: Context): MutableList<Rule> {
        val raw = prefs(c).getString("rules", null)
        if (raw == null) {
            val d = defaultRules()
            saveRules(c, d)
            return d
        }
        val out = mutableListOf<Rule>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Rule(
                        o.getString("id"), o.getString("platform"), o.getString("type"),
                        o.getInt("threshold"), o.getString("message"), o.getInt("mood"),
                        o.optBoolean("enabled", true)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveRules(c: Context, list: List<Rule>) {
        val a = JSONArray()
        for (r in list) {
            a.put(
                JSONObject().put("id", r.id).put("platform", r.platform).put("type", r.type)
                    .put("threshold", r.threshold).put("message", r.message).put("mood", r.mood)
                    .put("enabled", r.enabled)
            )
        }
        prefs(c).edit().putString("rules", a.toString()).apply()
    }

    fun firedToday(c: Context, id: String) = prefs(c).getBoolean("f|${day()}|$id", false)
    fun markFired(c: Context, id: String) = prefs(c).edit().putBoolean("f|${day()}|$id", true).apply()
}
