package com.viewcounter.app

import android.app.Activity
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var body: LinearLayout
    private lateinit var limitEdit: EditText
    private lateinit var debug: TextView
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            render()
            h.postDelayed(this, 2000)
        }
    }

    private val layoutMap = linkedMapOf(
        "instagram" to Pair("📸 Instagram", listOf("stories", "posts", "reels", "messages")),
        "facebook" to Pair("📘 Facebook", listOf("stories", "posts", "reels", "messages")),
        "twitter" to Pair("🐦 Twitter / X", listOf("posts", "reels", "messages")),
        "whatsapp" to Pair("💬 WhatsApp", listOf("stories", "messages"))
    )

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(32), dp(16), dp(32))
        }
        scroll.addView(col)

        col.addView(TextView(this).apply {
            text = "View Counter"
            textSize = 28f
        })
        status = TextView(this).apply { textSize = 15f; setPadding(0, dp(8), 0, dp(8)) }
        col.addView(status)

        col.addView(Button(this).apply {
            text = "Open Accessibility Settings"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })

        val limitRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        limitEdit = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "Daily limit per counter"
            setText(Store.getLimit(this@MainActivity).toString())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        limitRow.addView(limitEdit)
        limitRow.addView(Button(this).apply {
            text = "Save limit"
            setOnClickListener {
                val v = limitEdit.text.toString().toIntOrNull() ?: 50
                Store.setLimit(this@MainActivity, v)
            }
        })
        col.addView(limitRow)

        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(body)

        col.addView(Button(this).apply {
            text = "Reset all counts"
            setOnClickListener { Store.resetAll(this@MainActivity); render() }
        })
        debug = TextView(this).apply { textSize = 11f; setPadding(0, dp(16), 0, 0) }
        col.addView(debug)

        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        h.post(tick)
    }

    override fun onPause() {
        super.onPause()
        h.removeCallbacks(tick)
    }

    private fun label(type: String, platform: String) = when {
        platform == "whatsapp" && type == "stories" -> "Status"
        else -> type.replaceFirstChar { it.uppercase() }
    }

    private fun render() {
        val enabled = Settings.Secure.getString(
            contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )?.contains(packageName) == true
        status.text = if (enabled) "✅ Counting is ON" else "⚠️ Counting is OFF. Enable View Counter in Accessibility settings."

        body.removeAllViews()
        for ((platform, info) in layoutMap) {
            val (title, types) = info
            val sb = StringBuilder(title).append("\n")
            for (t in types) {
                sb.append("\n${label(t, platform)}:  today ${Store.today(this, platform, t)}  ·  total ${Store.total(this, platform, t)}")
            }
            val card = TextView(this).apply {
                text = sb.toString()
                textSize = 16f
                setPadding(dp(16), dp(16), dp(16), dp(16))
                background = GradientDrawable().apply {
                    setColor(0xFFEFEFF4.toInt())
                    cornerRadius = dp(16).toFloat()
                }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(12) }
            body.addView(card, lp)
        }
        debug.text = "Last detection: " + Store.getDebug(this)
    }
}
