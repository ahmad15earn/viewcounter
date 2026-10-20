package com.viewcounter.app

import android.Manifest
import android.animation.ValueAnimator
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.app.AlertDialog
import android.app.Dialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.Editable
import android.text.InputFilter
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private val SPLASH_BG = 0xFFFFFBE3.toInt()
private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

class MainActivity : Activity() {

    private class Plat(val key: String, val name: String, val badge: String, val color: Int, val pastel: Int, val types: List<String>)
    private class Row(val count: TextView, val bar: BarView)
    private class CustomRow(val total: TextView, val detail: TextView, val bar: BarView)
    private class AppInfo(val pkg: String, val label: String, val icon: Drawable, val suggested: Boolean)

    private lateinit var pal: Pal
    private var night = false
    private lateinit var root: FrameLayout
    private lateinit var content: FrameLayout
    private lateinit var nav: LinearLayout
    private val navIcons = ArrayList<IconView>()
    private val navBgs = ArrayList<View>()
    private var tab = 0
    private var introDone = false
    private var animateEnter = true
    private val enterViews = ArrayList<View>()
    private val h = Handler(Looper.getMainLooper())

    // live refs for the Home tab
    private val rows = HashMap<String, Row>()
    private val customRows = HashMap<String, CustomRow>()
    private val platTotals = HashMap<String, TextView>()
    private val tileNums = HashMap<String, TextView>()
    private var heroNumber: TextView? = null
    private var heroSub: TextView? = null
    private var heroMascot: ImageView? = null
    private var weekView: WeekView? = null
    private var shownTotal = 0
    private var countAnim: ValueAnimator? = null
    private var mascotAnim: ValueAnimator? = null
    private var lastMood = 0

    private val tick = object : Runnable {
        override fun run() {
            if (introDone && tab == 0) updateHome(true)
            h.postDelayed(this, 1500)
        }
    }

    private val plats = listOf(
        Plat("instagram", "Instagram", "IG", 0xFFE1306C.toInt(), Pastel.PINK, listOf("stories", "posts", "reels", "messages")),
        Plat("facebook", "Facebook", "FB", 0xFF1877F2.toInt(), Pastel.BLUE, listOf("stories", "posts", "reels", "messages")),
        Plat("twitter", "Twitter / X", "X", 0xFF1D9BF0.toInt(), Pastel.SKY, listOf("posts", "reels", "messages")),
        Plat("whatsapp", "WhatsApp", "WA", 0xFF128C7E.toInt(), Pastel.GREEN, listOf("stories", "messages"))
    )

    // ---------- small helpers ----------
    private fun lp(top: Int = 0, bottom: Int = 0, h: Int = WRAP) =
        LinearLayout.LayoutParams(MATCH, h).apply {
            topMargin = top
            bottomMargin = bottom
        }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    private fun label(type: String, platform: String) =
        if (platform == "whatsapp" && type == "stories") "Status" else Labels.type(type)

    private fun greeting(): String {
        val hr = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when {
            hr < 12 -> "Good morning"
            hr < 17 -> "Good afternoon"
            hr < 21 -> "Good evening"
            else -> "Good night"
        }
    }

