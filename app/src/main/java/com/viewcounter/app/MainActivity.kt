package com.viewcounter.app

import android.animation.ValueAnimator
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max

private class Pal(
    val bg: Int, val card: Int, val text: Int, val sub: Int, val track: Int,
    val green: Int, val orange: Int, val red: Int
)

private class Plat(val key: String, val name: String, val badge: String, val color: Int, val types: List<String>)

/** Thin rounded progress bar. Always animates from the value currently on screen, so it can be interrupted. */
class BarView(context: Context) : View(context) {
    private var trackColor = 0
    private var fillColor = 0
    private var shown = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setColors(track: Int, fill: Int) {
        trackColor = track
        fillColor = fill
        invalidate()
    }

    fun setTarget(t: Float, animate: Boolean) {
        val target = t.coerceIn(0f, 1f)
        anim?.cancel()
        if (!animate || abs(target - shown) < 0.001f) {
            shown = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(shown, target).apply {
            duration = 450
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                shown = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val w = width.toFloat()
        val r = h / 2f
        paint.color = trackColor
        rect.set(0f, 0f, w, h)
        canvas.drawRoundRect(rect, r, r, paint)
        if (shown > 0f) {
            paint.color = fillColor
            rect.set(0f, 0f, max(h, w * shown), h)
            canvas.drawRoundRect(rect, r, r, paint)
        }
    }
}

class MainActivity : Activity() {

    private class Row(val count: TextView, val bar: BarView)

    private val rows = HashMap<String, Row>()
    private val platTotals = HashMap<String, TextView>()
    private lateinit var pal: Pal
    private var night = false
    private lateinit var statusPill: TextView
    private lateinit var heroNumber: TextView
    private lateinit var heroAvatar: TextView
    private lateinit var heroSub: TextView
    private lateinit var limitValue: TextView
    private lateinit var diag: TextView
    private var firstRender = true

    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render(!firstRender)
            firstRender = false
            h.postDelayed(this, 1500)
        }
    }

    private val plats = listOf(
        Plat("instagram", "Instagram", "IG", 0xFFE1306C.toInt(), listOf("stories", "posts", "reels", "messages")),
        Plat("facebook", "Facebook", "FB", 0xFF1877F2.toInt(), listOf("stories", "posts", "reels", "messages")),
        Plat("twitter", "Twitter / X", "X", 0xFF1D9BF0.toInt(), listOf("posts", "reels", "messages")),
        Plat("whatsapp", "WhatsApp", "WA", 0xFF128C7E.toInt(), listOf("stories", "messages"))
    )

