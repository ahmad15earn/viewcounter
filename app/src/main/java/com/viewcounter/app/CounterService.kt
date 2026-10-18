package com.viewcounter.app

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Handler
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

class CounterService : AccessibilityService() {

    private val lastCount = HashMap<String, Long>()
    private val lastScan = HashMap<String, Long>()
    private val lastEvt = HashMap<String, Long>()
    private val handler = Handler(Looper.getMainLooper())
    private var trackedSet: Set<String> = emptySet()

    private var popup: View? = null
    private var pill: LinearLayout? = null
    private var pillText: TextView? = null
    private var pillImg: ImageView? = null

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "tracked") applyPackages()
    }
    private val hidePill = Runnable {
        pill?.animate()?.alpha(0f)?.setStartDelay(0)?.setDuration(250)?.withEndAction { removePill() }?.start()
    }

    override fun onServiceConnected() {
        Sfx.warm(this)
        Notifier.ensureChannel(this)
        applyPackages()
        Store.prefs(this).registerOnSharedPreferenceChangeListener(prefListener)
        Store.setDebug(this, "service connected")
    }

    override fun onDestroy() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
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
            info.packageNames = Store.watched(this).toTypedArray()
            serviceInfo = info
        } catch (_: Exception) {
        }
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

        val platform = Store.PACKAGES[pkg]
        if (platform == null) {
            // one of the apps you added with the + button
            if (pkg !in trackedSet) return
            val key = "app:$pkg"
            val prev = lastEvt[key] ?: 0L
            if (prev != 0L && now - prev < 5000L) Store.inc(this, key, "time", (now - prev).toInt())
            lastEvt[key] = now
            if (!isScroll) return
            if (now - (lastCount[key] ?: 0L) < 700L) return
            lastCount[key] = now
            val n = Store.inc(this, key, "scrolls")
            afterCount(key, "scrolls", n)
            return
        }

        if (isContent) {
            if (now - (lastScan[platform] ?: 0L) < 700L) return
            lastScan[platform] = now
        }

        val root = rootInActiveWindow ?: return
        val (mode, ids) = detect(root, pkg)

        // Only stories advance without scrolling, so only they count on content changes.
        if (isContent && mode != "stories") return

        // Ignore combinations that do not exist on that platform.
        if (platform == "whatsapp" && mode != "stories" && mode != "messages") return
        if (platform == "twitter" && mode == "stories") return

        val gap = when (mode) {
            "stories" -> 1500L
            "reels" -> 1200L
            "posts" -> 700L
            else -> 600L
        }
        val key = "$platform|$mode"
        if (now - (lastCount[key] ?: 0L) < gap) return
        lastCount[key] = now

        val n = Store.inc(this, platform, mode)
        Store.setDebug(this, "$platform -> $mode | ids: " + ids.take(15).joinToString(","))
        afterCount(platform, mode, n)
    }

    private fun detect(root: AccessibilityNodeInfo, pkg: String): Pair<String, List<String>> {
        val ids = LinkedHashSet<String>()
        var budget = 400
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
        return Pair(mode, ids.toList())
    }

    private fun afterCount(platform: String, type: String, n: Int) {
        showPill(n)
        for (r in Store.rules(this)) {
            if (!r.enabled) continue
            if (r.platform != "*" && r.platform != platform) continue
            if (r.type != "*" && r.type != type) continue
            if (Store.sumToday(this, r.platform, r.type) != r.threshold) continue
            if (Store.firedToday(this, r.id)) continue
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
        }
        val color = when {
            ratio >= 1f -> 0xF0D64545.toInt()
            ratio >= 0.8f -> 0xF0E67E22.toInt()
            else -> 0xF014121F.toInt()
        }
        pill?.background = rounded(color, 24)
        pillText?.text = n.toString()
        pillImg?.setImageResource(Moods.res(Moods.forRatio(ratio)))
        pill?.animate()?.cancel()
        pill?.alpha = 1f
        pill?.scaleX = 1.18f
        pill?.scaleY = 1.18f
        pill?.animate()?.scaleX(1f)?.scaleY(1f)?.setStartDelay(0)?.setDuration(260)
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
        Sfx.play(this, R.raw.pop)
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
