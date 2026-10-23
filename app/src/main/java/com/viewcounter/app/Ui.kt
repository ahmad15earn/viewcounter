package com.viewcounter.app

import android.animation.AnimatorListenerAdapter
import android.animation.Animator
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.BounceInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

// ---------- palette ----------
object Pastel {
    val PINK = 0xFFFFD6E3.toInt()
    val BLUE = 0xFFD3E4FF.toInt()
    val SKY = 0xFFC9EFF8.toInt()
    val GREEN = 0xFFCDF2D8.toInt()
    val YELLOW = 0xFFFFEBB8.toInt()
    val LAVENDER = 0xFFDDD5FF.toInt()
    val PEACH = 0xFFFFDCC7.toInt()
    val CYCLE = listOf(YELLOW, LAVENDER, PEACH, SKY, GREEN, PINK)
}

class Pal(val night: Boolean) {
    val bg = if (night) 0xFF0F0D17.toInt() else 0xFFF6F4FB.toInt()
    val card = if (night) 0xFF1B1828.toInt() else 0xFFFFFFFF.toInt()
    val ink = if (night) 0xFFF4F2FF.toInt() else 0xFF14121F.toInt()
    val sub = if (night) 0xFFB0ACC6.toInt() else 0xFF5A576E.toInt()
    val track = if (night) 0x33FFFFFF else 0x2214121F
    val accent = 0xFF6C4DFF.toInt()
    val nav = if (night) 0xFF2A2640.toInt() else 0xFF14121F.toInt()
    val green = if (night) 0xFF30D158.toInt() else 0xFF1F7A37.toInt()
    val orange = if (night) 0xFFFF9F0A.toInt() else 0xFFB84A00.toInt()
    val red = if (night) 0xFFFF453A.toInt() else 0xFFC4001A.toInt()

    /** Pastel cards stay pastel in light mode and become deep tinted cards in dark mode. */
    fun pastel(c: Int): Int {
        if (!night) return c
        return Color.rgb(
            (Color.red(c) * 0.30f + 14).toInt(),
            (Color.green(c) * 0.30f + 14).toInt(),
            (Color.blue(c) * 0.30f + 14).toInt()
        )
    }
}

// ---------- tiny helpers ----------
fun Context.dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
fun Context.dpf(v: Int): Float = v * resources.displayMetrics.density

fun Context.rounded(color: Int, radiusDp: Int): GradientDrawable = GradientDrawable().apply {
    setColor(color)
    cornerRadius = dpf(radiusDp)
}

fun Context.circle(color: Int): GradientDrawable = GradientDrawable().apply {
    shape = GradientDrawable.OVAL
    setColor(color)
}

fun Context.tv(t: String, sp: Float, color: Int, weight: Int = 0, spacing: Float = 0f): TextView =
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

/** Press feedback happens on touch-down: tiny squeeze, soft tick, haptic. Release springs back. */
fun View.pressable(sound: Int = R.raw.tap, onClick: () -> Unit) {
    isClickable = true
    setOnTouchListener { v, ev ->
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                v.animate().scaleX(0.96f).scaleY(0.96f).setStartDelay(0).setDuration(90)
                    .setInterpolator(DecelerateInterpolator()).start()
                if (sound != 0) Sfx.play(v.context, sound)
                Sfx.haptic(v)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                v.animate().scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(220)
                    .setInterpolator(OvershootInterpolator(2f)).start()
            }
        }
        false
    }
    setOnClickListener { onClick() }
}

fun popIn(v: View) {
    v.alpha = 0f
    v.scaleX = 0.7f
    v.scaleY = 0.7f
    v.animate().alpha(1f).scaleX(1f).scaleY(1f).setStartDelay(0).setDuration(420)
        .setInterpolator(OvershootInterpolator(1.8f)).start()
}

// ---------- mascot ----------
object Moods {
    private val ids = intArrayOf(
        R.drawable.mood_1, R.drawable.mood_2, R.drawable.mood_3, R.drawable.mood_4,
        R.drawable.mood_5, R.drawable.mood_6, R.drawable.mood_7, R.drawable.mood_8
    )

