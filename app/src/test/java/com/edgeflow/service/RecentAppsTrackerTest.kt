package com.edgeflow.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecentAppsTrackerTest {

    private val ownPkg = "com.aistudio.edgeflow.k7m2px"
    private val launchers = setOf("com.teslacoilsw.launcher", "com.mi.android.globallauncher")
    private val imes = setOf("com.google.android.inputmethod.latin", "com.touchtype.swiftkey")
    private val nonLaunchable = setOf("com.xiaomi.internal.daemon", "com.android.permissioncontroller")

    private fun newBuffer(): RecentAppsRingBuffer = RecentAppsRingBuffer(
        ownPackage = ownPkg,
        maxSize = 8,
        launcherPackagesProvider = { launchers },
        imePackagesProvider = { imes },
        isLaunchable = { pkg -> !nonLaunchable.contains(pkg) }
    )

    @Test
    fun filtersLaunchersSelfSystemUiImesAndNonLaunchables() {
        val buf = newBuffer()
        assertFalse(buf.recordForeground("com.android.systemui"))
        assertFalse(buf.recordForeground("android"))
        assertFalse(buf.recordForeground(ownPkg))
        assertFalse(buf.recordForeground("com.google.android.inputmethod.latin"))
        assertFalse(buf.recordForeground("com.touchtype.swiftkey"))
        assertFalse(buf.recordForeground("com.xiaomi.internal.daemon"))
        assertFalse(buf.recordForeground("com.teslacoilsw.launcher"))

        assertTrue(buf.isOnHomeScreen())
        assertTrue(buf.snapshotAll().isEmpty())
        assertNull(buf.getPreviousApp())
    }

    @Test
    fun deduplicatesPromotesMruAndExcludesCurrentForegroundForPreviousAppAndSwitcher() {
        val buf = newBuffer()
        assertTrue(buf.recordForeground("com.android.chrome"))
        assertTrue(buf.recordForeground("com.twitter.android"))
        assertTrue(buf.recordForeground("com.spotify.music"))

        // Current is Spotify -> Previous is Twitter, Recent excluding current is [Twitter, Chrome]
        assertFalse(buf.isOnHomeScreen())
        assertEquals("com.twitter.android", buf.getPreviousApp())
        assertEquals(
            listOf("com.twitter.android", "com.android.chrome"),
            buf.getRecentAppsExcludingCurrent(6)
        )

        // Revisit Chrome -> promotes Chrome to index 0 without duplication
        assertTrue(buf.recordForeground("com.android.chrome"))
        assertEquals(
            listOf("com.android.chrome", "com.spotify.music", "com.twitter.android"),
            buf.snapshotAll()
        )
        assertEquals("com.spotify.music", buf.getPreviousApp())

        // Transient SystemUI or IME event does NOT clobber currentForeground
        assertFalse(buf.recordForeground("com.android.systemui"))
        assertFalse(buf.recordForeground("com.google.android.inputmethod.latin"))
        assertEquals("com.android.chrome", buf.currentForegroundPackage())
        assertEquals("com.spotify.music", buf.getPreviousApp())

        // Returning to Nova Launcher sets isOnHomeScreen() == true and exposes Chrome as slot #1
        assertFalse(buf.recordForeground("com.teslacoilsw.launcher"))
        assertTrue(buf.isOnHomeScreen())
        assertEquals("com.android.chrome", buf.getPreviousApp())
        assertEquals(
            listOf("com.android.chrome", "com.spotify.music", "com.twitter.android"),
            buf.getRecentAppsExcludingCurrent(6)
        )
    }

    @Test
    fun capsRingBufferAtMaxSize8EvictingOldest() {
        val buf = newBuffer()
        for (i in 1..10) {
            assertTrue(buf.recordForeground("com.example.app$i"))
        }
        val all = buf.snapshotAll()
        assertEquals(8, all.size)
        assertEquals("com.example.app10", all.first())
        assertEquals("com.example.app3", all.last())
        assertEquals("com.example.app9", buf.getPreviousApp())
        assertEquals(6, buf.getRecentAppsExcludingCurrent(6).size)
    }

    @Test
    fun mergeSupplementBackfillsBehindRealtimeEntriesWithoutOverwritingOrder() {
        val buf = newBuffer()
        buf.recordForeground("com.app.fresh1")
        buf.recordForeground("com.app.fresh2")

        buf.mergeSupplement(
            listOf(
                "com.teslacoilsw.launcher", // filtered
                "com.android.systemui",     // filtered
                "com.app.fresh1",           // already present, keeps realtime position
                "com.app.backfill1",
                "com.xiaomi.internal.daemon", // non-launchable filtered
                "com.app.backfill2"
            )
        )

        assertEquals(
            listOf("com.app.fresh2", "com.app.fresh1", "com.app.backfill1", "com.app.backfill2"),
            buf.snapshotAll()
        )
        assertEquals("com.app.fresh1", buf.getPreviousApp())
    }
}
