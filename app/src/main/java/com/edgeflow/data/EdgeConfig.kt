package com.edgeflow.data

import java.lang.StringBuilder
import java.nio.charset.StandardCharsets
import java.util.Base64
import kotlin.math.max

enum class EdgeSide {
    LEFT,
    RIGHT,
    BOTTOM
}

/**
 * Unified action taxonomy shared across all edge gestures, bottom gestures,
 * bottom long-press, Quick Arc Menu slots, and Recent Switcher.
 */
enum class EdgeActionType {
    BACK,
    HOME,
    RECENTS,
    PREVIOUS_APP,
    NOTIFICATIONS,
    QUICK_SETTINGS,
    LOCK_SCREEN,
    SCREENSHOT,
    SPLIT_SCREEN,
    FLOAT_CURRENT_APP,
    CIRCLE_TO_SEARCH,
    LAUNCH_APP,
    LAUNCH_APP_FLOATING,
    LAUNCH_SHORTCUT,
    OPEN_URL,
    OPEN_QUICK_MENU,
    OPEN_RECENT_SWITCHER,
    TOGGLE_TORCH,
    TOGGLE_SCREEN_TRANSLATE,
    TRANSLATE_SCREEN_ONCE,
    NONE
}

data class EdgeAction(
    val type: EdgeActionType,
    val packageName: String = "",
    val targetData: String = ""
) {
    val isNone: Boolean get() = type == EdgeActionType.NONE

    val isAppLaunch: Boolean
        get() = type == EdgeActionType.LAUNCH_APP || type == EdgeActionType.LAUNCH_APP_FLOATING

    val isShortcutLaunch: Boolean
        get() = type == EdgeActionType.LAUNCH_SHORTCUT

    val isUrlLaunch: Boolean
        get() = type == EdgeActionType.OPEN_URL

    val isOneStrokeMenu: Boolean
        get() = type == EdgeActionType.OPEN_QUICK_MENU ||
            type == EdgeActionType.OPEN_RECENT_SWITCHER

    fun encode(): String = when (type) {
        EdgeActionType.LAUNCH_APP, EdgeActionType.LAUNCH_APP_FLOATING -> {
            if (packageName.isNotEmpty()) "${type.name}:$packageName" else type.name
        }
        EdgeActionType.OPEN_URL -> {
            if (targetData.isNotEmpty()) {
                "${type.name}:${encodeBase64Url(targetData)}"
            } else {
                type.name
            }
        }
        EdgeActionType.LAUNCH_SHORTCUT -> {
            val safePkg = packageName.replace(":", "").replace("|", "").replace(";", "").trim()
            if (targetData.isNotEmpty()) {
                "${type.name}:$safePkg:${encodeBase64Url(targetData)}"
            } else if (safePkg.isNotEmpty()) {
                "${type.name}:$safePkg:"
            } else {
                type.name
            }
        }
        else -> type.name
    }

    companion object {
        val BACK = EdgeAction(EdgeActionType.BACK)
        val HOME = EdgeAction(EdgeActionType.HOME)
        val RECENTS = EdgeAction(EdgeActionType.RECENTS)
        val PREVIOUS_APP = EdgeAction(EdgeActionType.PREVIOUS_APP)
        val NOTIFICATIONS = EdgeAction(EdgeActionType.NOTIFICATIONS)
        val QUICK_SETTINGS = EdgeAction(EdgeActionType.QUICK_SETTINGS)
        val LOCK_SCREEN = EdgeAction(EdgeActionType.LOCK_SCREEN)
        val SCREENSHOT = EdgeAction(EdgeActionType.SCREENSHOT)
        val SPLIT_SCREEN = EdgeAction(EdgeActionType.SPLIT_SCREEN)
        val FLOAT_CURRENT_APP = EdgeAction(EdgeActionType.FLOAT_CURRENT_APP)
        val CIRCLE_TO_SEARCH = EdgeAction(EdgeActionType.CIRCLE_TO_SEARCH)
        val OPEN_QUICK_MENU = EdgeAction(EdgeActionType.OPEN_QUICK_MENU)
        val OPEN_RECENT_SWITCHER = EdgeAction(EdgeActionType.OPEN_RECENT_SWITCHER)
        val TOGGLE_TORCH = EdgeAction(EdgeActionType.TOGGLE_TORCH)
        val TOGGLE_SCREEN_TRANSLATE = EdgeAction(EdgeActionType.TOGGLE_SCREEN_TRANSLATE)
        val TRANSLATE_SCREEN_ONCE = EdgeAction(EdgeActionType.TRANSLATE_SCREEN_ONCE)
        val NONE = EdgeAction(EdgeActionType.NONE)

        fun launchApp(packageName: String): EdgeAction =
            EdgeAction(EdgeActionType.LAUNCH_APP, packageName = packageName.trim())

        fun launchAppFloating(packageName: String): EdgeAction =
            EdgeAction(EdgeActionType.LAUNCH_APP_FLOATING, packageName = packageName.trim())

        fun openUrl(url: String): EdgeAction =
            EdgeAction(EdgeActionType.OPEN_URL, targetData = url.trim())

        fun launchShortcut(packageName: String, intentUri: String): EdgeAction =
            EdgeAction(
                type = EdgeActionType.LAUNCH_SHORTCUT,
                packageName = packageName.trim(),
                targetData = intentUri.trim()
            )

        fun decode(raw: String?, fallback: EdgeAction): EdgeAction {
            if (raw.isNullOrBlank()) return fallback
            val colon = raw.indexOf(':')
            return if (colon > 0) {
                val typeName = raw.substring(0, colon).trim()
                val payload = raw.substring(colon + 1).trim()
                val type = parseType(typeName) ?: return fallback
                when (type) {
                    EdgeActionType.LAUNCH_APP, EdgeActionType.LAUNCH_APP_FLOATING -> {
                        if (payload.isEmpty()) fallback else EdgeAction(type, packageName = payload)
                    }
                    EdgeActionType.OPEN_URL -> {
                        val decodedUrl = decodeBase64Url(payload)
                        if (decodedUrl.isEmpty()) fallback else EdgeAction(type, targetData = decodedUrl)
                    }
                    EdgeActionType.LAUNCH_SHORTCUT -> {
                        val secondColon = payload.indexOf(':')
                        if (secondColon < 0) return fallback
                        val pkg = payload.substring(0, secondColon).trim()
                        val b64Uri = payload.substring(secondColon + 1).trim()
                        val uri = decodeBase64Url(b64Uri)
                        if (uri.isEmpty() && pkg.isEmpty()) {
                            fallback
                        } else {
                            EdgeAction(type, packageName = pkg, targetData = uri)
                        }
                    }
                    else -> EdgeAction(type)
                }
            } else {
                val type = parseType(raw.trim()) ?: return fallback
                if (type == EdgeActionType.LAUNCH_APP ||
                    type == EdgeActionType.LAUNCH_APP_FLOATING ||
                    type == EdgeActionType.OPEN_URL ||
                    type == EdgeActionType.LAUNCH_SHORTCUT
                ) {
                    fallback
                } else {
                    EdgeAction(type)
                }
            }
        }

        private fun encodeBase64Url(plain: String): String {
            if (plain.isEmpty()) return ""
            return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(plain.toByteArray(StandardCharsets.UTF_8))
        }

        private fun decodeBase64Url(encoded: String): String {
            if (encoded.isEmpty()) return ""
            return try {
                val bytes = Base64.getUrlDecoder().decode(encoded)
                String(bytes, StandardCharsets.UTF_8).trim()
            } catch (_: IllegalArgumentException) {
                ""
            }
        }

        private fun parseType(name: String): EdgeActionType? = try {
            EdgeActionType.valueOf(name)
        } catch (_: IllegalArgumentException) {
            null
        }
    }
}

data class QuickSlotConfig(
    val action: EdgeAction,
    val customLabel: String = ""
) {
    fun encode(): String {
        val safeLabel = customLabel.replace("|", " ").replace(";", " ").replace("\n", " ").trim()
        return "${action.encode()}|$safeLabel"
    }

    companion object {
        fun decode(raw: String): QuickSlotConfig? {
            if (raw.isBlank()) return null
            val pipe = raw.indexOf('|')
            val actionRaw = if (pipe >= 0) raw.substring(0, pipe) else raw
            val label = if (pipe >= 0) raw.substring(pipe + 1) else ""
            val action = EdgeAction.decode(actionRaw, EdgeAction.NONE)
            if (action.isNone) return null
            return QuickSlotConfig(action = action, customLabel = label)
        }
    }
}

