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

/** A focus block: apps stay closed from [start] to [end] (minutes since midnight) on the chosen [days] (bit 0 = Monday). */
class Sched(
    val id: String,
    val name: String,
    val start: Int,
    val end: Int,
    val days: Int,
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
            if (k.startsWith("d|") || k.startsWith("t|") || k.startsWith("f|") || k.startsWith("pu|") || k.startsWith("aw|")) e.remove(k)
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
    fun setGateDebug(c: Context, s: String) = prefs(c).edit().putString("gdebug", s).apply()
    fun getGateDebug(c: Context): String = prefs(c).getString("gdebug", "not seen yet") ?: ""

    // ---------- tracked apps ----------
    fun tracked(c: Context): List<String> =
        (prefs(c).getString("tracked", "") ?: "").split(",").filter { it.isNotBlank() }

    fun setTracked(c: Context, list: List<String>) =
        prefs(c).edit().putString("tracked", list.distinct().joinToString(",")).apply()

    fun watched(c: Context): Set<String> = tracked(c).toSet()

    /** Built-in platforms (Instagram, Facebook, X, WhatsApp) the user has added, in a fixed order. */
    fun trackedPlatforms(c: Context): List<String> {
        val set = tracked(c).mapNotNull { PACKAGES[it] }.toSet()
        return listOf("instagram", "facebook", "twitter", "whatsapp").filter { set.contains(it) }
    }

    fun customApps(c: Context): List<String> = tracked(c).filter { !PACKAGES.containsKey(it) }

    fun maxCountToday(c: Context): Int {
        var m = 0
        val prefix = "d|${day()}|"
        for ((k, v) in prefs(c).all) {
            if (v !is Int || !k.startsWith(prefix)) continue
            val parts = k.split("|")
            if (parts.size == 4 && parts[3] in COUNT_TYPES && v > m) m = v
        }
        return m
    }

    fun todayMinutes(c: Context): Int {
        var ms = 0L
        val prefix = "d|${day()}|"
        for ((k, v) in prefs(c).all) {
            if (v !is Int || !k.startsWith(prefix)) continue
            val parts = k.split("|")
            if (parts.size == 4 && parts[3] == "time") ms += v
        }
        return (ms / 60000L).toInt()
    }

    // ---------- widget messages ----------
    private val DEFAULT_WIDGET = listOf(
        "{reels}টা রিল দেখেছ ভাই, এবার একটু বই পড়ো।",
        "ভাই, একটু লেখাপড়া করো! আজ {mins} মিনিট স্ক্রলে চলে গেছে।",
        "এই {mins} মিনিটে বন্ধুদের সাথে একটু আড্ডা দিতে পারতে।",
        "আজ {total}টা কনটেন্ট স্ক্রল করেছ। ফোনটা রেখে একটু হাঁটো।",
        "{mins} মিনিট নষ্ট! এই সময়ে নতুন কিছু শিখতে পারতে।"
    )

    fun widgetMsgs(c: Context): MutableList<String> {
        val raw = prefs(c).getString("wmsgs", null) ?: return DEFAULT_WIDGET.toMutableList()
        val out = mutableListOf<String>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) out.add(a.getString(i))
        } catch (_: Exception) {
        }
        return out
    }

    fun saveWidgetMsgs(c: Context, list: List<String>) {
        val a = JSONArray()
        for (m in list) a.put(m)
        prefs(c).edit().putString("wmsgs", a.toString()).apply()
    }

    /** Fills {reels} {posts} {stories} {messages} {total} {mins}. Bangla text gets Bangla digits. */
    fun fillTemplate(c: Context, tpl: String): String {
        val bn = tpl.any { it.code in 0x0980..0x09FF }
        fun num(n: Int): String {
            val s = n.toString()
            if (!bn) return s
            val sb = StringBuilder()
            for (ch in s) sb.append(if (ch in '0'..'9') (0x09E6 + (ch - '0')).toChar() else ch)
            return sb.toString()
        }
        return tpl
            .replace("{reels}", num(sumToday(c, "*", "reels")))
            .replace("{posts}", num(sumToday(c, "*", "posts")))
            .replace("{stories}", num(sumToday(c, "*", "stories")))
            .replace("{messages}", num(sumToday(c, "*", "messages")))
            .replace("{total}", num(sumToday(c, "*", "*")))
            .replace("{mins}", num(todayMinutes(c)))
    }

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

    // ---------- points ----------
    fun points(c: Context) = prefs(c).getInt("points", 0)
    fun addPoints(c: Context, n: Int) = prefs(c).edit().putInt("points", maxOf(0, points(c) + n)).apply()
    fun spendPoints(c: Context, n: Int): Boolean {
        if (points(c) < n) return false
        addPoints(c, -n)
        return true
    }

    fun hardLimit(c: Context) = prefs(c).getInt("hard_limit", 1000)
    fun setHardLimit(c: Context, v: Int) = prefs(c).edit().putInt("hard_limit", v).apply()

    fun pillMode(c: Context): String = prefs(c).getString("pill_mode", "count") ?: "count"
    fun setPillMode(c: Context, m: String) = prefs(c).edit().putString("pill_mode", m).apply()

    // ---------- focus schedule ----------
    fun fmtTime(min: Int): String = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)

    fun schedules(c: Context): MutableList<Sched> {
        val raw = prefs(c).getString("sched", null) ?: return mutableListOf()
        val out = mutableListOf<Sched>()
        try {
            val a = JSONArray(raw)
            for (i in 0 until a.length()) {
                val o = a.getJSONObject(i)
                out.add(
                    Sched(
                        o.getString("id"), o.getString("name"), o.getInt("start"), o.getInt("end"),
                        o.getInt("days"), o.optBoolean("enabled", true)
                    )
                )
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveSchedules(c: Context, list: List<Sched>) {
        val a = JSONArray()
        for (s in list) {
            a.put(
                JSONObject().put("id", s.id).put("name", s.name).put("start", s.start)
                    .put("end", s.end).put("days", s.days).put("enabled", s.enabled)
            )
        }
        prefs(c).edit().putString("sched", a.toString()).apply()
    }

    /** The focus block that is running right now, if any. Overnight blocks belong to the day they start on. */
    fun activeBlock(c: Context): Sched? {
        val cal = Calendar.getInstance()
        val nowMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        val dow = (cal.get(Calendar.DAY_OF_WEEK) + 5) % 7
        val prev = (dow + 6) % 7
        for (s in schedules(c)) {
            if (!s.enabled || s.start == s.end) continue
            val today = ((s.days shr dow) and 1) == 1
            val yesterday = ((s.days shr prev) and 1) == 1
            if (s.start < s.end) {
                if (today && nowMin >= s.start && nowMin < s.end) return s
            } else {
                if (today && nowMin >= s.start) return s
                if (yesterday && nowMin < s.end) return s
            }
        }
        return null
    }

    fun passUsed(c: Context, date: String, id: String) = prefs(c).getBoolean("pu|$date|$id", false)
    fun markPassUsed(c: Context, date: String, id: String) = prefs(c).edit().putBoolean("pu|$date|$id", true).apply()
    fun awarded(c: Context, date: String, id: String) = prefs(c).getBoolean("aw|$date|$id", false)
    fun markAwarded(c: Context, date: String, id: String) = prefs(c).edit().putBoolean("aw|$date|$id", true).apply()

    fun firedToday(c: Context, id: String) = prefs(c).getBoolean("f|${day()}|$id", false)
    fun markFired(c: Context, id: String) = prefs(c).edit().putBoolean("f|${day()}|$id", true).apply()
}
