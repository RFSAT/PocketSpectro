package com.rfsat.pocketspectro.core

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager

/** Audible / haptic fire alarm, rate-limited so continuous detections don't stack up. */
class AlarmPlayer(context: Context) {
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }
    private var tone: ToneGenerator? = null
    private var lastMs = 0L

    @Synchronized
    fun trigger(sound: Boolean, vibrate: Boolean) {
        val now = System.currentTimeMillis()
        if (now - lastMs < REPEAT_MS) return
        lastMs = now
        if (sound) {
            try {
                val t = tone ?: ToneGenerator(AudioManager.STREAM_ALARM, 90).also { tone = it }
                t.startTone(ToneGenerator.TONE_CDMA_EMERGENCY_RINGBACK, 1500)
            } catch (_: Exception) {
            }
        }
        if (vibrate) {
            try {
                vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 400, 200, 400), -1))
            } catch (_: Exception) {
            }
        }
    }

    @Synchronized
    fun stop() {
        lastMs = 0L
        try { tone?.stopTone() } catch (_: Exception) {}
        try { vibrator?.cancel() } catch (_: Exception) {}
    }

    private companion object { const val REPEAT_MS = 3000L }
}
