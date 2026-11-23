package com.edgeflow.service

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Path
import android.graphics.RectF
import android.os.Build
import androidx.core.content.ContextCompat
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.EdgeSide
import com.edgeflow.data.VisualTheme

/**
 * Zero-allocation shared translucent glass palette & vector action glyph renderer
 * shared by [EdgeFeedbackView], [QuickArcMenuView], and idle edge/pill guides so all
 * resident overlays maintain identical glassmorphism polish with zero duplication.
 */
class GlassPalette {
    var fillRgb: Int = 0x101826
    var fillTriggeredRgb: Int = 0x162235
    var rimRgb: Int = 0xFFFFFF
    var rimBaseAlpha: Float = 0.22f
    var rimTriggeredAlpha: Float = 0.38f
    var iconIdleRgb: Int = 0xE2E8F0
    var iconActiveRgb: Int = 0xFFFFFF
    var accentRgb: Int = 0x60A5FA
    var labelBgRgb: Int = 0x0F172A
    var labelTextRgb: Int = 0xF8FAFC
    var guideRgb: Int = 0xFFFFFF

    fun resolve(context: Context, theme: VisualTheme) {
        val isNight = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val effective = if (theme == VisualTheme.AUTO) {
            if (isNight) VisualTheme.DARK_GLASS else VisualTheme.LIGHT_GLASS
        } else {
            theme
        }

        when (effective) {
            VisualTheme.DARK_GLASS, VisualTheme.AUTO -> {
                fillRgb = 0x0E1522
                fillTriggeredRgb = 0x162336
                rimRgb = 0xFFFFFF
                rimBaseAlpha = 0.20f
                rimTriggeredAlpha = 0.36f
                iconIdleRgb = 0xE2E8F0
                iconActiveRgb = 0xFFFFFF
                accentRgb = 0x60A5FA
                labelBgRgb = 0x0B111E
                labelTextRgb = 0xF8FAFC
                guideRgb = 0xFFFFFF
            }

            VisualTheme.LIGHT_GLASS -> {
                fillRgb = 0xF8FAFC
                fillTriggeredRgb = 0xFFFFFF
                rimRgb = 0x0F172A
                rimBaseAlpha = 0.18f
                rimTriggeredAlpha = 0.32f
                iconIdleRgb = 0x1E293B
                iconActiveRgb = 0x0F172A
                accentRgb = 0x2563EB
                labelBgRgb = 0xF8FAFC
                labelTextRgb = 0x0F172A
                guideRgb = 0x0F172A
            }

            VisualTheme.MATERIAL_YOU -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    val accent = runCatching {
                        ContextCompat.getColor(context, android.R.color.system_accent1_300)
                    }.getOrDefault(0xFF60A5FA.toInt())
                    val surface = runCatching {
                        ContextCompat.getColor(
                            context,
                            if (isNight) android.R.color.system_neutral1_900
                            else android.R.color.system_neutral1_50
                        )
                    }.getOrDefault(if (isNight) 0xFF0E1522.toInt() else 0xFFF8FAFC.toInt())
                    fillRgb = surface and 0x00FFFFFF
                    fillTriggeredRgb = fillRgb
                    accentRgb = accent and 0x00FFFFFF
                    rimRgb = accentRgb
                    rimBaseAlpha = 0.25f
                    rimTriggeredAlpha = 0.48f
                    iconIdleRgb = if (isNight) 0xE2E8F0 else 0x1E293B
                    iconActiveRgb = if (isNight) 0xFFFFFF else 0x0F172A
                    labelBgRgb = fillRgb
                    labelTextRgb = iconActiveRgb
                    guideRgb = accentRgb
                } else {
                    fillRgb = 0x0E1522
                    fillTriggeredRgb = 0x162336
                    accentRgb = 0x60A5FA
                    rimRgb = 0x60A5FA
                    rimBaseAlpha = 0.24f
                    rimTriggeredAlpha = 0.44f
                    iconIdleRgb = 0xE2E8F0
                    iconActiveRgb = 0xFFFFFF
                    labelBgRgb = 0x0B111E
                    labelTextRgb = 0xF8FAFC
                    guideRgb = 0x60A5FA
                }
            }
        }
    }

    companion object {
        fun argb(rgb: Int, alphaFraction: Float): Int {
            val a = (alphaFraction.coerceIn(0f, 1f) * 255f + 0.5f).toInt().coerceIn(0, 255)
            return (a shl 24) or (rgb and 0x00FFFFFF)
        }

        /**
         * Smooth Hermite interpolation between [edge0] and [edge1] (Zero-GC).
         * Matches GLSL/AGSL `smoothstep` semantics for continuous luminance transitions.
         */
        fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
            if (edge1 <= edge0) return if (x >= edge1) 1f else 0f
            val t = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
            return t * t * (3f - 2f * t)
        }

        /**
         * Zero-GC linear RGB interpolation returning a 24-bit `0x00RRGGBB` value.
         */
        fun lerpRgb(rgbStart: Int, rgbEnd: Int, fraction: Float): Int {
            val t = fraction.coerceIn(0f, 1f)
            val r0 = (rgbStart shr 16) and 0xFF
            val g0 = (rgbStart shr 8) and 0xFF
            val b0 = rgbStart and 0xFF
            val r1 = (rgbEnd shr 16) and 0xFF
            val g1 = (rgbEnd shr 8) and 0xFF
            val b1 = rgbEnd and 0xFF
            val r = (r0 + (r1 - r0) * t + 0.5f).toInt().coerceIn(0, 255)
            val g = (g0 + (g1 - g0) * t + 0.5f).toInt().coerceIn(0, 255)
            val b = (b0 + (b1 - b0) * t + 0.5f).toInt().coerceIn(0, 255)
            return (r shl 16) or (g shl 8) or b
        }
    }
}

