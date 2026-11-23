package com.edgeflow.service

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.os.Process
import android.util.LruCache
import android.view.inputmethod.InputMethodManager
import com.edgeflow.data.RuntimeStateRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Pure-Kotlin, framework-independent MRU ring buffer for tracking launchable recent apps.
 * Deduplicates entries, maintains strict latest-first ordering up to [maxSize], and enforces
 * exclusion filters for launchers, self, system UI, IMEs, and non-launchable internal packages.
 */
class RecentAppsRingBuffer(
    private val ownPackage: String,
    private val maxSize: Int = MAX_HISTORY_SIZE,
    private val launcherPackagesProvider: () -> Set<String> = { emptySet() },
    private val imePackagesProvider: () -> Set<String> = { emptySet() },
    private val isLaunchable: (String) -> Boolean = { true }
) {
    private val lock = Any()
    private val history = ArrayList<String>(maxSize + 1)

    @Volatile
    private var currentForeground: String = ""

    fun recordForeground(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        if (isTransientOverlayOrIme(pkg)) return false

        synchronized(lock) {
            if (pkg == currentForeground && history.firstOrNull() == pkg) {
                return false
            }
            currentForeground = pkg
            if (!shouldRecordInHistory(pkg)) {
                return false
            }
            history.remove(pkg)
            history.add(0, pkg)
            while (history.size > maxSize) {
                history.removeAt(history.lastIndex)
            }
            return true
        }
    }

    /**
     * Merges optional UsageStatsManager history behind real-time ring-buffer entries
     * (never displacing fresher real-time transitions).
     */
    fun mergeSupplement(packagesMostRecentFirst: List<String>) {
        synchronized(lock) {
            for (raw in packagesMostRecentFirst) {
                if (history.size >= maxSize) break
                val pkg = raw.trim()
                if (pkg.isEmpty() || history.contains(pkg)) continue
                if (!shouldRecordInHistory(pkg)) continue
                history.add(pkg)
            }
        }
    }

    fun isOnHomeScreen(): Boolean {
        val fg = currentForeground
        if (fg.isEmpty()) return false
        return launcherPackagesProvider().contains(fg)
    }

    fun currentForegroundPackage(): String = currentForeground

    fun getPreviousApp(): String? = synchronized(lock) {
        for (i in 0 until history.size) {
            val candidate = history[i]
            if (candidate != currentForeground) return candidate
        }
        null
    }

    fun getRecentAppsExcludingCurrent(limit: Int = 6): List<String> = synchronized(lock) {
        val out = ArrayList<String>(limit.coerceAtMost(history.size))
        for (i in 0 until history.size) {
            if (out.size >= limit) break
            val pkg = history[i]
            if (pkg != currentForeground) {
                out.add(pkg)
            }
        }
        out
    }

    fun snapshotAll(): List<String> = synchronized(lock) {
        ArrayList(history)
    }

    private fun isTransientOverlayOrIme(pkg: String): Boolean {
        if (pkg == "com.android.systemui" || pkg == "android") return true
        if (pkg.contains("inputmethod", ignoreCase = true)) return true
        if (pkg.contains("keyboard", ignoreCase = true)) return true
        if (imePackagesProvider().contains(pkg)) return true
        return false
    }

    private fun shouldRecordInHistory(pkg: String): Boolean {
        if (pkg == ownPackage) return false
        if (isTransientOverlayOrIme(pkg)) return false
        if (launcherPackagesProvider().contains(pkg)) return false
        return isLaunchable(pkg)
    }

    companion object {
        const val MAX_HISTORY_SIZE = 8
    }
}

/**
 * Hybrid recent-apps tracker combining:
 * 1. Zero-latency real-time AccessibilityService window transition ring buffer (no permission required)
 * 2. Optional UsageStatsManager backfill on Dispatchers.IO when PACKAGE_USAGE_STATS is granted
 * 3. Lightweight 12-entry LruCache<String, Bitmap> pre-resized to 44dp on Dispatchers.IO for 120fps zero-GC drawing
 */
