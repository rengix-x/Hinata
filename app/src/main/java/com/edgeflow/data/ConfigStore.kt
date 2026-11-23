package com.edgeflow.data

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import java.io.File
import java.io.IOException

/**
 * Cross-process safe configuration, telemetry & recent-apps snapshot bridge.
 *
 * Runs in the default (resident) process. Both the resident EdgeAccessibilityService
 * and the isolated :settings process read/write atomically via ContentProvider.call().
 */
class EdgeConfigProvider : ContentProvider() {

    override fun onCreate(): Boolean {
        context?.let { ctx ->
            ShizukuShellExecutor.initMultiProcess(ctx, isMainProcess = true)
        }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return Bundle.EMPTY
        return when (method) {
            METHOD_GET_CONFIG -> {
                Bundle(1).apply {
                    putString(KEY_PAYLOAD, readConfigRaw(ctx))
                }
            }

            METHOD_SAVE_CONFIG -> {
                val payload = extras?.getString(KEY_PAYLOAD).orEmpty()
                val sanitized = EdgeConfig.deserialize(payload).serialize()
                writeConfigRaw(ctx, sanitized)
                ctx.contentResolver.notifyChange(configUri(ctx), null)
                val broadcast = Intent(ACTION_CONFIG_CHANGED).apply {
                    setPackage(ctx.packageName)
                }
                ctx.sendBroadcast(broadcast)
                Bundle(1).apply {
                    putBoolean(KEY_OK, true)
                }
            }

            METHOD_GET_RUNTIME_STATUS -> {
                val snapshot = RuntimeStateRegistry.snapshot()
                Bundle(6).apply {
                    putBoolean(KEY_SERVICE_ACTIVE, snapshot.serviceActive)
                    putBoolean(KEY_IME_VISIBLE, snapshot.imeVisible)
                    putBoolean(KEY_LANDSCAPE_ACTIVE, snapshot.landscapeActive)
                    putString(KEY_FOREGROUND_PKG, snapshot.foregroundPackage)
                    putBoolean(KEY_BLACKLISTED_ACTIVE, snapshot.blacklistedActive)
                    putInt(KEY_RESIDENT_PID, Process.myPid())
                }
            }

            METHOD_GET_RECENT_APPS -> {
                val recentList = ArrayList(RuntimeStateRegistry.recentAppsSnapshot())
                Bundle(1).apply {
                    putStringArrayList(KEY_RECENT_APPS, recentList)
                }
            }

            METHOD_CALIBRATE_CC_FLOAT_TILE -> {
                val method = extras?.getString(KEY_CALIBRATION_METHOD)
                    ?.ifBlank { EdgeConfig.CC_TILE_METHOD_NODE }
                    ?: EdgeConfig.CC_TILE_METHOD_NODE
                handleCalibrateCcFloatTile(ctx, method)
            }

            METHOD_GET_CC_CALIBRATION_STATUS -> {
                val st = RuntimeStateRegistry.ccCalibrationSnapshot()
                Bundle(6).apply {
                    putBoolean(KEY_CALIBRATION_IN_PROGRESS, st.inProgress)
                    putString(KEY_STATUS_CODE, st.statusCode)
                    putString(KEY_CALIBRATION_METHOD, st.methodUsed)
                    putFloat(KEY_CALIBRATION_NORM_X, st.normX)
                    putFloat(KEY_CALIBRATION_NORM_Y, st.normY)
                    putLong(KEY_CALIBRATION_TIMESTAMP_MS, st.timestampMs)
                }
            }

            METHOD_TRIGGER_CC_FLOAT_SCAN -> {
                val rawMode = extras?.getString(KEY_SCAN_MODE).orEmpty()
                val mappedMethod = if (rawMode.equals(SCAN_MODE_OCR, ignoreCase = true)) {
                    EdgeConfig.CC_TILE_METHOD_OCR
                } else {
                    EdgeConfig.CC_TILE_METHOD_NODE
                }
                handleCalibrateCcFloatTile(ctx, mappedMethod)
            }

            else -> Bundle.EMPTY
        }
    }

