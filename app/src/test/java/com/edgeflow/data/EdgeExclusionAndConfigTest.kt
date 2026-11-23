package com.edgeflow.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EdgeExclusionAndConfigTest {

    @Test
    fun edgeConfig_dropletCtsAndFloatingMacroFields_roundTripAndClamp() {
        val defaults = EdgeConfig()
        assertTrue(defaults.noShizukuControlCenterFloat)
        assertEquals(0.98f, defaults.translateOverlayAlpha, 0.001f)

        val original = EdgeConfig(
            dropletPeakWidthDp = 42f,
            dropletBaseWidthDp = 168f,
            ctsAutoRepairAssistantWithShizuku = false,
            noShizukuControlCenterFloat = false
        )
        val restored = EdgeConfig.deserialize(original.serialize())
        assertEquals(42f, restored.dropletPeakWidthDp, 0.01f)
        assertEquals(168f, restored.dropletBaseWidthDp, 0.01f)
        assertFalse(restored.ctsAutoRepairAssistantWithShizuku)
        assertFalse(restored.noShizukuControlCenterFloat)

        // Legacy configs containing removed circleToSearchMode key must still deserialize safely
        val legacyPayload = "${original.serialize()}\ncircleToSearchMode=BUILT_IN_LENS"
        val fromLegacy = EdgeConfig.deserialize(legacyPayload)
        assertFalse(fromLegacy.ctsAutoRepairAssistantWithShizuku)
        assertFalse(fromLegacy.noShizukuControlCenterFloat)

        val clampedLow = EdgeConfig(dropletPeakWidthDp = 2f, dropletBaseWidthDp = 10f).clamped()
        assertEquals(EdgeConfig.MIN_DROPLET_PEAK_WIDTH_DP, clampedLow.dropletPeakWidthDp, 0.01f)
        assertEquals(EdgeConfig.MIN_DROPLET_BASE_WIDTH_DP, clampedLow.dropletBaseWidthDp, 0.01f)

        val clampedHigh = EdgeConfig(dropletPeakWidthDp = 999f, dropletBaseWidthDp = 999f).clamped()
        assertEquals(EdgeConfig.MAX_DROPLET_PEAK_WIDTH_DP, clampedHigh.dropletPeakWidthDp, 0.01f)
        assertEquals(EdgeConfig.MAX_DROPLET_BASE_WIDTH_DP, clampedHigh.dropletBaseWidthDp, 0.01f)
    }

    @Test
    fun edgeExclusionHelper_detectsLimitAndAutoAdjustsWithin196Dp() {
        val screenHeightDp = 880
        val tallConfig = EdgeConfig(edgeHeightPercent = 0.65f, edgeBottomOffsetPercent = 0.20f)
        assertTrue(EdgeExclusionHelper.exceedsSystemExclusionLimit(tallConfig, screenHeightDp))

        val adjusted = EdgeExclusionHelper.optimizeForSystemExclusionLimit(tallConfig, screenHeightDp)
        val estAfter = EdgeExclusionHelper.estimateTotalSideEdgeHeightDp(adjusted, screenHeightDp)
        assertTrue("Expected <= 196dp, got $estAfter", estAfter <= EdgeExclusionHelper.SAFE_TARGET_BUDGET_DP + 1)
        assertEquals(0.05f, adjusted.edgeBottomOffsetPercent, 0.001f)
        assertFalse(EdgeExclusionHelper.exceedsSystemExclusionLimit(adjusted, screenHeightDp))

        // Dedicated Quick Arc zone shares the same 200dp per-edge cap (when screen height allows >= 0.30 ratio)
        val dedicatedConfig = EdgeConfig(
            edgeHeightPercent = 0.45f,
            quickArcTriggerMethod = QuickArcTriggerMethod.DEDICATED_ZONE,
            quickArcZoneHeightPercent = 0.25f
        )
        assertTrue(EdgeExclusionHelper.exceedsSystemExclusionLimit(dedicatedConfig, 640))
        val adjustedDedicated = EdgeExclusionHelper.optimizeForSystemExclusionLimit(dedicatedConfig, 640)
        val estDedicated = EdgeExclusionHelper.estimateTotalSideEdgeHeightDp(adjustedDedicated, 640)
        assertTrue("Expected dedicated total <= 200dp, got $estDedicated", estDedicated <= EdgeExclusionHelper.ANDROID_EXCLUSION_LIMIT_DP)
    }

    @Test
    fun edgeExclusionHelper_bottomPrioritizedRangePx_protectsLowerRegionFirst() {
        val full = EdgeExclusionHelper.computeBottomPrioritizedRangePx(viewHeightPx = 400, budgetPx = 600)
        assertEquals(0 to 400, full)

        val capped = EdgeExclusionHelper.computeBottomPrioritizedRangePx(viewHeightPx = 900, budgetPx = 600)
        assertEquals(300 to 900, capped)
    }

    @Test
    fun deviceCompatibilityHelper_detectsAllFiveOemFamilies() {
        assertEquals(
            DeviceFamily.XIAOMI_HYPEROS,
            DeviceCompatibilityHelper.detectDeviceFamily("Xiaomi", "POCO")
        )
        assertEquals(
            DeviceFamily.REALME_OPPO_ONEPLUS,
            DeviceCompatibilityHelper.detectDeviceFamily("realme", "realme")
        )
        assertEquals(
            DeviceFamily.REALME_OPPO_ONEPLUS,
            DeviceCompatibilityHelper.detectDeviceFamily("OPPO", "OnePlus")
        )
        assertEquals(
            DeviceFamily.VIVO_ORIGIN_OS,
            DeviceCompatibilityHelper.detectDeviceFamily("vivo", "iQOO")
        )
        assertEquals(
            DeviceFamily.SAMSUNG_ONEUI,
            DeviceCompatibilityHelper.detectDeviceFamily("samsung", "samsung")
        )
        assertEquals(
            DeviceFamily.STANDARD_ANDROID,
            DeviceCompatibilityHelper.detectDeviceFamily("Google", "google")
        )
        assertEquals(
            DeviceFamily.STANDARD_ANDROID,
            DeviceCompatibilityHelper.detectDeviceFamily("Sony", "Sony")
        )
    }

    @Test
    fun deviceCompatibilityHelper_resolveCapabilities_strictlyDistinguishesHyperOsAndOtherOems() {
        // 1. Genuine Xiaomi HyperOS device (ro.mi.os.version.name present)
        val genuineHyperOs = DeviceCompatibilityHelper.resolveCapabilities(
            manufacturer = "Xiaomi",
            brand = "Xiaomi",
            hyperOsProp = "OS2.0.10.0.VNCMIXM",
            miuiProp = "V816",
            hasMiuiWindowClass = true
        )
        assertEquals(DeviceFamily.XIAOMI_HYPEROS, genuineHyperOs.deviceFamily)
        assertTrue(genuineHyperOs.isHyperOsOrMiui)
        assertEquals("OS2.0.10.0.VNCMIXM", genuineHyperOs.hyperOsVersionName)
        assertTrue(genuineHyperOs.supportsHyperOsRecentsCompat)
        assertTrue(genuineHyperOs.supportsHyperOsAutoStartSettings)
        assertTrue(genuineHyperOs.supportsHyperOsSecondSpaceTrick)
        assertTrue(genuineHyperOs.supportsHyperOsGestureLineToggle)
        assertTrue(genuineHyperOs.supportsHyperOsNativeFloatingWindow)
        assertTrue(genuineHyperOs.supportsStandardFreeformWithShizuku)
        assertTrue(genuineHyperOs.isChineseRomCtsRepairRelevant)

        // 2. Xiaomi hardware running AOSP/LineageOS custom ROM (no HyperOS/MIUI props or classes)
        val xiaomiCustomRom = DeviceCompatibilityHelper.resolveCapabilities(
            manufacturer = "Xiaomi",
            brand = "POCO",
            hyperOsProp = "",
            miuiProp = "",
            hasMiuiWindowClass = false
        )
        assertEquals(DeviceFamily.STANDARD_ANDROID, xiaomiCustomRom.deviceFamily)
        assertFalse(xiaomiCustomRom.isHyperOsOrMiui)
        assertFalse(xiaomiCustomRom.supportsHyperOsRecentsCompat)
        assertFalse(xiaomiCustomRom.supportsHyperOsAutoStartSettings)
        assertFalse(xiaomiCustomRom.supportsHyperOsSecondSpaceTrick)
        assertFalse(xiaomiCustomRom.supportsHyperOsGestureLineToggle)
        assertFalse(xiaomiCustomRom.supportsHyperOsNativeFloatingWindow)
        assertTrue(xiaomiCustomRom.supportsStandardFreeformWithShizuku)
        assertFalse(xiaomiCustomRom.isChineseRomCtsRepairRelevant)

        // 3. Google Pixel (Standard Android)
        val pixel = DeviceCompatibilityHelper.resolveCapabilities(
            manufacturer = "Google",
            brand = "google",
            hyperOsProp = "",
            miuiProp = "",
            hasMiuiWindowClass = false
        )
        assertEquals(DeviceFamily.STANDARD_ANDROID, pixel.deviceFamily)
        assertFalse(pixel.isHyperOsOrMiui)
        assertFalse(pixel.supportsHyperOsNativeFloatingWindow)
        assertFalse(pixel.supportsHyperOsRecentsCompat)
        assertFalse(pixel.isChineseRomCtsRepairRelevant)
        assertTrue(pixel.supportsStandardFreeformWithShizuku)

        // 4. Samsung One UI
        val samsung = DeviceCompatibilityHelper.resolveCapabilities(
            manufacturer = "samsung",
            brand = "samsung",
            hyperOsProp = "",
            miuiProp = "",
            hasMiuiWindowClass = false
        )
        assertEquals(DeviceFamily.SAMSUNG_ONEUI, samsung.deviceFamily)
        assertFalse(samsung.isHyperOsOrMiui)
        assertFalse(samsung.supportsHyperOsNativeFloatingWindow)
        assertFalse(samsung.isChineseRomCtsRepairRelevant)

        // 5. OPPO / ColorOS (non-HyperOS, but CN ROM CTS repair relevant)
        val oppo = DeviceCompatibilityHelper.resolveCapabilities(
            manufacturer = "OPPO",
            brand = "OnePlus",
            hyperOsProp = "",
            miuiProp = "",
            hasMiuiWindowClass = false
        )
        assertEquals(DeviceFamily.REALME_OPPO_ONEPLUS, oppo.deviceFamily)
        assertFalse(oppo.isHyperOsOrMiui)
        assertFalse(oppo.supportsHyperOsNativeFloatingWindow)
        assertFalse(oppo.supportsHyperOsAutoStartSettings)
        assertTrue(oppo.isChineseRomCtsRepairRelevant)

        // 6. vivo / OriginOS (non-HyperOS, but CN ROM CTS repair relevant)
        val vivo = DeviceCompatibilityHelper.resolveCapabilities(
            manufacturer = "vivo",
            brand = "iQOO",
            hyperOsProp = "",
            miuiProp = "",
            hasMiuiWindowClass = false
        )
        assertEquals(DeviceFamily.VIVO_ORIGIN_OS, vivo.deviceFamily)
        assertFalse(vivo.isHyperOsOrMiui)
        assertFalse(vivo.supportsHyperOsNativeFloatingWindow)
        assertTrue(vivo.isChineseRomCtsRepairRelevant)
    }
}

