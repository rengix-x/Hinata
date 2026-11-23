package com.edgeflow.service

import com.edgeflow.data.EdgeSide
import com.edgeflow.data.QuickMenuRetreatMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Pure-Kotlin, zero-allocation geometry evaluator for one-stroke Quick Menu & Recent Switcher
 * lower-stage return control using the two-zone Orange Protected Zone vs Green Horizontal
 * Return Corridor model:
 *
 * 1. **Orange Protected Zone** (`shouldHandBack == false`):
 *    - Vertical / lateral selection & rotary scrolling band (`abs(perpDelta) > 44dp` OR
 *      `abs(angleDeg) > 26°`) so top/bottom slots and deep downward/upward rotary spins
 *      NEVER falsely cancel the menu.
 *    - Deep side beyond the return boundary (`primaryPull >= retreatTriggerPullPx`).
 *
 * 2. **Green Horizontal Return Corridor** (`shouldHandBack == true`):
 *    - Finger moves horizontally back inside the central corridor (`abs(perpDelta) <= 44dp` AND
 *      `abs(angleDeg) <= 26°`), crosses toward the edge (`primaryPull < retreatTriggerPullPx`),
 *      AND has clearly pulled back at least `22dp` from the menu-open pull distance (`initialPullPx`).
 *
 * 3. **Edge Safety Valve** (`shouldHandBack == true`):
 *    - Regardless of vertical offset, returning almost all the way back to the screen edge
 *      (`primaryPull < 18dp && dist < deadZoneRadiusPx * 0.72f`) safely cancels and hands back.
 */
object MenuRetreatEvaluator {

    const val RADIAL_MIN_RATIO = 0.15f
    const val RADIAL_MAX_RATIO = 0.72f
    const val CUSTOM_LINE_MIN_RATIO = 0.08f
    const val CUSTOM_LINE_MAX_RATIO = 0.60f
    const val CUSTOM_LINE_ARC_GUARD_DEG = 35f
    const val EXIT_BUFFER_DP = 12f
    const val OPEN_INSIDE_MARGIN_DP = 8f
    const val CLEAR_PULLBACK_DP = 16f

    // Spec 54: Orange Protected Zone vs Green Horizontal Return Corridor constants
    const val HORIZONTAL_CORRIDOR_HALF_HEIGHT_DP = 44f
    const val HORIZONTAL_CORRIDOR_MAX_ANGLE_DEG = 26f
    const val CORRIDOR_MIN_PULLBACK_DP = 22f
    const val EDGE_SAFETY_VALVE_PULL_DP = 18f
    const val EDGE_SAFETY_VALVE_DEADZONE_MULT = 0.72f

    fun computeRadialRetreatRadiusPx(
        radiusPx: Float,
        deadZoneRadiusPx: Float,
        boundaryRatio: Float
    ): Float {
        val clampedRatio = boundaryRatio.coerceIn(RADIAL_MIN_RATIO, RADIAL_MAX_RATIO)
        val minR = (deadZoneRadiusPx * 0.65f).coerceAtMost(radiusPx * RADIAL_MAX_RATIO)
        val maxR = (radiusPx * RADIAL_MAX_RATIO).coerceAtLeast(minR)
        return (radiusPx * clampedRatio).coerceIn(minR, maxR)
    }

    fun computeCustomLinePx(
        screenAxisPx: Float,
        boundaryRatio: Float
    ): Float {
        val clampedRatio = boundaryRatio.coerceIn(CUSTOM_LINE_MIN_RATIO, CUSTOM_LINE_MAX_RATIO)
        return screenAxisPx.coerceAtLeast(1f) * clampedRatio
    }

    /**
     * Dynamically tunes the rotary overscroll start angle (`68°..80°`, `74°` at default `1.0x`)
     * outside the top/bottom visible slots (~±68°) so selecting edge slots never accidentally
     * triggers rotary scrolling.
     */
    fun computeDynamicOverscrollStartDeg(rotarySpeed: Float): Float {
        val speed = rotarySpeed.coerceIn(0.5f, 2.0f)
        val deg = if (speed <= 1.0f) {
            74f + (1.0f - speed) * 12f
        } else {
            74f - (speed - 1.0f) * 6f
        }
        return deg.coerceIn(68f, 80f)
    }

    fun isInitiallyOutsideRetreatZone(
        mode: QuickMenuRetreatMode,
        radialDistPx: Float,
        radiusPx: Float,
        deadZoneRadiusPx: Float,
        boundaryRatio: Float,
        density: Float
    ): Boolean {
        val margin = OPEN_INSIDE_MARGIN_DP * density
        return when (mode) {
            QuickMenuRetreatMode.RADIAL_ARC -> {
                val retreatR = computeRadialRetreatRadiusPx(radiusPx, deadZoneRadiusPx, boundaryRatio)
                radialDistPx >= retreatR + margin
            }
            QuickMenuRetreatMode.EDGE_DEADZONE -> {
                radialDistPx >= deadZoneRadiusPx * 0.85f + margin
            }
            QuickMenuRetreatMode.CUSTOM_LINE -> true
        }
    }

