package com.edgeflow.data

import android.content.Context
import com.google.mlkit.common.MlKit
import com.google.mlkit.common.sdkinternal.MlKitContext

/**
 * Thread-safe, crash-proof ML Kit initializer that works reliably in BOTH the default resident
 * service process and the isolated `:settings` process (where `MlKitInitProvider` is NOT
 * auto-instantiated by Android).
 *
 * ZERO Jetpack Compose imports.
 */
object MlKitSafeInit {

    @Volatile
    private var initialized: Boolean = false

    private val initLock = Any()

    /**
     * Ensures [MlKitContext] is initialized before touching `RemoteModelManager`,
     * `TextRecognition`, `LanguageIdentification`, or `Translation`.
     *
     * Never throws; returns `true` if ML Kit is ready or `false` on unsupported/non-GMS devices.
     */
    fun ensureInitialized(context: Context): Boolean {
        if (initialized) return true
        synchronized(initLock) {
            if (initialized) return true
            val appCtx = context.applicationContext ?: context
            // 1. Primary path: MlKitContext.initializeIfNeeded(appCtx)
            val ok1 = runCatching {
                MlKitContext.initializeIfNeeded(appCtx)
                true
            }.getOrDefault(false)
            if (ok1) {
                initialized = true
                return true
            }
            // 2. Fallback path: MlKit.initialize(appCtx) — ignore "already initialized" ISE
            val ok2 = try {
                MlKit.initialize(appCtx)
                true
            } catch (e: IllegalStateException) {
                // "MlKitContext is already initialized"
                runCatching { MlKitContext.getInstance() }.isSuccess
            } catch (_: Throwable) {
                false
            }
            if (ok2) {
                initialized = true
                return true
            }
            return false
        }
    }
}