/**
 * Populates a pre-allocated [Path] with a clean geometric stroke glyph for any [EdgeActionType].
 * Strictly zero allocations.
 */
object ActionGlyphPathBuilder {

    fun build(
        path: Path,
        tempRect: RectF,
        type: EdgeActionType,
        side: EdgeSide,
        cx: Float,
        cy: Float,
        r: Float
    ) {
        path.rewind()
        when (type) {
            EdgeActionType.BACK, EdgeActionType.NONE -> {
                val dir = if (side == EdgeSide.RIGHT) -1f else 1f
                path.moveTo(cx - dir * r * 0.35f, cy - r * 0.70f)
                path.lineTo(cx + dir * r * 0.40f, cy)
                path.lineTo(cx - dir * r * 0.35f, cy + r * 0.70f)
            }

            EdgeActionType.HOME -> {
                path.moveTo(cx - r * 0.78f, cy + r * 0.05f)
                path.lineTo(cx, cy - r * 0.74f)
                path.lineTo(cx + r * 0.78f, cy + r * 0.05f)
                path.moveTo(cx - r * 0.50f, cy + r * 0.05f)
                path.lineTo(cx - r * 0.50f, cy + r * 0.72f)
                path.lineTo(cx + r * 0.50f, cy + r * 0.72f)
                path.lineTo(cx + r * 0.50f, cy + r * 0.05f)
            }

            EdgeActionType.RECENTS -> {
                tempRect.set(cx - r * 0.64f, cy - r * 0.54f, cx + r * 0.34f, cy + r * 0.44f)
                path.addRoundRect(tempRect, r * 0.22f, r * 0.22f, Path.Direction.CW)
                path.moveTo(cx - r * 0.24f, cy + r * 0.74f)
                path.lineTo(cx + r * 0.66f, cy + r * 0.74f)
                path.lineTo(cx + r * 0.66f, cy - r * 0.22f)
            }

            EdgeActionType.PREVIOUS_APP -> {
                val dir = if (side == EdgeSide.RIGHT) -1f else 1f
                path.moveTo(cx - dir * r * 0.62f, cy - r * 0.58f)
                path.lineTo(cx - dir * r * 0.04f, cy)
                path.lineTo(cx - dir * r * 0.62f, cy + r * 0.58f)
                path.moveTo(cx + dir * r * 0.04f, cy - r * 0.58f)
                path.lineTo(cx + dir * r * 0.62f, cy)
                path.lineTo(cx + dir * r * 0.04f, cy + r * 0.58f)
            }

            EdgeActionType.NOTIFICATIONS -> {
                path.moveTo(cx, cy - r * 0.70f)
                path.lineTo(cx, cy + r * 0.54f)
                path.moveTo(cx - r * 0.56f, cy + r * 0.08f)
                path.lineTo(cx, cy + r * 0.66f)
                path.lineTo(cx + r * 0.56f, cy + r * 0.08f)
            }

            EdgeActionType.QUICK_SETTINGS -> {
                path.moveTo(cx - r * 0.74f, cy - r * 0.38f)
                path.lineTo(cx + r * 0.74f, cy - r * 0.38f)
                path.addCircle(cx - r * 0.22f, cy - r * 0.38f, r * 0.19f, Path.Direction.CW)
                path.moveTo(cx - r * 0.74f, cy + r * 0.38f)
                path.lineTo(cx + r * 0.74f, cy + r * 0.38f)
                path.addCircle(cx + r * 0.24f, cy + r * 0.38f, r * 0.19f, Path.Direction.CW)
            }

            EdgeActionType.LOCK_SCREEN -> {
                tempRect.set(cx - r * 0.60f, cy - r * 0.04f, cx + r * 0.60f, cy + r * 0.76f)
                path.addRoundRect(tempRect, r * 0.20f, r * 0.20f, Path.Direction.CW)
                tempRect.set(cx - r * 0.36f, cy - r * 0.74f, cx + r * 0.36f, cy + r * 0.04f)
                path.arcTo(tempRect, 180f, 180f, false)
            }

            EdgeActionType.SCREENSHOT -> {
                val s = r * 0.70f
                val arm = r * 0.34f
                path.moveTo(cx - s, cy - s + arm); path.lineTo(cx - s, cy - s); path.lineTo(cx - s + arm, cy - s)
                path.moveTo(cx + s - arm, cy - s); path.lineTo(cx + s, cy - s); path.lineTo(cx + s, cy - s + arm)
                path.moveTo(cx - s, cy + s - arm); path.lineTo(cx - s, cy + s); path.lineTo(cx - s + arm, cy + s)
                path.moveTo(cx + s - arm, cy + s); path.lineTo(cx + s, cy + s); path.lineTo(cx + s, cy + s - arm)
            }

            EdgeActionType.SPLIT_SCREEN -> {
                tempRect.set(cx - r * 0.66f, cy - r * 0.70f, cx + r * 0.66f, cy - r * 0.14f)
                path.addRoundRect(tempRect, r * 0.18f, r * 0.18f, Path.Direction.CW)
                tempRect.set(cx - r * 0.66f, cy + r * 0.14f, cx + r * 0.66f, cy + r * 0.70f)
                path.addRoundRect(tempRect, r * 0.18f, r * 0.18f, Path.Direction.CW)
            }

            EdgeActionType.FLOAT_CURRENT_APP,
            EdgeActionType.LAUNCH_APP_FLOATING -> {
                // Outer screen frame + crisp inset floating mini-window rectangle
                tempRect.set(cx - r * 0.72f, cy - r * 0.62f, cx + r * 0.72f, cy + r * 0.62f)
                path.addRoundRect(tempRect, r * 0.20f, r * 0.20f, Path.Direction.CW)
                tempRect.set(cx - r * 0.04f, cy - r * 0.08f, cx + r * 0.56f, cy + r * 0.44f)
                path.addRoundRect(tempRect, r * 0.14f, r * 0.14f, Path.Direction.CW)
                // Mini top handle bar inside floating window
                path.moveTo(cx + r * 0.14f, cy + r * 0.08f)
                path.lineTo(cx + r * 0.38f, cy + r * 0.08f)
            }

            EdgeActionType.CIRCLE_TO_SEARCH -> {
                // Iconic search circle + subtle sparkle star at top-right
                path.addCircle(cx - r * 0.10f, cy + r * 0.08f, r * 0.56f, Path.Direction.CW)
                val sx = cx + r * 0.56f
                val sy = cy - r * 0.56f
                val sr = r * 0.24f
                path.moveTo(sx, sy - sr); path.lineTo(sx, sy + sr)
                path.moveTo(sx - sr, sy); path.lineTo(sx + sr, sy)
            }

            EdgeActionType.OPEN_QUICK_MENU -> {
                // Radial 3-dot fan arc icon
                tempRect.set(cx - r * 0.70f, cy - r * 0.70f, cx + r * 0.70f, cy + r * 0.70f)
                path.arcTo(tempRect, -60f, 120f, true)
                path.addCircle(cx - r * 0.36f, cy, r * 0.16f, Path.Direction.CW)
                path.addCircle(cx + r * 0.32f, cy - r * 0.44f, r * 0.14f, Path.Direction.CW)
                path.addCircle(cx + r * 0.32f, cy + r * 0.44f, r * 0.14f, Path.Direction.CW)
            }

            EdgeActionType.OPEN_RECENT_SWITCHER -> {
                // Layered carousel switcher cards (center hero + side peeking frames)
                tempRect.set(cx - r * 0.36f, cy - r * 0.64f, cx + r * 0.36f, cy + r * 0.64f)
                path.addRoundRect(tempRect, r * 0.18f, r * 0.18f, Path.Direction.CW)
                path.moveTo(cx - r * 0.68f, cy - r * 0.44f)
                path.lineTo(cx - r * 0.68f, cy + r * 0.44f)
                path.moveTo(cx + r * 0.68f, cy - r * 0.44f)
                path.lineTo(cx + r * 0.68f, cy + r * 0.44f)
            }

            EdgeActionType.TOGGLE_TORCH -> {
                // Minimalist flashlight body + beam rays
                path.moveTo(cx - r * 0.36f, cy - r * 0.20f)
                path.lineTo(cx + r * 0.36f, cy - r * 0.20f)
                path.lineTo(cx + r * 0.20f, cy + r * 0.12f)
                path.lineTo(cx + r * 0.20f, cy + r * 0.74f)
                path.lineTo(cx - r * 0.20f, cy + r * 0.74f)
                path.lineTo(cx - r * 0.20f, cy + r * 0.12f)
                path.close()
                path.moveTo(cx, cy - r * 0.48f); path.lineTo(cx, cy - r * 0.76f)
                path.moveTo(cx - r * 0.38f, cy - r * 0.44f); path.lineTo(cx - r * 0.58f, cy - r * 0.64f)
                path.moveTo(cx + r * 0.38f, cy - r * 0.44f); path.lineTo(cx + r * 0.58f, cy - r * 0.64f)
            }

            EdgeActionType.TOGGLE_SCREEN_TRANSLATE,
            EdgeActionType.TRANSLATE_SCREEN_ONCE -> {
                // Crisp dual-script "文 / A" translation glyph (zero allocations)
                // Top-left "文" radical
                path.moveTo(cx - r * 0.68f, cy - r * 0.42f)
                path.lineTo(cx - r * 0.04f, cy - r * 0.42f)
                path.moveTo(cx - r * 0.36f, cy - r * 0.66f)
                path.lineTo(cx - r * 0.36f, cy - r * 0.42f)
                path.moveTo(cx - r * 0.16f, cy - r * 0.42f)
                path.lineTo(cx - r * 0.62f, cy + r * 0.10f)
                path.moveTo(cx - r * 0.54f, cy - r * 0.26f)
                path.lineTo(cx - r * 0.08f, cy + r * 0.08f)
                // Bottom-right "A" letter
                path.moveTo(cx + r * 0.06f, cy + r * 0.66f)
                path.lineTo(cx + r * 0.38f, cy - r * 0.06f)
                path.lineTo(cx + r * 0.70f, cy + r * 0.66f)
                path.moveTo(cx + r * 0.18f, cy + r * 0.42f)
                path.lineTo(cx + r * 0.58f, cy + r * 0.42f)
            }

            EdgeActionType.LAUNCH_APP -> {
                // 2x2 rounded app launcher tiles
                val g = r * 0.12f
                val s = r * 0.64f
                tempRect.set(cx - s, cy - s, cx - g, cy - g)
                path.addRoundRect(tempRect, r * 0.14f, r * 0.14f, Path.Direction.CW)
                tempRect.set(cx + g, cy - s, cx + s, cy - g)
                path.addRoundRect(tempRect, r * 0.14f, r * 0.14f, Path.Direction.CW)
                tempRect.set(cx - s, cy + g, cx - g, cy + s)
                path.addRoundRect(tempRect, r * 0.14f, r * 0.14f, Path.Direction.CW)
                tempRect.set(cx + g, cy + g, cx + s, cy + s)
                path.addRoundRect(tempRect, r * 0.14f, r * 0.14f, Path.Direction.CW)
            }

            EdgeActionType.OPEN_URL -> {
                // Crisp minimalist horizontal chain-link glyph
                val linkH = r * 0.36f
                tempRect.set(cx - r * 0.76f, cy - linkH, cx - r * 0.04f, cy + linkH)
                path.addRoundRect(tempRect, linkH, linkH, Path.Direction.CW)
                tempRect.set(cx + r * 0.04f, cy - linkH, cx + r * 0.76f, cy + linkH)
                path.addRoundRect(tempRect, linkH, linkH, Path.Direction.CW)
                path.moveTo(cx - r * 0.28f, cy)
                path.lineTo(cx + r * 0.28f, cy)
            }

            EdgeActionType.LAUNCH_SHORTCUT -> {
                // Rounded shortcut box + crisp launch arrow pointing top-right
                path.moveTo(cx + r * 0.05f, cy - r * 0.56f)
                path.lineTo(cx - r * 0.52f, cy - r * 0.56f)
                path.lineTo(cx - r * 0.52f, cy + r * 0.58f)
                path.lineTo(cx + r * 0.56f, cy + r * 0.58f)
                path.lineTo(cx + r * 0.56f, cy - r * 0.02f)
                // Diagonal launch arrow
                path.moveTo(cx - r * 0.06f, cy + r * 0.08f)
                path.lineTo(cx + r * 0.64f, cy - r * 0.62f)
                path.moveTo(cx + r * 0.24f, cy - r * 0.62f)
                path.lineTo(cx + r * 0.64f, cy - r * 0.62f)
                path.lineTo(cx + r * 0.64f, cy - r * 0.22f)
            }
        }
    }
}
