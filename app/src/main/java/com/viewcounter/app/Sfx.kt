package com.viewcounter.app

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import android.view.HapticFeedbackConstants
import android.view.View

/** Tiny sound + haptic helper. Everything respects the switches in Settings. */
object Sfx {
    private var pool: SoundPool? = null
    private val ids = HashMap<Int, Int>()

    private fun ensure(c: Context): SoundPool {
        val existing = pool
        if (existing != null) return existing
        val sp = SoundPool.Builder()
            .setMaxStreams(4)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .build()
        val app = c.applicationContext
        ids[R.raw.tap] = sp.load(app, R.raw.tap, 1)
        ids[R.raw.pop] = sp.load(app, R.raw.pop, 1)
        ids[R.raw.chime] = sp.load(app, R.raw.chime, 1)
        ids[R.raw.alert] = sp.load(app, R.raw.alert, 1)
        pool = sp
        return sp
    }

    fun warm(c: Context) {
        ensure(c)
    }

    fun play(c: Context, res: Int, volume: Float = 1f) {
        if (!Store.flag(c, "sound")) return
        val sp = ensure(c)
        val id = ids[res] ?: return
        sp.play(id, volume, volume, 1, 0, 1f)
    }

    fun haptic(v: View) {
        if (Store.flag(v.context, "haptics")) v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