enum class SecondaryTriggerMode {
    DISTANCE,
    HOLD,
    BOTH
}

/**
 * Side-edge visual feedback presentation style supporting custom transparent GIF/WebP/PNG media.
 */
enum class EdgeVisualStyle {
    /** Default translucent glass Bezier droplet / minimal indicator */
    DEFAULT_DROPLET,
    /** Transparent custom GIF/WebP/PNG slides directly from the edge with no droplet background */
    CUSTOM_MEDIA_ONLY,
    /** Translucent glass droplet & 1dp rim framing the custom GIF/WebP/PNG inside */
    DROPLET_WITH_CUSTOM_MEDIA
}

/**
 * User-selectable activation methods for One-Stroke Quick Arc Menu so it never
 * conflicts with hold or long-swipe gestures (while preserving the classic method).
 */
enum class QuickArcTriggerMethod {
    /** Dedicated independent sub-zone on left/right edge: 0ms instant one-stroke arc */
    DEDICATED_ZONE,
    /** Stationary press-and-hold directly on the edge bezel (0 conflict with inward swipes) */
    EDGE_STATIONARY_HOLD,
    /** Pull inward past Stage 1 then return toward the edge (0ms U-turn, 0 extra zone) */
    PULL_AND_RETURN,
    /** Classic assignment via normal gesture slots (long-swipe / hold / diagonal / etc.) */
    GESTURE_ACTION
}

enum class QuickArcZoneSide {
    BOTH,
    LEFT_ONLY,
    RIGHT_ONLY
}

enum class QuickMenuRetreatMode {
    RADIAL_ARC,
    EDGE_DEADZONE,
    CUSTOM_LINE
}

enum class FeedbackStyle {
    BEZIER_BUBBLE,
    MINIMAL_ARROW,
    HIDDEN
}

enum class VisualTheme {
    AUTO,
    DARK_GLASS,
    LIGHT_GLASS,
    MATERIAL_YOU
}

enum class HapticStrength {
    OFF,
    LIGHT,
    MEDIUM,
    STRONG
}

enum class ImeAvoidanceMode {
    SHIFT_UP,
    DISABLE_TOUCH,
    NONE
}

enum class LandscapeMode {
    DOUBLE_SWIPE_CONFIRM,
    DISABLE,
    KEEP_ENABLED
}

enum class LiveTranslateTrackingMode {
    SMART_AUTO,
    GAME_CONTINUOUS,
    MANUAL_ONCE
}

enum class LiveTranslateRefreshSpeed(val intervalMs: Long) {
    POWER_SAVER(1400L),
    BALANCED(900L),
    FAST(550L)
}

enum class LiveTranslateRenderStyle {
    ADAPTIVE_INPLACE,
    FROSTED_CARD
}

enum class LiveTranslateEngineMode {
    OFFLINE_MLKIT,
    ONLINE_HIGH_QUALITY
}

/**
 * Immutable lightweight configuration model shared between the resident gesture process
 * and the isolated :settings process. Uses a compact plain-text codec with zero reflection
 * or third-party serializer overhead.
 */
