package com.viewcounter.app

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.ConcurrentHashMap

class CounterService : AccessibilityService() {

    private class Detection(val mode: String, val ts: Long)

    private val lastCount = HashMap<String, Long>()
    private val lastEvt = HashMap<String, Long>()
    private val lastDetect = HashMap<String, Long>()
    private val timeAcc = HashMap<String, Long>()
    private val lastFlush = HashMap<String, Long>()
    private var lastWidget = 0L
    private val lastMode = HashMap<String, String>()
    private val cache = ConcurrentHashMap<String, Detection>()
    private val pending = ConcurrentHashMap<String, Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var workHandler: Handler? = null
    private var trackedSet: Set<String> = emptySet()
    private var rulesCache: List<Rule>? = null

    private var popup: View? = null
    private var pill: LinearLayout? = null
    private var pillText: TextView? = null
    private var pillImg: ImageView? = null
    private var pillColor = 0
    private var pillMood = 0

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "tracked") applyPackages()
        if (key == "rules") rulesCache = null
    }
    private val hidePill = Runnable {
        pill?.animate()?.alpha(0f)?.setStartDelay(0)?.setDuration(250)?.withEndAction { removePill() }?.start()
    }

    override fun onServiceConnected() {
        Sfx.warm(this)
        Notifier.ensureChannel(this)
        val t = HandlerThread("vc-detect")
        t.start()
        worker = t
        workHandler = Handler(t.looper)
        applyPackages()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        Store.setDebug(this, "service connected")
    }

    override fun onDestroy() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        worker?.quitSafely()
        removePill()
        removePopup()
        super.onDestroy()
    }

    override fun onInterrupt() {}

    /** Only the apps you chose are ever sent to this service. Everything else is filtered by Android. */
    private fun applyPackages() {
        try {
            trackedSet = Store.tracked(this).toSet()
            val info = serviceInfo ?: return
            val w = Store.watched(this)
            // an empty list would mean every app, so use a package that never exists
            info.packageNames = if (w.isEmpty()) arrayOf("com.viewcounter.none") else w.toTypedArray()
            serviceInfo = info
        } catch (_: Exception) {
        }
    }

    private fun rules(): List<Rule> {
        val cached = rulesCache
        if (cached != null) return cached
        val fresh = Store.rules(this)
        rulesCache = fresh
        return fresh
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        try {
            handle(e)
        } catch (_: Exception) {
            // never let one bad event crash the service
        }
    }

    private fun handle(e: AccessibilityEvent?) {
        if (e == null) return
        val pkg = e.packageName?.toString() ?: return
        val isScroll = e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val isContent = e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isScroll && !isContent) return
        val now = SystemClock.uptimeMillis()

        // only apps you added with the + button are counted
        if (pkg !in trackedSet) return
        val platform = Store.PACKAGES[pkg]
        trackTime(platform ?: "app:$pkg", now)
        if (platform == null) {
            val key = "app:$pkg"
            if (!isScroll) return
            if (now - (lastCount[key] ?: 0L) < 450L) return
            lastCount[key] = now
            val n = Store.inc(this, key, "scrolls")
            afterCount(key, "scrolls", n)
            return
        }

        // Fast path: counting only reads a cached answer. The heavy screen check runs on a background thread.
        val cached = cache[platform]
        val mode = cached?.mode
        if (isContent) {
            if (now - (lastDetect[platform] ?: 0L) >= 400L) requestDetect(platform, pkg, now)
            if (mode != "stories") return
        } else {
            if (cached == null || now - cached.ts > 700L) requestDetect(platform, pkg, now)
        }
        if (mode == null || mode == "none") return

        // Ignore combinations that do not exist on that platform.
        if (platform == "whatsapp" && mode != "stories" && mode != "messages") return
        if (platform == "twitter" && mode == "stories") return

        val entering = mode == "stories" && lastMode[platform] != "stories"
        lastMode[platform] = mode
        val gap = when (mode) {
            "stories" -> if (isContent) 2500L else 900L
            "reels" -> 450L
            "posts" -> 500L
            else -> 600L
        }
        val key = "$platform|$mode"
        if (!entering && now - (lastCount[key] ?: 0L) < gap) return
        lastCount[key] = now

        val n = Store.inc(this, platform, mode)
        afterCount(platform, mode, n)
    }

    /** Active time: gaps between events shorter than 5 seconds, flushed to disk every 5 seconds. */
    private fun trackTime(key: String, now: Long) {
        val prev = lastEvt[key] ?: 0L
        lastEvt[key] = now
        if (prev != 0L && now - prev < 5000L) timeAcc[key] = (timeAcc[key] ?: 0L) + (now - prev)
        if (now - (lastFlush[key] ?: 0L) >= 5000L) {
            lastFlush[key] = now
            val ms = timeAcc[key] ?: 0L
            if (ms > 0L) {
                timeAcc[key] = 0L
                Store.inc(this, key, "time", ms.toInt())
            }
        }
    }

    private fun requestDetect(platform: String, pkg: String, now: Long) {
        lastDetect[platform] = now
        if (pending.putIfAbsent(platform, true) != null) return
        val root = rootInActiveWindow
        val wh = workHandler
        if (root == null || wh == null) {
            pending.remove(platform)
            return
        }
        wh.post {
            try {
                val d = detect(root, pkg)
                cache[platform] = Detection(d.first, SystemClock.uptimeMillis())
                Store.setDebug(this, "$platform -> ${d.first} | " + d.second.joinToString(","))
            } catch (_: Exception) {
            } finally {
                pending.remove(platform)
            }
        }
    }

    private fun detect(root: AccessibilityNodeInfo, pkg: String): Pair<String, List<String>> {
        if (pkg == "com.facebook.katana") return detectFacebook(root)
        val ids = LinkedHashSet<String>()
        var budget = 300
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || budget <= 0) return
            budget--
            n.viewIdResourceName?.let { ids.add(it.substringAfter(":id/").lowercase()) }
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val s = ids.joinToString(" ")
        val mode = when {
            pkg == "com.facebook.orca" -> "messages"
            s.contains("reel_viewer") || s.contains("stories_viewer") ||
                s.contains("status_playback") || s.contains("status_viewer") -> "stories"
            s.contains("clips") -> "reels"
            s.contains("direct") || s.contains("thread") || s.contains("conversation") ||
                s.contains("message") || s.contains("chat") -> "messages"
            else -> "posts"
        }
        return Pair(mode, ids.take(12))
    }

    /**
     * Facebook hides its view ids, so we read the bottom tab bar: Home -> posts, Reels -> reels.
     * Screens without a tab bar are full-screen viewers (stories or reels).
     * Labels are only looked at in memory to decide the mode. Nothing is saved.
     */
    private fun detectFacebook(root: AccessibilityNodeInfo): Pair<String, List<String>> {
        var tabBar = false
        var selected: String? = null
        val texts = ArrayList<String>()
        var budget = 300
        val tabRe = Regex("tab \\d+ of \\d+")
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || budget <= 0) return
            budget--
            val d = n.contentDescription?.toString()?.lowercase()
            val t = n.text?.toString()?.lowercase()
            if (d != null) {
                if (tabRe.containsMatchIn(d)) {
                    tabBar = true
                    if (n.isSelected) selected = d
                }
                if (texts.size < 80) texts.add(d)
            }
            if (t != null && texts.size < 80) texts.add(t)
            for (i in 0 until n.childCount) walk(n.getChild(i))
        }
        walk(root)
        val sel = selected
        val sample = texts.take(8)
        if (tabBar) {
            if (sel != null) {
                return when {
                    sel.startsWith("home") -> Pair("posts", sample)
                    sel.startsWith("reels") || sel.startsWith("video") || sel.startsWith("watch") -> Pair("reels", sample)
                    else -> Pair("none", sample)
                }
            }
            val reelsHeader = texts.any { it == "reels" } && texts.any { it.contains("follow") }
            return if (reelsHeader) Pair("reels", sample) else Pair("posts", sample)
        }
        val storyCue = texts.any {
            it.contains("reply") || it.contains("viewers") || it.contains("seen by") ||
                it.contains("send message") || it.contains("add new") || it.contains("story")
        }
        if (storyCue) return Pair("stories", sample)
        val reelCue = texts.any { it.contains("follow") || it.contains("reel") }
        if (reelCue) return Pair("reels", sample)
        return Pair("none", sample)
    }

    private fun afterCount(platform: String, type: String, n: Int) {
        showPill(n)
        val t = SystemClock.uptimeMillis()
        if (t - lastWidget > 15000L) {
            lastWidget = t
            WidgetProvider.refresh(this)
        }
        for (r in rules()) {
            if (!r.enabled) continue
            if (r.platform != "*" && r.platform != platform) continue
            if (r.type != "*" && r.type != type) continue
            if (Store.firedToday(this, r.id)) continue
            if (Store.sumToday(this, r.platform, r.type) < r.threshold) continue
            Store.markFired(this, r.id)
            val title = Labels.ruleTitle(this, r)
            Notifier.post(this, r.id.hashCode(), title, r.message, r.mood)
            showPopup(title, r.message, r.mood)
        }
    }

    // ---------- live counter pill ----------
    private fun showPill(n: Int) {
        if (!Store.flag(this, "pill")) return
        val limit = Store.getLimit(this)
        val ratio = if (limit > 0) n.toFloat() / limit else 0f
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        if (pill == null) {
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(8), dp(6), dp(16), dp(6))
            }
            val img = ImageView(this)
            row.addView(img, LinearLayout.LayoutParams(dp(30), dp(30)))
            val t = tv("0", 17f, Color.WHITE, 2)
            row.addView(t, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginStart = dp(8)
            })
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(56)
            }
            try {
                wm.addView(row, lp)
            } catch (_: Exception) {
                return
            }
            pill = row
            pillText = t
            pillImg = img
            pillColor = 0
            pillMood = 0
        }
        val color = when {
            ratio >= 1f -> 0xF0D64545.toInt()
            ratio >= 0.8f -> 0xF0E67E22.toInt()
            else -> 0xF014121F.toInt()
        }
        if (color != pillColor) {
            pillColor = color
            pill?.background = rounded(color, 24)
        }
        val mood = Moods.forRatio(ratio)
        if (mood != pillMood) {
            pillMood = mood
            pillImg?.setImageResource(Moods.res(mood))
        }
        pillText?.text = n.toString()
        pill?.animate()?.cancel()
        pill?.alpha = 1f
        pill?.scaleX = 1.12f
        pill?.scaleY = 1.12f
        pill?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(0)?.setDuration(200)
            ?.setInterpolator(OvershootInterpolator(2f))?.start()
        handler.removeCallbacks(hidePill)
        handler.postDelayed(hidePill, 2600)
    }

    private fun removePill() {
        val v = pill ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        pill = null
        pillText = null
        pillImg = null
    }

    // ---------- reaction pop-up ----------
    private fun showPopup(title: String, message: String, mood: Int) {
        removePopup()
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val card = buildReactionCard(this, title, message, mood) { dismissPopup() }
        val frame = FrameLayout(this)
        frame.addView(card, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        val lp = WindowManager.LayoutParams(
            dp(320),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.45f
        }
        try {
            wm.addView(frame, lp)
        } catch (_: Exception) {
            return
        }
        popup = frame
        popIn(card)
        Sfx.play(this, R.raw.alert)
        handler.postDelayed({ dismissPopup() }, 9000)
    }

    private fun dismissPopup() {
        val v = popup ?: return
        v.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(180)
            .withEndAction { removePopup() }.start()
    }

    private fun removePopup() {
        val v = popup ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        popup = null
    }
}