    private fun handleCalibrateCcFloatTile(ctx: Context, method: String): Bundle {
        val activeService = com.edgeflow.service.EdgeAccessibilityService.getActiveInstance()
        if (activeService != null) {
            RuntimeStateRegistry.updateCcCalibrationState(
                CcTileCalibrationState(
                    inProgress = true,
                    statusCode = STATUS_RUNNING,
                    methodUsed = method,
                    normX = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                    normY = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
                    timestampMs = System.currentTimeMillis()
                )
            )
            val dispatched = activeService.requestControlCenterTileCalibration(method)
            return Bundle(2).apply {
                putBoolean(KEY_OK, dispatched)
                putString(KEY_STATUS_CODE, if (dispatched) STATUS_RUNNING else STATUS_SERVICE_NOT_BOUND)
            }
        }

        // Fallback to registered handler if service activeInstance isn't wired yet
        val legacyDispatched = RuntimeStateRegistry.dispatchCcFloatScan(method)
        if (legacyDispatched) {
            return Bundle(2).apply {
                putBoolean(KEY_OK, true)
                putString(KEY_STATUS_CODE, STATUS_RUNNING)
            }
        }

        val enabledInSystem = ShizukuShellExecutor.isEdgeFlowAccessibilityEnabled(ctx)
        val statusCode = if (enabledInSystem) STATUS_SERVICE_NOT_BOUND else STATUS_SERVICE_DISABLED
        RuntimeStateRegistry.updateCcCalibrationState(
            CcTileCalibrationState(
                inProgress = false,
                statusCode = statusCode,
                methodUsed = method,
                timestampMs = System.currentTimeMillis()
            )
        )
        return Bundle(2).apply {
            putBoolean(KEY_OK, false)
            putString(KEY_STATUS_CODE, statusCode)
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0

    companion object {
        private const val CONFIG_FILE_NAME = "edgeflow_config.cfg"
        private const val CONFIG_TMP_FILE_NAME = "edgeflow_config.cfg.tmp"

        const val ACTION_CONFIG_CHANGED = "com.edgeflow.ACTION_CONFIG_CHANGED"
        const val METHOD_GET_CONFIG = "get_config"
        const val METHOD_SAVE_CONFIG = "save_config"
        const val METHOD_GET_RUNTIME_STATUS = "get_runtime_status"
        const val METHOD_GET_RECENT_APPS = "get_recent_apps"
        const val METHOD_TRIGGER_CC_FLOAT_SCAN = "trigger_cc_float_scan"
        const val METHOD_CALIBRATE_CC_FLOAT_TILE = "calibrate_cc_float_tile"
        const val METHOD_GET_CC_CALIBRATION_STATUS = "get_cc_calibration_status"

        const val KEY_PAYLOAD = "payload"
        const val KEY_OK = "ok"
        const val KEY_SCAN_MODE = "scan_mode"
        const val KEY_CALIBRATION_METHOD = "calibration_method"
        const val KEY_STATUS_CODE = "status_code"
        const val KEY_CALIBRATION_IN_PROGRESS = "calibration_in_progress"
        const val KEY_CALIBRATION_NORM_X = "calibration_norm_x"
        const val KEY_CALIBRATION_NORM_Y = "calibration_norm_y"
        const val KEY_CALIBRATION_TIMESTAMP_MS = "calibration_timestamp_ms"

        const val STATUS_IDLE = "IDLE"
        const val STATUS_RUNNING = "RUNNING"
        const val STATUS_WAITING_TAP = "WAITING_TAP"
        const val STATUS_SUCCESS = "SUCCESS"
        const val STATUS_NOT_FOUND = "NOT_FOUND"
        const val STATUS_SERVICE_DISABLED = "SERVICE_DISABLED"
        const val STATUS_SERVICE_NOT_BOUND = "SERVICE_NOT_BOUND"
        const val STATUS_UNSUPPORTED_OS = "UNSUPPORTED_OS"

        const val SCAN_MODE_INTERNAL = "INTERNAL"
        const val SCAN_MODE_OCR = "OCR"
        const val KEY_SERVICE_ACTIVE = "service_active"
        const val KEY_IME_VISIBLE = "ime_visible"
        const val KEY_LANDSCAPE_ACTIVE = "landscape_active"
        const val KEY_FOREGROUND_PKG = "foreground_pkg"
        const val KEY_BLACKLISTED_ACTIVE = "blacklisted_active"
        const val KEY_RESIDENT_PID = "resident_pid"
        const val KEY_RECENT_APPS = "recent_apps"

        private val fileLock = Any()

        fun authority(context: Context): String = "${context.packageName}.config"

        fun configUri(context: Context): Uri =
            Uri.parse("content://${authority(context)}/config")

        internal fun readConfigRaw(context: Context): String = synchronized(fileLock) {
            val file = File(context.filesDir, CONFIG_FILE_NAME)
            if (!file.exists()) return ""
            return try {
                file.readText(Charsets.UTF_8)
            } catch (_: IOException) {
                ""
            }
        }

        internal fun writeConfigRaw(context: Context, serialized: String) = synchronized(fileLock) {
            val dir = context.filesDir
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, CONFIG_TMP_FILE_NAME)
            val target = File(dir, CONFIG_FILE_NAME)
            try {
                tmp.writeText(serialized, Charsets.UTF_8)
                if (!tmp.renameTo(target)) {
                    target.writeText(serialized, Charsets.UTF_8)
                    tmp.delete()
                }
            } catch (_: IOException) {
                runCatching { target.writeText(serialized, Charsets.UTF_8) }
            }
        }
    }
}

data class CcTileCalibrationState(
    val inProgress: Boolean = false,
    val statusCode: String = EdgeConfigProvider.STATUS_IDLE,
    val methodUsed: String = "",
    val normX: Float = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
    val normY: Float = EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD,
    val timestampMs: Long = 0L
)

/**
 * In-memory resident-process status & recent-app snapshot registry updated by
 * EdgeAccessibilityService and queried safely across processes by EdgeConfigProvider.
 */
object RuntimeStateRegistry {
    @Volatile
    private var status: RuntimeStatus = RuntimeStatus()