    fun res(m: Int): Int = ids[(m - 1).coerceIn(0, 7)]

    fun forRatio(r: Float): Int = when {
        r < 0.25f -> 4
        r < 0.5f -> 6
        r < 0.75f -> 3
        r < 1f -> 2
        r < 1.5f -> 1
        r < 2f -> 7
        r < 3f -> 5
        else -> 8
    }

    fun line(r: Float): String = when {
        r < 0.25f -> "Fresh brain. Keep it up!"
        r < 0.5f -> "Doing great today."
        r < 0.75f -> "Getting warmer..."
        r < 1f -> "Careful, almost at your limit."
        r < 1.5f -> "Limit reached. Take a break."
        r < 2f -> "Way past your limit!"
        r < 3f -> "Brain overload."
        else -> "Your brain is fried."
    }
}

object Labels {
    fun appLabel(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (_: Exception) {
        pkg
    }

    fun platform(c: Context, key: String): String = when (key) {
        "*" -> "All apps"
        "instagram" -> "Instagram"
        "facebook" -> "Facebook"
        "twitter" -> "Twitter / X"
        "whatsapp" -> "WhatsApp"
        else -> if (key.startsWith("app:")) appLabel(c, key.removePrefix("app:")) else key
    }

    fun type(t: String): String = when (t) {
        "stories" -> "Stories"
        "posts" -> "Posts"
        "reels" -> "Reels"
        "messages" -> "Messages"
        else -> "scrolls"
    }

    fun ruleTitle(c: Context, r: Rule): String =
        "${r.threshold} ${type(r.type)} · ${platform(c, r.platform)}"
}

/** The reaction card: used for the pop-up over other apps and for the preview inside the app. */
fun buildReactionCard(c: Context, title: String, message: String, mood: Int, onDone: () -> Unit): LinearLayout {
    val card = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        setPadding(c.dp(24), c.dp(20), c.dp(24), c.dp(24))
        background = c.rounded(0xFFFFE3EA.toInt(), 32)
        elevation = c.dpf(12)
    }
    val img = ImageView(c).apply {
        setImageResource(Moods.res(mood))
        rotation = -8f
    }
    card.addView(img, LinearLayout.LayoutParams(c.dp(180), c.dp(180)))
    img.animate().rotation(0f).setStartDelay(0).setDuration(600).setInterpolator(OvershootInterpolator(3f)).start()
    card.addView(
        c.tv(title, 13f, 0xFF5A576E.toInt(), 1).apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
    )
    card.addView(
        c.tv(message, 20f, 0xFF14121F.toInt(), 2).apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = c.dp(8) }
    )
    val btn = c.tv("Got it", 16f, Color.WHITE, 1).apply {
        gravity = Gravity.CENTER
        minHeight = c.dp(52)
        background = c.rounded(0xFF6C4DFF.toInt(), 26)
    }
    btn.pressable(onClick = onDone)
    card.addView(
        btn,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = c.dp(20) }
    )
    return card
}

// ---------- custom views ----------

/** Rounded progress bar. Always animates from the value currently on screen, so it can be interrupted. */
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
            duration = 500
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