    private fun newScreen(): Pair<ScrollView, LinearLayout> {
        enterViews.clear()
        val sv = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(130))
        }
        sv.addView(col, FrameLayout.LayoutParams(MATCH, WRAP))
        return Pair(sv, col)
    }

    private fun add(col: LinearLayout, v: View, params: LinearLayout.LayoutParams) {
        if (animateEnter) {
            v.alpha = 0f
            v.translationY = dpf(24)
        }
        col.addView(v, params)
        enterViews.add(v)
    }

    private fun playEnter(base: Long) {
        enterViews.forEachIndexed { i, v ->
            v.animate().alpha(1f).translationY(0f)
                .setStartDelay(base + i * 55L)
                .setDuration(480)
                .setInterpolator(DecelerateInterpolator(2f))
                .start()
        }
    }

    private fun header(title: String, sub: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(tv(title, 32f, pal.ink, 2, -0.02f))
        addView(tv(sub, 15f, pal.sub))
    }

    private fun card(color: Int, radius: Int = 28): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = rounded(color, radius)
    }

    private fun primaryButton(text: String): TextView = tv(text, 16f, Color.WHITE, 1).apply {
        gravity = Gravity.CENTER
        minHeight = dp(56)
        background = rounded(pal.accent, 28)
    }

    private fun secondaryButton(text: String): TextView = tv(text, 16f, pal.ink, 1).apply {
        gravity = Gravity.CENTER
        minHeight = dp(56)
        background = rounded(pal.track, 28)
    }

    private fun applyBars() {
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        window.decorView.systemUiVisibility =
            if (night) 0
            else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
    }

    // ---------- lifecycle ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        pal = Pal(night)
        Sfx.warm(this)
        Notifier.ensureChannel(this)

        window.statusBarColor = SPLASH_BG
        window.navigationBarColor = SPLASH_BG

        root = FrameLayout(this).apply {
            setBackgroundColor(pal.bg)
            clipChildren = false
        }
        content = FrameLayout(this)
        root.addView(content, FrameLayout.LayoutParams(MATCH, MATCH))
        buildNav()
        root.addView(nav, FrameLayout.LayoutParams(MATCH, dp(72)).apply {
            gravity = Gravity.BOTTOM
            setMargins(dp(20), 0, dp(20), dp(16))
        })
        nav.translationY = dpf(160)

        animateEnter = true
        showTab(0, false)

        val splash = SplashView(this, SPLASH_BG, 0xFF14121F.toInt(), 0xFF5A576E.toInt())
        root.addView(splash, FrameLayout.LayoutParams(MATCH, MATCH))
        setContentView(root)
        splash.play { finishIntro(splash) }
    }

    private fun finishIntro(splash: View) {
        introDone = true
        applyBars()
        splash.animate().alpha(0f).setStartDelay(0).setDuration(300).withEndAction {
            (splash.parent as? ViewGroup)?.removeView(splash)
        }.start()
        playEnter(80L)
        nav.animate().translationY(0f).setStartDelay(300).setDuration(600)
            .setInterpolator(OvershootInterpolator(1.1f)).start()
        // numbers and bars grow from zero once the cards slide in
        shownTotal = 0
        heroNumber?.text = "0"
        for (r in rows.values) r.bar.setTarget(0f, false)
        for (r in customRows.values) r.bar.setTarget(0f, false)
        weekView?.reset()
        lastWeekTotal = -1
        h.postDelayed({ updateHome(true) }, 550)
        h.postDelayed({
            if (!Store.setupShown(this) && setupLeft() > 0) {
                Store.markSetupShown(this)
                showSetupSheet()
            }
        }, 1300)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
        if (introDone) showTab(tab, false)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
        mascotAnim?.cancel()
        WidgetProvider.refresh(this)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (tab != 0) selectTab(0) else super.onBackPressed()
    }

    // ---------- navigation ----------
    private fun buildNav() {
        nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(pal.nav, 36)
            setPadding(dp(8), 0, dp(8), 0)
            clipChildren = false
            elevation = dpf(10)
        }
        val defs = listOf("home" to 0, "chat" to 1, "plus" to -1, "grid" to 2, "gear" to 3)
        for ((kind, idx) in defs) {
            val slot = FrameLayout(this).apply { clipChildren = false }
            if (idx >= 0) {
                val bgc = View(this).apply {
                    background = circle(Color.WHITE)
                    alpha = 0f
                    scaleX = 0.6f
                    scaleY = 0.6f
                }
                slot.addView(bgc, FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
                val ic = IconView(this, kind, Color.WHITE)
                slot.addView(ic, FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                navBgs.add(bgc)
                navIcons.add(ic)
                slot.pressable(0) { selectTab(idx) }
            } else {
                val fab = FrameLayout(this).apply {
                    background = circle(pal.accent)
                    elevation = dpf(4)
                }
                fab.addView(IconView(this, "plus", Color.WHITE), FrameLayout.LayoutParams(dp(26), dp(26), Gravity.CENTER))
                slot.addView(fab, FrameLayout.LayoutParams(dp(52), dp(52), Gravity.CENTER))
                fab.pressable(R.raw.pop) { showAppPicker() }
            }
            nav.addView(slot, LinearLayout.LayoutParams(0, MATCH, 1f))
        }
    }

    private fun updateNav(selected: Int) {
        for (i in 0 until navBgs.size) {
            val on = i == selected
            navBgs[i].animate().alpha(if (on) 1f else 0f).scaleX(if (on) 1f else 0.6f)
                .scaleY(if (on) 1f else 0.6f).setStartDelay(0).setDuration(240)
                .setInterpolator(OvershootInterpolator(1.4f)).start()
            navIcons[i].setIconColor(if (on) 0xFF14121F.toInt() else Color.WHITE)
            navIcons[i].setFilled(on)
        }
    }

    private fun selectTab(i: Int) {
        if (i != tab) showTab(i, true)
    }

    private fun showTab(i: Int, play: Boolean) {
        tab = i
        mascotAnim?.cancel()
        animateEnter = play || !introDone
        val view = when (i) {
            0 -> buildHome()
            1 -> buildMessages()
            2 -> buildApps()
            else -> buildSettings()
        }
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
        updateNav(i)
        if (i == 0) updateHome(false)
        if (play) playEnter(0L)
    }

    // ---------- setup checks ----------
    private fun notifOk(): Boolean =
        if (Build.VERSION.SDK_INT >= 33)
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        else true

    private fun batteryOk(): Boolean =
        (getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(packageName)

    private fun a11yOn(): Boolean =
        Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?.contains("$packageName/") == true

    private fun setupLeft(): Int = listOf(notifOk(), batteryOk(), a11yOn()).count { !it }

    private fun askNotif() {
        if (Build.VERSION.SDK_INT >= 33 && !Store.askedNotif(this)) {
            Store.markAskedNotif(this)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7)
        } else {
            try {
                startActivity(
                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName)
                )
            } catch (_: Exception) {
            }
        }
    }

    private fun askBattery() {
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (_: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {
            }
        }
    }

    private fun stepRow(title: String, sub: String, done: Boolean, action: String, onClick: () -> Unit): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val dot = FrameLayout(this).apply { background = circle(if (done) pal.green else pal.track) }
        if (done) dot.addView(IconView(this, "check", Color.WHITE), FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
        row.addView(dot, LinearLayout.LayoutParams(dp(28), dp(28)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(tv(title, 16f, pal.ink, 1))
        t.addView(tv(sub, 13f, pal.sub))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(8)
        })
        if (!done) {
            val b = tv(action, 14f, Color.WHITE, 1).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(18), 0, dp(18), 0)
                background = rounded(pal.accent, 22)
            }
            b.pressable(onClick = onClick)
            row.addView(b)
        }
        return row
    }

    private fun addSetupRows(box: LinearLayout, close: (() -> Unit)?) {
        box.addView(stepRow("Allow notifications", "So your messages can reach you", notifOk(), "Allow") {
            close?.invoke()
            askNotif()
        })
        box.addView(stepRow("Keep running in background", "Samsung: also add it under Battery > Never sleeping apps", batteryOk(), "Allow") {
            close?.invoke()
            askBattery()
        })
        box.addView(stepRow("Turn on counting", "Android asks for two quick taps", a11yOn(), "Set up") {
            close?.invoke()
            showA11ySheet()
        })
    }

    private fun showSetupSheet() {
        sheet { box, close ->
            box.addView(tv("Let's get you set up", 24f, pal.ink, 2, -0.01f))
            box.addView(tv("Three quick taps and you never need to come back here.", 14f, pal.sub), lp(bottom = dp(12)))
            addSetupRows(box, close)
            val later = secondaryButton("Later")
            later.pressable { close() }
            box.addView(later, lp(top = dp(12)))
        }
    }

    private fun showA11ySheet() {
        sheet { box, close ->
            box.addView(tv("Turn on counting", 24f, pal.ink, 2, -0.01f))
            box.addView(
                tv("Why? Android has no other way to tell which part of an app you are in. We only count scrolls. Nothing is saved or sent, and this app has no internet permission.", 14f, pal.sub),
                lp(bottom = dp(8))
            )
            box.addView(
                tv("It takes two quick taps. We open each screen for you.", 14f, pal.sub),
                lp(bottom = dp(16))
            )
            box.addView(tv("Step 1 · Allow restricted settings", 16f, pal.ink, 1))
            box.addView(
                tv("On the next screen tap ⋮ (top right), then Allow restricted settings. No ⋮? Skip to step 2.", 13f, pal.sub),
                lp(top = dp(4), bottom = dp(8))
            )
            val b1 = secondaryButton("Open app info")
            b1.pressable {
                close()
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {
                }
            }
            box.addView(b1, lp())
            box.addView(tv("Step 2 · Switch it on", 16f, pal.ink, 1), lp(top = dp(20)))
            box.addView(
                tv("Find View Counter, switch it on, tap Allow.", 13f, pal.sub),
                lp(top = dp(4), bottom = dp(8))
            )
            val b2 = primaryButton("Open Accessibility")
            b2.pressable {
                close()
                try {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                } catch (_: Exception) {
                }
            }
            box.addView(b2, lp())
        }
    }

    // ---------- sheets and chips ----------
    private fun sheet(build: (LinearLayout, () -> Unit) -> Unit) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            background = rounded(pal.card, 32)
        }
        val sv = ScrollView(this)
        sv.addView(box)
        val frame = FrameLayout(this).apply { setPadding(dp(12), dp(12), dp(12), dp(12)) }
        frame.addView(sv)
        build(box, { d.dismiss() })
        d.setContentView(frame)
        d.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(MATCH, WRAP)
            setGravity(Gravity.BOTTOM)
            setDimAmount(0.4f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        d.show()
        frame.translationY = dpf(60)
        frame.alpha = 0f
        frame.animate().translationY(0f).alpha(1f).setStartDelay(0).setDuration(340)
            .setInterpolator(DecelerateInterpolator(2f)).start()
        Sfx.play(this, R.raw.pop, 0.6f)
    }

    private fun chips(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit): HorizontalScrollView {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        hs.addView(row)
        val views = ArrayList<Pair<String, TextView>>()
        fun paint(sel: String) {
            for ((k, v) in views) {
                val on = k == sel
                v.setTextColor(if (on) Color.WHITE else pal.ink)
                v.background = rounded(if (on) pal.accent else pal.track, 20)
            }
        }
        for ((key, name) in options) {
            val t = tv(name, 14f, pal.ink, 1).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(16), 0, dp(16), 0)
            }
            t.pressable {
                onSelect(key)
                paint(key)
            }
            views.add(Pair(key, t))
            row.addView(t, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginEnd = dp(8) })
        }
        paint(selected)
        return hs
    }

    private fun sectionLabel(t: String): TextView = tv(t, 13f, pal.sub, 1).apply {
        letterSpacing = 0.04f
    }

    // ---------- HOME ----------
    private fun buildHome(): View {
        val (sv, col) = newScreen()
        rows.clear()
        customRows.clear()
        platTotals.clear()
        tileNums.clear()

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val greet = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        greet.addView(tv(greeting(), 30f, pal.ink, 2, -0.02f))
        greet.addView(tv(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), 14f, pal.sub))
        header.addView(greet, LinearLayout.LayoutParams(0, WRAP, 1f))
        val av = ImageView(this).apply {
            setImageResource(R.drawable.brain_logo)
            background = circle(pal.pastel(Pastel.PINK))
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        header.addView(av, LinearLayout.LayoutParams(dp(52), dp(52)))
        add(col, header, lp(top = dp(8), bottom = dp(24)))

        val left = setupLeft()
        if (left > 0) {
            val b = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(20), dp(16), dp(16), dp(16))
                background = rounded(pal.pastel(Pastel.LAVENDER), 24)
            }
            val tx = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            tx.addView(tv("Finish setup", 17f, pal.ink, 1))
            tx.addView(tv("$left step${if (left > 1) "s" else ""} left so counting always works", 13f, pal.sub))
            b.addView(tx, LinearLayout.LayoutParams(0, WRAP, 1f))
            val go = tv("Fix", 14f, Color.WHITE, 1).apply {
                gravity = Gravity.CENTER
                minHeight = dp(44)
                setPadding(dp(20), 0, dp(20), 0)
                background = rounded(pal.accent, 22)
            }
            b.addView(go)
            b.pressable { showSetupSheet() }
            add(col, b, lp(bottom = dp(16)))
        }

        // hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(20), dp(16), dp(20))
            background = rounded(pal.pastel(Pastel.PINK), 32)
        }
        val ht = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        ht.addView(tv("Views today", 14f, pal.sub, 1))
        val hn = tv("0", 64f, pal.ink, 2, -0.03f)
        heroNumber = hn
        ht.addView(hn)
        val hs = tv("", 15f, pal.ink)
        heroSub = hs
        ht.addView(hs, lp(top = dp(4)))
        hero.addView(ht, LinearLayout.LayoutParams(0, WRAP, 1f))
        val m = ImageView(this)
        m.setImageResource(Moods.res(4))
        heroMascot = m
        lastMood = 0
        hero.addView(m, LinearLayout.LayoutParams(dp(140), dp(140)))
        mascotAnim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener { m.translationY = -dpf(8) * (it.animatedValue as Float) }
            start()
        }
        add(col, hero, lp(bottom = dp(12)))

        // quick totals
        val tilesRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tileDefs = listOf(
            Triple("reels", "Reels", Pastel.PEACH), Triple("posts", "Posts", Pastel.SKY),
            Triple("stories", "Stories", Pastel.GREEN), Triple("messages", "Chats", Pastel.YELLOW)
        )
        tileDefs.forEachIndexed { i, def ->
            val t = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(14), dp(14), dp(14))
                background = rounded(pal.pastel(def.third), 22)
            }
            t.addView(IconView(this, def.first, pal.ink), LinearLayout.LayoutParams(dp(22), dp(22)))
            val num = tv("0", 22f, pal.ink, 2)
            tileNums[def.first] = num
            t.addView(num, lp(top = dp(8)))
            t.addView(tv(def.second, 12f, pal.sub, 1))
            tilesRow.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) marginStart = dp(8) })
        }
        add(col, tilesRow, lp(bottom = dp(16)))

        // week
        val wk = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(12))
            background = rounded(pal.pastel(Pastel.BLUE), 28)
        }
        wk.addView(tv("This week", 17f, pal.ink, 1))
        val wv = WeekView(this)
        weekView = wv
        wk.addView(wv, lp(top = dp(8), h = dp(140)))
        add(col, wk, lp(bottom = dp(32)))

        // feeds
        val shownPlats = Store.trackedPlatforms(this)
        val customApps = Store.customApps(this)
        if (shownPlats.isEmpty() && customApps.isEmpty()) {
            val c = card(pal.pastel(Pastel.LAVENDER))
            c.gravity = Gravity.CENTER_HORIZONTAL
            val face = ImageView(this)
            face.setImageResource(Moods.res(2))
            c.addView(face, LinearLayout.LayoutParams(dp(120), dp(120)))
            c.addView(
                tv("Add your first app", 20f, pal.ink, 2).apply { gravity = Gravity.CENTER },
                lp(top = dp(8))
            )
            c.addView(
                tv("Pick the apps you scroll the most, like Instagram, Facebook, YouTube or WhatsApp. Only the ones you add are counted.", 14f, pal.sub)
                    .apply { gravity = Gravity.CENTER },
                lp(top = dp(4), bottom = dp(16))
            )
            val b = primaryButton("+  Add apps")
            b.pressable { showAppPicker() }
            c.addView(b, lp())
            add(col, c, lp(bottom = dp(12)))
        } else {
            if (shownPlats.isNotEmpty()) {
                add(col, tv("Your feeds", 22f, pal.ink, 2), lp(bottom = dp(12)))
            }
            for (p in plats) {
                if (!shownPlats.contains(p.key)) continue
                val c = card(pal.pastel(p.pastel))
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                head.addView(BrandView(this, p.key), LinearLayout.LayoutParams(dp(44), dp(44)))
                head.addView(tv(p.name, 17f, pal.ink, 1), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
                val pt = tv("0", 22f, pal.ink, 2)
                platTotals[p.key] = pt
                head.addView(pt)
                c.addView(head)
                for (t in p.types) {
                    val line = LinearLayout(this).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    line.addView(IconView(this, t, p.color), LinearLayout.LayoutParams(dp(20), dp(20)))
                    line.addView(tv(label(t, p.key), 15f, pal.ink), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
                    val cnt = tv("0", 15f, pal.sub, 1)
                    line.addView(cnt)
                    c.addView(line, lp(top = dp(16)))
                    val bar = BarView(this)
                    c.addView(bar, lp(top = dp(8), h = dp(6)))
                    rows["${p.key}|$t"] = Row(cnt, bar)
                }
                add(col, c, lp(bottom = dp(12)))
            }
            if (customApps.isNotEmpty()) {
                add(col, tv("Other apps", 22f, pal.ink, 2), lp(top = dp(20), bottom = dp(12)))
            }
            customApps.forEachIndexed { idx, pkg ->
                val c = card(pal.pastel(Pastel.CYCLE[idx % Pastel.CYCLE.size]))
                val head = LinearLayout(this).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }
                val icon = ImageView(this)
                try {
                    icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
                } catch (_: Exception) {
                    icon.setImageResource(R.drawable.ic_stat_eye)
                }
                head.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
                head.addView(tv(Labels.appLabel(this, pkg), 17f, pal.ink, 1), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
                val total = tv("0", 22f, pal.ink, 2)
                head.addView(total)
                c.addView(head)
                val detail = tv("", 13f, pal.sub)
                c.addView(detail, lp(top = dp(12)))
                val bar = BarView(this)
                c.addView(bar, lp(top = dp(8), h = dp(6)))
                customRows["app:$pkg"] = CustomRow(total, detail, bar)
                add(col, c, lp(bottom = dp(12)))
            }
        }
        return sv
    }

    private fun setHeroNumber(target: Int, animate: Boolean) {
        val hn = heroNumber ?: return
        countAnim?.cancel()
        if (!animate || target == shownTotal) {
            shownTotal = target
            hn.text = target.toString()
            return
        }
        countAnim = ValueAnimator.ofInt(shownTotal, target).apply {
            duration = 600
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                shownTotal = it.animatedValue as Int
                hn.text = shownTotal.toString()
            }
            start()
        }
    }

    private fun updateHome(animate: Boolean) {
        val limit = Store.getLimit(this)
        var total = 0
        var maxRatio = 0f
        for (p in plats) {
            if (!platTotals.containsKey(p.key)) continue
            var pt = 0
            for (t in p.types) {
                val n = Store.today(this, p.key, t)
                pt += n
                val ratio = if (limit > 0) n.toFloat() / limit else 0f
                if (ratio > maxRatio) maxRatio = ratio
                val row = rows["${p.key}|$t"] ?: continue
                row.count.text = "$n / $limit"
                val fill = when {
                    ratio >= 1f -> pal.red
                    ratio >= 0.8f -> pal.orange
                    else -> p.color
                }
                row.bar.setColors(pal.track, fill)
                row.bar.setTarget(ratio, animate)
            }
            platTotals[p.key]?.text = pt.toString()
            total += pt
        }
        for ((type, numView) in tileNums) {
            var s = 0
            for (p in plats) if (platTotals.containsKey(p.key) && p.types.contains(type)) s += Store.today(this, p.key, type)
            numView.text = s.toString()
        }
        for ((key, cr) in customRows) {
            val n = Store.today(this, key, "scrolls")
            total += n
            val ratio = if (limit > 0) n.toFloat() / limit else 0f
            if (ratio > maxRatio) maxRatio = ratio
            val appMins = Store.today(this, key, "time") / 60000
            cr.total.text = n.toString()
            cr.detail.text = "$n scrolls  ·  $appMins min active"
            val fill = when {
                ratio >= 1f -> pal.red
                ratio >= 0.8f -> pal.orange
                else -> pal.accent
            }
            cr.bar.setColors(pal.track, fill)
            cr.bar.setTarget(ratio, animate)
        }

        setHeroNumber(total, animate)
        val mins = Store.todayMinutes(this)
        val skipped = Store.today(this, "gate", "no")
        heroSub?.text = Moods.line(maxRatio) +
            (if (mins > 0) "  ·  about $mins min" else "") +
            (if (skipped > 0) "  ·  $skipped skipped" else "")
        val mood = Moods.forRatio(maxRatio)
        val img = heroMascot
        if (img != null && mood != lastMood) {
            img.setImageResource(Moods.res(mood))
            if (animate && lastMood != 0) {
                // the brain changed state, so a small springy pop is earned here
                img.scaleX = 0.7f
                img.scaleY = 0.7f
                img.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420)
                    .setInterpolator(OvershootInterpolator(2.5f)).start()
            }
            lastMood = mood
        }

        val vals = IntArray(7)
        val labels = Array(7) { "" }
        val df = SimpleDateFormat("EEE", Locale.getDefault())
        for (i in 0 until 7) {
            vals[i] = Store.dayTotal(this, Store.dayAgo(6 - i))
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -(6 - i))
            labels[i] = df.format(cal.time)
        }
        weekView?.setData(vals, labels, 6, pal.accent, pal.track, pal.ink, animate && lastWeekTotal != vals.sum())
        lastWeekTotal = vals.sum()
    }

    private var lastWeekTotal = -1

    // ---------- MESSAGES ----------
    private fun buildMessages(): View {
        val (sv, col) = newScreen()
        add(col, header("Messages", "What your brain says when you hit a number."), lp(top = dp(8), bottom = dp(24)))
        val rules = Store.rules(this)
        if (rules.isEmpty()) {
            val c = card(pal.pastel(Pastel.LAVENDER))
            c.addView(tv("No messages yet", 17f, pal.ink, 1))
            c.addView(tv("Add one, like: after 150 reels, tell me to go outside.", 14f, pal.sub), lp(top = dp(4)))
            add(col, c, lp(bottom = dp(12)))
        }
        rules.forEachIndexed { i, r -> add(col, ruleCard(r, i), lp(bottom = dp(12))) }
        val btn = primaryButton("+  New message")
        btn.pressable { showRuleEditor(null) }
        add(col, btn, lp(top = dp(8)))

        add(col, tv("Home screen widget", 22f, pal.ink, 2), lp(top = dp(32), bottom = dp(4)))
        add(col, tv("A little nudge on your home screen. It changes as you scroll.", 14f, pal.sub), lp(bottom = dp(12)))
        Store.widgetMsgs(this).forEachIndexed { i, wm -> add(col, widgetCard(i, wm), lp(bottom = dp(8))) }
        val addW = secondaryButton("+  Add widget message")
        addW.pressable { showWidgetMsgEditor(null) }
        add(col, addW, lp(top = dp(8), bottom = dp(8)))
        val pin = primaryButton("Add widget to home screen")
        pin.pressable { pinWidget() }
        add(col, pin, lp(top = dp(4)))
        return sv
    }

    private fun widgetCard(i: Int, msg: String): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(16), dp(18), dp(16))
            background = rounded(pal.pastel(Pastel.CYCLE[(i + 1) % Pastel.CYCLE.size]), 22)
        }
        val t = tv(msg, 14f, pal.ink)
        t.maxLines = 3
        c.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        c.pressable { showWidgetMsgEditor(i) }
        return c
    }

    private fun pinWidget() {
        val mgr = AppWidgetManager.getInstance(this)
        if (mgr.isRequestPinAppWidgetSupported) {
            mgr.requestPinAppWidget(ComponentName(this, WidgetProvider::class.java), null, null)
        } else {
            toast("Long-press your home screen, tap Widgets, then View Counter.")
        }
    }

    private fun showWidgetMsgEditor(index: Int?) {
        val list = Store.widgetMsgs(this)
        sheet { box, close ->
            box.addView(tv(if (index == null) "New widget message" else "Edit widget message", 24f, pal.ink, 2, -0.01f), lp(bottom = dp(8)))
            box.addView(
                tv("Use {reels}, {posts}, {stories}, {messages}, {total} and {mins} to show your numbers.", 13f, pal.sub),
                lp(bottom = dp(12))
            )
            val et = EditText(this).apply {
                setText(if (index != null && index < list.size) list[index] else "")
                hint = "ভাই, এবার একটু বই পড়ো..."
                setHintTextColor(pal.sub)
                setTextColor(pal.ink)
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
                maxLines = 4
                gravity = Gravity.TOP
                background = rounded(pal.track, 16)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            box.addView(et, lp(bottom = dp(12)))
            val prev = tv("", 14f, pal.sub)
            fun refreshPreview() {
                val raw = et.text.toString().trim()
                prev.text = "Preview: " + if (raw.isEmpty()) "..." else Store.fillTemplate(this, raw)
            }
            et.addTextChangedListener(object : TextWatcher {
                override fun afterTextChanged(s: Editable?) {
                    refreshPreview()
                }

                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            })
            refreshPreview()
            box.addView(prev, lp(bottom = dp(16)))
            val save = primaryButton("Save")
            save.pressable {
                val text = et.text.toString().trim()
                if (text.isEmpty()) {
                    toast("Write a message first")
                } else {
                    if (index == null || index >= list.size) list.add(text) else list[index] = text
                    Store.saveWidgetMsgs(this, list)
                    WidgetProvider.refresh(this)
                    close()
                    showTab(1, false)
                    Sfx.play(this, R.raw.chime, 0.7f)
                }
            }
            box.addView(save, lp())
            if (index != null && index < list.size && list.size > 1) {
                val del = tv("Delete message", 15f, pal.red, 1).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(52)
                }
                del.pressable {
                    list.removeAt(index)
                    Store.saveWidgetMsgs(this, list)
                    WidgetProvider.refresh(this)
                    close()
                    showTab(1, false)
                }
                box.addView(del, lp(top = dp(4)))
            }
        }
    }

    private fun ruleCard(r: Rule, i: Int): LinearLayout {
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(8), dp(16))
            background = rounded(pal.pastel(Pastel.CYCLE[i % Pastel.CYCLE.size]), 28)
            alpha = if (r.enabled) 1f else 0.55f
        }
        val img = ImageView(this)
        img.setImageResource(Moods.res(r.mood))
        c.addView(img, LinearLayout.LayoutParams(dp(72), dp(72)))
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(tv(Labels.ruleTitle(this, r), 16f, pal.ink, 1))
        val msg = tv(r.message, 14f, pal.sub)
        msg.maxLines = 3
        t.addView(msg, lp(top = dp(2)))
        c.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply {
            marginStart = dp(12)
            marginEnd = dp(4)
        })
        val tg = ToggleView(this, pal.accent, pal.track)
        tg.set(r.enabled, false)
        tg.onChange = { on ->
            Store.saveRules(this, Store.rules(this).map {
                if (it.id == r.id) Rule(it.id, it.platform, it.type, it.threshold, it.message, it.mood, on) else it
            })
            c.animate().alpha(if (on) 1f else 0.55f).setStartDelay(0).setDuration(200).start()
        }
        c.addView(tg)
        c.pressable { showRuleEditor(r) }
        return c
    }

    private fun showRuleEditor(existing: Rule?) {
        var platform = existing?.platform ?: "*"
        var type = existing?.type ?: "reels"
        var mood = existing?.mood ?: 3
        sheet { box, close ->
            box.addView(tv(if (existing == null) "New message" else "Edit message", 24f, pal.ink, 2, -0.01f), lp(bottom = dp(16)))

            box.addView(sectionLabel("WHERE"))
            val platOptions = mutableListOf(Pair("*", "All apps"))
            val seenKeys = HashSet<String>()
            for (pkg in Store.tracked(this)) {
                val key = Store.PACKAGES[pkg] ?: "app:$pkg"
                if (seenKeys.add(key)) platOptions.add(Pair(key, Labels.platform(this, key)))
            }
            box.addView(chips(platOptions, platform) { platform = it }, lp(top = dp(8), bottom = dp(16)))

            box.addView(sectionLabel("WHAT"))
            val typeOptions = listOf(
                Pair("*", "Anything"), Pair("reels", "Reels"), Pair("stories", "Stories"),
                Pair("posts", "Posts"), Pair("messages", "Messages")
            )
            box.addView(chips(typeOptions, type) { type = it }, lp(top = dp(8), bottom = dp(16)))

            box.addView(sectionLabel("AFTER HOW MANY TODAY"))
            val thr = EditText(this).apply {
                setText((existing?.threshold ?: 50).toString())
                inputType = InputType.TYPE_CLASS_NUMBER
                textSize = 22f
                setTextColor(pal.ink)
                gravity = Gravity.CENTER
                background = rounded(pal.track, 16)
                minHeight = dp(56)
                filters = arrayOf(InputFilter.LengthFilter(5))
            }
            box.addView(thr, lp(top = dp(8), bottom = dp(16)))

            box.addView(sectionLabel("YOUR MESSAGE"))
            val msg = EditText(this).apply {
                setText(existing?.message ?: "")
                hint = "What should your brain say?"
                setHintTextColor(pal.sub)
                setTextColor(pal.ink)
                textSize = 16f
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                minLines = 2
                maxLines = 4
                gravity = Gravity.TOP
                background = rounded(pal.track, 16)
                setPadding(dp(16), dp(14), dp(16), dp(14))
            }
            box.addView(msg, lp(top = dp(8), bottom = dp(16)))

            box.addView(sectionLabel("REACTION"))
            val picker = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
            val prow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            picker.addView(prow)
            val faces = ArrayList<ImageView>()
            fun paintFaces() {
                for (i in 0 until faces.size) {
                    faces[i].background = if (i + 1 == mood) GradientDrawable().apply {
                        cornerRadius = dpf(20)
                        setColor(pal.pastel(Pastel.LAVENDER))
                        setStroke(dp(3), pal.accent)
                    } else rounded(pal.track, 20)
                }
            }
            for (i in 1..8) {
                val f = ImageView(this)
                f.setImageResource(Moods.res(i))
                f.setPadding(dp(6), dp(6), dp(6), dp(6))
                f.pressable {
                    mood = i
                    paintFaces()
                }
                faces.add(f)
                prow.addView(f, LinearLayout.LayoutParams(dp(72), dp(72)).apply { marginEnd = dp(8) })
            }
            paintFaces()
            box.addView(picker, lp(top = dp(8), bottom = dp(20)))

            val preview = secondaryButton("Preview")
            preview.pressable {
                val title = "${thr.text.toString().ifBlank { "0" }} ${Labels.type(type)}"
                showPreview(title, msg.text.toString().ifBlank { "Your message shows up here." }, mood)
            }
            box.addView(preview, lp())
            val save = primaryButton("Save")
            save.pressable {
                val n = thr.text.toString().toIntOrNull() ?: 0
                val text = msg.text.toString().trim()
                if (n < 1) {
                    toast("Enter a number above 0")
                } else if (text.isEmpty()) {
                    toast("Write a message first")
                } else {
                    val rule = Rule(existing?.id ?: "r" + System.currentTimeMillis(), platform, type, n, text, mood, existing?.enabled ?: true)
                    val list = Store.rules(this)
                    val at = list.indexOfFirst { it.id == rule.id }
                    if (at >= 0) list[at] = rule else list.add(rule)
                    Store.saveRules(this, list)
                    if (Store.sumToday(this, platform, type) >= n) Store.markFired(this, rule.id)
                    close()
                    showTab(1, false)
                    Sfx.play(this, R.raw.chime, 0.7f)
                }
            }
            box.addView(save, lp(top = dp(12)))
            if (existing != null) {
                val del = tv("Delete message", 15f, pal.red, 1).apply {
                    gravity = Gravity.CENTER
                    minHeight = dp(52)
                }
                del.pressable {
                    Store.saveRules(this, Store.rules(this).filter { it.id != existing.id })
                    close()
                    showTab(1, false)
                }
                box.addView(del, lp(top = dp(4)))
            }
        }
    }

    private fun showPreview(title: String, message: String, mood: Int) {
        val d = Dialog(this)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val card = buildReactionCard(this, title, message, mood) { d.dismiss() }
        val frame = FrameLayout(this).apply { setPadding(dp(16), dp(16), dp(16), dp(16)) }
        frame.addView(card)
        d.setContentView(frame)
        d.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        d.show()
        popIn(card)
        Sfx.play(this, R.raw.alert)
    }

    // ---------- APPS ----------
    private fun buildApps(): View {
        val (sv, col) = newScreen()
        add(col, header("Apps", "Only the apps you add are counted."), lp(top = dp(8), bottom = dp(24)))

        val tracked = Store.tracked(this)
        if (tracked.isEmpty()) {
            val c = card(pal.pastel(Pastel.PEACH))
            c.addView(tv("Nothing here yet", 17f, pal.ink, 1))
            c.addView(tv("Tap the + button below to add Instagram, Facebook, YouTube, WhatsApp or any app that distracts you.", 14f, pal.sub), lp(top = dp(4)))
            add(col, c, lp(bottom = dp(12)))
        }
        tracked.forEachIndexed { idx, pkg ->
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(16), dp(14), dp(12), dp(14))
                background = rounded(pal.pastel(Pastel.CYCLE[idx % Pastel.CYCLE.size]), 24)
            }
            val platKey = Store.PACKAGES[pkg]
            if (platKey != null) {
                c.addView(BrandView(this, platKey), LinearLayout.LayoutParams(dp(44), dp(44)))
            } else {
                val icon = ImageView(this)
                try {
                    icon.setImageDrawable(packageManager.getApplicationIcon(pkg))
                } catch (_: Exception) {
                    icon.setImageResource(R.drawable.ic_stat_eye)
                }
                c.addView(icon, LinearLayout.LayoutParams(dp(44), dp(44)))
            }
            val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            t.addView(tv(Labels.appLabel(this, pkg), 16f, pal.ink, 1))
            t.addView(tv(if (platKey != null) "Counts stories, posts, reels and messages" else "Counts scrolls and active time", 13f, pal.sub))
            c.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(12) })
            val rm = FrameLayout(this).apply { background = circle(pal.track) }
            rm.addView(IconView(this, "close", pal.ink), FrameLayout.LayoutParams(dp(18), dp(18), Gravity.CENTER))
            rm.contentDescription = "Remove"
            rm.pressable {
                Store.setTracked(this, Store.tracked(this) - pkg)
                toast("Removed. Tap + to add it again.")
                showTab(2, false)
            }
            c.addView(rm, LinearLayout.LayoutParams(dp(44), dp(44)))
            add(col, c, lp(bottom = dp(12)))
        }
        val addBtn = primaryButton("+  Add apps")
        addBtn.pressable { showAppPicker() }
        add(col, addBtn, lp(top = dp(8)))
        return sv
    }

    private fun showAppPicker() {
        Thread {
            val pm = packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            val sugg = setOf(
                "com.instagram.android", "com.facebook.katana", "com.facebook.orca", "com.twitter.android",
                "com.whatsapp", "com.google.android.youtube", "com.zhiliaoapp.musically", "com.ss.android.ugc.trill",
                "com.snapchat.android", "com.reddit.frontpage", "com.pinterest", "com.linkedin.android",
                "org.telegram.messenger", "com.discord", "com.facebook.lite", "com.instagram.lite"
            )
            val list = ArrayList<AppInfo>()
            val seen = HashSet<String>()
            for (ri in pm.queryIntentActivities(intent, 0)) {
                val pkg = ri.activityInfo.packageName
                if (pkg == packageName || !seen.add(pkg)) continue
                list.add(AppInfo(pkg, ri.loadLabel(pm).toString(), ri.loadIcon(pm), sugg.contains(pkg)))
            }
            list.sortWith(compareBy<AppInfo>({ !it.suggested }, { it.label.lowercase() }))
            runOnUiThread { buildPicker(list) }
        }.start()
    }

    private fun buildPicker(all: List<AppInfo>) {
        val selected = HashSet<String>(Store.tracked(this))
        val theme = if (night) android.R.style.Theme_Material_NoActionBar else android.R.style.Theme_Material_Light_NoActionBar
        val d = Dialog(this, theme)
        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(pal.bg)
            setPadding(dp(20), dp(24), dp(20), dp(16))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        top.addView(tv("Add apps", 28f, pal.ink, 2, -0.02f), LinearLayout.LayoutParams(0, WRAP, 1f))
        val done = tv("Done", 15f, Color.WHITE, 1).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(24), 0, dp(24), 0)
            background = rounded(pal.accent, 24)
        }
        top.addView(done)
        page.addView(top, lp(bottom = dp(16)))
        val search = EditText(this).apply {
            hint = "Search apps"
            setHintTextColor(pal.sub)
            setTextColor(pal.ink)
            textSize = 16f
            maxLines = 1
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(pal.card, 20)
            setPadding(dp(20), 0, dp(20), 0)
            minHeight = dp(52)
        }
        page.addView(search, lp(bottom = dp(8)))
        val sv = ScrollView(this).apply { isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        sv.addView(list)
        page.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))

        fun appRow(a: AppInfo): View {
            val r = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(64)
                setPadding(dp(4), dp(8), dp(4), dp(8))
            }
            val ic = ImageView(this)
            ic.setImageDrawable(a.icon)
            r.addView(ic, LinearLayout.LayoutParams(dp(44), dp(44)))
            r.addView(tv(a.label, 16f, pal.ink, 1), LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(16) })
            val box = FrameLayout(this)
            val mark = IconView(this, "check", Color.WHITE)
            box.addView(mark, FrameLayout.LayoutParams(dp(16), dp(16), Gravity.CENTER))
            fun paint() {
                val on = selected.contains(a.pkg)
                box.background = circle(if (on) pal.accent else pal.track)
                mark.visibility = if (on) View.VISIBLE else View.INVISIBLE
            }
            paint()
            r.addView(box, LinearLayout.LayoutParams(dp(28), dp(28)))
            r.pressable {
                if (!selected.add(a.pkg)) selected.remove(a.pkg)
                paint()
                Sfx.play(this, R.raw.pop, 0.7f)
            }
            return r
        }

        fun fill(q: String) {
            list.removeAllViews()
            var shownSugg = false
            var shownAll = false
            for (a in all) {
                if (q.isNotEmpty() && !a.label.lowercase().contains(q)) continue
                if (q.isEmpty()) {
                    if (a.suggested && !shownSugg) {
                        list.addView(sectionLabel("SUGGESTED"), lp(top = dp(12), bottom = dp(4)))
                        shownSugg = true
                    } else if (!a.suggested && !shownAll) {
                        list.addView(sectionLabel("ALL APPS"), lp(top = dp(16), bottom = dp(4)))
                        shownAll = true
                    }
                }
                list.addView(appRow(a), lp())
            }
        }
        fill("")
        search.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                fill(s.toString().trim().lowercase())
            }

            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        })
        done.pressable {
            Store.setTracked(this, selected.toList())
            d.dismiss()
            showTab(tab, false)
            Sfx.play(this, R.raw.chime, 0.7f)
        }
        d.setContentView(page)
        d.window?.setLayout(MATCH, MATCH)
        d.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        d.show()
    }

    // ---------- SETTINGS ----------
    private fun toggleRow(title: String, sub: String, key: String, def: Boolean = true): LinearLayout {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(56)
        }
        val t = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        t.addView(tv(title, 16f, pal.ink, 1))
        t.addView(tv(sub, 13f, pal.sub))
        row.addView(t, LinearLayout.LayoutParams(0, WRAP, 1f))
        val tg = ToggleView(this, pal.accent, pal.track)
        tg.set(Store.flag(this, key, def), false)
        tg.onChange = { Store.setFlag(this, key, it) }
        row.addView(tg)
        return row
    }

    private fun exportData() {
        val all = HashMap<String, Any?>(Store.prefs(this).all)
        val sb = StringBuilder("date,app,type,count\n")
        for (k in all.keys.sorted()) {
            val v = all[k]
            if (v !is Int || !k.startsWith("d|")) continue
            val p = k.split("|")
            if (p.size == 4) sb.append("${p[1]},${p[2]},${p[3]},$v\n")
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "View Counter data")
            putExtra(Intent.EXTRA_TEXT, sb.toString())
        }
        startActivity(Intent.createChooser(send, "Export data"))
    }

    private fun buildSettings(): View {
        val (sv, col) = newScreen()
        add(col, header("Settings", "Everything in one place."), lp(top = dp(8), bottom = dp(24)))

        // setup
        val setup = card(pal.card)
        setup.addView(tv("Setup", 17f, pal.ink, 1))
        if (setupLeft() == 0) {
            setup.addView(tv("All set. Counting runs by itself, even after a restart.", 14f, pal.green, 1), lp(top = dp(8)))
        } else {
            addSetupRows(setup, null)
        }
        add(col, setup, lp(bottom = dp(12)))

        // limit
        val lim = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(16), dp(12), dp(16))
            background = rounded(pal.card, 28)
        }
        val lt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lt.addView(tv("Daily limit", 17f, pal.ink, 1))
        lt.addView(tv("Per counter. Drives bars and your brain's mood.", 13f, pal.sub))
        lim.addView(lt, LinearLayout.LayoutParams(0, WRAP, 1f))
        val value = tv(Store.getLimit(this).toString(), 20f, pal.ink, 2).apply {
            gravity = Gravity.CENTER
            minWidth = dp(52)
        }
        fun step(symbol: String, desc: String, delta: Int): TextView {
            val b = tv(symbol, 24f, pal.ink, 1).apply {
                gravity = Gravity.CENTER
                contentDescription = desc
                background = circle(pal.track)
            }
            b.pressable {
                val v = (Store.getLimit(this) + delta).coerceIn(10, 500)
                Store.setLimit(this, v)
                value.text = v.toString()
            }
            return b
        }
        lim.addView(step("−", "Decrease limit", -10), LinearLayout.LayoutParams(dp(48), dp(48)))
        lim.addView(value)
        lim.addView(step("+", "Increase limit", 10), LinearLayout.LayoutParams(dp(48), dp(48)))
        add(col, lim, lp(bottom = dp(12)))

        // preferences
        val prefs = card(pal.card)
        prefs.addView(toggleRow("Sounds", "Soft taps and chimes", "sound"))
        prefs.addView(toggleRow("Haptics", "A tiny buzz when you tap", "haptics"))
        prefs.addView(toggleRow("Live counter", "Brain pill over your apps while you scroll", "pill"))
        prefs.addView(toggleRow("Notifications", "Your messages as notifications", "notify"))
        prefs.addView(toggleRow("Mindful pause", "Breathe for 3 seconds and ask before opening your apps", "gate", false))
        add(col, prefs, lp(bottom = dp(12)))

        val test = secondaryButton("Send a test message")
        test.pressable {
            val title = "150 Reels · Test"
            val msg = Store.rules(this).firstOrNull()?.message ?: "Hey! This is how your messages will look."
            Notifier.post(this, 99, title, msg, 6)
            showPreview(title, msg, 6)
        }
        add(col, test, lp(bottom = dp(12)))

        // privacy
        val priv = card(pal.pastel(Pastel.GREEN))
        priv.addView(tv("Private by design", 17f, pal.ink, 1))
        priv.addView(
            tv("It only looks at screen structure and button labels to tell Reels from Posts. Nothing is saved or sent anywhere, and the app has no internet access at all.", 14f, pal.sub),
            lp(top = dp(4))
        )
        add(col, priv, lp(bottom = dp(12)))

        // data + about
        val reset = tv("Reset all counts", 15f, pal.red, 1).apply {
            gravity = Gravity.CENTER
            minHeight = dp(52)
        }
        reset.pressable {
            val theme = if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert
            else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
            AlertDialog.Builder(this, theme)
                .setTitle("Reset all counts?")
                .setMessage("This clears today's and all-time numbers. Your messages and limit stay.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Reset") { _, _ ->
                    Store.resetAll(this@MainActivity)
                    toast("Counts cleared")
                }
                .show()
        }
        val export = secondaryButton("Export my data")
        export.pressable { exportData() }
        add(col, export, lp(top = dp(8)))
        add(col, reset, lp(top = dp(8)))

        val about = tv("View Counter 2.3.0", 13f, pal.sub).apply { gravity = Gravity.CENTER }
        val diag = tv("", 11f, pal.sub).apply {
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        about.pressable {
            diag.text = "Last detection: " + Store.getDebug(this)
            diag.visibility = if (diag.visibility == View.GONE) View.VISIBLE else View.GONE
        }
        add(col, about, lp(top = dp(8)))
        add(col, diag, lp(top = dp(4)))
        return sv
    }
}