    @Volatile
    private var recentApps: List<String> = emptyList()

    @Volatile
    private var ccFloatScanHandler: ((String) -> Boolean)? = null

    @Volatile
    private var ccCalibrationState: CcTileCalibrationState = CcTileCalibrationState()

    fun registerCcFloatScanHandler(handler: ((String) -> Boolean)?) {
        ccFloatScanHandler = handler
    }

    fun dispatchCcFloatScan(mode: String): Boolean {
        return ccFloatScanHandler?.invoke(mode) ?: false
    }

    fun updateCcCalibrationState(state: CcTileCalibrationState) {
        ccCalibrationState = state
    }

    fun ccCalibrationSnapshot(): CcTileCalibrationState = ccCalibrationState

    fun update(
        serviceActive: Boolean = status.serviceActive,
        imeVisible: Boolean = status.imeVisible,
        landscapeActive: Boolean = status.landscapeActive,
        foregroundPackage: String = status.foregroundPackage,
        blacklistedActive: Boolean = status.blacklistedActive
    ) {
        status = RuntimeStatus(
            serviceActive = serviceActive,
            imeVisible = imeVisible,
            landscapeActive = landscapeActive,
            foregroundPackage = foregroundPackage,
            blacklistedActive = blacklistedActive,
            residentProcessPid = Process.myPid()
        )
    }

    fun updateRecentApps(packages: List<String>) {
        recentApps = packages.toList()
    }

    fun snapshot(): RuntimeStatus = status

    fun recentAppsSnapshot(): List<String> = recentApps
}

/**
 * High-level API used by both the resident service and :settings UI to read, save,
 * and observe EdgeConfig changes seamlessly across process boundaries.
 */
object ConfigStore {

    fun load(context: Context): EdgeConfig {
        val uri = EdgeConfigProvider.configUri(context)
        val raw = try {
            context.contentResolver
                .call(uri, EdgeConfigProvider.METHOD_GET_CONFIG, null, null)
                ?.getString(EdgeConfigProvider.KEY_PAYLOAD)
        } catch (_: Exception) {
            EdgeConfigProvider.readConfigRaw(context)
        }
        return EdgeConfig.deserialize(raw)
    }

    fun save(context: Context, config: EdgeConfig) {
        val sanitized = config.clamped().serialize()
        val uri = EdgeConfigProvider.configUri(context)
        val extras = Bundle(1).apply {
            putString(EdgeConfigProvider.KEY_PAYLOAD, sanitized)
        }
        try {
            context.contentResolver.call(
                uri,
                EdgeConfigProvider.METHOD_SAVE_CONFIG,
                null,
                extras
            )
        } catch (_: Exception) {
            EdgeConfigProvider.writeConfigRaw(context, sanitized)
        }
    }