/** Line icons drawn with paths so we need no image files. */
class IconView(context: Context, private val kind: String, private var color: Int) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    private var filled = false

    fun setIconColor(c: Int) {
        color = c
        invalidate()
    }

    fun setFilled(f: Boolean) {
        filled = f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val u = width / 24f
        p.color = color
        p.strokeWidth = 2f * u
        p.style = Paint.Style.STROKE
        when (kind) {
            "stories" -> {
                c.drawCircle(12 * u, 12 * u, 8 * u, p)
                p.style = Paint.Style.FILL
                c.drawCircle(12 * u, 12 * u, 3 * u, p)
            }
            "posts", "scrolls" -> {
                rect.set(4 * u, 4 * u, 20 * u, 20 * u)
                c.drawRoundRect(rect, 4 * u, 4 * u, p)
                c.drawLine(8 * u, 9 * u, 16 * u, 9 * u, p)
                c.drawLine(8 * u, 13 * u, 16 * u, 13 * u, p)
                c.drawLine(8 * u, 17 * u, 12 * u, 17 * u, p)
            }
            "reels" -> {
                rect.set(4 * u, 4 * u, 20 * u, 20 * u)
                c.drawRoundRect(rect, 4 * u, 4 * u, p)
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(10 * u, 8 * u)
                path.lineTo(16 * u, 12 * u)
                path.lineTo(10 * u, 16 * u)
                path.close()
                c.drawPath(path, p)
            }
            "messages", "chat" -> {
                if (filled) p.style = Paint.Style.FILL
                rect.set(3 * u, 4 * u, 21 * u, 16 * u)
                c.drawRoundRect(rect, 5 * u, 5 * u, p)
                path.reset()
                path.moveTo(8 * u, 16 * u)
                path.lineTo(8 * u, 20.5f * u)
                path.lineTo(13 * u, 16 * u)
                if (filled) path.close()
                c.drawPath(path, p)
            }
            "home" -> if (filled) {
                p.style = Paint.Style.FILL
                path.reset()
                path.moveTo(12 * u, 3.5f * u)
                path.lineTo(21 * u, 11 * u)
                path.lineTo(18.5f * u, 11 * u)
                path.lineTo(18.5f * u, 20.5f * u)
                path.lineTo(5.5f * u, 20.5f * u)
                path.lineTo(5.5f * u, 11 * u)
                path.lineTo(3 * u, 11 * u)
                path.close()
                c.drawPath(path, p)
            } else {
                path.reset()
                path.moveTo(3.5f * u, 11 * u)
                path.lineTo(12 * u, 4 * u)
                path.lineTo(20.5f * u, 11 * u)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(6 * u, 9.5f * u)
                path.lineTo(6 * u, 20 * u)
                path.lineTo(18 * u, 20 * u)
                path.lineTo(18 * u, 9.5f * u)
                c.drawPath(path, p)
            }
            "plus" -> {
                p.strokeWidth = 2.6f * u
                c.drawLine(12 * u, 5 * u, 12 * u, 19 * u, p)
                c.drawLine(5 * u, 12 * u, 19 * u, 12 * u, p)
            }
            "grid" -> {
                if (filled) p.style = Paint.Style.FILL
                rect.set(4 * u, 4 * u, 10 * u, 10 * u)
                c.drawRoundRect(rect, 2 * u, 2 * u, p)
                rect.set(14 * u, 4 * u, 20 * u, 10 * u)
                c.drawRoundRect(rect, 2 * u, 2 * u, p)
                rect.set(4 * u, 14 * u, 10 * u, 20 * u)
                c.drawRoundRect(rect, 2 * u, 2 * u, p)
                rect.set(14 * u, 14 * u, 20 * u, 20 * u)
                c.drawRoundRect(rect, 2 * u, 2 * u, p)
            }
            "gear" -> {
                if (filled) {
                    p.style = Paint.Style.FILL
                    c.drawCircle(12 * u, 12 * u, 7.4f * u, p)
                    p.style = Paint.Style.STROKE
                } else {
                    c.drawCircle(12 * u, 12 * u, 3.2f * u, p)
                    c.drawCircle(12 * u, 12 * u, 7f * u, p)
                }
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    val x1 = (12 + 8.6 * cos(a)).toFloat() * u
                    val y1 = (12 + 8.6 * sin(a)).toFloat() * u
                    val x2 = (12 + 10.8 * cos(a)).toFloat() * u
                    val y2 = (12 + 10.8 * sin(a)).toFloat() * u
                    c.drawLine(x1, y1, x2, y2, p)
                }
            }
            "close" -> {
                c.drawLine(6 * u, 6 * u, 18 * u, 18 * u, p)
                c.drawLine(18 * u, 6 * u, 6 * u, 18 * u, p)
            }
            "check" -> {
                p.strokeWidth = 2.8f * u
                path.reset()
                path.moveTo(5 * u, 12.5f * u)
                path.lineTo(10 * u, 17 * u)
                path.lineTo(19 * u, 7 * u)
                c.drawPath(path, p)
            }
            "clock" -> {
                c.drawCircle(12 * u, 12 * u, 8.5f * u, p)
                path.reset()
                path.moveTo(12 * u, 7 * u)
                path.lineTo(12 * u, 12 * u)
                path.lineTo(15.5f * u, 14 * u)
                c.drawPath(path, p)
            }
        }
    }
}