    // ---------- helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun dpf(v: Int) = v * resources.displayMetrics.density

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dpf(radiusDp)
    }

    private fun tint(c: Int) = (c and 0x00FFFFFF) or 0x26000000

    private fun tv(t: String, sp: Float, color: Int, weight: Int = 0, spacing: Float = 0f): TextView =
        TextView(this).apply {
            text = t
            textSize = sp
            setTextColor(color)
            letterSpacing = spacing
            typeface = when (weight) {
                2 -> Typeface.create("sans-serif", Typeface.BOLD)
                1 -> Typeface.create("sans-serif-medium", Typeface.NORMAL)
                else -> Typeface.DEFAULT
            }
        }

    private fun lp(top: Int = 0, bottom: Int = 0, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h).apply {
            topMargin = top
            bottomMargin = bottom
        }

    /** Feedback on press-down, instant, never waits for release. */
    private fun pressable(v: View, onClick: () -> Unit) {
        v.isClickable = true
        v.setOnTouchListener { view, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN ->
                    view.animate().scaleX(0.97f).scaleY(0.97f).setDuration(100).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                    view.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
            }
            false
        }
        v.setOnClickListener { onClick() }
    }

    private fun label(type: String, platform: String) =
        if (platform == "whatsapp" && type == "stories") "Status"
        else type.replaceFirstChar { it.uppercase() }

    private fun stepButton(symbol: String, desc: String, onClick: () -> Unit): TextView {
        val b = tv(symbol, 24f, pal.text, 1).apply {
            gravity = Gravity.CENTER
            contentDescription = desc
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(pal.track)
            }
            layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        }
        pressable(b, onClick)
        return b
    }

    // ---------- UI ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        pal = if (night) {
            Pal(0xFF000000.toInt(), 0xFF1C1C1E.toInt(), 0xFFFFFFFF.toInt(), 0xFF98989F.toInt(),
                0xFF3A3A3C.toInt(), 0xFF30D158.toInt(), 0xFFFF9F0A.toInt(), 0xFFFF453A.toInt())
        } else {
            Pal(0xFFF2F2F7.toInt(), 0xFFFFFFFF.toInt(), 0xFF1C1C1E.toInt(), 0xFF6C6C70.toInt(),
                0xFFE5E5EA.toInt(), 0xFF248A3D.toInt(), 0xFFC93400.toInt(), 0xFFD70015.toInt())
        }
        window.statusBarColor = pal.bg
        window.navigationBarColor = pal.bg
        if (!night) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        }

        val scroll = ScrollView(this).apply {
            setBackgroundColor(pal.bg)
            isVerticalScrollBarEnabled = false
        }
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(12), dp(20), dp(40))
        }
        scroll.addView(col)

        // Header
        col.addView(tv("Today", 34f, pal.text, 2, -0.02f), lp(top = dp(16)))
        col.addView(
            tv(SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()).format(Date()), 15f, pal.sub),
            lp(bottom = dp(16))
        )

        // Status pill
        statusPill = tv("", 15f, pal.green, 1).apply {
            gravity = Gravity.CENTER_VERTICAL
            minHeight = dp(48)
            setPadding(dp(16), 0, dp(16), 0)
        }
        pressable(statusPill) { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        col.addView(statusPill, lp(bottom = dp(12)))

        // Hero
        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(20))
            background = rounded(pal.card, 24)
        }
        val heroText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        heroNumber = tv("0", 56f, pal.text, 2, -0.03f)
        heroSub = tv("", 15f, pal.sub)
        heroText.addView(heroNumber)
        heroText.addView(tv("views today", 15f, pal.sub))
        heroText.addView(heroSub, lp(top = dp(8)))
        hero.addView(heroText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        heroAvatar = tv("😌", 56f, pal.text)
        hero.addView(heroAvatar)
        col.addView(hero, lp(bottom = dp(12)))

        // Platform cards
        for (p in plats) {
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(16), dp(16), dp(16), dp(20))
                background = rounded(pal.card, 20)
            }
            val header = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val badge = tv(p.badge, 13f, Color.WHITE, 2).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(p.color)
                }
            }
            header.addView(badge, LinearLayout.LayoutParams(dp(40), dp(40)))
            val name = tv(p.name, 17f, pal.text, 1)
            header.addView(name, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginStart = dp(12)
            })
            val pt = tv("0", 22f, pal.text, 2)
            platTotals[p.key] = pt
            header.addView(pt)
            card.addView(header)

            for (t in p.types) {
                val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
                line.addView(
                    tv(label(t, p.key), 15f, pal.text),
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                )
                val c = tv("0 / 50", 15f, pal.sub)
                line.addView(c)
                card.addView(line, lp(top = dp(16)))
                val bar = BarView(this)
                card.addView(bar, lp(top = dp(8), h = dp(6)))
                rows["${p.key}|$t"] = Row(c, bar)
            }
            col.addView(card, lp(bottom = dp(12)))
        }

        // Limit stepper
        val limitCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            background = rounded(pal.card, 20)
        }
        val limitText = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        limitText.addView(tv("Daily limit", 17f, pal.text, 1))
        limitText.addView(tv("Per counter. Your avatar appears when you reach it.", 13f, pal.sub))
        limitCard.addView(limitText, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        limitCard.addView(stepButton("−", "Decrease limit") { changeLimit(-10) })
        limitValue = tv("50", 20f, pal.text, 2).apply {
            gravity = Gravity.CENTER
            minWidth = dp(52)
        }
        limitCard.addView(limitValue)
        limitCard.addView(stepButton("+", "Increase limit") { changeLimit(10) })
        col.addView(limitCard, lp(bottom = dp(8)))

        // Reset
        val reset = tv("Reset all counts", 15f, pal.red, 1).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
        }
        pressable(reset) { confirmReset() }
        col.addView(reset, lp(top = dp(8)))

        diag = tv("", 11f, pal.sub)
        col.addView(diag, lp(top = dp(12)))

        setContentView(scroll)
    }

    private fun changeLimit(delta: Int) {
        val v = (Store.getLimit(this) + delta).coerceIn(10, 500)
        Store.setLimit(this, v)
        render(true)
    }

    private fun confirmReset() {
        val theme = if (night) android.R.style.Theme_DeviceDefault_Dialog_Alert
        else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        AlertDialog.Builder(this, theme)
            .setTitle("Reset all counts?")
            .setMessage("This clears today's and all-time numbers. Your limit stays.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Reset") { _, _ ->
                Store.resetAll(this@MainActivity)
                render(true)
            }
            .show()
    }

    override fun onResume() {
        super.onResume()
        firstRender = true
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    private fun render(animate: Boolean) {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
        val statusColor = if (enabled) pal.green else pal.orange
        statusPill.text = if (enabled) "●  Counting is on" else "●  Counting is off. Tap to enable"
        statusPill.setTextColor(statusColor)
        statusPill.background = rounded(tint(statusColor), 14)

        val limit = Store.getLimit(this)
        var total = 0
        var maxRatio = 0f
        for (p in plats) {
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

        heroNumber.text = total.toString()
        heroAvatar.text = when {
            maxRatio >= 2f -> "🤯"
            maxRatio >= 1f -> "😴"
            maxRatio >= 0.8f -> "😬"
            maxRatio >= 0.5f -> "🙂"
            else -> "😌"
        }
        heroSub.text = when {
            maxRatio >= 1f -> "Limit reached. Time for a break."
            maxRatio >= 0.8f -> "Close to your limit."
            else -> "You're within your limits."
        }
        limitValue.text = limit.toString()
        diag.text = "Diagnostics: " + Store.getDebug(this)
    }
}