    fun queryRuntimeStatus(context: Context): RuntimeStatus {
        val uri = EdgeConfigProvider.configUri(context)
        val bundle = try {
            context.contentResolver.call(
                uri,
                EdgeConfigProvider.METHOD_GET_RUNTIME_STATUS,
                null,
                null
            )
        } catch (_: Exception) {
            null
        } ?: return RuntimeStatus()
        return RuntimeStatus(
            serviceActive = bundle.getBoolean(EdgeConfigProvider.KEY_SERVICE_ACTIVE, false),
            imeVisible = bundle.getBoolean(EdgeConfigProvider.KEY_IME_VISIBLE, false),
            landscapeActive = bundle.getBoolean(EdgeConfigProvider.KEY_LANDSCAPE_ACTIVE, false),
            foregroundPackage = bundle.getString(EdgeConfigProvider.KEY_FOREGROUND_PKG, ""),
            blacklistedActive = bundle.getBoolean(EdgeConfigProvider.KEY_BLACKLISTED_ACTIVE, false),
            residentProcessPid = bundle.getInt(EdgeConfigProvider.KEY_RESIDENT_PID, 0)
        )
    }

    fun queryRecentApps(context: Context): List<String> {
        val uri = EdgeConfigProvider.configUri(context)
        val bundle = try {
            context.contentResolver.call(
                uri,
                EdgeConfigProvider.METHOD_GET_RECENT_APPS,
                null,
                null
            )
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return bundle.getStringArrayList(EdgeConfigProvider.KEY_RECENT_APPS).orEmpty()
    }

    fun requestControlCenterTileCalibration(
        context: Context,
        method: String
    ): Pair<Boolean, String> {
        val uri = EdgeConfigProvider.configUri(context)
        val extras = Bundle(1).apply {
            putString(EdgeConfigProvider.KEY_CALIBRATION_METHOD, method)
        }
        return try {
            val result = context.contentResolver.call(
                uri,
                EdgeConfigProvider.METHOD_CALIBRATE_CC_FLOAT_TILE,
                null,
                extras
            )
            val ok = result?.getBoolean(EdgeConfigProvider.KEY_OK, false) ?: false
            val code = result?.getString(EdgeConfigProvider.KEY_STATUS_CODE)
                ?: if (ok) EdgeConfigProvider.STATUS_RUNNING else EdgeConfigProvider.STATUS_SERVICE_NOT_BOUND
            ok to code
        } catch (_: Exception) {
            false to EdgeConfigProvider.STATUS_SERVICE_NOT_BOUND
        }
    }

    fun queryControlCenterCalibrationStatus(context: Context): CcTileCalibrationState {
        val uri = EdgeConfigProvider.configUri(context)
        val bundle = try {
            context.contentResolver.call(
                uri,
                EdgeConfigProvider.METHOD_GET_CC_CALIBRATION_STATUS,
                null,
                null
            )
        } catch (_: Exception) {
            null
        } ?: return CcTileCalibrationState()
        return CcTileCalibrationState(
            inProgress = bundle.getBoolean(EdgeConfigProvider.KEY_CALIBRATION_IN_PROGRESS, false),
            statusCode = bundle.getString(EdgeConfigProvider.KEY_STATUS_CODE)
                ?: EdgeConfigProvider.STATUS_IDLE,
            methodUsed = bundle.getString(EdgeConfigProvider.KEY_CALIBRATION_METHOD).orEmpty(),
            normX = bundle.getFloat(
                EdgeConfigProvider.KEY_CALIBRATION_NORM_X,
                EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD
            ),
            normY = bundle.getFloat(
                EdgeConfigProvider.KEY_CALIBRATION_NORM_Y,
                EdgeConfig.DEFAULT_CC_FLOAT_TILE_NORM_COORD
            ),
            timestampMs = bundle.getLong(EdgeConfigProvider.KEY_CALIBRATION_TIMESTAMP_MS, 0L)
        )
    }

    fun triggerCcFloatTileScan(context: Context, mode: String): Boolean {
        val mapped = if (mode.equals(EdgeConfigProvider.SCAN_MODE_OCR, ignoreCase = true) ||
            mode.equals(EdgeConfig.CC_TILE_METHOD_OCR, ignoreCase = true)
        ) {
            EdgeConfig.CC_TILE_METHOD_OCR
        } else {
            EdgeConfig.CC_TILE_METHOD_NODE
        }
        return requestControlCenterTileCalibration(context, mapped).first
    }

    fun registerObserver(
        context: Context,
        onChanged: (EdgeConfig) -> Unit
    ): ContentObserver {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                onChanged(load(context))
            }
        }
        context.contentResolver.registerContentObserver(
            EdgeConfigProvider.configUri(context),
            false,
            observer
        )
        return observer
    }

    fun unregisterObserver(context: Context, observer: ContentObserver) {
        runCatching {
            context.contentResolver.unregisterContentObserver(observer)
        }
    }
}