/** Seven soft bars for the last seven days. */
class WeekView(context: Context) : View(context) {
    private var values = IntArray(7)
    private var labels = Array(7) { "" }
    private var todayIdx = 6
    private var barColor = 0
    private var dimColor = 0
    private var textColor = 0
    private var grow = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    fun setData(v: IntArray, l: Array<String>, today: Int, bar: Int, dim: Int, text: Int, animate: Boolean) {
        values = v
        labels = l
        todayIdx = today
        barColor = bar
        dimColor = dim
        textColor = text
        anim?.cancel()
        if (!animate) {
            grow = 1f
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 700
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                grow = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    fun reset() {
        anim?.cancel()
        grow = 0f
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val hh = height.toFloat()
        val labelH = context.dpf(24)
        val topPad = context.dpf(22)
        val slot = w / 7f
        val barW = context.dpf(20)
        var maxV = 1
        for (x in values) if (x > maxV) maxV = x
        val base = hh - labelH
        val minH = context.dpf(8)
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = context.dpf(12)
        for (i in 0 until 7) {
            val cx = slot * i + slot / 2f
            val frac = values[i].toFloat() / maxV
            val bh = (minH + (base - topPad - minH) * frac) * grow
            paint.style = Paint.Style.FILL
            paint.color = if (i == todayIdx) barColor else dimColor
            rect.set(cx - barW / 2, base - bh, cx + barW / 2, base)
            c.drawRoundRect(rect, barW / 2, barW / 2, paint)
            paint.color = textColor
            paint.alpha = if (i == todayIdx) 255 else 150
            c.drawText(labels[i], cx, hh - context.dpf(4), paint)
            if (i == todayIdx) {
                paint.alpha = 255
                c.drawText(values[i].toString(), cx, base - bh - context.dpf(6), paint)
            }
        }
    }
}

/** Our own switch: springy knob, soft pop sound. */
class ToggleView(context: Context, private val onColor: Int, private val offColor: Int) : View(context) {
    var isOn = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private var pos = 0f
    private var anim: ValueAnimator? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val eval = ArgbEvaluator()

    init {
        isClickable = true
        setOnClickListener {
            set(!isOn, true)
            Sfx.play(context, R.raw.pop)
            Sfx.haptic(this)
            onChange?.invoke(isOn)
        }
    }

    fun set(v: Boolean, animate: Boolean) {
        isOn = v
        val target = if (v) 1f else 0f
        anim?.cancel()
        if (!animate) {
            pos = target
            invalidate()
            return
        }
        anim = ValueAnimator.ofFloat(pos, target).apply {
            duration = 240
            interpolator = DecelerateInterpolator(2f)
            addUpdateListener {
                pos = it.animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(context.dp(60), context.dp(44))
    }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val tw = context.dpf(48)
        val th = context.dpf(28)
        val left = (w - tw) / 2f
        val top = (h - th) / 2f
        paint.color = eval.evaluate(pos, offColor, onColor) as Int
        rect.set(left, top, left + tw, top + th)
        c.drawRoundRect(rect, th / 2, th / 2, paint)
        val kr = context.dpf(11)
        val kx = left + th / 2 + (tw - th) * pos
        paint.color = Color.WHITE
        c.drawCircle(kx, top + th / 2, kr, paint)
    }
}

/** Launch animation: the brain drops in and bounces, then our own loading bar fills up. */
class SplashView(context: Context, private val bg: Int, private val ink: Int, private val sub: Int) : View(context) {
    private var progress = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()
    private val bmp = BitmapFactory.decodeResource(resources, R.drawable.brain_logo)
    private val bounce = BounceInterpolator()

    fun play(onEnd: () -> Unit) {
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 2400
            interpolator = LinearInterpolator()
            addUpdateListener {
                progress = it.animatedValue as Float
                invalidate()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    onEnd()
                }
            })
            start()
        }
    }