class RecentAppsTracker(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val density: Float = context.resources.displayMetrics.density
    private val iconPx: Int = (ICON_SIZE_DP * density).toInt().coerceIn(48, 176)

    private val launchableCache = ConcurrentHashMap<String, Boolean>(32)
    private val labelCache = ConcurrentHashMap<String, String>(16)
    private val iconCache = object : LruCache<String, Bitmap>(ICON_CACHE_MAX_ENTRIES) {}
    private val iconCacheLock = Any()

    @Volatile
    private var cachedLauncherPackages: Set<String> = emptySet()

    @Volatile
    private var cachedImePackages: Set<String> = emptySet()

    private val ringBuffer = RecentAppsRingBuffer(
        ownPackage = context.packageName,
        maxSize = RecentAppsRingBuffer.MAX_HISTORY_SIZE,
        launcherPackagesProvider = { cachedLauncherPackages },
        imePackagesProvider = { cachedImePackages },
        isLaunchable = { pkg -> checkLaunchableCached(pkg) }
    )

    init {
        refreshSystemExclusions()
    }

    fun refreshSystemExclusions() {
        val launchers = LinkedHashSet<String>(4)
        val pm = context.packageManager
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        runCatching {
            pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName
                ?.let { launchers.add(it) }
        }
        runCatching {
            val allHomes = pm.queryIntentActivities(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)
            for (info in allHomes) {
                info.activityInfo?.packageName?.let { launchers.add(it) }
            }
        }
        cachedLauncherPackages = launchers

        val imes = LinkedHashSet<String>(4)
        runCatching {
            val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
            imm?.enabledInputMethodList?.forEach { info ->
                imes.add(info.packageName)
            }
        }
        cachedImePackages = imes
    }

    fun onForegroundPackageChanged(packageName: String) {
        val added = ringBuffer.recordForeground(packageName)
        if (added) {
            publishSnapshot()
            warmCacheAsync(packageName)
        }
    }

    fun supplementFromUsageStatsIfPermitted() {
        if (!hasUsageStatsPermission(context)) return
        scope.launch {
            val discovered = queryUsageStatsPackages(context)
            if (discovered.isNotEmpty()) {
                ringBuffer.mergeSupplement(discovered)
                publishSnapshot()
                for (pkg in ringBuffer.snapshotAll()) {
                    loadIconAndLabelSync(pkg)
                }
            }
        }
    }

    fun isOnHomeScreen(): Boolean = ringBuffer.isOnHomeScreen()

    fun getCurrentForegroundPackage(): String = ringBuffer.currentForegroundPackage()

    fun isSystemOrLauncherPackage(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return true
        if (pkg == context.packageName) return true
        if (pkg == "com.android.systemui" || pkg == "android") return true
        if (cachedLauncherPackages.contains(pkg)) return true
        if (cachedImePackages.contains(pkg)) return true
        return false
    }

    fun getPreviousApp(): String? = ringBuffer.getPreviousApp()

    fun getRecentAppsExcludingCurrent(limit: Int = 6): List<String> =
        ringBuffer.getRecentAppsExcludingCurrent(limit)

    fun snapshotAll(): List<String> = ringBuffer.snapshotAll()

    fun getCachedIcon(packageName: String): Bitmap? = synchronized(iconCacheLock) {
        iconCache.get(packageName)
    }

    fun getCachedLabel(packageName: String): String {
        return labelCache[packageName] ?: packageName
    }

    fun release() {
        scope.cancel()
        synchronized(iconCacheLock) {
            iconCache.evictAll()
        }
        labelCache.clear()
        launchableCache.clear()
    }

    private fun publishSnapshot() {
        RuntimeStateRegistry.updateRecentApps(ringBuffer.snapshotAll())
    }

    private fun checkLaunchableCached(pkg: String): Boolean {
        launchableCache[pkg]?.let { return it }
        val launchable = runCatching {
            context.packageManager.getLaunchIntentForPackage(pkg) != null
        }.getOrDefault(false)
        launchableCache[pkg] = launchable
        return launchable
    }

    private fun warmCacheAsync(pkg: String) {
        val alreadyCached = synchronized(iconCacheLock) { iconCache.get(pkg) != null } &&
            labelCache.containsKey(pkg)
        if (alreadyCached) return
        scope.launch {
            loadIconAndLabelSync(pkg)
        }
    }

    private fun loadIconAndLabelSync(pkg: String) {
        val pm = context.packageManager
        runCatching {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            if (!labelCache.containsKey(pkg)) {
                labelCache[pkg] = pm.getApplicationLabel(appInfo).toString()
            }
            val needsIcon = synchronized(iconCacheLock) { iconCache.get(pkg) == null }
            if (needsIcon) {
                val drawable = pm.getApplicationIcon(appInfo)
                val bmp = renderResizedBitmap(drawable, iconPx)
                synchronized(iconCacheLock) {
                    iconCache.put(pkg, bmp)
                }
            }
        }
    }

    private fun renderResizedBitmap(drawable: Drawable, sizePx: Int): Bitmap {
        if (drawable is BitmapDrawable && drawable.bitmap != null) {
            val src = drawable.bitmap
            if (src.width == sizePx && src.height == sizePx) {
                return src.copy(Bitmap.Config.ARGB_8888, false)
            }
            return Bitmap.createScaledBitmap(src, sizePx, sizePx, true)
        }
        val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, sizePx, sizePx)
        drawable.draw(canvas)
        return bitmap
    }

    companion object {
        private const val ICON_SIZE_DP = 44f
        private const val ICON_CACHE_MAX_ENTRIES = 12
        private const val USAGE_LOOKBACK_MS = 24L * 60L * 60L * 1000L

        fun hasUsageStatsPermission(context: Context): Boolean {
            return runCatching {
                val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
                    ?: return false
                @Suppress("DEPRECATION")
                val mode = appOps.unsafeCheckOpNoThrow(
                    AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(),
                    context.packageName
                )
                mode == AppOpsManager.MODE_ALLOWED
            }.getOrDefault(false)
        }

        fun queryUsageStatsPackages(context: Context): List<String> {
            if (!hasUsageStatsPermission(context)) return emptyList()
            val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as? UsageStatsManager
                ?: return emptyList()
            val now = System.currentTimeMillis()
            val start = now - USAGE_LOOKBACK_MS

            // Prefer fine-grained UsageEvents (ACTIVITY_RESUMED) for accurate MRU ordering
            val latestTimestampByPkg = HashMap<String, Long>(24)
            runCatching {
                val events = usm.queryEvents(start, now)
                val event = UsageEvents.Event()
                while (events != null && events.hasNextEvent()) {
                    events.getNextEvent(event)
                    @Suppress("DEPRECATION")
                    val isForeground = event.eventType == UsageEvents.Event.ACTIVITY_RESUMED ||
                        event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND
                    if (isForeground) {
                        val pkg = event.packageName
                        if (!pkg.isNullOrEmpty()) {
                            val prev = latestTimestampByPkg[pkg] ?: 0L
                            if (event.timeStamp > prev) {
                                latestTimestampByPkg[pkg] = event.timeStamp
                            }
                        }
                    }
                }
            }

            if (latestTimestampByPkg.isEmpty()) {
                runCatching {
                    val stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, start, now)
                    for (stat in stats.orEmpty()) {
                        if (stat.lastTimeUsed > 0L && !stat.packageName.isNullOrEmpty()) {
                            val prev = latestTimestampByPkg[stat.packageName] ?: 0L
                            if (stat.lastTimeUsed > prev) {
                                latestTimestampByPkg[stat.packageName] = stat.lastTimeUsed
                            }
                        }
                    }
                }
            }

            return latestTimestampByPkg.entries
                .sortedByDescending { it.value }
                .map { it.key }
        }
    }
}
