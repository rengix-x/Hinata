package com.edgeflow.service

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import com.edgeflow.data.EdgeActionType
import com.edgeflow.data.QuickSlotConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Ultra-lightweight icon & label cache for Quick Arc Menu slots (max 6 apps).
 *
 * Pre-decodes and resizes ONLY the registered app slot icons on [Dispatchers.IO]
 * into fixed 44dp-equivalent [Bitmap]s so `QuickArcMenuView.onDraw()` performs zero
 * allocations and zero PackageManager I/O.
 */
class QuickMenuIconCache(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val density: Float = context.resources.displayMetrics.density
    private val targetIconPx: Int = (ICON_SIZE_DP * density).toInt().coerceIn(48, 176)

    private val iconCache = ConcurrentHashMap<String, Bitmap>(16)
    private val labelCache = ConcurrentHashMap<String, String>(16)

    private var refreshJob: Job? = null

    fun refreshForSlots(slots: List<QuickSlotConfig>, onReadyOnMain: () -> Unit = {}) {
        val packages = LinkedHashSet<String>(slots.size)
        for (slot in slots) {
            if ((slot.action.isAppLaunch || slot.action.isShortcutLaunch) &&
                slot.action.packageName.isNotEmpty()
            ) {
                packages.add(slot.action.packageName)
            }
        }

        refreshJob?.cancel()
        refreshJob = scope.launch {
            // Evict packages no longer registered to keep resident memory minimal
            val staleKeys = iconCache.keys.filter { !packages.contains(it) }
            for (stale in staleKeys) {
                iconCache.remove(stale)
                labelCache.remove(stale)
            }

            val pm = context.packageManager
            for (pkg in packages) {
                if (iconCache.containsKey(pkg)) continue
                runCatching {
                    val appInfo = pm.getApplicationInfo(pkg, 0)
                    val label = pm.getApplicationLabel(appInfo).toString()
                    val drawable = pm.getApplicationIcon(appInfo)
                    val bitmap = renderResizedBitmap(drawable, targetIconPx)
                    iconCache[pkg] = bitmap
                    labelCache[pkg] = label
                }
            }

            withContext(Dispatchers.Main) {
                onReadyOnMain()
            }
        }
    }

    fun getIcon(packageName: String): Bitmap? = iconCache[packageName]

    fun getAppLabel(packageName: String): String? = labelCache[packageName]

    fun release() {
        refreshJob?.cancel()
        scope.cancel()
        iconCache.clear()
        labelCache.clear()
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
    }
}