    override fun onDraw(canvas: Canvas) {
        val s = context.dpf(1)
        val t = progress
        canvas.drawColor(bg)
        val cx = width / 2f
        val cy = height / 2f - 70 * s

        val fall = bounce.getInterpolation(min(1f, t / 0.3f))
        var yOff = (1f - fall) * (-340 * s)
        if (t > 0.3f) yOff += sin((t - 0.3f) * 6.2832f * 2.5f) * 5 * s
        val breathe = 1f + 0.025f * sin(t * 6.2832f * 3f)
        val bw = 210 * s
        val bh = bw * bmp.height / bmp.width

        // soft shadow that shrinks while the brain is high
        val sh = 1f - min(1f, abs(yOff) / (340 * s)) * 0.7f
        paint.style = Paint.Style.FILL
        paint.color = 0x30D4688A
        rect.set(cx - 80 * s * sh, cy + bh / 2 + 2 * s, cx + 80 * s * sh, cy + bh / 2 + 14 * s)
        canvas.drawOval(rect, paint)

        canvas.save()
        canvas.translate(0f, yOff)
        canvas.scale(breathe, 2f - breathe, cx, cy + bh / 2)
        rect.set(cx - bw / 2, cy - bh / 2, cx + bw / 2, cy + bh / 2)
        canvas.drawBitmap(bmp, null, rect, paint)
        canvas.restore()

        // title
        val a = ((t - 0.25f) / 0.2f).coerceIn(0f, 1f)
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
        paint.textSize = 28 * s
        paint.letterSpacing = -0.02f
        paint.color = ink
        paint.alpha = (a * 255).toInt()
        canvas.drawText("View Counter", cx, cy + bh / 2 + 62 * s, paint)
        paint.typeface = Typeface.DEFAULT
        paint.textSize = 14 * s
        paint.letterSpacing = 0f
        paint.color = sub
        paint.alpha = (a * 255).toInt()
        canvas.drawText("Know what you scroll", cx, cy + bh / 2 + 88 * s, paint)

        // our own loading bar
        val barW = 220 * s
        val barH = 12 * s
        val bx = cx - barW / 2
        val by = cy + bh / 2 + 118 * s
        paint.color = 0x33FF7A93
        rect.set(bx, by, bx + barW, by + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, paint)
        val e = ((t - 0.1f) / 0.88f).coerceIn(0f, 1f)
        val p = e * e * (3 - 2 * e)
        val fw = max(barH, barW * p)
        paint.color = 0xFFFF7A93.toInt()
        rect.set(bx, by, bx + fw, by + barH)
        canvas.drawRoundRect(rect, barH / 2, barH / 2, paint)
        canvas.save()
        clip.reset()
        clip.addRoundRect(rect, barH / 2, barH / 2, Path.Direction.CW)
        canvas.clipPath(clip)
        val sx = bx + ((t * 3.5f) % 1f) * (fw + 60 * s) - 60 * s
        paint.color = 0x66FFFFFF
        canvas.drawRect(sx, by, sx + 40 * s, by + barH, paint)
        canvas.restore()

        val msg = when {
            t < 0.4f -> "Waking up your brain..."
            t < 0.75f -> "Counting your scrolls..."
            else -> "Ready!"
        }
        paint.textSize = 13 * s
        paint.color = sub
        paint.alpha = 255
        canvas.drawText(msg, cx, by + barH + 28 * s, paint)
    }
}


