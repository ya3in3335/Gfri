package com.greenaccesse.app.presentation

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.greenaccesse.app.domain.ScanFeedback

/** Short beep / vibration on scan. Uses the VIBRATE permission only. */
class AndroidScanFeedback(context: Context) : ScanFeedback {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private val tone: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80) }.getOrNull()

    override fun onCardDetected(sound: Boolean, vibrate: Boolean) {
        if (sound) runCatching { tone?.startTone(ToneGenerator.TONE_PROP_BEEP, 80) }
        if (vibrate) vibrate(40)
    }

    override fun onResult(success: Boolean, sound: Boolean, vibrate: Boolean) {
        if (success) return // detection feedback already given; keep it short at the front desk
        if (sound) runCatching { tone?.startTone(ToneGenerator.TONE_PROP_NACK, 200) }
        if (vibrate) vibrate(200)
    }

    private fun vibrate(ms: Long) {
        val v = vibrator ?: return
        if (!v.hasVibrator()) return
        runCatching { v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
    }
}
