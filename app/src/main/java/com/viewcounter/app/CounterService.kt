package com.viewcounter.app

import android.accessibilityservice.AccessibilityService
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.PowerManager
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
import kotlin.math.min

class CounterService : AccessibilityService() {

    private class Det(val mode: String, val sample: List<String>, val sig: Int)
    private class Detection(val mode: String, val ts: Long, val sig: Int)
    private class Blk(val kind: String, val title: String, val sub: String, val schedId: String?)

    private val lastCount = HashMap<String, Long>()
    private val lastDetect = HashMap<String, Long>()
    private val lastMode = HashMap<String, String>()
    private val lastSig = HashMap<String, Int>()
    private val candSig = HashMap<String, Int>()
    private val seenSigs = HashMap<String, LinkedHashSet<Int>>()
    private val lastStoryTs = HashMap<String, Long>()
    private val maxIdx = HashMap<String, Int>()
    private val idxTime = HashMap<String, Long>()
    private val timeAcc = HashMap<String, Long>()
    private val lastFlush = HashMap<String, Long>()
    private val passUntil = HashMap<String, Long>()
    private var passAllUntil = 0L
    private var lastWidget = 0L
    private val numRe = Regex("[0-9\u09E6-\u09EF:.]+")

    private val cache = ConcurrentHashMap<String, Detection>()
    private val pending = ConcurrentHashMap<String, Boolean>()
    private val handler = Handler(Looper.getMainLooper())
    private var worker: HandlerThread? = null
    private var workHandler: Handler? = null
    private var trackedSet: Set<String> = emptySet()
    private var rulesCache: List<Rule>? = null

    private var fg: String? = null
    private var awayCount = 0
    private var lastPolled: String? = "?"
    private var sessionStart = 0L
    private var lastActiveId: String? = null
    private var activeDate = ""

    private var popup: View? = null
    private var gate: View? = null
    private var block: View? = null
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
    private val gateTimeout = Runnable { closeGate() }
    private val blockTimeout = Runnable {
        closeBlock()
        performGlobalAction(GLOBAL_ACTION_HOME)
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
        handler.removeCallbacks(poll)
        handler.post(poll)
    }