/** Little brand-style badges for each platform, drawn with paths. */
class BrandView(context: Context, private val key: String) : View(context) {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val rect = RectF()
    private val path = Path()

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val u = w / 24f
        p.shader = null
        p.style = Paint.Style.FILL
        when (key) {
            "instagram" -> {
                p.shader = LinearGradient(
                    0f, w, w, 0f,
                    intArrayOf(0xFFFEDA77.toInt(), 0xFFF58529.toInt(), 0xFFDD2A7B.toInt(), 0xFF8134AF.toInt()),
                    null, Shader.TileMode.CLAMP
                )
                rect.set(0f, 0f, w, w)
                c.drawRoundRect(rect, 7 * u, 7 * u, p)
                p.shader = null
                p.color = Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.8f * u
                rect.set(5.5f * u, 5.5f * u, 18.5f * u, 18.5f * u)
                c.drawRoundRect(rect, 4.5f * u, 4.5f * u, p)
                c.drawCircle(12 * u, 12 * u, 3.4f * u, p)
                p.style = Paint.Style.FILL
                c.drawCircle(16.2f * u, 7.8f * u, 1.1f * u, p)
            }
            "facebook" -> {
                p.color = 0xFF1877F2.toInt()
                c.drawCircle(w / 2, w / 2, w / 2, p)
                p.color = Color.WHITE
                p.typeface = Typeface.create("sans-serif", Typeface.BOLD)
                p.textSize = 19 * u
                p.textAlign = Paint.Align.CENTER
                c.drawText("f", w / 2 + 1.2f * u, w / 2 + 6.4f * u, p)
            }
            "twitter" -> {
                p.color = 0xFF14121F.toInt()
                c.drawCircle(w / 2, w / 2, w / 2, p)
                p.color = Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = 2.2f * u
                c.drawLine(8 * u, 8 * u, 16 * u, 16 * u, p)
                c.drawLine(16 * u, 8 * u, 8 * u, 16 * u, p)
            }
            else -> {
                p.color = 0xFF25D366.toInt()
                c.drawCircle(w / 2, w / 2, w / 2, p)
                p.color = Color.WHITE
                p.style = Paint.Style.STROKE
                p.strokeWidth = 1.8f * u
                c.drawCircle(12 * u, 11.6f * u, 6.2f * u, p)
                path.reset()
                path.moveTo(7.4f * u, 15.6f * u)
                path.lineTo(6.2f * u, 18.8f * u)
                path.lineTo(9.6f * u, 17.6f * u)
                c.drawPath(path, p)
                path.reset()
                path.moveTo(9.7f * u, 9.2f * u)
                path.quadTo(9.5f * u, 11.8f * u, 12.3f * u, 14.2f * u)
                path.quadTo(14.4f * u, 14.8f * u, 14.8f * u, 13.6f * u)
                c.drawPath(path, p)
            }
        }
    }
}

/**
 * Mindful pause: three calm seconds, then a question. "No" is the big button on top, "Yes" is small below.
 */
