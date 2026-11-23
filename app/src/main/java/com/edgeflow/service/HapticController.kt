package com.edgeflow.service

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.edgeflow.data.HapticStrength

/**
 * Linear-motor (X-axis LRA) optimized 3-stage haptic controller with pre-allocated
 * [VibrationEffect]s to guarantee zero heap allocations during 120Hz gesture interaction:
 * - Stage 1: Light crisp tick (`EFFECT_TICK`)
 * - Stage 2: Clear tactile click (`EFFECT_CLICK`)
 * - Stage 3: Distinct heavy click / crisp double micro-pulse (`EFFECT_HEAVY_CLICK`)
 */
class HapticController(context: Context) {

    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
        manager?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    private val hasAmplitudeControl: Boolean = vibrator?.hasAmplitudeControl() == true

    // Stage 1 pre-allocated effects (EFFECT_TICK profile)
    private val stage1LightEffect: VibrationEffect = if (hasAmplitudeControl) {
        VibrationEffect.createOneShot(9L, 60)
    } else {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    }
    private val stage1MediumEffect: VibrationEffect =
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    private val stage1StrongEffect: VibrationEffect = if (hasAmplitudeControl) {
        VibrationEffect.createOneShot(14L, 165)
    } else {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    }

    // Stage 2 pre-allocated effects (EFFECT_CLICK profile)
    private val stage2LightEffect: VibrationEffect =
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
    private val stage2MediumEffect: VibrationEffect =
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    private val stage2StrongEffect: VibrationEffect =
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)

    // Stage 3 pre-allocated effects (EFFECT_HEAVY_CLICK / crisp double pulse)
    private val stage3LightEffect: VibrationEffect =
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_CLICK)
    private val stage3MediumEffect: VibrationEffect =
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_HEAVY_CLICK)
    private val stage3StrongEffect: VibrationEffect = if (hasAmplitudeControl) {
        VibrationEffect.createWaveform(
            longArrayOf(0L, 18L, 34L, 24L),
            intArrayOf(0, 210, 0, 255),
            -1
        )
    } else {
        VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    @Volatile
    private var strength: HapticStrength = HapticStrength.MEDIUM

    fun updateStrength(newStrength: HapticStrength) {
        strength = newStrength
    }

    /**
     * Stage 1: Crisp single tick the instant swipe crosses the activation threshold.
     */
    fun performPrimaryTrigger() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val effect = when (strength) {
            HapticStrength.OFF -> return
            HapticStrength.LIGHT -> stage1LightEffect
            HapticStrength.MEDIUM -> stage1MediumEffect
            HapticStrength.STRONG -> stage1StrongEffect
        }
        runCatching { vib.vibrate(effect) }
    }

    /**
     * Stage 2: Distinct tactile click when Stage 2 locks in.
     */
    fun performHoldTrigger() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val effect = when (strength) {
            HapticStrength.OFF -> return
            HapticStrength.LIGHT -> stage2LightEffect
            HapticStrength.MEDIUM -> stage2MediumEffect
            HapticStrength.STRONG -> stage2StrongEffect
        }
        runCatching { vib.vibrate(effect) }
    }

    /**
     * Stage 3: Weighted heavy click / double micro-pulse when deep Stage 3 locks in.
     */
    fun performTertiaryTrigger() {
        val vib = vibrator ?: return
        if (!vib.hasVibrator()) return
        val effect = when (strength) {
            HapticStrength.OFF -> return
            HapticStrength.LIGHT -> stage3LightEffect
            HapticStrength.MEDIUM -> stage3MediumEffect
            HapticStrength.STRONG -> stage3StrongEffect
        }
        runCatching { vib.vibrate(effect) }
    }
}
