package com.edgeflow.service

import com.edgeflow.data.EdgeConfig
import com.edgeflow.service.shizuku.AutoGuardRetryPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NavBarAutoGuardPolicyTest {

    @Test
    fun cooldownPreventsRapidReentryWithin2000ms() {
        val policy = AutoGuardRetryPolicy(
            cooldownMs = 2_000L,
            failureWindowMs = 10_000L,
            maxFailuresInWindow = 3
        )

        assertTrue(policy.tryAcquireAttempt(1_000L))
        assertFalse(policy.tryAcquireAttempt(1_500L))
        assertFalse(policy.tryAcquireAttempt(2_999L))
        assertTrue(policy.tryAcquireAttempt(3_000L))
    }

    @Test
    fun threeFailuresWithinTenSecondsTriggersAutoDisable() {
        val policy = AutoGuardRetryPolicy(
            cooldownMs = 2_000L,
            failureWindowMs = 10_000L,
            maxFailuresInWindow = 3
        )

        assertFalse(policy.recordFailure(1_000L))
        assertFalse(policy.recordFailure(4_000L))
        assertTrue(policy.recordFailure(7_000L))
    }

    @Test
    fun oldFailuresOutsideTenSecondWindowDoNotTriggerAutoDisable() {
        val policy = AutoGuardRetryPolicy(
            cooldownMs = 2_000L,
            failureWindowMs = 10_000L,
            maxFailuresInWindow = 3
        )

        assertFalse(policy.recordFailure(1_000L))
        assertFalse(policy.recordFailure(4_000L))
        // 12_000L is > 10s after 1_000L, so only [4_000L, 12_000L] remain in window
        assertFalse(policy.recordFailure(12_000L))
        // Third failure within window [12_000L - 10s] -> [4_000L, 12_000L, 13_500L]
        assertTrue(policy.recordFailure(13_500L))
    }

    @Test
    fun successClearsFailureWindow() {
        val policy = AutoGuardRetryPolicy(
            cooldownMs = 2_000L,
            failureWindowMs = 10_000L,
            maxFailuresInWindow = 3
        )

        assertFalse(policy.recordFailure(1_000L))
        assertFalse(policy.recordFailure(4_000L))
        policy.recordSuccess()
        assertFalse(policy.recordFailure(6_000L))
        assertFalse(policy.recordFailure(8_500L))
        assertTrue(policy.recordFailure(11_000L))
    }

    @Test
    fun edgeConfigSerializesAndDeserializesShizukuFlagsBackwardsCompatibly() {
        // Legacy serialized payload without Shizuku keys uses safe defaults
        val legacy = EdgeConfig.deserialize("leftEnabled=true\nrightEnabled=false\n")
        assertFalse(legacy.autoHideNavBarWithShizuku)
        assertTrue(legacy.hideGestureLine)

        // Round-trip with explicit values
        val custom = EdgeConfig(
            autoHideNavBarWithShizuku = true,
            hideGestureLine = false
        )
        val restored = EdgeConfig.deserialize(custom.serialize())
        assertTrue(restored.autoHideNavBarWithShizuku)
        assertFalse(restored.hideGestureLine)
        assertEquals(custom, restored)
    }
}