fun buildGateView(c: Context, appName: String, onNo: () -> Unit, onYes: () -> Unit): FrameLayout {
    val root = FrameLayout(c)
    root.setBackgroundColor(0xF5FFFBE3.toInt())
    val col = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(c.dp(32), 0, c.dp(32), 0)
    }
    root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))

    val face = ImageView(c)
    face.setImageResource(Moods.res(6))
    col.addView(face, LinearLayout.LayoutParams(c.dp(170), c.dp(170)))

    val title = c.tv("একটু দীর্ঘ নিঃশ্বাস নিন", 26f, 0xFF14121F.toInt(), 2, -0.01f).apply { gravity = Gravity.CENTER }
    col.addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = c.dp(20) })
    val count = c.tv("৩", 64f, 0xFF6C4DFF.toInt(), 2).apply { gravity = Gravity.CENTER }
    col.addView(count, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

    val question = c.tv("আপনি কি এখনো\n$appName\nচালু করতে চান?", 24f, 0xFF14121F.toInt(), 2).apply {
        gravity = Gravity.CENTER
        visibility = View.GONE
    }
    col.addView(question, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = c.dp(20) })
    val no = c.tv("না, এখন খুলব না", 18f, Color.WHITE, 1).apply {
        gravity = Gravity.CENTER
        minHeight = c.dp(60)
        background = c.rounded(0xFF6C4DFF.toInt(), 30)
        visibility = View.GONE
    }
    no.pressable(onClick = onNo)
    col.addView(no, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = c.dp(28) })
    val yes = c.tv("হ্যাঁ, খুলব", 15f, 0xFF5A576E.toInt(), 1).apply {
        gravity = Gravity.CENTER
        minHeight = c.dp(48)
        visibility = View.GONE
    }
    yes.pressable(onClick = onYes)
    col.addView(yes, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        .apply { topMargin = c.dp(12) })

    // the face breathes in and out while we count down
    val breath = ValueAnimator.ofFloat(1f, 1.22f).apply {
        duration = 1500
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = android.view.animation.AccelerateDecelerateInterpolator()
        addUpdateListener {
            val s = it.animatedValue as Float
            face.scaleX = s
            face.scaleY = s
        }
    }
    root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) {
            breath.start()
        }

        override fun onViewDetachedFromWindow(v: View) {
            breath.cancel()
        }
    })
    root.postDelayed({ count.text = "২" }, 1000)
    root.postDelayed({ count.text = "১" }, 2000)
    root.postDelayed({
        title.visibility = View.GONE
        count.visibility = View.GONE
        for (v in listOf<View>(question, no, yes)) {
            v.visibility = View.VISIBLE
            v.alpha = 0f
            v.animate().alpha(1f).setStartDelay(0).setDuration(350).start()
        }
    }, 3000)
    return root
}


fun fmtDuration(ms: Long): String {
    val s = ms / 1000
    val h = s / 3600
    val m = (s % 3600) / 60
    val sec = s % 60
    return if (h > 0) String.format(Locale.US, "%dh %02dm %02ds", h, m, sec)
    else if (m > 0) String.format(Locale.US, "%dm %02ds", m, sec)
    else "${sec}s"
}

/** Full-screen block: a focus block or the daily hard limit. "Go back" is the big button. */
fun buildBlockView(
    c: Context, title: String, sub: String, canSpend: Boolean, cost: Int, points: Int,
    onHome: () -> Unit, onSpend: () -> Unit
): FrameLayout {
    val root = FrameLayout(c)
    root.setBackgroundColor(0xF8FFE3E8.toInt())
    val col = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setPadding(c.dp(32), 0, c.dp(32), 0)
    }
    root.addView(col, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    val face = ImageView(c)
    face.setImageResource(Moods.res(5))
    col.addView(face, LinearLayout.LayoutParams(c.dp(170), c.dp(170)))
    popIn(face)
    col.addView(
        c.tv(title, 28f, 0xFF14121F.toInt(), 2, -0.01f).apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(16) }
    )
    col.addView(
        c.tv(sub, 16f, 0xFF5A576E.toInt()).apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(6) }
    )
    col.addView(
        c.tv("তোমার পয়েন্ট: $points", 15f, 0xFF14121F.toInt(), 1).apply { gravity = Gravity.CENTER },
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(12) }
    )
    val home = c.tv("ফিরে যাই", 18f, Color.WHITE, 1).apply {
        gravity = Gravity.CENTER
        minHeight = c.dp(60)
        background = c.rounded(0xFF6C4DFF.toInt(), 30)
    }
    home.pressable(onClick = onHome)
    col.addView(
        home,
        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(28) }
    )
    if (canSpend) {
        val ok = points >= cost
        val spend = c.tv(
            if (ok) "$cost পয়েন্ট দিয়ে ১০ মিনিট" else "১০ মিনিটের জন্য $cost পয়েন্ট লাগবে",
            15f, if (ok) 0xFF14121F.toInt() else 0xFF8A879C.toInt(), 1
        ).apply {
            gravity = Gravity.CENTER
            minHeight = c.dp(48)
        }
        if (ok) spend.pressable(onClick = onSpend)
        col.addView(
            spend,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = c.dp(12) }
        )
    }
    return root
}
