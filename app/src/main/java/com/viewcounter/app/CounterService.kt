package com.viewcounter.app

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

class CounterService : AccessibilityService() {

    private val lastCount = HashMap<String, Long>()
    private val lastScan = HashMap<String, Long>()
    private val handler = Handler(Looper.getMainLooper())
    private var overlay: View? = null

    override fun onServiceConnected() {
        Store.setDebug(this, "service connected, open Instagram/WhatsApp etc. and scroll")
    }

    override fun onInterrupt() {}

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
        val platform = when (pkg) {
            "com.instagram.android" -> "instagram"
            "com.facebook.katana", "com.facebook.orca" -> "facebook"
            "com.twitter.android" -> "twitter"
            "com.whatsapp" -> "whatsapp"
            else -> return
        }
        val isScroll = e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val isContent = e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isScroll && !isContent) return

        val now = SystemClock.uptimeMillis()
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

        val limit = Store.getLimit(this)
        if (limit > 0 && n % limit == 0) {
            val level = n / limit
            val emoji = when (level) { 1 -> "😴"; 2 -> "😵"; else -> "🤯" }
            showAvatar(emoji, "$platform $mode: $n today.\nTake a break?")
        }
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

    private fun showAvatar(emoji: String, text: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        removeOverlay()
        val bg = GradientDrawable().apply {
            setColor(0xEE222222.toInt())
            cornerRadius = 48f
        }
        val tv = TextView(this).apply {
            this.text = "$emoji\n$text"
            textSize = 20f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = bg
            setPadding(48, 48, 48, 48)
            setOnClickListener { removeOverlay() }
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP
            y = 160
        }
        try {
            wm.addView(tv, lp)
            overlay = tv
            handler.postDelayed({ removeOverlay() }, 7000)
        } catch (_: Exception) {
        }
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
    }
}
