package org.ntust.app.tigerduck.ui.haptics

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import org.ntust.app.tigerduck.data.preferences.AppPreferences

object Haptics {

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface PrefsEntryPoint {
        fun appPreferences(): AppPreferences
    }

    fun perform(context: Context, scenario: HapticScenario) {
        val (strengthPct, durationMs) = if (scenario.userTunable) {
            val prefs = EntryPointAccessors
                .fromApplication(context.applicationContext, PrefsEntryPoint::class.java)
                .appPreferences()
            prefs.hapticStrength(scenario) to prefs.hapticDurationMs(scenario)
        } else {
            scenario.defaultStrengthPct to scenario.defaultDurationMs
        }
        vibrate(context, strengthPct, durationMs, forceOneShot = scenario.forceOneShot)
    }

    fun previewCustom(context: Context, strengthPct: Int, durationMs: Int) {
        vibrate(context, strengthPct, durationMs, forceOneShot = false)
    }

    private fun vibrate(context: Context, strengthPct: Int, durationMs: Int, forceOneShot: Boolean) {
        if (strengthPct <= 0) return
        if (!systemHapticsEnabled(context)) return

        try {
            val vibrator = resolveVibrator(context) ?: return
            val effect = buildEffect(vibrator, strengthPct, durationMs, forceOneShot)
            vibrator.vibrate(effect)
        } catch (_: Exception) {
            // Vibrator not available or denied; silently no-op (matches prior behavior).
        }
    }

    private fun resolveVibrator(context: Context): Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager)
                ?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }

    private fun systemHapticsEnabled(context: Context): Boolean = try {
        Settings.System.getInt(
            context.contentResolver,
            Settings.System.HAPTIC_FEEDBACK_ENABLED,
            1,
        ) != 0
    } catch (_: Exception) {
        true
    }

    private fun buildEffect(
        vibrator: Vibrator,
        strengthPct: Int,
        durationMs: Int,
        forceOneShot: Boolean,
    ): VibrationEffect {
        val scale = (strengthPct.coerceIn(1, 100)) / 100f
        val safeDuration = durationMs.coerceAtLeast(1).toLong()

        if (!forceOneShot && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val primitive = if (durationMs <= TICK_THRESHOLD_MS) {
                VibrationEffect.Composition.PRIMITIVE_TICK
            } else {
                VibrationEffect.Composition.PRIMITIVE_CLICK
            }
            if (vibrator.areAllPrimitivesSupported(primitive)) {
                return VibrationEffect.startComposition()
                    .addPrimitive(primitive, scale)
                    .compose()
            }
        }

        val amplitude = if (vibrator.hasAmplitudeControl()) {
            (strengthPct * 255 / 100).coerceIn(1, 255)
        } else {
            VibrationEffect.DEFAULT_AMPLITUDE
        }
        return VibrationEffect.createOneShot(safeDuration, amplitude)
    }

    internal const val TICK_THRESHOLD_MS = 10
}
