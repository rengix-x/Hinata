package com.edgeflow.service.shizuku

import com.edgeflow.data.EdgeConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure JVM unit tests for:
 * 1. `EdgeConfig` safety flags (`keepAliveForegroundNotification`, `autoReviveWithShizuku`)
 *    default values, serialization round-trip, and legacy payload backward compatibility.
 * 2. `NavBarUserService` `enabled_accessibility_services` string validation, temporary self-exclusion
 *    without deleting other services, self-append, and shell-injection rejection.
 */
class NavBarUserServiceRecoveryTest {

    private val appPkg = "io.github.rengixx.hinata"
    private val ownFull = "$appPkg/com.edgeflow.service.EdgeAccessibilityService"
    private val ownShort = "$appPkg/.service.EdgeAccessibilityService"
    private val otherSvcA = "com.example.talkback/com.example.talkback.TalkBackService"
    private val otherSvcB = "com.vendor.helper/com.vendor.helper.Outer\$InnerService"

    @Test
    fun edgeConfig_safetyFlagsDefaultTrue_andRoundTrip() {
        val defaultCfg = EdgeConfig()
        assertTrue(defaultCfg.keepAliveForegroundNotification)
        assertTrue(defaultCfg.autoReviveWithShizuku)

        val disabledCfg = defaultCfg.copy(
            keepAliveForegroundNotification = false,
            autoReviveWithShizuku = false
        )
        val restored = EdgeConfig.deserialize(disabledCfg.serialize())
        assertFalse(restored.keepAliveForegroundNotification)
        assertFalse(restored.autoReviveWithShizuku)

        val mixedCfg = defaultCfg.copy(
            keepAliveForegroundNotification = false,
            autoReviveWithShizuku = true
        )
        val restoredMixed = EdgeConfig.deserialize(mixedCfg.serialize())
        assertFalse(restoredMixed.keepAliveForegroundNotification)
        assertTrue(restoredMixed.autoReviveWithShizuku)
    }

    @Test
    fun edgeConfig_legacyPayloadWithoutSafetyKeys_defaultsToTrue() {
        val legacyRaw = """
            leftEnabled=true
            rightEnabled=true
            autoHideNavBarWithShizuku=false
            appLanguageTag=ja
        """.trimIndent()

        val restored = EdgeConfig.deserialize(legacyRaw)
        assertTrue(restored.keepAliveForegroundNotification)
        assertTrue(restored.autoReviveWithShizuku)
    }