    override fun onDestroy() {
        Store.prefs(this).unregisterOnSharedPreferenceChangeListener(prefListener)
        handler.removeCallbacks(poll)
        worker?.quitSafely()
        removePill()
        removePopup()
        removeGate()
        removeBlock()
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
        if (pkg !in trackedSet) return
        // nothing is counted while a pause or block is on screen
        if (gate != null || block != null) return
        val now = SystemClock.uptimeMillis()

        val isScroll = e.eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED
        val isContent = e.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        if (!isScroll && !isContent) return

        val platform = Store.PACKAGES[pkg]
        if (platform == null) {
            // an app you added with the + button: count only forward scrolling
            if (!isScroll) return
            val key = "app:$pkg"
            val add = forwardItems("$key|scrolls", e, now)
            if (add > 0) {
                val n = Store.inc(this, key, "scrolls", add)
                afterCount(key, "scrolls", n)
            }
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

        val key = "$platform|$mode"
        var add = 0
        if (mode == "stories") {
            // One story = one stable change of what is on screen. Waiting on a story, going back
            // to one you already saw, or a flickering screen must not count again.
            val sig = cached?.sig ?: 0
            val seen = seenSigs.getOrPut(platform) { LinkedHashSet() }
            val wasIn = lastMode[platform] == "stories"
            val sinceStory = now - (lastStoryTs[platform] ?: 0L)
            lastStoryTs[platform] = now
            lastMode[platform] = mode
            if (!wasIn && sinceStory > 3000L) {
                seen.clear()
                seen.add(sig)
                lastSig[platform] = sig
                candSig.remove(platform)
                add = 1
            } else if (sig != lastSig[platform]) {
                if (candSig[platform] == sig) {
                    candSig.remove(platform)
                    lastSig[platform] = sig
                    if (seen.add(sig) && now - (lastCount[key] ?: 0L) > 1000L) {
                        add = 1
                        if (seen.size > 30) seen.remove(seen.first())
                    }
                } else {
                    candSig[platform] = sig
                }
            } else {
                candSig.remove(platform)
            }
        } else {
            lastMode[platform] = mode
            if (!isScroll) return
            add = if (mode == "messages") {
                if (now - (lastCount[key] ?: 0L) < 600L) 0 else 1
            } else {
                forwardItems(key, e, now)
            }
        }
        if (add <= 0) return
        lastCount[key] = now
        val n = Store.inc(this, platform, mode, add)
        afterCount(platform, mode, n)
    }

    /**
     * How many new items this scroll moved forward. Scrolling back, or passing items you already saw, counts 0.
     * Lists report item positions, which also makes fast scrolling exact.
     */
    private fun forwardItems(key: String, e: AccessibilityEvent, now: Long): Int {
        val last = e.toIndex
        if (last >= 0 && e.itemCount > 0) {
            val prev = maxIdx[key]
            val stale = now - (idxTime[key] ?: 0L) > 60000L
            idxTime[key] = now
            if (prev == null || stale || last < prev - 30) {
                maxIdx[key] = last
                return if (key.endsWith("|reels")) 1 else 0
            }
            if (last > prev) {
                maxIdx[key] = last
                return min(last - prev, 5)
            }
            return 0
        }
        var dy = 0
        var dx = 0
        if (Build.VERSION.SDK_INT >= 28) {
            dy = e.scrollDeltaY
            dx = e.scrollDeltaX
        }
        if (dy < 0 || (dy == 0 && dx != 0)) return 0
        if (now - (lastCount[key] ?: 0L) < 450L) return 0
        lastCount[key] = now
        return 1
    }

    /** Real time in an app: one second added for every second it is in front (screen on, no pause screen). */
    private fun accrueTime(key: String, ms: Long) {
        val now = SystemClock.uptimeMillis()
        timeAcc[key] = (timeAcc[key] ?: 0L) + ms
        if (now - (lastFlush[key] ?: 0L) >= 5000L) {
            lastFlush[key] = now
            val total = timeAcc[key] ?: 0L
            if (total > 0L) {
                timeAcc[key] = 0L
                Store.inc(this, key, "time", total.toInt())
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
                cache[platform] = Detection(d.mode, SystemClock.uptimeMillis(), d.sig)
                Store.setDebug(this, "$platform -> ${d.mode} | " + d.sample.joinToString(","))
            } catch (_: Exception) {
            } finally {
                pending.remove(platform)
            }
        }
    }

    private fun detect(root: AccessibilityNodeInfo, pkg: String): Det {
        if (pkg == "com.facebook.katana") return detectFacebook(root)
        val ids = LinkedHashSet<String>()
        val sb = StringBuilder()
        var budget = 300
        var textBudget = 120
        fun walk(n: AccessibilityNodeInfo?) {
            if (n == null || budget <= 0) return
            budget--
            n.viewIdResourceName?.let { ids.add(it.substringAfter(":id/").lowercase()) }
            if (textBudget > 0) {
                val t = n.text
                val d = n.contentDescription
                if (t != null || d != null) {
                    textBudget--
                    if (t != null) sb.append(t)
                    if (d != null) sb.append(d)
                }
            }
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
        // numbers and clocks are removed so a ticking timer does not look like a new story
        return Det(mode, ids.take(12), numRe.replace(sb.toString(), "").hashCode())
    }

    /**
     * Facebook hides its view ids, so we read the bottom tab bar: Home -> posts, Reels -> reels.
     * Screens without a tab bar are full-screen viewers (stories or reels).
     * Labels are only looked at in memory to decide the mode. Nothing is saved.
     */
    private fun detectFacebook(root: AccessibilityNodeInfo): Det {
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
        val sig = numRe.replace(texts.joinToString("|"), "").hashCode()
        if (tabBar) {
            if (sel != null) {
                val m = when {
                    sel.startsWith("home") -> "posts"
                    sel.startsWith("reels") || sel.startsWith("video") || sel.startsWith("watch") -> "reels"
                    else -> "none"
                }
                return Det(m, sample, sig)
            }
            val reelsHeader = texts.any { it == "reels" } && texts.any { it.contains("follow") }
            return Det(if (reelsHeader) "reels" else "posts", sample, sig)
        }
        val storyCue = texts.any {
            it.contains("reply") || it.contains("viewers") || it.contains("seen by") ||
                it.contains("send message") || it.contains("add new") || it.contains("story")
        }
        if (storyCue) return Det("stories", sample, sig)
        val reelCue = texts.any { it.contains("follow") || it.contains("reel") }
        if (reelCue) return Det("reels", sample, sig)
        return Det("none", sample, sig)
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

    // ---------- live pill (count or speed timer) ----------
    private fun ensurePill(): Boolean {
        if (pill != null) return true
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
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
            return false
        }
        pill = row
        pillText = t
        pillImg = img
        pillColor = 0
        pillMood = 0
        return true
    }

    private fun stylePill(color: Int, mood: Int) {
        if (color != pillColor) {
            pillColor = color
            pill?.background = rounded(color, 24)
        }
        if (mood != pillMood) {
            pillMood = mood
            pillImg?.setImageResource(Moods.res(mood))
        }
    }

    private fun showPill(n: Int) {
        if (!Store.flag(this, "pill")) return
        if (Store.pillMode(this) != "count") return
        val limit = Store.getLimit(this)
        val ratio = if (limit > 0) n.toFloat() / limit else 0f
        if (!ensurePill()) return
        val color = when {
            ratio >= 1f -> 0xF0D64545.toInt()
            ratio >= 0.8f -> 0xF0E67E22.toInt()
            else -> 0xF014121F.toInt()
        }
        stylePill(color, Moods.forRatio(ratio))
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

    /** Speed timer mode: a stopwatch for this visit that keeps running while you are in the app. */
    private fun updateTimerPill() {
        if (!Store.flag(this, "pill") || Store.pillMode(this) != "timer") return
        if (!ensurePill()) return
        val ms = SystemClock.uptimeMillis() - sessionStart
        val mins = (ms / 60000L).toInt()
        val color = when {
            mins >= 60 -> 0xF0D64545.toInt()
            mins >= 30 -> 0xF0E67E22.toInt()
            else -> 0xF014121F.toInt()
        }
        val mood = when {
            mins < 10 -> 4
            mins < 30 -> 6
            mins < 60 -> 3
            else -> 1
        }
        stylePill(color, mood)
        pillText?.text = fmtDuration(ms)
        handler.removeCallbacks(hidePill)
        pill?.alpha = 1f
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

    // ---------- which app is in front ----------
    private val ignoreWindows = setOf(
        "com.android.systemui", "com.samsung.android.app.cocktailbarservice",
        "com.samsung.android.honeyboard", "com.google.android.inputmethod.latin", "com.sec.android.inputmethod"
    )

    /** Once a second (screen on) check which app is in front. A tracked app coming to the front = "opening". */
    private val poll = object : Runnable {
        override fun run() {
            try {
                pollForeground()
            } catch (_: Exception) {
            }
            handler.postDelayed(this, 1000)
        }
    }

    private fun pollForeground() {
        val pm = getSystemService(POWER_SERVICE) as PowerManager
        if (!pm.isInteractive) {
            fg = null
            awayCount = 3
            if (Store.pillMode(this) == "timer") removePill()
            return
        }
        val p = rootInActiveWindow?.packageName?.toString()
        if (p != lastPolled) {
            lastPolled = p
            Store.setGateDebug(this, "front app: $p, tracked=${p != null && trackedSet.contains(p)}")
        }
        if (p != null && trackedSet.contains(p)) {
            awayCount = 0
            if (fg != p) {
                fg = p
                onOpened(p)
            }
        } else if (p == null || !ignoreWindows.contains(p)) {
            awayCount++
            // away only after 3 seconds, so the notification shade or a keyboard does not count as leaving
            if (awayCount >= 3) fg = null
        }

        checkSchedules()

        // from here on only while a tracked app is really in front (not while we are going home)
        if (p == null || !trackedSet.contains(p)) {
            if (Store.pillMode(this) == "timer") removePill()
            return
        }
        val cur: String = p
        if (gate != null || block != null) return
        if (blockFor() != null) {
            showBlock()
            return
        }
        accrueTime(Store.PACKAGES[cur] ?: "app:$cur", 1000L)
        updateTimerPill()
    }

    private fun onOpened(pkg: String) {
        Store.inc(this, "gate", "attempt")
        sessionStart = SystemClock.uptimeMillis()
        if (gate != null || block != null) return
        if (blockFor() != null) {
            Store.inc(this, "gate", "blocked")
            showBlock()
            return
        }
        if (!Store.flag(this, "gate", false)) return
        if (SystemClock.uptimeMillis() < (passUntil[pkg] ?: 0L)) return
        showGate(pkg)
    }

    // ---------- focus schedule, points, hard limit ----------
    private fun blockFor(): Blk? {
        val now = SystemClock.uptimeMillis()
        val sched = Store.activeBlock(this)
        if (sched != null && now >= passAllUntil) {
            return Blk("schedule", sched.name, "${Store.fmtTime(sched.end)} পর্যন্ত অ্যাপ বন্ধ", sched.id)
        }
        if (Store.flag(this, "limit_on", true) && Store.sumToday(this, "*", "*") >= Store.hardLimit(this)) {
            return Blk("limit", "আজকের সীমা শেষ", "আগামীকাল আবার চেষ্টা করো", null)
        }
        return null
    }

    private fun checkSchedules() {
        val active = Store.activeBlock(this)
        val id = active?.id
        if (id == lastActiveId) return
        val prev = lastActiveId
        if (prev != null) finishBlock(prev)
        lastActiveId = id
        if (active != null) {
            activeDate = Store.day()
            Notifier.post(
                this, active.id.hashCode(), active.name,
                "এখন ফোকাস সময়। ${Store.fmtTime(active.end)} পর্যন্ত অ্যাপ বন্ধ থাকবে।", 6
            )
        }
    }

    private fun finishBlock(id: String) {
        val s = Store.schedules(this).firstOrNull { it.id == id } ?: return
        if (Store.passUsed(this, activeDate, id) || Store.awarded(this, activeDate, id)) return
        Store.markAwarded(this, activeDate, id)
        Store.addPoints(this, 20)
        Notifier.post(this, id.hashCode() + 7, s.name, "ফোকাস ব্লক শেষ! +২০ পয়েন্ট পেলে।", 4)
    }

    private fun showBlock() {
        if (block != null) return
        removeGate()
        val b = blockFor() ?: return
        val cost = 30
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val view = buildBlockView(
            this, b.title, b.sub, b.kind == "schedule", cost, Store.points(this),
            {
                closeBlock()
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
            {
                if (Store.spendPoints(this, cost)) {
                    passAllUntil = SystemClock.uptimeMillis() + 10 * 60 * 1000L
                    val sid = b.schedId
                    if (sid != null) Store.markPassUsed(this, activeDate.ifEmpty { Store.day() }, sid)
                    sessionStart = SystemClock.uptimeMillis()
                    closeBlock()
                }
            }
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, lp)
        } catch (_: Exception) {
            return
        }
        block = view
        removePill()
        view.alpha = 0f
        view.animate().alpha(1f).setStartDelay(0).setDuration(250).start()
        Sfx.play(this, R.raw.pop)
        handler.removeCallbacks(blockTimeout)
        handler.postDelayed(blockTimeout, 12000)
    }

    private fun closeBlock() {
        handler.removeCallbacks(blockTimeout)
        val v = block ?: return
        v.animate().alpha(0f).setStartDelay(0).setDuration(200).withEndAction { removeBlock() }.start()
    }

    private fun removeBlock() {
        val v = block ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        block = null
    }

    // ---------- mindful pause ----------
    private fun showGate(pkg: String) {
        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val name = Labels.appLabel(this, pkg)
        val view = buildGateView(
            this, name,
            {
                Store.inc(this, "gate", "no")
                Store.addPoints(this, 5)
                closeGate()
                Sfx.play(this, R.raw.chime, 0.8f)
                performGlobalAction(GLOBAL_ACTION_HOME)
            },
            {
                passUntil[pkg] = SystemClock.uptimeMillis() + 90 * 1000L
                maxIdx.clear()
                idxTime.clear()
                sessionStart = SystemClock.uptimeMillis()
                closeGate()
            }
        )
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        try {
            wm.addView(view, lp)
        } catch (_: Exception) {
            return
        }
        gate = view
        removePill()
        view.alpha = 0f
        view.animate().alpha(1f).setStartDelay(0).setDuration(250).start()
        Sfx.play(this, R.raw.pop)
        handler.removeCallbacks(gateTimeout)
        handler.postDelayed(gateTimeout, 60000)
    }

    private fun closeGate() {
        handler.removeCallbacks(gateTimeout)
        val v = gate ?: return
        v.animate().alpha(0f).setStartDelay(0).setDuration(200).withEndAction { removeGate() }.start()
    }

    private fun removeGate() {
        val v = gate ?: return
        try {
            (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(v)
        } catch (_: Exception) {
        }
        gate = null
    }
}