data class EdgeConfig(
    val leftEnabled: Boolean = true,
    val rightEnabled: Boolean = true,
    val bottomEnabled: Boolean = true,
    val edgeWidthDp: Float = DEFAULT_EDGE_WIDTH_DP,
    val edgeHeightPercent: Float = DEFAULT_EDGE_HEIGHT_PERCENT,
    val edgeBottomOffsetPercent: Float = DEFAULT_EDGE_BOTTOM_OFFSET_PERCENT,
    val bottomBarWidthPercent: Float = DEFAULT_BOTTOM_WIDTH_PERCENT,
    val bottomBarHeightDp: Float = DEFAULT_BOTTOM_HEIGHT_DP,
    val bottomBarBottomOffsetDp: Float = DEFAULT_BOTTOM_OFFSET_DP,
    val bottomBarHorizontalOffsetPercent: Float = DEFAULT_BOTTOM_HORIZONTAL_OFFSET_PERCENT,
    val swipeThresholdDp: Float = DEFAULT_SWIPE_THRESHOLD_DP,
    val diagonalAngleDeg: Float = DEFAULT_DIAGONAL_ANGLE_DEG,
    // Independent Side & Bottom trigger modes (DISTANCE / HOLD / BOTH)
    val sideTriggerMode: SecondaryTriggerMode = SecondaryTriggerMode.DISTANCE,
    val bottomTriggerMode: SecondaryTriggerMode = SecondaryTriggerMode.DISTANCE,
    // 3-Stage distance ratios (unlocked up to 85%)
    val sideStage2DistanceRatio: Float = DEFAULT_SIDE_STAGE2_DISTANCE_RATIO,
    val sideStage3DistanceRatio: Float = DEFAULT_SIDE_STAGE3_DISTANCE_RATIO,
    val bottomStage2DistanceRatio: Float = DEFAULT_BOTTOM_STAGE2_DISTANCE_RATIO,
    val bottomStage3DistanceRatio: Float = DEFAULT_BOTTOM_STAGE3_DISTANCE_RATIO,
    // 3-Stage hold duration thresholds (ms)
    val sideStage2HoldMs: Long = DEFAULT_STAGE2_HOLD_MS,
    val sideStage3HoldMs: Long = DEFAULT_STAGE3_HOLD_MS,
    val bottomStage2HoldMs: Long = DEFAULT_STAGE2_HOLD_MS,
    val bottomStage3HoldMs: Long = DEFAULT_STAGE3_HOLD_MS,
    // HyperOS Recents compatibility mode
    val hyperOsRecentsCompatMode: Boolean = false,
    val hyperOsRecentsDelayMs: Int = DEFAULT_HYPEROS_RECENTS_DELAY_MS,
    // Unified gesture actions (Stage 1 / Stage 2 / Stage 3 + diagonals)
    val horizontalAction: EdgeAction = EdgeAction.BACK,
    val horizontalHoldAction: EdgeAction = EdgeAction.RECENTS,
    val leftStage3Action: EdgeAction = EdgeAction.OPEN_QUICK_MENU,
    val rightStage3Action: EdgeAction = EdgeAction.OPEN_QUICK_MENU,
    val diagonalUpAction: EdgeAction = EdgeAction.FLOAT_CURRENT_APP,
    val diagonalDownAction: EdgeAction = EdgeAction.NONE,
    val bottomSwipeUpAction: EdgeAction = EdgeAction.HOME,
    val bottomSwipeHoldAction: EdgeAction = EdgeAction.RECENTS,
    val bottomStage3Action: EdgeAction = EdgeAction.FLOAT_CURRENT_APP,
    // Bottom long-press (Circle to Search default)
    val bottomLongPressEnabled: Boolean = true,
    val bottomLongPressDurationMs: Int = DEFAULT_BOTTOM_LONG_PRESS_MS,
    val bottomLongPressAction: EdgeAction = EdgeAction.CIRCLE_TO_SEARCH,
    // Translucent glass visuals & haptics
    val feedbackStyle: FeedbackStyle = FeedbackStyle.BEZIER_BUBBLE,
    val visualTheme: VisualTheme = VisualTheme.AUTO,
    val feedbackAlpha: Float = DEFAULT_FEEDBACK_ALPHA,
    val edgeGuideAlpha: Float = DEFAULT_EDGE_GUIDE_ALPHA,
    val bottomPillAlpha: Float = DEFAULT_BOTTOM_PILL_ALPHA,
    val hapticStrength: HapticStrength = HapticStrength.MEDIUM,
    // Apple iOS 26 Liquid Glass (API 33+ AGSL RuntimeShader + Zero-Copy HardwareBuffer)
    val liquidGlassEnabled: Boolean = true,
    val liquidGlassRefraction: Float = DEFAULT_LIQUID_GLASS_REFRACTION,
    // Droplet mountain shape proportions (native smooth curve)
    val dropletPeakWidthDp: Float = DEFAULT_DROPLET_PEAK_WIDTH_DP,
    val dropletBaseWidthDp: Float = DEFAULT_DROPLET_BASE_WIDTH_DP,
    // Custom side-edge GIF / WebP / PNG visual feedback
    val edgeVisualStyle: EdgeVisualStyle = EdgeVisualStyle.DEFAULT_DROPLET,
    val customMediaPath: String = "",
    val customMediaVersion: Long = 0L,
    val customMediaSizeDp: Int = DEFAULT_CUSTOM_MEDIA_SIZE_DP,
    val customMediaAlpha: Float = DEFAULT_CUSTOM_MEDIA_ALPHA,
    val customMediaMirrorRight: Boolean = true,
    // Quick Arc Menu & zero-conflict trigger methods
    val quickArcTriggerMethod: QuickArcTriggerMethod = QuickArcTriggerMethod.GESTURE_ACTION,
    val quickArcZoneSide: QuickArcZoneSide = QuickArcZoneSide.BOTH,
    val quickArcZoneHeightPercent: Float = DEFAULT_QUICK_ARC_ZONE_HEIGHT_PERCENT,
    val quickArcZoneBottomOffsetPercent: Float = DEFAULT_QUICK_ARC_ZONE_BOTTOM_OFFSET_PERCENT,
    val quickArcEdgeHoldMs: Int = DEFAULT_QUICK_ARC_EDGE_HOLD_MS,
    val quickMenuSlots: List<QuickSlotConfig> = DEFAULT_QUICK_MENU_SLOTS,
    val quickMenuRadiusDp: Float = DEFAULT_QUICK_MENU_RADIUS_DP,
    val quickMenuDimAlpha: Float = DEFAULT_QUICK_MENU_DIM_ALPHA,
    val quickMenuRetreatMode: QuickMenuRetreatMode = QuickMenuRetreatMode.RADIAL_ARC,
    val quickMenuRetreatBoundaryRatio: Float = DEFAULT_QUICK_MENU_RETREAT_BOUNDARY_RATIO,
    val quickMenuDeadZoneDp: Float = DEFAULT_QUICK_MENU_DEAD_ZONE_DP,
    val quickMenuRotarySpeed: Float = DEFAULT_QUICK_MENU_ROTARY_SPEED,
    // Smart avoidance
    val imeAvoidanceMode: ImeAvoidanceMode = ImeAvoidanceMode.SHIFT_UP,
    val showBottomBarDuringIme: Boolean = true,
    val landscapeMode: LandscapeMode = LandscapeMode.DOUBLE_SWIPE_CONFIRM,
    val blacklistedPackages: Set<String> = emptySet(),
    // Opt-in Shizuku 3-button navigation bar control & no-Shizuku floating window macro
    val autoHideNavBarWithShizuku: Boolean = false,
    val shizukuSystemNavModified: Boolean = false,
    val hideGestureLine: Boolean = true,
    val noShizukuControlCenterFloat: Boolean = true,
    val ccFloatTileNormX: Float = DEFAULT_CC_FLOAT_TILE_NORM_COORD,
    val ccFloatTileNormY: Float = DEFAULT_CC_FLOAT_TILE_NORM_COORD,
    val ccFloatTileDetectionMethod: String = CC_FLOAT_METHOD_AUTO,
    // Live Interactive Screen Translate (100% on-device ML Kit, touch-through, zero idle overhead)
    val translateSourceLang: String = DEFAULT_TRANSLATE_SOURCE_LANG,
    val translateTargetLang: String = DEFAULT_TRANSLATE_TARGET_LANG,
    val translateTrackingMode: LiveTranslateTrackingMode = LiveTranslateTrackingMode.MANUAL_ONCE,
    val translateAutoClearOnScroll: Boolean = true,
    val translateRefreshSpeed: LiveTranslateRefreshSpeed = LiveTranslateRefreshSpeed.BALANCED,
    val translateRenderStyle: LiveTranslateRenderStyle = LiveTranslateRenderStyle.ADAPTIVE_INPLACE,
    val translateOverlayAlpha: Float = DEFAULT_TRANSLATE_OVERLAY_ALPHA,
    val translateWifiOnlyDownload: Boolean = true,
    val translateSkipTargetLanguage: Boolean = true,
    val translateEngineMode: LiveTranslateEngineMode = LiveTranslateEngineMode.OFFLINE_MLKIT,
    val translateOnlineConsentAccepted: Boolean = false,
    // Native Circle to Search optional Shizuku Assistant auto-repair for CN ROMs
    val ctsAutoRepairAssistantWithShizuku: Boolean = true,
    // 4-Layer Safety: quiet foreground keep-alive notification & Shizuku auto-revive / nav recovery
    val keepAliveForegroundNotification: Boolean = true,
    val autoReviveWithShizuku: Boolean = true,
    // Cross-process synced app language tag ("", "en", "ja", "zh-CN", "hi")
    val appLanguageTag: String = ""
) {
    fun triggerModeFor(side: EdgeSide): SecondaryTriggerMode =
        if (side == EdgeSide.BOTTOM) bottomTriggerMode else sideTriggerMode

    fun stage3ActionFor(side: EdgeSide): EdgeAction = when (side) {
        EdgeSide.LEFT -> leftStage3Action
        EdgeSide.RIGHT -> rightStage3Action
        EdgeSide.BOTTOM -> bottomStage3Action
    }

    fun hasStage3For(side: EdgeSide): Boolean = !stage3ActionFor(side).isNone

    fun hasAnySideStage3(): Boolean = !leftStage3Action.isNone || !rightStage3Action.isNone

    fun hasCustomMedia(): Boolean = customMediaPath.isNotBlank()

    fun hasSavedCcFloatTileCoords(): Boolean =
        ccFloatTileNormX in CC_FLOAT_MIN_VALID_COORD..CC_FLOAT_MAX_VALID_COORD &&
            ccFloatTileNormY in CC_FLOAT_MIN_VALID_COORD..CC_FLOAT_MAX_VALID_COORD

    fun clamped(): EdgeConfig {
        val clampedHeight = edgeHeightPercent.coerceIn(0.12f, 0.90f)
        val maxOffset = (1f - clampedHeight).coerceAtLeast(0f)
        val clampedArcZoneHeight = quickArcZoneHeightPercent.coerceIn(0.08f, 0.40f)
        val maxArcZoneOffset = (1f - clampedArcZoneHeight).coerceAtLeast(0f)

        val clampedSideS2 = sideStage2DistanceRatio.coerceIn(
            MIN_SIDE_STAGE2_RATIO,
            MAX_STAGE2_DISTANCE_RATIO
        )
        val clampedSideS3 = max(
            sideStage3DistanceRatio.coerceIn(MIN_SIDE_STAGE3_RATIO, MAX_STAGE3_DISTANCE_RATIO),
            (clampedSideS2 + MIN_STAGE_DISTANCE_GAP).coerceAtMost(MAX_STAGE3_DISTANCE_RATIO)
        )

        val clampedBottomS2 = bottomStage2DistanceRatio.coerceIn(
            MIN_BOTTOM_STAGE2_RATIO,
            MAX_STAGE2_DISTANCE_RATIO
        )
        val clampedBottomS3 = max(
            bottomStage3DistanceRatio.coerceIn(MIN_BOTTOM_STAGE3_RATIO, MAX_STAGE3_DISTANCE_RATIO),
            (clampedBottomS2 + MIN_STAGE_DISTANCE_GAP).coerceAtMost(MAX_STAGE3_DISTANCE_RATIO)
        )

        val clampedSideHold2 = sideStage2HoldMs.coerceIn(MIN_STAGE2_HOLD_MS, MAX_STAGE2_HOLD_MS)
        val clampedSideHold3 = max(
            sideStage3HoldMs.coerceIn(MIN_STAGE3_HOLD_MS, MAX_STAGE3_HOLD_MS),
            (clampedSideHold2 + MIN_STAGE_HOLD_GAP_MS).coerceAtMost(MAX_STAGE3_HOLD_MS)
        )

        val clampedBottomHold2 = bottomStage2HoldMs.coerceIn(MIN_STAGE2_HOLD_MS, MAX_STAGE2_HOLD_MS)
        val clampedBottomHold3 = max(
            bottomStage3HoldMs.coerceIn(MIN_STAGE3_HOLD_MS, MAX_STAGE3_HOLD_MS),
            (clampedBottomHold2 + MIN_STAGE_HOLD_GAP_MS).coerceAtMost(MAX_STAGE3_HOLD_MS)
        )

        val validSlots = quickMenuSlots
            .filter { !it.action.isNone }
            .take(MAX_QUICK_MENU_SLOTS)
            .let { list ->
                if (list.size < MIN_QUICK_MENU_SLOTS) {
                    val padded = ArrayList<QuickSlotConfig>(MIN_QUICK_MENU_SLOTS)
                    padded.addAll(list)
                    for (def in DEFAULT_QUICK_MENU_SLOTS) {
                        if (padded.size >= MIN_QUICK_MENU_SLOTS) break
                        if (padded.none { it.action == def.action }) padded.add(def)
                    }
                    padded
                } else {
                    list
                }
            }

        return copy(
            edgeWidthDp = edgeWidthDp.coerceIn(8f, 36f),
            edgeHeightPercent = clampedHeight,
            edgeBottomOffsetPercent = edgeBottomOffsetPercent.coerceIn(0f, maxOffset),
            bottomBarWidthPercent = bottomBarWidthPercent.coerceIn(
                MIN_BOTTOM_WIDTH_PERCENT,
                MAX_BOTTOM_WIDTH_PERCENT
            ),
            bottomBarHeightDp = bottomBarHeightDp.coerceIn(
                MIN_BOTTOM_HEIGHT_DP,
                MAX_BOTTOM_HEIGHT_DP
            ),
            bottomBarBottomOffsetDp = bottomBarBottomOffsetDp.coerceIn(
                0f,
                MAX_BOTTOM_OFFSET_DP
            ),
            bottomBarHorizontalOffsetPercent = bottomBarHorizontalOffsetPercent.coerceIn(
                MIN_BOTTOM_HORIZONTAL_OFFSET_PERCENT,
                MAX_BOTTOM_HORIZONTAL_OFFSET_PERCENT
            ),
            swipeThresholdDp = swipeThresholdDp.coerceIn(16f, 56f),
            diagonalAngleDeg = diagonalAngleDeg.coerceIn(20f, 45f),
            sideStage2DistanceRatio = clampedSideS2,
            sideStage3DistanceRatio = clampedSideS3,
            bottomStage2DistanceRatio = clampedBottomS2,
            bottomStage3DistanceRatio = clampedBottomS3,
            sideStage2HoldMs = clampedSideHold2,
            sideStage3HoldMs = clampedSideHold3,
            bottomStage2HoldMs = clampedBottomHold2,
            bottomStage3HoldMs = clampedBottomHold3,
            hyperOsRecentsDelayMs = hyperOsRecentsDelayMs.coerceIn(50, 250),
            bottomLongPressDurationMs = bottomLongPressDurationMs.coerceIn(250, 800),
            feedbackAlpha = feedbackAlpha.coerceIn(0.0f, 1.0f),
            edgeGuideAlpha = edgeGuideAlpha.coerceIn(0.0f, 0.8f),
            bottomPillAlpha = bottomPillAlpha.coerceIn(0.0f, 1.0f),
            liquidGlassRefraction = liquidGlassRefraction.coerceIn(
                MIN_LIQUID_GLASS_REFRACTION,
                MAX_LIQUID_GLASS_REFRACTION
            ),
            dropletPeakWidthDp = dropletPeakWidthDp.coerceIn(
                MIN_DROPLET_PEAK_WIDTH_DP,
                MAX_DROPLET_PEAK_WIDTH_DP
            ),
            dropletBaseWidthDp = dropletBaseWidthDp.coerceIn(
                MIN_DROPLET_BASE_WIDTH_DP,
                MAX_DROPLET_BASE_WIDTH_DP
            ),
            customMediaPath = customMediaPath.replace("\n", "").trim(),
            customMediaVersion = customMediaVersion.coerceAtLeast(0L),
            customMediaSizeDp = customMediaSizeDp.coerceIn(MIN_CUSTOM_MEDIA_SIZE_DP, MAX_CUSTOM_MEDIA_SIZE_DP),
            customMediaAlpha = customMediaAlpha.coerceIn(MIN_CUSTOM_MEDIA_ALPHA, 1.0f),
            quickArcZoneHeightPercent = clampedArcZoneHeight,
            quickArcZoneBottomOffsetPercent = quickArcZoneBottomOffsetPercent.coerceIn(0f, maxArcZoneOffset),
            quickArcEdgeHoldMs = quickArcEdgeHoldMs.coerceIn(200, 650),
            quickMenuSlots = validSlots,
            quickMenuRadiusDp = quickMenuRadiusDp.coerceIn(84f, 156f),
            quickMenuDimAlpha = quickMenuDimAlpha.coerceIn(0.0f, 0.4f),
            quickMenuRetreatBoundaryRatio = quickMenuRetreatBoundaryRatio.coerceIn(
                MIN_QUICK_MENU_RETREAT_RATIO,
                MAX_QUICK_MENU_RETREAT_RATIO
            ),
            quickMenuDeadZoneDp = quickMenuDeadZoneDp.coerceIn(
                MIN_QUICK_MENU_DEAD_ZONE_DP,
                MAX_QUICK_MENU_DEAD_ZONE_DP
            ),
            quickMenuRotarySpeed = quickMenuRotarySpeed.coerceIn(
                MIN_QUICK_MENU_ROTARY_SPEED,
                MAX_QUICK_MENU_ROTARY_SPEED
            ),
            ccFloatTileNormX = if (isInvalidCcCoordPair(ccFloatTileNormX, ccFloatTileNormY)) {
                DEFAULT_CC_FLOAT_TILE_NORM_COORD
            } else {
                ccFloatTileNormX.coerceIn(CC_FLOAT_MIN_VALID_COORD, CC_FLOAT_MAX_VALID_COORD)
            },
            ccFloatTileNormY = if (isInvalidCcCoordPair(ccFloatTileNormX, ccFloatTileNormY)) {
                DEFAULT_CC_FLOAT_TILE_NORM_COORD
            } else {
                ccFloatTileNormY.coerceIn(CC_FLOAT_MIN_VALID_COORD, CC_FLOAT_MAX_VALID_COORD)
            },
            ccFloatTileDetectionMethod = sanitizeCcFloatMethod(ccFloatTileDetectionMethod),
            translateSourceLang = TranslateLanguageCatalog.sanitizeSourceCode(translateSourceLang),
            translateTargetLang = TranslateLanguageCatalog.sanitizeTargetCode(translateTargetLang),
            translateOverlayAlpha = translateOverlayAlpha.coerceIn(
                MIN_TRANSLATE_OVERLAY_ALPHA,
                MAX_TRANSLATE_OVERLAY_ALPHA
            ),
            translateEngineMode = if (translateOnlineConsentAccepted) {
                translateEngineMode
            } else {
                LiveTranslateEngineMode.OFFLINE_MLKIT
            },
            translateOnlineConsentAccepted = translateOnlineConsentAccepted,
            appLanguageTag = appLanguageTag.replace("\n", "").trim()
        )
    }

    fun serialize(): String {
        val sb = StringBuilder(1536)
        sb.append("leftEnabled=").append(leftEnabled).append('\n')
        sb.append("rightEnabled=").append(rightEnabled).append('\n')
        sb.append("bottomEnabled=").append(bottomEnabled).append('\n')
        sb.append("edgeWidthDp=").append(edgeWidthDp).append('\n')
        sb.append("edgeHeightPercent=").append(edgeHeightPercent).append('\n')
        sb.append("edgeBottomOffsetPercent=").append(edgeBottomOffsetPercent).append('\n')
        sb.append("bottomBarWidthPercent=").append(bottomBarWidthPercent).append('\n')
        sb.append("bottomBarHeightDp=").append(bottomBarHeightDp).append('\n')
        sb.append("bottomBarBottomOffsetDp=").append(bottomBarBottomOffsetDp).append('\n')
        sb.append("bottomBarHorizontalOffsetPercent=").append(bottomBarHorizontalOffsetPercent).append('\n')
        sb.append("swipeThresholdDp=").append(swipeThresholdDp).append('\n')
        sb.append("diagonalAngleDeg=").append(diagonalAngleDeg).append('\n')
        // Split trigger modes & 3-stage thresholds
        sb.append("sideTriggerMode=").append(sideTriggerMode.name).append('\n')
        sb.append("bottomTriggerMode=").append(bottomTriggerMode.name).append('\n')
        sb.append("sideStage2DistanceRatio=").append(sideStage2DistanceRatio).append('\n')
        sb.append("sideStage3DistanceRatio=").append(sideStage3DistanceRatio).append('\n')
        sb.append("bottomStage2DistanceRatio=").append(bottomStage2DistanceRatio).append('\n')
        sb.append("bottomStage3DistanceRatio=").append(bottomStage3DistanceRatio).append('\n')
        sb.append("sideStage2HoldMs=").append(sideStage2HoldMs).append('\n')
        sb.append("sideStage3HoldMs=").append(sideStage3HoldMs).append('\n')
        sb.append("bottomStage2HoldMs=").append(bottomStage2HoldMs).append('\n')
        sb.append("bottomStage3HoldMs=").append(bottomStage3HoldMs).append('\n')
        // Legacy mirror keys for backwards compatibility
        sb.append("secondaryTriggerMode=").append(sideTriggerMode.name).append('\n')
        sb.append("horizontalLongSwipeRatio=").append(sideStage2DistanceRatio).append('\n')
        sb.append("bottomLongSwipeRatio=").append(bottomStage2DistanceRatio).append('\n')
        sb.append("holdDurationMs=").append(sideStage2HoldMs).append('\n')

        sb.append("hyperOsRecentsCompatMode=").append(hyperOsRecentsCompatMode).append('\n')
        sb.append("hyperOsRecentsDelayMs=").append(hyperOsRecentsDelayMs).append('\n')
        sb.append("horizontalAction=").append(horizontalAction.encode()).append('\n')
        sb.append("horizontalHoldAction=").append(horizontalHoldAction.encode()).append('\n')
        sb.append("leftStage3Action=").append(leftStage3Action.encode()).append('\n')
        sb.append("rightStage3Action=").append(rightStage3Action.encode()).append('\n')
        sb.append("diagonalUpAction=").append(diagonalUpAction.encode()).append('\n')
        sb.append("diagonalDownAction=").append(diagonalDownAction.encode()).append('\n')
        sb.append("bottomSwipeUpAction=").append(bottomSwipeUpAction.encode()).append('\n')
        sb.append("bottomSwipeHoldAction=").append(bottomSwipeHoldAction.encode()).append('\n')
        sb.append("bottomStage3Action=").append(bottomStage3Action.encode()).append('\n')
        sb.append("bottomLongPressEnabled=").append(bottomLongPressEnabled).append('\n')
        sb.append("bottomLongPressDurationMs=").append(bottomLongPressDurationMs).append('\n')
        sb.append("bottomLongPressAction=").append(bottomLongPressAction.encode()).append('\n')
        sb.append("feedbackStyle=").append(feedbackStyle.name).append('\n')
        sb.append("visualTheme=").append(visualTheme.name).append('\n')
        sb.append("feedbackAlpha=").append(feedbackAlpha).append('\n')
        sb.append("edgeGuideAlpha=").append(edgeGuideAlpha).append('\n')
        sb.append("bottomPillAlpha=").append(bottomPillAlpha).append('\n')
        sb.append("hapticStrength=").append(hapticStrength.name).append('\n')
        sb.append("liquidGlassEnabled=").append(liquidGlassEnabled).append('\n')
        sb.append("liquidGlassRefraction=").append(liquidGlassRefraction).append('\n')
        sb.append("dropletPeakWidthDp=").append(dropletPeakWidthDp).append('\n')
        sb.append("dropletBaseWidthDp=").append(dropletBaseWidthDp).append('\n')
        sb.append("edgeVisualStyle=").append(edgeVisualStyle.name).append('\n')
        sb.append("customMediaPath=").append(customMediaPath).append('\n')
        sb.append("customMediaVersion=").append(customMediaVersion).append('\n')
        sb.append("customMediaSizeDp=").append(customMediaSizeDp).append('\n')
        sb.append("customMediaAlpha=").append(customMediaAlpha).append('\n')
        sb.append("customMediaMirrorRight=").append(customMediaMirrorRight).append('\n')
        sb.append("quickArcTriggerMethod=").append(quickArcTriggerMethod.name).append('\n')
        sb.append("quickArcZoneSide=").append(quickArcZoneSide.name).append('\n')
        sb.append("quickArcZoneHeightPercent=").append(quickArcZoneHeightPercent).append('\n')
        sb.append("quickArcZoneBottomOffsetPercent=").append(quickArcZoneBottomOffsetPercent).append('\n')
        sb.append("quickArcEdgeHoldMs=").append(quickArcEdgeHoldMs).append('\n')
        sb.append("quickMenuRadiusDp=").append(quickMenuRadiusDp).append('\n')
        sb.append("quickMenuDimAlpha=").append(quickMenuDimAlpha).append('\n')
        sb.append("quickMenuRetreatMode=").append(quickMenuRetreatMode.name).append('\n')
        sb.append("quickMenuRetreatBoundaryRatio=").append(quickMenuRetreatBoundaryRatio).append('\n')
        sb.append("quickMenuDeadZoneDp=").append(quickMenuDeadZoneDp).append('\n')
        sb.append("quickMenuRotarySpeed=").append(quickMenuRotarySpeed).append('\n')
        sb.append("quickMenuSlots=")
        for (i in quickMenuSlots.indices) {
            if (i > 0) sb.append(';')
            sb.append(quickMenuSlots[i].encode())
        }
        sb.append('\n')
        sb.append("imeAvoidanceMode=").append(imeAvoidanceMode.name).append('\n')
        sb.append("showBottomBarDuringIme=").append(showBottomBarDuringIme).append('\n')
        sb.append("landscapeMode=").append(landscapeMode.name).append('\n')
        sb.append("blacklistedPackages=")
        var first = true
        for (pkg in blacklistedPackages) {
            val clean = pkg.trim()
            if (clean.isEmpty()) continue
            if (!first) sb.append(',')
            sb.append(clean)
            first = false
        }
        sb.append('\n')
        sb.append("autoHideNavBarWithShizuku=").append(autoHideNavBarWithShizuku).append('\n')
        sb.append("shizukuSystemNavModified=").append(shizukuSystemNavModified).append('\n')
        sb.append("hideGestureLine=").append(hideGestureLine).append('\n')
        sb.append("noShizukuControlCenterFloat=").append(noShizukuControlCenterFloat).append('\n')
        sb.append("ccFloatTileNormX=").append(ccFloatTileNormX).append('\n')
        sb.append("ccFloatTileNormY=").append(ccFloatTileNormY).append('\n')
        sb.append("ccFloatTileDetectionMethod=").append(ccFloatTileDetectionMethod).append('\n')
        sb.append("translateSourceLang=").append(translateSourceLang).append('\n')
        sb.append("translateTargetLang=").append(translateTargetLang).append('\n')
        sb.append("translateTrackingMode=").append(translateTrackingMode.name).append('\n')
        sb.append("translateAutoClearOnScroll=").append(translateAutoClearOnScroll).append('\n')
        sb.append("translateRefreshSpeed=").append(translateRefreshSpeed.name).append('\n')
        sb.append("translateRenderStyle=").append(translateRenderStyle.name).append('\n')
        sb.append("translateOverlayAlpha=").append(translateOverlayAlpha).append('\n')
        sb.append("translateWifiOnlyDownload=").append(translateWifiOnlyDownload).append('\n')
        sb.append("translateSkipTargetLanguage=").append(translateSkipTargetLanguage).append('\n')
        sb.append("translateEngineMode=").append(translateEngineMode.name).append('\n')
        sb.append("translateOnlineConsentAccepted=").append(translateOnlineConsentAccepted).append('\n')
        sb.append("ctsAutoRepairAssistantWithShizuku=").append(ctsAutoRepairAssistantWithShizuku).append('\n')
        sb.append("keepAliveForegroundNotification=").append(keepAliveForegroundNotification).append('\n')
        sb.append("autoReviveWithShizuku=").append(autoReviveWithShizuku).append('\n')
        sb.append("appLanguageTag=").append(appLanguageTag).append('\n')
        return sb.toString()
    }

    companion object {
        const val DEFAULT_CC_FLOAT_TILE_NORM_COORD = -1f
        const val CC_FLOAT_MIN_VALID_COORD = 0.02f
        const val CC_FLOAT_MAX_VALID_COORD = 0.98f
        const val CC_TILE_METHOD_AUTO = "AUTO"
        const val CC_TILE_METHOD_NODE = "NODE"
        const val CC_TILE_METHOD_OCR = "OCR"
        const val CC_TILE_METHOD_MANUAL_TAP = "MANUAL_TAP"
        const val CC_FLOAT_METHOD_AUTO = CC_TILE_METHOD_AUTO
        const val CC_FLOAT_METHOD_OCR = CC_TILE_METHOD_OCR
        const val CC_FLOAT_METHOD_MANUAL = CC_TILE_METHOD_MANUAL_TAP

        fun isInvalidCcCoordPair(x: Float, y: Float): Boolean =
            x.isNaN() || y.isNaN() ||
                x !in CC_FLOAT_MIN_VALID_COORD..CC_FLOAT_MAX_VALID_COORD ||
                y !in CC_FLOAT_MIN_VALID_COORD..CC_FLOAT_MAX_VALID_COORD

        fun sanitizeCcFloatCoord(value: Float): Float {
            if (value.isNaN() || value < 0f) return DEFAULT_CC_FLOAT_TILE_NORM_COORD
            return value.coerceIn(CC_FLOAT_MIN_VALID_COORD, CC_FLOAT_MAX_VALID_COORD)
        }

        fun sanitizeCcFloatMethod(raw: String?): String = when (raw?.trim()?.uppercase()) {
            CC_TILE_METHOD_NODE -> CC_TILE_METHOD_NODE
            CC_TILE_METHOD_OCR -> CC_TILE_METHOD_OCR
            CC_TILE_METHOD_MANUAL_TAP, "MANUAL" -> CC_TILE_METHOD_MANUAL_TAP
            else -> CC_TILE_METHOD_AUTO
        }

        const val DEFAULT_EDGE_WIDTH_DP = 15f
        const val DEFAULT_EDGE_HEIGHT_PERCENT = 0.83f
        const val DEFAULT_EDGE_BOTTOM_OFFSET_PERCENT = 0.03f
        const val DEFAULT_BOTTOM_WIDTH_PERCENT = 0.36f
        const val MIN_BOTTOM_WIDTH_PERCENT = 0.15f
        const val MAX_BOTTOM_WIDTH_PERCENT = 0.90f
        const val DEFAULT_BOTTOM_HEIGHT_DP = 35f
        const val MIN_BOTTOM_HEIGHT_DP = 10f
        const val MAX_BOTTOM_HEIGHT_DP = 48f
        const val DEFAULT_BOTTOM_OFFSET_DP = 4f
        const val MAX_BOTTOM_OFFSET_DP = 32f
        const val DEFAULT_BOTTOM_HORIZONTAL_OFFSET_PERCENT = 0f
        const val MIN_BOTTOM_HORIZONTAL_OFFSET_PERCENT = -0.30f
        const val MAX_BOTTOM_HORIZONTAL_OFFSET_PERCENT = 0.30f
        const val DEFAULT_SWIPE_THRESHOLD_DP = 28f
        const val DEFAULT_DIAGONAL_ANGLE_DEG = 34f

        const val DEFAULT_SIDE_STAGE2_DISTANCE_RATIO = 0.64f
        const val DEFAULT_SIDE_STAGE3_DISTANCE_RATIO = 0.79f
        const val DEFAULT_BOTTOM_STAGE2_DISTANCE_RATIO = 0.27f
        const val DEFAULT_BOTTOM_STAGE3_DISTANCE_RATIO = 0.50f

        const val MIN_SIDE_STAGE2_RATIO = 0.12f
        const val MIN_SIDE_STAGE3_RATIO = 0.20f
        const val MIN_BOTTOM_STAGE2_RATIO = 0.10f
        const val MIN_BOTTOM_STAGE3_RATIO = 0.18f
        const val MAX_STAGE2_DISTANCE_RATIO = 0.75f
        const val MAX_STAGE3_DISTANCE_RATIO = 0.85f
        const val MIN_STAGE_DISTANCE_GAP = 0.08f

        const val DEFAULT_STAGE2_HOLD_MS = 280L
        const val DEFAULT_STAGE3_HOLD_MS = 650L
        const val MIN_STAGE2_HOLD_MS = 150L
        const val MAX_STAGE2_HOLD_MS = 800L
        const val MIN_STAGE3_HOLD_MS = 350L
        const val MAX_STAGE3_HOLD_MS = 1500L
        const val MIN_STAGE_HOLD_GAP_MS = 150L

        const val DEFAULT_HYPEROS_RECENTS_DELAY_MS = 110
        const val DEFAULT_BOTTOM_LONG_PRESS_MS = 300
        const val DEFAULT_FEEDBACK_ALPHA = 0.60f
        const val DEFAULT_EDGE_GUIDE_ALPHA = 0.0f
        const val DEFAULT_BOTTOM_PILL_ALPHA = 0.78f
        const val DEFAULT_LIQUID_GLASS_REFRACTION = 1.50f
        const val MIN_LIQUID_GLASS_REFRACTION = 0.2f
        const val MAX_LIQUID_GLASS_REFRACTION = 1.5f
        const val DEFAULT_DROPLET_PEAK_WIDTH_DP = 34f
        const val MIN_DROPLET_PEAK_WIDTH_DP = 18f
        const val MAX_DROPLET_PEAK_WIDTH_DP = 56f
        const val DEFAULT_DROPLET_BASE_WIDTH_DP = 173f
        const val MIN_DROPLET_BASE_WIDTH_DP = 72f
        const val MAX_DROPLET_BASE_WIDTH_DP = 200f

        const val DEFAULT_CUSTOM_MEDIA_SIZE_DP = 56
        const val MIN_CUSTOM_MEDIA_SIZE_DP = 32
        const val MAX_CUSTOM_MEDIA_SIZE_DP = 96
        const val DEFAULT_CUSTOM_MEDIA_ALPHA = 0.95f
        const val MIN_CUSTOM_MEDIA_ALPHA = 0.20f

        const val DEFAULT_QUICK_ARC_ZONE_HEIGHT_PERCENT = 0.18f
        const val DEFAULT_QUICK_ARC_ZONE_BOTTOM_OFFSET_PERCENT = 0.67f
        const val DEFAULT_QUICK_ARC_EDGE_HOLD_MS = 300
        const val DEFAULT_QUICK_MENU_RADIUS_DP = 142f
        const val DEFAULT_QUICK_MENU_DIM_ALPHA = 0.30f
        const val DEFAULT_QUICK_MENU_RETREAT_BOUNDARY_RATIO = 0.42f
        const val MIN_QUICK_MENU_RETREAT_RATIO = 0.10f
        const val MAX_QUICK_MENU_RETREAT_RATIO = 0.72f
        const val DEFAULT_QUICK_MENU_DEAD_ZONE_DP = 36f
        const val MIN_QUICK_MENU_DEAD_ZONE_DP = 20f
        const val MAX_QUICK_MENU_DEAD_ZONE_DP = 64f
        const val DEFAULT_QUICK_MENU_ROTARY_SPEED = 1.0f
        const val MIN_QUICK_MENU_ROTARY_SPEED = 0.5f
        const val MAX_QUICK_MENU_ROTARY_SPEED = 2.0f
        const val MIN_QUICK_MENU_SLOTS = 3
        const val MAX_QUICK_MENU_SLOTS = 16

        const val DEFAULT_TRANSLATE_SOURCE_LANG = "auto"
        const val DEFAULT_TRANSLATE_TARGET_LANG = "ja"
        const val DEFAULT_TRANSLATE_OVERLAY_ALPHA = 0.98f
        const val MIN_TRANSLATE_OVERLAY_ALPHA = 0.55f
        const val MAX_TRANSLATE_OVERLAY_ALPHA = 1.0f

        val DEFAULT_QUICK_MENU_SLOTS: List<QuickSlotConfig> = listOf(
            QuickSlotConfig(EdgeAction.RECENTS),
            QuickSlotConfig(EdgeAction.HOME),
            QuickSlotConfig(EdgeAction.SCREENSHOT),
            QuickSlotConfig(EdgeAction.FLOAT_CURRENT_APP),
            QuickSlotConfig(EdgeAction.TRANSLATE_SCREEN_ONCE)
        )

        fun deserialize(raw: String?): EdgeConfig {
            if (raw.isNullOrBlank()) return EdgeConfig()
            val map = HashMap<String, String>(64)
            raw.lineSequence().forEach { line ->
                val idx = line.indexOf('=')
                if (idx > 0) {
                    val key = line.substring(0, idx).trim()
                    val value = line.substring(idx + 1).trim()
                    map[key] = value
                }
            }
            val blacklistRaw = map["blacklistedPackages"].orEmpty()
            val blacklist = if (blacklistRaw.isEmpty()) {
                emptySet()
            } else {
                blacklistRaw.split(',').mapNotNullTo(LinkedHashSet()) { item ->
                    item.trim().takeIf { it.isNotEmpty() }
                }
            }

            val slotsRaw = map["quickMenuSlots"]
            val slots = if (slotsRaw.isNullOrBlank()) {
                DEFAULT_QUICK_MENU_SLOTS
            } else {
                slotsRaw.split(';').mapNotNull { QuickSlotConfig.decode(it) }
                    .ifEmpty { DEFAULT_QUICK_MENU_SLOTS }
            }

            val legacyTheme = when (map["feedbackTheme"]) {
                "DARK" -> VisualTheme.DARK_GLASS
                "LIGHT" -> VisualTheme.LIGHT_GLASS
                "MATERIAL_YOU" -> VisualTheme.MATERIAL_YOU
                else -> VisualTheme.AUTO
            }

            // Seamless migration from legacy shared trigger mode / distance / hold keys
            val legacyTriggerMode = parseEnum(map["secondaryTriggerMode"], SecondaryTriggerMode.DISTANCE)
            val legacyHoldMs = map["holdDurationMs"]?.toLongOrNull() ?: DEFAULT_STAGE2_HOLD_MS
            val sideStage2Ratio = map["sideStage2DistanceRatio"]?.toFloatOrNull()
                ?: map["horizontalLongSwipeRatio"]?.toFloatOrNull()
                ?: DEFAULT_SIDE_STAGE2_DISTANCE_RATIO
            val bottomStage2Ratio = map["bottomStage2DistanceRatio"]?.toFloatOrNull()
                ?: map["bottomLongSwipeRatio"]?.toFloatOrNull()
                ?: DEFAULT_BOTTOM_STAGE2_DISTANCE_RATIO

            return EdgeConfig(
                leftEnabled = map["leftEnabled"]?.toBooleanStrictOrNull() ?: true,
                rightEnabled = map["rightEnabled"]?.toBooleanStrictOrNull() ?: true,
                bottomEnabled = map["bottomEnabled"]?.toBooleanStrictOrNull() ?: true,
                edgeWidthDp = map["edgeWidthDp"]?.toFloatOrNull() ?: DEFAULT_EDGE_WIDTH_DP,
                edgeHeightPercent = map["edgeHeightPercent"]?.toFloatOrNull() ?: DEFAULT_EDGE_HEIGHT_PERCENT,
                edgeBottomOffsetPercent = map["edgeBottomOffsetPercent"]?.toFloatOrNull()
                    ?: DEFAULT_EDGE_BOTTOM_OFFSET_PERCENT,
                bottomBarWidthPercent = map["bottomBarWidthPercent"]?.toFloatOrNull()
                    ?: DEFAULT_BOTTOM_WIDTH_PERCENT,
                bottomBarHeightDp = map["bottomBarHeightDp"]?.toFloatOrNull()
                    ?: DEFAULT_BOTTOM_HEIGHT_DP,
                bottomBarBottomOffsetDp = map["bottomBarBottomOffsetDp"]?.toFloatOrNull()
                    ?: DEFAULT_BOTTOM_OFFSET_DP,
                bottomBarHorizontalOffsetPercent = map["bottomBarHorizontalOffsetPercent"]?.toFloatOrNull()
                    ?: DEFAULT_BOTTOM_HORIZONTAL_OFFSET_PERCENT,
                swipeThresholdDp = map["swipeThresholdDp"]?.toFloatOrNull()
                    ?: DEFAULT_SWIPE_THRESHOLD_DP,
                diagonalAngleDeg = map["diagonalAngleDeg"]?.toFloatOrNull()
                    ?: DEFAULT_DIAGONAL_ANGLE_DEG,
                sideTriggerMode = parseEnum(map["sideTriggerMode"], legacyTriggerMode),
                bottomTriggerMode = parseEnum(map["bottomTriggerMode"], legacyTriggerMode),
                sideStage2DistanceRatio = sideStage2Ratio,
                sideStage3DistanceRatio = map["sideStage3DistanceRatio"]?.toFloatOrNull()
                    ?: DEFAULT_SIDE_STAGE3_DISTANCE_RATIO,
                bottomStage2DistanceRatio = bottomStage2Ratio,
                bottomStage3DistanceRatio = map["bottomStage3DistanceRatio"]?.toFloatOrNull()
                    ?: DEFAULT_BOTTOM_STAGE3_DISTANCE_RATIO,
                sideStage2HoldMs = map["sideStage2HoldMs"]?.toLongOrNull() ?: legacyHoldMs,
                sideStage3HoldMs = map["sideStage3HoldMs"]?.toLongOrNull() ?: DEFAULT_STAGE3_HOLD_MS,
                bottomStage2HoldMs = map["bottomStage2HoldMs"]?.toLongOrNull() ?: legacyHoldMs,
                bottomStage3HoldMs = map["bottomStage3HoldMs"]?.toLongOrNull() ?: DEFAULT_STAGE3_HOLD_MS,
                hyperOsRecentsCompatMode = map["hyperOsRecentsCompatMode"]?.toBooleanStrictOrNull()
                    ?: false,
                hyperOsRecentsDelayMs = map["hyperOsRecentsDelayMs"]?.toIntOrNull()
                    ?: DEFAULT_HYPEROS_RECENTS_DELAY_MS,
                horizontalAction = EdgeAction.decode(map["horizontalAction"], EdgeAction.BACK),
                horizontalHoldAction = EdgeAction.decode(
                    map["horizontalHoldAction"],
                    EdgeAction.RECENTS
                ),
                leftStage3Action = EdgeAction.decode(map["leftStage3Action"], EdgeAction.OPEN_QUICK_MENU),
                rightStage3Action = EdgeAction.decode(map["rightStage3Action"], EdgeAction.OPEN_QUICK_MENU),
                diagonalUpAction = EdgeAction.decode(map["diagonalUpAction"], EdgeAction.FLOAT_CURRENT_APP),
                diagonalDownAction = EdgeAction.decode(
                    map["diagonalDownAction"],
                    EdgeAction.NONE
                ),
                bottomSwipeUpAction = EdgeAction.decode(map["bottomSwipeUpAction"], EdgeAction.HOME),
                bottomSwipeHoldAction = EdgeAction.decode(
                    map["bottomSwipeHoldAction"],
                    EdgeAction.RECENTS
                ),
                bottomStage3Action = EdgeAction.decode(map["bottomStage3Action"], EdgeAction.FLOAT_CURRENT_APP),
                bottomLongPressEnabled = map["bottomLongPressEnabled"]?.toBooleanStrictOrNull() ?: true,
                bottomLongPressDurationMs = map["bottomLongPressDurationMs"]?.toIntOrNull()
                    ?: DEFAULT_BOTTOM_LONG_PRESS_MS,
                bottomLongPressAction = EdgeAction.decode(
                    map["bottomLongPressAction"],
                    EdgeAction.CIRCLE_TO_SEARCH
                ),
                feedbackStyle = parseEnum(map["feedbackStyle"], FeedbackStyle.BEZIER_BUBBLE),
                visualTheme = parseEnum(map["visualTheme"], legacyTheme),
                feedbackAlpha = map["feedbackAlpha"]?.toFloatOrNull() ?: DEFAULT_FEEDBACK_ALPHA,
                edgeGuideAlpha = map["edgeGuideAlpha"]?.toFloatOrNull() ?: DEFAULT_EDGE_GUIDE_ALPHA,
                bottomPillAlpha = map["bottomPillAlpha"]?.toFloatOrNull() ?: DEFAULT_BOTTOM_PILL_ALPHA,
                hapticStrength = parseEnum(map["hapticStrength"], HapticStrength.MEDIUM),
                liquidGlassEnabled = map["liquidGlassEnabled"]?.toBooleanStrictOrNull() ?: true,
                liquidGlassRefraction = map["liquidGlassRefraction"]?.toFloatOrNull()
                    ?: DEFAULT_LIQUID_GLASS_REFRACTION,
                dropletPeakWidthDp = map["dropletPeakWidthDp"]?.toFloatOrNull()
                    ?: DEFAULT_DROPLET_PEAK_WIDTH_DP,
                dropletBaseWidthDp = map["dropletBaseWidthDp"]?.toFloatOrNull()
                    ?: DEFAULT_DROPLET_BASE_WIDTH_DP,
                edgeVisualStyle = parseEnum(map["edgeVisualStyle"], EdgeVisualStyle.DEFAULT_DROPLET),
                customMediaPath = map["customMediaPath"].orEmpty(),
                customMediaVersion = map["customMediaVersion"]?.toLongOrNull() ?: 0L,
                customMediaSizeDp = map["customMediaSizeDp"]?.toIntOrNull()
                    ?: DEFAULT_CUSTOM_MEDIA_SIZE_DP,
                customMediaAlpha = map["customMediaAlpha"]?.toFloatOrNull()
                    ?: DEFAULT_CUSTOM_MEDIA_ALPHA,
                customMediaMirrorRight = map["customMediaMirrorRight"]?.toBooleanStrictOrNull() ?: true,
                quickArcTriggerMethod = parseEnum(
                    map["quickArcTriggerMethod"],
                    QuickArcTriggerMethod.GESTURE_ACTION
                ),
                quickArcZoneSide = parseEnum(
                    map["quickArcZoneSide"],
                    QuickArcZoneSide.BOTH
                ),
                quickArcZoneHeightPercent = map["quickArcZoneHeightPercent"]?.toFloatOrNull()
                    ?: DEFAULT_QUICK_ARC_ZONE_HEIGHT_PERCENT,
                quickArcZoneBottomOffsetPercent = map["quickArcZoneBottomOffsetPercent"]?.toFloatOrNull()
                    ?: DEFAULT_QUICK_ARC_ZONE_BOTTOM_OFFSET_PERCENT,
                quickArcEdgeHoldMs = map["quickArcEdgeHoldMs"]?.toIntOrNull()
                    ?: DEFAULT_QUICK_ARC_EDGE_HOLD_MS,
                quickMenuSlots = slots,
                quickMenuRadiusDp = map["quickMenuRadiusDp"]?.toFloatOrNull()
                    ?: DEFAULT_QUICK_MENU_RADIUS_DP,
                quickMenuDimAlpha = map["quickMenuDimAlpha"]?.toFloatOrNull()
                    ?: DEFAULT_QUICK_MENU_DIM_ALPHA,
                quickMenuRetreatMode = parseEnum(
                    map["quickMenuRetreatMode"],
                    QuickMenuRetreatMode.RADIAL_ARC
                ),
                quickMenuRetreatBoundaryRatio = map["quickMenuRetreatBoundaryRatio"]?.toFloatOrNull()
                    ?: DEFAULT_QUICK_MENU_RETREAT_BOUNDARY_RATIO,
                quickMenuDeadZoneDp = map["quickMenuDeadZoneDp"]?.toFloatOrNull()
                    ?: DEFAULT_QUICK_MENU_DEAD_ZONE_DP,
                quickMenuRotarySpeed = map["quickMenuRotarySpeed"]?.toFloatOrNull()
                    ?: DEFAULT_QUICK_MENU_ROTARY_SPEED,
                imeAvoidanceMode = parseEnum(map["imeAvoidanceMode"], ImeAvoidanceMode.SHIFT_UP),
                showBottomBarDuringIme = map["showBottomBarDuringIme"]?.toBooleanStrictOrNull()
                    ?: true,
                landscapeMode = parseEnum(map["landscapeMode"], LandscapeMode.DOUBLE_SWIPE_CONFIRM),
                blacklistedPackages = blacklist,
                autoHideNavBarWithShizuku = map["autoHideNavBarWithShizuku"]?.toBooleanStrictOrNull()
                    ?: false,
                shizukuSystemNavModified = map["shizukuSystemNavModified"]?.toBooleanStrictOrNull()
                    ?: false,
                hideGestureLine = map["hideGestureLine"]?.toBooleanStrictOrNull() ?: true,
                noShizukuControlCenterFloat = map["noShizukuControlCenterFloat"]?.toBooleanStrictOrNull()
                    ?: true,
                ccFloatTileNormX = map["ccFloatTileNormX"]?.toFloatOrNull()
                    ?: DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                ccFloatTileNormY = map["ccFloatTileNormY"]?.toFloatOrNull()
                    ?: DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                ccFloatTileDetectionMethod = sanitizeCcFloatMethod(map["ccFloatTileDetectionMethod"]),
                translateSourceLang = map["translateSourceLang"] ?: DEFAULT_TRANSLATE_SOURCE_LANG,
                translateTargetLang = map["translateTargetLang"] ?: DEFAULT_TRANSLATE_TARGET_LANG,
                translateTrackingMode = parseEnum(
                    map["translateTrackingMode"],
                    LiveTranslateTrackingMode.MANUAL_ONCE
                ),
                translateAutoClearOnScroll = map["translateAutoClearOnScroll"]?.toBooleanStrictOrNull()
                    ?: true,
                translateRefreshSpeed = parseEnum(
                    map["translateRefreshSpeed"],
                    LiveTranslateRefreshSpeed.BALANCED
                ),
                translateRenderStyle = parseEnum(
                    map["translateRenderStyle"],
                    LiveTranslateRenderStyle.ADAPTIVE_INPLACE
                ),
                translateOverlayAlpha = map["translateOverlayAlpha"]?.toFloatOrNull()
                    ?: DEFAULT_TRANSLATE_OVERLAY_ALPHA,
                translateWifiOnlyDownload = map["translateWifiOnlyDownload"]?.toBooleanStrictOrNull()
                    ?: true,
                translateSkipTargetLanguage = map["translateSkipTargetLanguage"]?.toBooleanStrictOrNull()
                    ?: true,
                translateEngineMode = parseEnum(
                    map["translateEngineMode"],
                    LiveTranslateEngineMode.OFFLINE_MLKIT
                ),
                translateOnlineConsentAccepted = map["translateOnlineConsentAccepted"]?.toBooleanStrictOrNull()
                    ?: false,
                ctsAutoRepairAssistantWithShizuku = map["ctsAutoRepairAssistantWithShizuku"]?.toBooleanStrictOrNull()
                    ?: true,
                keepAliveForegroundNotification = map["keepAliveForegroundNotification"]?.toBooleanStrictOrNull()
                    ?: true,
                autoReviveWithShizuku = map["autoReviveWithShizuku"]?.toBooleanStrictOrNull()
                    ?: true,
                appLanguageTag = map["appLanguageTag"].orEmpty()
            ).clamped()
        }

        private inline fun <reified T : Enum<T>> parseEnum(name: String?, fallback: T): T {
            if (name.isNullOrEmpty()) return fallback
            return try {
                enumValueOf<T>(name)
            } catch (_: IllegalArgumentException) {
                fallback
            }
        }
    }
}

/**
 * Snapshot of resident service runtime telemetry surfaced to the :settings process.
 */
data class RuntimeStatus(
    val serviceActive: Boolean = false,
    val imeVisible: Boolean = false,
    val landscapeActive: Boolean = false,
    val foregroundPackage: String = "",
    val blacklistedActive: Boolean = false,
    val residentProcessPid: Int = 0
)