    fun shouldMarkExitedRetreatZone(
        mode: QuickMenuRetreatMode,
        radialDistPx: Float,
        radiusPx: Float,
        deadZoneRadiusPx: Float,
        boundaryRatio: Float,
        density: Float
    ): Boolean {
        val exitBuffer = EXIT_BUFFER_DP * density
        return when (mode) {
            QuickMenuRetreatMode.RADIAL_ARC -> {
                val retreatR = computeRadialRetreatRadiusPx(radiusPx, deadZoneRadiusPx, boundaryRatio)
                radialDistPx >= retreatR + exitBuffer
            }
            QuickMenuRetreatMode.EDGE_DEADZONE -> {
                radialDistPx >= deadZoneRadiusPx * 0.85f + exitBuffer
            }
            QuickMenuRetreatMode.CUSTOM_LINE -> true
        }
    }

    fun shouldHandBack(
        mode: QuickMenuRetreatMode,
        side: EdgeSide,
        launchedStage: Int,
        localX: Float,
        localY: Float,
        originX: Float,
        originY: Float,
        rawX: Float,
        rawY: Float,
        anchorRawX: Float,
        anchorRawY: Float,
        radiusPx: Float,
        deadZoneRadiusPx: Float,
        boundaryRatio: Float,
        screenAxisPx: Float,
        density: Float,
        hasExitedRetreatZone: Boolean,
        initialRadialDistPx: Float,
        retreatBoundaryPx: Float = 0f,
        initialPullPx: Float = initialRadialDistPx
    ): Boolean {
        if (launchedStage < 2) return false

        val dx = localX - originX
        val dy = localY - originY
        val dist = hypot(dx.toDouble(), dy.toDouble()).toFloat()

        val primaryPull = when (side) {
            EdgeSide.LEFT -> rawX - anchorRawX
            EdgeSide.RIGHT -> anchorRawX - rawX
            EdgeSide.BOTTOM -> anchorRawY - rawY
        }

        // 1. Edge Safety Valve: complete pullback right back to the screen edge near origin
        if (primaryPull < EDGE_SAFETY_VALVE_PULL_DP * density &&
            dist < deadZoneRadiusPx * EDGE_SAFETY_VALVE_DEADZONE_MULT
        ) {
            return true
        }

        val perpDelta = when (side) {
            EdgeSide.LEFT, EdgeSide.RIGHT -> abs(dy)
            EdgeSide.BOTTOM -> abs(dx)
        }
        val inwardPrimary = when (side) {
            EdgeSide.LEFT -> dx.coerceAtLeast(1f)
            EdgeSide.RIGHT -> (-dx).coerceAtLeast(1f)
            EdgeSide.BOTTOM -> (-dy).coerceAtLeast(1f)
        }
        val angleRad = atan2(perpDelta.toDouble(), inwardPrimary.toDouble()).toFloat()
        val angleDeg = (angleRad * 180.0 / PI).toFloat()

        // 2. Orange Protected Zone (vertical/lateral slot selection & rotary scrolling band)
        val corridorHalfBandPx = HORIZONTAL_CORRIDOR_HALF_HEIGHT_DP * density
        if (perpDelta > corridorHalfBandPx || angleDeg > HORIZONTAL_CORRIDOR_MAX_ANGLE_DEG) {
            return false
        }

        // 3. Determine return boundary on primary pull axis
        val retreatTriggerPullPx = if (retreatBoundaryPx > 0f) {
            retreatBoundaryPx
        } else {
            when (mode) {
                QuickMenuRetreatMode.RADIAL_ARC ->
                    computeRadialRetreatRadiusPx(radiusPx, deadZoneRadiusPx, boundaryRatio)
                QuickMenuRetreatMode.EDGE_DEADZONE ->
                    deadZoneRadiusPx * 0.85f
                QuickMenuRetreatMode.CUSTOM_LINE ->
                    computeCustomLinePx(screenAxisPx, boundaryRatio)
            }
        }

        // Orange Protected Zone (deep side beyond return boundary)
        if (primaryPull >= retreatTriggerPullPx) {
            return false
        }

        // 4. Green Horizontal Return Corridor: must have clearly pulled back >= 22dp from open point
        val pulledBackPx = initialPullPx - primaryPull
        return pulledBackPx >= CORRIDOR_MIN_PULLBACK_DP * density
    }
}
