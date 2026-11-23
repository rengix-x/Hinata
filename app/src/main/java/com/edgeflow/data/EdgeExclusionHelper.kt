package com.edgeflow.data

import kotlin.math.roundToInt

/**
 * Pure-Kotlin helper for Android's 200dp-per-edge `systemGestureExclusionRects` budget
 * (Android 10–16). Enables Shizuku-free zero-conflict placement on Pixel, Galaxy, OPPO,
 * realme, vivo, Xperia, Nothing, and HyperOS devices.
 *
 * ZERO Android / Compose dependencies so all calculations are 100% pure-JVM testable.
 */
object EdgeExclusionHelper {

    const val ANDROID_EXCLUSION_LIMIT_DP = 200
    const val SAFE_TARGET_BUDGET_DP = 196
    const val DEFAULT_SAFE_BOTTOM_OFFSET_PERCENT = 0.05f

    /**
     * Estimates the total vertical height (in dp) claimed on a single side edge by Hinata
     * (main edge trigger + dedicated Quick Arc zone when active on side edges).
     */
    fun estimateTotalSideEdgeHeightDp(config: EdgeConfig, screenHeightDp: Int): Int {
        if (screenHeightDp <= 0) return 0
        if (!config.leftEnabled && !config.rightEnabled) return 0
        val mainDp = config.edgeHeightPercent * screenHeightDp
        val arcDp = if (config.quickArcTriggerMethod == QuickArcTriggerMethod.DEDICATED_ZONE) {
            config.quickArcZoneHeightPercent * screenHeightDp
        } else {
            0f
        }
        return (mainDp + arcDp).roundToInt()
    }

    /**
     * Returns `true` when the configured side edge zone(s) exceed Android's 200dp-per-edge
     * system gesture exclusion cap, meaning OS Back gestures may conflict near the upper section.
     */
    fun exceedsSystemExclusionLimit(config: EdgeConfig, screenHeightDp: Int): Boolean {
        return estimateTotalSideEdgeHeightDp(config, screenHeightDp) > ANDROID_EXCLUSION_LIMIT_DP
    }

    /**
     * Returns a copy of [config] whose total side-edge height fits strictly within `<= 196dp`
     * (Android's 200dp limit with a 4dp safety margin), positioned in the thumb-friendly lower
     * region (`edgeBottomOffsetPercent = 0.05f`) so OS Back gestures never collide even without Shizuku.
     */
    fun optimizeForSystemExclusionLimit(config: EdgeConfig, screenHeightDp: Int): EdgeConfig {
        val safeScreenH = screenHeightDp.coerceAtLeast(360).toFloat()
        val hasDedicatedArc = config.quickArcTriggerMethod == QuickArcTriggerMethod.DEDICATED_ZONE
        val totalBudgetRatio = (SAFE_TARGET_BUDGET_DP / safeScreenH).coerceIn(0.12f, 0.90f)

        return if (!hasDedicatedArc) {
            val newMainHeight = totalBudgetRatio.coerceIn(0.12f, 0.90f)
            val newMainBottom = DEFAULT_SAFE_BOTTOM_OFFSET_PERCENT
                .coerceAtMost((1f - newMainHeight).coerceAtLeast(0f))
            config.copy(
                edgeHeightPercent = newMainHeight,
                edgeBottomOffsetPercent = newMainBottom
            ).clamped()
        } else {
            // Allocate ~65% of budget to main edge and ~35% to dedicated arc zone, respecting EdgeConfig clamps
            val desiredArc = (totalBudgetRatio * 0.35f).coerceIn(0.08f, 0.40f)
            val maxMain = (totalBudgetRatio - desiredArc).coerceIn(0.12f, 0.90f)
            // If clamping pushed total slightly over budget (e.g. tall screen), tighten arc first
            val adjustedArc = if ((maxMain + desiredArc) * safeScreenH > SAFE_TARGET_BUDGET_DP) {
                ((SAFE_TARGET_BUDGET_DP / safeScreenH) - maxMain).coerceIn(0.08f, 0.40f)
            } else {
                desiredArc
            }
            val newMainBottom = DEFAULT_SAFE_BOTTOM_OFFSET_PERCENT
                .coerceAtMost((1f - maxMain).coerceAtLeast(0f))
            val gap = 0.02f
            val newArcBottom = (newMainBottom + maxMain + gap)
                .coerceIn(0f, (1f - adjustedArc).coerceAtLeast(0f))
            config.copy(
                edgeHeightPercent = maxMain,
                edgeBottomOffsetPercent = newMainBottom,
                quickArcZoneHeightPercent = adjustedArc,
                quickArcZoneBottomOffsetPercent = newArcBottom
            ).clamped()
        }
    }

    /**
     * Computes the prioritized vertical `[topPx, bottomPx]` exclusion slice within a single view
     * of height [viewHeightPx], protecting the bottom thumb region first when [viewHeightPx]
     * exceeds [budgetPx].
     */
    fun computeBottomPrioritizedRangePx(viewHeightPx: Int, budgetPx: Int): Pair<Int, Int> {
        if (viewHeightPx <= 0 || budgetPx <= 0) return 0 to 0
        if (viewHeightPx <= budgetPx) return 0 to viewHeightPx
        val top = (viewHeightPx - budgetPx).coerceAtLeast(0)
        return top to viewHeightPx
    }
}