    @Test
    fun accessibilityServicesValidation_acceptsValidListsAndRejectsInjection() {
        assertTrue(NavBarUserService.isValidAccessibilityServicesRaw(""))
        assertTrue(NavBarUserService.isValidAccessibilityServicesRaw("null"))
        assertTrue(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA:$ownFull:$otherSvcB"))

        // Reject shell metacharacters, whitespace, quotes, and newlines
        assertFalse(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA; rm -rf /"))
        assertFalse(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA && id"))
        assertFalse(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA|cat /etc/passwd"))
        assertFalse(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA`whoami`"))
        assertFalse(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA\""))
        assertFalse(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA'"))
        assertFalse(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA\n$ownFull"))
        assertFalse(NavBarUserService.isValidAccessibilityServicesRaw("$otherSvcA $(id)"))

        assertNull(NavBarUserService.buildAccessibilityServicesWithoutSelf("$otherSvcA;id", appPkg))
        assertNull(NavBarUserService.buildAccessibilityServicesWithSelf("$otherSvcA;id", appPkg))
    }

    @Test
    fun buildWithoutSelf_preservesAllOtherServicesAndRemovesBothFullAndShortOwnForms() {
        val raw = "$otherSvcA:$ownFull:$otherSvcB:$ownShort"
        val withoutSelf = NavBarUserService.buildAccessibilityServicesWithoutSelf(raw, appPkg)
        assertEquals("$otherSvcA:$otherSvcB", withoutSelf)

        // When only own component is enabled, temporary exclusion yields empty string
        assertEquals("", NavBarUserService.buildAccessibilityServicesWithoutSelf(ownFull, appPkg))
        assertEquals("", NavBarUserService.buildAccessibilityServicesWithoutSelf("null", appPkg))
    }

    @Test
    fun buildWithSelf_appendsOwnComponentWithoutDuplicatesAndHandlesEmptyOrNull() {
        assertEquals(ownFull, NavBarUserService.buildAccessibilityServicesWithSelf("", appPkg))
        assertEquals(ownFull, NavBarUserService.buildAccessibilityServicesWithSelf("null", appPkg))

        // Appends at the end when missing while keeping existing services intact
        assertEquals(
            "$otherSvcA:$otherSvcB:$ownFull",
            NavBarUserService.buildAccessibilityServicesWithSelf("$otherSvcA:$otherSvcB", appPkg)
        )

        // Does not duplicate when already present (full or short form)
        assertEquals(
            "$otherSvcA:$ownFull:$otherSvcB",
            NavBarUserService.buildAccessibilityServicesWithSelf("$otherSvcA:$ownFull:$otherSvcB", appPkg)
        )
        assertEquals(
            "$otherSvcA:$ownShort",
            NavBarUserService.buildAccessibilityServicesWithSelf("$otherSvcA:$ownShort", appPkg)
        )
    }

    @Test
    fun forceRebindCycle_temporarilyExcludesSelfThenAppendsFullComponentPreservingOthers() {
        val initial = "$otherSvcA:$ownShort:$otherSvcB"
        val step1 = NavBarUserService.buildAccessibilityServicesWithoutSelf(initial, appPkg)
        assertEquals("$otherSvcA:$otherSvcB", step1)

        val step2 = NavBarUserService.buildAccessibilityServicesWithSelf(step1, appPkg)
        assertEquals("$otherSvcA:$otherSvcB:$ownFull", step2)
    }

    @Test
    fun edgeConfig_shizukuSystemNavModified_defaultsFalse_roundTrips_andLegacyFallback() {
        val defaultCfg = EdgeConfig()
        assertFalse(defaultCfg.shizukuSystemNavModified)

        val modifiedCfg = defaultCfg.copy(shizukuSystemNavModified = true)
        val restoredTrue = EdgeConfig.deserialize(modifiedCfg.serialize())
        assertTrue(restoredTrue.shizukuSystemNavModified)

        val restoredFalse = EdgeConfig.deserialize(
            modifiedCfg.copy(shizukuSystemNavModified = false).serialize()
        )
        assertFalse(restoredFalse.shizukuSystemNavModified)

        val legacyRaw = """
            leftEnabled=true
            rightEnabled=true
            autoHideNavBarWithShizuku=true
            appLanguageTag=ja
        """.trimIndent()
        val restoredLegacy = EdgeConfig.deserialize(legacyRaw)
        assertFalse(restoredLegacy.shizukuSystemNavModified)
    }

    @Test
    fun isOwnAccessibilityEnabledInList_detectsFullShortAndGlobalOffStates() {
        assertTrue(NavBarUserService.isOwnAccessibilityEnabledInList("$otherSvcA:$ownFull", 1, appPkg))
        assertTrue(NavBarUserService.isOwnAccessibilityEnabledInList("$otherSvcA:$ownShort:$otherSvcB", 1, appPkg))

        // Global accessibility_enabled=0 means disabled even if list contains own service
        assertFalse(NavBarUserService.isOwnAccessibilityEnabledInList("$otherSvcA:$ownFull", 0, appPkg))
        assertFalse(NavBarUserService.isOwnAccessibilityEnabledInList(null, 1, appPkg))

        // Missing own service from enabled list
        assertFalse(NavBarUserService.isOwnAccessibilityEnabledInList("$otherSvcA:$otherSvcB", 1, appPkg))
        assertFalse(NavBarUserService.isOwnAccessibilityEnabledInList("", 1, appPkg))
        assertFalse(NavBarUserService.isOwnAccessibilityEnabledInList("null", 1, appPkg))
    }
}
