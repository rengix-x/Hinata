package com.edgeflow.service.shizuku

import android.annotation.SuppressLint
import android.app.ActivityManager
import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.database.ContentObserver
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.UserHandle
import android.provider.Settings
import androidx.annotation.Keep
import com.example.BuildConfig
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Modifier
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.exitProcess

/**
 * Shizuku UserService running in an adb-privileged (shell UID 2000) process for
 * HyperOS navigation bar control and direct native scaled floating windows.
 *
 * Security & reliability guarantees:
 * - Strictly allowlists only the exact navigation bar settings/overlay/freeform commands.
 * - Invokes real HyperOS 3.0 native small-freeform APIs directly inside UID 2000 without
 *   marshaling ActivityOptions Bundles across AIDL boundaries.
 * - Records detailed execution trace for instant diagnostics via [dumpFreeformApi].
 * - Cleanly exits on Shizuku's destroy() signal so zero background processes remain.
 */
@Keep
class NavBarUserService() : INavBarControlService.Stub() {

    private var providedContext: Context? = null

    @Volatile
    private var hiddenApiExempted = false

    @Volatile
    private var lastExecutionTrace: String = "(no freeform execution recorded yet)"

    @Volatile
    private var preferredLaunchMethodIndex: Int = 0

    private val sysCtx: Context? by lazy { resolveSystemContext() }

    private val deadManLock = Any()
    private var deadManToken: IBinder? = null
    private var deadManRecipient: IBinder.DeathRecipient? = null
    @Volatile
    private var deadManRestoreNav: Boolean = false
    @Volatile
    private var deadManAutoRevive: Boolean = false
    private var a11yObserverThread: HandlerThread? = null
    private var a11yContentObserver: ContentObserver? = null
    private val a11ySensorHandling = AtomicBoolean(false)

    /**
     * Optional Context constructor supported by Shizuku v13+ UserService instantiation.
     */
    @Keep
    constructor(context: Context) : this() {
        providedContext = context
    }

    override fun destroy() {
        synchronized(deadManLock) {
            disarmDeadManSwitchLocked()
        }
        runCatching { exitProcess(0) }
    }

    override fun runCommand(cmd: String?): Int {
        val sanitized = cmd?.trim().orEmpty()
        if (!isAllowedCommand(sanitized)) {
            return EXIT_COMMAND_NOT_ALLOWED
        }
        return executeInternal(sanitized).first
    }

    override fun runCommandWithOutput(cmd: String?): String {
        val sanitized = cmd?.trim().orEmpty()
        if (!isAllowedCommand(sanitized)) {
            return "EXIT:$EXIT_COMMAND_NOT_ALLOWED\nDisallowed command"
        }
        val (exitCode, output) = executeInternal(sanitized)
        return "EXIT:$exitCode\n$output"
    }

    /**
     * Hardened self-only AccessibilityService reviver executed under Shizuku UID 2000.
     *
     * Security guarantees:
     * - Never accepts arbitrary package/component names; strictly locked to Hinata's own
     *   [BuildConfig.APPLICATION_ID] AccessibilityService component.
     * - Validates existing `enabled_accessibility_services` against [ACCESSIBILITY_SERVICES_REGEX]
     *   and invokes `settings` directly via argv (`ProcessBuilder`) without shell interpolation.
     * - Preserves all other enabled AccessibilityServices during both force-toggle and append.
     */
    override fun reviveOwnAccessibilityService(forceToggleRebind: Boolean): Boolean {
        val (readExit, rawServices) = executeArgs(
            "settings", "get", "secure", "enabled_accessibility_services"
        )
        if (readExit != 0 || !isValidAccessibilityServicesRaw(rawServices)) {
            return false
        }

        val updatedServices = if (forceToggleRebind) {
            val withoutSelf = buildAccessibilityServicesWithoutSelf(rawServices) ?: return false
            if (withoutSelf.isEmpty()) {
                val (delExit, _) = executeArgs(
                    "settings", "delete", "secure", "enabled_accessibility_services"
                )
                if (delExit != 0) {
                    executeArgs("settings", "put", "secure", "enabled_accessibility_services", "")
                }
            } else {
                val (tmpExit, _) = executeArgs(
                    "settings", "put", "secure", "enabled_accessibility_services", withoutSelf
                )
                if (tmpExit != 0) return false
            }
            runCatching { Thread.sleep(FORCE_REBIND_DELAY_MS) }
            buildAccessibilityServicesWithSelf(withoutSelf) ?: return false
        } else {
            buildAccessibilityServicesWithSelf(rawServices) ?: return false
        }

        val (putExit, _) = executeArgs(
            "settings", "put", "secure", "enabled_accessibility_services", updatedServices
        )
        if (putExit != 0) return false

        val (enableExit, _) = executeArgs(
            "settings", "put", "secure", "accessibility_enabled", "1"
        )
        return enableExit == 0
    }

    /**
     * Single-IPC, 0ms highest-priority system navigation restoration executed under UID 2000.
     * Runs HyperOS 3-button recovery first, then clears gesture line, overlays, statusbar
     * NavHide flags, OS back gesture insets, and Samsung gesture hint.
     */
    override fun restoreSystemNavigationImmediate(): Boolean {
        val commands = arrayOf(
            arrayOf("settings", "put", "global", "force_fsg_nav_bar", "0"),
            arrayOf("settings", "put", "global", "hide_gesture_line", "0"),
            arrayOf("cmd", "overlay", "enable", "com.android.internal.systemui.navbar.threebutton"),
            arrayOf("cmd", "statusbar", "send-disable-flag", "none"),
            arrayOf("cmd", "statusbar", "disable-for-setup", "false"),
            arrayOf("settings", "delete", "secure", "back_gesture_inset_scale_left"),
            arrayOf("settings", "delete", "secure", "back_gesture_inset_scale_right"),
            arrayOf("settings", "put", "global", "navigation_bar_gesture_hint", "1")
        )
        var anySuccess = false
        for (argv in commands) {
            val (exitCode, _) = executeArgs(*argv)
            if (exitCode == 0) {
                anySuccess = true
            }
        }
        return anySuccess
    }

    /**
     * Arms or disarms the UID 2000 Dead-Man's Switch (`IBinder.linkToDeath`) and direct
     * `ENABLED_ACCESSIBILITY_SERVICES` / `ACCESSIBILITY_ENABLED` ContentObserver sensor.
     *
     * If Hinata's main process dies via SIGKILL/OOM/crash OR Accessibility is turned off by the
     * system while armed:
     * 1. Immediately (0ms) restores 3-button & OS back navigation if [restoreNavOnDeath] is true.
     * 2. Attempts to auto-revive Hinata's AccessibilityService if [autoReviveOnDeath] is true.
     */
    override fun armDeadManSwitch(
        clientToken: IBinder?,
        restoreNavOnDeath: Boolean,
        autoReviveOnDeath: Boolean
    ) {
        var triggerImmediateRecovery = false
        synchronized(deadManLock) {
            disarmDeadManSwitchLocked()
            if (clientToken == null || (!restoreNavOnDeath && !autoReviveOnDeath)) {
                return
            }
            deadManRestoreNav = restoreNavOnDeath
            deadManAutoRevive = autoReviveOnDeath

            val recipient = IBinder.DeathRecipient {
                synchronized(deadManLock) {
                    disarmA11yObserverLocked()
                    deadManToken = null
                    deadManRecipient = null
                }
                if (restoreNavOnDeath) {
                    restoreSystemNavigationImmediate()
                }
                if (autoReviveOnDeath) {
                    runCatching { Thread.sleep(DEAD_MAN_REVIVE_DELAY_MS) }
                    reviveOwnAccessibilityService(true)
                }
            }

            val linked = runCatching {
                clientToken.linkToDeath(recipient, 0)
                true
            }.getOrDefault(false)

            if (linked) {
                deadManToken = clientToken
                deadManRecipient = recipient
                registerA11yObserverLocked()
            } else {
                // Client token is already dead! Recover immediately outside the lock.
                deadManRestoreNav = false
                deadManAutoRevive = false
                triggerImmediateRecovery = true
            }
        }

        if (triggerImmediateRecovery) {
            if (restoreNavOnDeath) {
                restoreSystemNavigationImmediate()
            }
            if (autoReviveOnDeath) {
                runCatching { Thread.sleep(DEAD_MAN_REVIVE_DELAY_MS) }
                reviveOwnAccessibilityService(true)
            }
        }
    }

    private fun disarmDeadManSwitchLocked() {
        val token = deadManToken
        val recipient = deadManRecipient
        deadManToken = null
        deadManRecipient = null
        deadManRestoreNav = false
        deadManAutoRevive = false
        if (token != null && recipient != null) {
            runCatching { token.unlinkToDeath(recipient, 0) }
        }
        disarmA11yObserverLocked()
    }

    private fun registerA11yObserverLocked() {
        val ctx = sysCtx ?: return
        val resolver = runCatching { ctx.contentResolver }.getOrNull() ?: return
        val thread = HandlerThread("hinata-uid2000-a11y-watch").apply {
            isDaemon = true
            start()
        }
        val observer = object : ContentObserver(Handler(thread.looper)) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                handleUid2000AccessibilitySettingChanged(ctx)
            }
        }
        val registered = runCatching {
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
                false,
                observer
            )
            resolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ACCESSIBILITY_ENABLED),
                false,
                observer
            )
            true
        }.getOrDefault(false)

        if (registered) {
            a11yObserverThread = thread
            a11yContentObserver = observer
        } else {
            runCatching { thread.quitSafely() }
        }
    }

    private fun disarmA11yObserverLocked() {
        val observer = a11yContentObserver
        val thread = a11yObserverThread
        a11yContentObserver = null
        a11yObserverThread = null
        if (observer != null) {
            runCatching { sysCtx?.contentResolver?.unregisterContentObserver(observer) }
        }
        if (thread != null) {
            runCatching { thread.quitSafely() }
        }
    }

    private fun handleUid2000AccessibilitySettingChanged(ctx: Context) {
        val restoreNav = deadManRestoreNav
        val autoRevive = deadManAutoRevive
        if (!restoreNav && !autoRevive) return

        val enabledRaw = runCatching {
            Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        }.getOrNull()
        val enabledFlag = runCatching {
            Settings.Secure.getInt(ctx.contentResolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        }.getOrDefault(1)

        if (isOwnAccessibilityEnabledInList(enabledRaw, enabledFlag)) {
            return
        }
        if (!a11ySensorHandling.compareAndSet(false, true)) {
            return
        }

        Thread({
            try {
                if (restoreNav) {
                    restoreSystemNavigationImmediate()
                }
                if (autoRevive) {
                    reviveOwnAccessibilityService(true)
                }
            } finally {
                runCatching { Thread.sleep(A11Y_SENSOR_COOLDOWN_MS) }
                a11ySensorHandling.set(false)
            }
        }, "hinata-uid2000-a11y-recover").apply {
            isDaemon = true
            start()
        }
    }

    override fun startWithOptions(intent: Intent?, opts: Bundle?, userId: Int): Boolean {
        if (intent == null) return false
        return runCatching {
            ensureHiddenApiExemptions()
            val ctx = sysCtx ?: return false

            val safeIntent = Intent(intent).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            applyMiuiFreeformFlags(safeIntent)

            if (userId > 0) {
                val startedAsUser = runCatching {
                    val userHandle = createUserHandle(userId) ?: return@runCatching false
                    val method = ctx.javaClass.getMethod(
                        "startActivityAsUser",
                        Intent::class.java,
                        Bundle::class.java,
                        UserHandle::class.java
                    )
                    method.isAccessible = true
                    method.invoke(ctx, safeIntent, opts, userHandle)
                    true
                }.getOrDefault(false)
                if (startedAsUser) return true
            }

            ctx.startActivity(safeIntent, opts)
            true
        }.getOrDefault(false)
    }

    override fun getForegroundTaskId(): Int {
        return runCatching {
            val task = fetchTopRunningTaskInfo() ?: return -1
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                task.taskId
            } else {
                @Suppress("DEPRECATION")
                task.id
            }
        }.getOrDefault(-1)
    }

    override fun getForegroundPackage(): String {
        return runCatching {
            val task = fetchTopRunningTaskInfo() ?: return ""
            task.topActivity?.packageName
                ?: task.baseActivity?.packageName
                ?: ""
        }.getOrDefault("")
    }

    override fun moveTaskToFreeform(taskId: Int): Boolean {
        if (taskId < 0) return false
        return runCatching {
            ensureHiddenApiExemptions()
            val ctx = sysCtx
            val classNames = arrayOf(
                "android.app.MiuiFreeFormManager",
                "miui.app.MiuiFreeFormManager"
            )
            for (className in classNames) {
                val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue
                val methods = getDeclaredMethodsSafe(clazz).filter { method ->
                    val lower = method.name.lowercase()
                    lower.contains("startfreeform") ||
                        lower.contains("enterfreeform") ||
                        lower.contains("tomiuifreeform") ||
                        lower.contains("miuifreeform")
                }.sortedBy { it.parameterTypes.size }

                for (method in methods) {
                    val args = buildMoveTaskArgs(method.parameterTypes, ctx, taskId) ?: continue
                    val target: Any? = if (Modifier.isStatic(method.modifiers)) {
                        null
                    } else {
                        obtainSingletonInstance(clazz) ?: continue
                    }
                    val invoked = runCatching {
                        method.isAccessible = true
                        when (val result = method.invoke(target, *args)) {
                            is Boolean -> result
                            is Int -> result >= 0
                            null -> true
                            else -> true
                        }
                    }.getOrDefault(false)
                    if (invoked) return true
                }
            }
            false
        }.getOrDefault(false)
    }

    /**
     * Launches [packageName] in HyperOS native scaled small-freeform mode directly from UID 2000.
     * Tries all 5 verified POCO F7 / HyperOS 3.0 APIs in priority order, returning immediately on
     * first success while recording every attempt to [lastExecutionTrace].
     */
    override fun launchNativeHyperOsFreeform(packageName: String?, launchIntent: Intent?): Boolean {
        ensureHiddenApiExemptions()
        val trace = StringBuilder(512)
        val pkg = packageName?.trim().orEmpty()
        trace.appendLine("op=launchNativeHyperOsFreeform pkg='$pkg' uid=${android.os.Process.myUid()}")
        val ok = launchNativeHyperOsFreeformInternal(
            pkg = pkg,
            launchIntent = launchIntent,
            extraFlags = 0,
            trace = trace
        )
        trace.appendLine("result=$ok")
        recordExecutionTrace(trace.toString().trim())
        return ok
    }

    /**
     * Converts the currently active foreground app into a HyperOS native scaled small-freeform
     * window directly from UID 2000.
     */
    override fun floatForegroundNativeHyperOs(
        fallbackPackageName: String?,
        fallbackLaunchIntent: Intent?
    ): Boolean {
        ensureHiddenApiExemptions()
        val trace = StringBuilder(768)
        val fgPkg = getForegroundPackage().trim()
        val fgTaskId = getForegroundTaskId()
        trace.appendLine(
            "op=floatForegroundNativeHyperOs fgPkg='$fgPkg' fgTaskId=$fgTaskId " +
                "fallbackPkg='${fallbackPackageName?.trim().orEmpty()}' uid=${android.os.Process.myUid()}"
        )

        val ctx = sysCtx
        if (ctx == null) {
            trace.appendLine("ABORT: sysCtx is null")
            recordExecutionTrace(trace.toString().trim())
            return false
        }

        if (fgPkg.isNotEmpty() && isNonFloatableForegroundPackage(ctx, fgPkg)) {
            trace.appendLine("ABORT: non-floatable foreground pkg='$fgPkg'")
            recordExecutionTrace(trace.toString().trim())
            return false
        }

        val beforeCount = getFreeFormStackCount()
        trace.appendLine("beforeFreeFormStackCount=$beforeCount")

        // Method 1 (Primary — verified enterMiniFreeformReason=from_control_center on HyperOS 3.0):
        // MiuiMultiWindowUtils.startSmallFreeformForControlCenter(sysCtx)
        val m1Invoked = runCatching {
            val clazz = Class.forName(CLASS_MIUI_MULTI_WINDOW_UTILS)
            val method = findExactStaticMethod(
                clazz = clazz,
                name = "startSmallFreeformForControlCenter",
                matcher = { p -> p.size == 1 && Context::class.java.isAssignableFrom(p[0]) }
            ) ?: error("method startSmallFreeformForControlCenter(Context) not found")
            method.isAccessible = true
            val res = method.invoke(null, ctx)
            val str = res as? String
            trace.appendLine("F1 startSmallFreeformForControlCenter(ctx) -> ret=${str?.let { "'$it'" } ?: res}")
            true
        }.getOrElse { t ->
            trace.appendLine("F1 startSmallFreeformForControlCenter(ctx) -> ERR ${formatThrowable(t)}")
            false
        }
        if (m1Invoked) {
            runCatching { Thread.sleep(90L) }
            val afterCount = getFreeFormStackCount()
            val fgFreeform = isForegroundTaskInFreeform()
            trace.appendLine("F1 verify(90ms): afterCount=$afterCount fgFreeform=$fgFreeform")
            if ((beforeCount >= 0 && afterCount > beforeCount) || fgFreeform) {
                trace.appendLine("result=true (via F1 verified — skipped double invoke)")
                recordExecutionTrace(trace.toString().trim())
                return true
            }
        }

        // Method 2 (Fallback): MiuiMultiWindowUtils.startSmallFreeform(sysCtx)
        val m2Invoked = runCatching {
            val clazz = Class.forName(CLASS_MIUI_MULTI_WINDOW_UTILS)
            val method = findExactStaticMethod(
                clazz = clazz,
                name = "startSmallFreeform",
                matcher = { p -> p.size == 1 && Context::class.java.isAssignableFrom(p[0]) }
            ) ?: error("method startSmallFreeform(Context) not found")
            method.isAccessible = true
            val res = method.invoke(null, ctx)
            trace.appendLine("F2 startSmallFreeform(ctx) -> ret=$res")
            true
        }.getOrElse { t ->
            trace.appendLine("F2 startSmallFreeform(ctx) -> ERR ${formatThrowable(t)}")
            false
        }
        if (m2Invoked) {
            runCatching { Thread.sleep(90L) }
            val afterCount = getFreeFormStackCount()
            val fgFreeform = isForegroundTaskInFreeform()
            trace.appendLine("F2 verify(90ms): afterCount=$afterCount fgFreeform=$fgFreeform")
            if ((beforeCount >= 0 && afterCount > beforeCount) || fgFreeform) {
                trace.appendLine("result=true (via F2 verified)")
                recordExecutionTrace(trace.toString().trim())
                return true
            }
        }

        // Method 3: MiuiMultiWindowUtils.startSmallFreeformForRecent(taskId, 0, rectOrNull)
        if (fgTaskId >= 0) {
            val m3Ok = runCatching {
                val clazz = Class.forName(CLASS_MIUI_MULTI_WINDOW_UTILS)
                val method = findExactStaticMethod(
                    clazz = clazz,
                    name = "startSmallFreeformForRecent",
                    matcher = { p ->
                        p.size == 3 &&
                            isIntType(p[0]) &&
                            isIntType(p[1]) &&
                            p[2] == Rect::class.java
                    }
                ) ?: error("method startSmallFreeformForRecent(int, int, Rect) not found")
                method.isAccessible = true

                val nullRectAttempt = runCatching {
                    method.invoke(null, fgTaskId, 0, null)
                    true
                }
                if (nullRectAttempt.isSuccess) {
                    trace.appendLine("F3 startSmallFreeformForRecent($fgTaskId, 0, null) -> OK")
                    true
                } else {
                    val errNull = nullRectAttempt.exceptionOrNull()
                    trace.appendLine(
                        "F3a startSmallFreeformForRecent($fgTaskId, 0, null) -> ERR ${errNull?.let(::formatThrowable)}"
                    )
                    val resolvedRect = obtainSmallFreeformRect(clazz, ctx)
                    if (resolvedRect != null) {
                        method.invoke(null, fgTaskId, 0, resolvedRect)
                        trace.appendLine("F3b startSmallFreeformForRecent($fgTaskId, 0, $resolvedRect) -> OK")
                        true
                    } else {
                        false
                    }
                }
            }.getOrElse { t ->
                trace.appendLine("F3 startSmallFreeformForRecent($fgTaskId, 0, ...) -> ERR ${formatThrowable(t)}")
                false
            }
            if (m3Ok) {
                trace.appendLine("result=true (via F3)")
                recordExecutionTrace(trace.toString().trim())
                return true
            }
        } else {
            trace.appendLine("F3 startSmallFreeformForRecent skipped (fgTaskId=$fgTaskId)")
        }

        // Method 4: Fall back to 2.1 launchNativeHyperOsFreeform with FLAG_ACTIVITY_REORDER_TO_FRONT
        val targetPkg = fgPkg.ifEmpty { fallbackPackageName?.trim().orEmpty() }
        if (targetPkg.isEmpty()) {
            trace.appendLine("F4 skipped: targetPkg is empty")
            trace.appendLine("result=false")
            recordExecutionTrace(trace.toString().trim())
            return false
        }

        val targetIntent = if (fallbackLaunchIntent != null &&
            targetPkg == fallbackPackageName?.trim().orEmpty()
        ) {
            Intent(fallbackLaunchIntent)
        } else {
            runCatching { ctx.packageManager.getLaunchIntentForPackage(targetPkg) }.getOrNull()
                ?: fallbackLaunchIntent?.let { Intent(it) }
        }

        trace.appendLine("F4 delegating to launchNativeHyperOsFreeform(pkg='$targetPkg', REORDER_TO_FRONT)")
        val m4Ok = launchNativeHyperOsFreeformInternal(
            pkg = targetPkg,
            launchIntent = targetIntent,
            extraFlags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT,
            trace = trace
        )
        trace.appendLine("result=$m4Ok (via F4)")
        recordExecutionTrace(trace.toString().trim())
        return m4Ok
    }

    private fun launchNativeHyperOsFreeformInternal(
        pkg: String,
        launchIntent: Intent?,
        extraFlags: Int,
        trace: StringBuilder
    ): Boolean {
        if (pkg.isEmpty()) {
            trace.appendLine("ABORT: packageName is empty")
            return false
        }
        val ctx = sysCtx
        if (ctx == null) {
            trace.appendLine("ABORT: sysCtx is null")
            return false
        }

        val resolvedIntent = launchIntent?.let { Intent(it) }
            ?: runCatching { ctx.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
        val safeIntent = resolvedIntent?.apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED or
                    extraFlags
            )
            applyMiuiFreeformFlags(this)
        }
        if (safeIntent == null) {
            trace.appendLine("WARN: safeIntent is null for pkg='$pkg'; ActivityOptions routes will be skipped")
        }

        val orderedIndices = buildLaunchMethodOrder(preferredLaunchMethodIndex)
        for (methodIdx in orderedIndices) {
            val ok = tryLaunchMethodByIndex(methodIdx, ctx, pkg, safeIntent, trace)
            if (ok) {
                preferredLaunchMethodIndex = methodIdx
                return true
            }
        }
        preferredLaunchMethodIndex = 0
        return false
    }

    private fun buildLaunchMethodOrder(preferred: Int): IntArray {
        val defaults = intArrayOf(1, 2, 3, 4, 5)
        if (preferred !in 1..5) return defaults
        val result = IntArray(5)
        result[0] = preferred
        var pos = 1
        for (idx in defaults) {
            if (idx != preferred) {
                result[pos++] = idx
            }
        }
        return result
    }

    private fun tryLaunchMethodByIndex(
        methodIdx: Int,
        ctx: Context,
        pkg: String,
        safeIntent: Intent?,
        trace: StringBuilder
    ): Boolean = when (methodIdx) {
        // Method 1: miui.app.MiuiFreeFormManager.getActivityOptions(sysCtx, pkg, true, false) -> startActivity
        1 -> if (safeIntent == null) false else runCatching {
            val clazz = Class.forName(CLASS_MIUI_FREEFORM_MANAGER)
            val method = findExactStaticMethod(
                clazz = clazz,
                name = "getActivityOptions",
                matcher = { p ->
                    p.size == 4 &&
                        Context::class.java.isAssignableFrom(p[0]) &&
                        p[1] == String::class.java &&
                        isBooleanType(p[2]) &&
                        isBooleanType(p[3])
                }
            ) ?: error("4-arg getActivityOptions not found on $CLASS_MIUI_FREEFORM_MANAGER")
            method.isAccessible = true
            val bundle = unwrapOptionsBundle(method.invoke(null, ctx, pkg, true, false))
            if (bundle == null || bundle.isEmpty) {
                trace.appendLine("M1 MiuiFreeFormManager.getActivityOptions(4) -> empty/null bundle")
                false
            } else {
                ctx.startActivity(safeIntent, bundle)
                trace.appendLine("M1 MiuiFreeFormManager.getActivityOptions(4) -> OK keys=${bundle.keySet()}")
                true
            }
        }.getOrElse { t ->
            trace.appendLine("M1 MiuiFreeFormManager.getActivityOptions(4) -> ERR ${formatThrowable(t)}")
            false
        }

        // Method 2: android.util.MiuiMultiWindowUtils.getActivityOptions(sysCtx, pkg, true, false) -> startActivity
        2 -> if (safeIntent == null) false else runCatching {
            val clazz = Class.forName(CLASS_MIUI_MULTI_WINDOW_UTILS)
            val method = findExactStaticMethod(
                clazz = clazz,
                name = "getActivityOptions",
                matcher = { p ->
                    p.size == 4 &&
                        Context::class.java.isAssignableFrom(p[0]) &&
                        p[1] == String::class.java &&
                        isBooleanType(p[2]) &&
                        isBooleanType(p[3])
                }
            ) ?: error("4-arg getActivityOptions not found on $CLASS_MIUI_MULTI_WINDOW_UTILS")
            method.isAccessible = true
            val bundle = unwrapOptionsBundle(method.invoke(null, ctx, pkg, true, false))
            if (bundle == null || bundle.isEmpty) {
                trace.appendLine("M2 MiuiMultiWindowUtils.getActivityOptions(4) -> empty/null bundle")
                false
            } else {
                ctx.startActivity(safeIntent, bundle)
                trace.appendLine("M2 MiuiMultiWindowUtils.getActivityOptions(4) -> OK keys=${bundle.keySet()}")
                true
            }
        }.getOrElse { t ->
            trace.appendLine("M2 MiuiMultiWindowUtils.getActivityOptions(4) -> ERR ${formatThrowable(t)}")
            false
        }

        // Method 3: android.util.MiuiMultiWindowUtils.startSmallFreeform(sysCtx, pkg)
        3 -> runCatching {
            val clazz = Class.forName(CLASS_MIUI_MULTI_WINDOW_UTILS)
            val method = findExactStaticMethod(
                clazz = clazz,
                name = "startSmallFreeform",
                matcher = { p ->
                    p.size == 2 &&
                        Context::class.java.isAssignableFrom(p[0]) &&
                        p[1] == String::class.java
                }
            ) ?: error("2-arg startSmallFreeform(Context, String) not found")
            method.isAccessible = true
            val res = method.invoke(null, ctx, pkg)
            trace.appendLine("M3 MiuiMultiWindowUtils.startSmallFreeform(ctx, '$pkg') -> ret=$res")
            res is Int && res >= 0
        }.getOrElse { t ->
            trace.appendLine("M3 MiuiMultiWindowUtils.startSmallFreeform(ctx, '$pkg') -> ERR ${formatThrowable(t)}")
            false
        }

        // Method 4: android.util.MiuiMultiWindowUtils.startSmallFreeformVersion2(sysCtx, 0, pkg)
        4 -> runCatching {
            val clazz = Class.forName(CLASS_MIUI_MULTI_WINDOW_UTILS)
            val method = findExactStaticMethod(
                clazz = clazz,
                name = "startSmallFreeformVersion2",
                matcher = { p ->
                    p.size == 3 &&
                        Context::class.java.isAssignableFrom(p[0]) &&
                        isIntType(p[1]) &&
                        p[2] == String::class.java
                }
            ) ?: error("3-arg startSmallFreeformVersion2(Context, int, String) not found")
            method.isAccessible = true
            val res = method.invoke(null, ctx, 0, pkg)
            trace.appendLine("M4 MiuiMultiWindowUtils.startSmallFreeformVersion2(ctx, 0, '$pkg') -> ret=$res")
            when {
                method.returnType == Void.TYPE -> true
                res is String -> res.isNotBlank()
                res is Int -> res >= 0
                res is Boolean -> res
                else -> res != null
            }
        }.getOrElse { t ->
            trace.appendLine("M4 MiuiMultiWindowUtils.startSmallFreeformVersion2(ctx, 0, '$pkg') -> ERR ${formatThrowable(t)}")
            false
        }

        // Method 5: android.util.MiuiMultiWindowUtils.getActivityOptions(sysCtx, pkg, true) (3-arg) -> startActivity
        5 -> if (safeIntent == null) false else runCatching {
            val clazz = Class.forName(CLASS_MIUI_MULTI_WINDOW_UTILS)
            val method = findExactStaticMethod(
                clazz = clazz,
                name = "getActivityOptions",
                matcher = { p ->
                    p.size == 3 &&
                        Context::class.java.isAssignableFrom(p[0]) &&
                        p[1] == String::class.java &&
                        isBooleanType(p[2])
                }
            ) ?: error("3-arg getActivityOptions not found on $CLASS_MIUI_MULTI_WINDOW_UTILS")
            method.isAccessible = true
            val bundle = unwrapOptionsBundle(method.invoke(null, ctx, pkg, true))
            if (bundle == null || bundle.isEmpty) {
                trace.appendLine("M5 MiuiMultiWindowUtils.getActivityOptions(3) -> empty/null bundle")
                false
            } else {
                ctx.startActivity(safeIntent, bundle)
                trace.appendLine("M5 MiuiMultiWindowUtils.getActivityOptions(3) -> OK keys=${bundle.keySet()}")
                true
            }
        }.getOrElse { t ->
            trace.appendLine("M5 MiuiMultiWindowUtils.getActivityOptions(3) -> ERR ${formatThrowable(t)}")
            false
        }

        else -> false
    }

    override fun dumpFreeformApi(): String {
        ensureHiddenApiExemptions()
        val sb = StringBuilder(4096)
        sb.appendLine("=== Last Freeform Execution Trace ===")
        sb.appendLine(readLastExecutionTrace())
        sb.appendLine()
        sb.appendLine("=== Hinata HyperOS Freeform API Dump ===")
        sb.appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
        sb.appendLine("Android: ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        sb.appendLine("HyperOS: ${readSystemProperty("ro.mi.os.version.name")} / MIUI: ${readSystemProperty("ro.miui.ui.version.name")}")
        sb.appendLine("UID: ${android.os.Process.myUid()} | sysCtx=${sysCtx?.javaClass?.name ?: "null"}")
        sb.appendLine("Foreground: pkg=${getForegroundPackage()} taskId=${getForegroundTaskId()}")
        sb.appendLine()

        val fullInspectClasses = arrayOf(
            CLASS_MIUI_MULTI_WINDOW_UTILS,
            "miui.util.MiuiMultiWindowUtils",
            "android.util.MiuiMultiWindowAdapter",
            "android.app.MiuiFreeFormManager",
            CLASS_MIUI_FREEFORM_MANAGER
        )
        for (className in fullInspectClasses) {
            appendClassDump(sb, className, nameFilter = null)
        }

        appendClassDump(
            sb,
            "android.app.ActivityOptions",
            nameFilter = { name ->
                val l = name.lowercase()
                l.contains("miui") || l.contains("freeform") || l.contains("window") ||
                    l.contains("multi") || l.contains("scale") || l.contains("launch")
            }
        )
        appendClassDump(
            sb,
            "android.content.Intent",
            nameFilter = { name ->
                val l = name.lowercase()
                l.contains("miui") || l.contains("freeform")
            }
        )

        sb.appendLine("--- getAllFreeFormStackInfo ---")
        sb.appendLine(dumpCurrentFreeFormStackInfo())
        return sb.toString().trim()
    }

    private fun recordExecutionTrace(traceText: String) {
        lastExecutionTrace = traceText
        // Also persist to shell-writable temp file so trace survives UserService unbind/respawn
        runCatching {
            File(TRACE_PERSIST_PATH).writeText(traceText.take(MAX_TRACE_CHARS))
        }
    }

    private fun readLastExecutionTrace(): String {
        val current = lastExecutionTrace
        if (current.isNotBlank() && current != DEFAULT_TRACE_PLACEHOLDER) {
            return current
        }
        val persisted = runCatching {
            val file = File(TRACE_PERSIST_PATH)
            if (file.isFile) file.readText().trim() else ""
        }.getOrDefault("")
        return persisted.ifBlank { current }
    }

    private fun findExactStaticMethod(
        clazz: Class<*>,
        name: String,
        matcher: (Array<Class<*>>) -> Boolean
    ): Method? {
        return getDeclaredMethodsSafe(clazz).firstOrNull { method ->
            method.name == name &&
                Modifier.isStatic(method.modifiers) &&
                matcher(method.parameterTypes)
        }
    }

    private fun unwrapOptionsBundle(result: Any?): Bundle? {
        return when (result) {
            is ActivityOptions -> result.toBundle()
            is Bundle -> result
            else -> null
        }
    }

    private fun obtainSmallFreeformRect(utilsClass: Class<*>, ctx: Context): Rect? {
        val methods = getDeclaredMethodsSafe(utilsClass)
            .filter { it.name.contains("getSmallFreeformRect", ignoreCase = true) && Modifier.isStatic(it.modifiers) }
            .sortedBy { it.parameterTypes.size }
        for (method in methods) {
            val p = method.parameterTypes
            val args: Array<Any?> = when {
                p.isEmpty() -> emptyArray()
                p.size == 1 && Context::class.java.isAssignableFrom(p[0]) -> arrayOf(ctx)
                p.size == 2 && Context::class.java.isAssignableFrom(p[0]) && isBooleanType(p[1]) -> arrayOf(ctx, false)
                else -> continue
            }
            val rect = runCatching {
                method.isAccessible = true
                method.invoke(null, *args) as? Rect
            }.getOrNull()
            if (rect != null && !rect.isEmpty) return rect
        }
        return null
    }

    private fun isNonFloatableForegroundPackage(ctx: Context, pkg: String): Boolean {
        if (pkg == BuildConfig.APPLICATION_ID ||
            pkg == "com.android.systemui" ||
            pkg == "com.miui.home" ||
            pkg == "com.mi.android.globallauncher"
        ) {
            return true
        }
        val homePkg = runCatching {
            val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            ctx.packageManager.resolveActivity(homeIntent, 0)?.activityInfo?.packageName
        }.getOrNull()
        return !homePkg.isNullOrBlank() && pkg == homePkg
    }

    private fun formatThrowable(t: Throwable): String {
        val root = if (t is InvocationTargetException && t.targetException != null) {
            t.targetException
        } else {
            t
        }
        return "${root.javaClass.simpleName}: ${root.message}"
    }

    private fun ensureHiddenApiExemptions() {
        if (hiddenApiExempted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        hiddenApiExempted = true
    }

    private fun resolveSystemContext(): Context? {
        return runCatching {
            ensureHiddenApiExemptions()
            providedContext?.let { return it }

            if (Looper.getMainLooper() == null) {
                @Suppress("DEPRECATION")
                runCatching { Looper.prepareMainLooper() }
            }

            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentThread = runCatching {
                val m = activityThreadClass.getDeclaredMethod("currentActivityThread")
                m.isAccessible = true
                m.invoke(null)
            }.getOrNull()

            val activityThread = currentThread ?: runCatching {
                val m = activityThreadClass.getDeclaredMethod("systemMain")
                m.isAccessible = true
                m.invoke(null)
            }.getOrNull() ?: return null

            val getSystemContext = activityThreadClass.getDeclaredMethod("getSystemContext")
            getSystemContext.isAccessible = true
            getSystemContext.invoke(activityThread) as? Context
        }.getOrNull()
    }

    private fun applyMiuiFreeformFlags(intent: Intent) {
        val candidates = arrayOf("addMiuiFlags", "setMiuiFlags")
        for (methodName in candidates) {
            val applied = runCatching {
                val method = getDeclaredMethodsSafe(Intent::class.java).firstOrNull { m ->
                    m.name == methodName &&
                        m.parameterTypes.size == 1 &&
                        isIntType(m.parameterTypes[0])
                } ?: return@runCatching false
                method.isAccessible = true
                method.invoke(intent, MIUI_FLAG_FREEFORM)
                true
            }.getOrDefault(false)
            if (applied) return
        }
    }

    private fun createUserHandle(userId: Int): UserHandle? {
        return runCatching {
            val ofMethod = UserHandle::class.java.getMethod("of", Int::class.javaPrimitiveType)
            ofMethod.invoke(null, userId) as? UserHandle
        }.getOrNull() ?: runCatching {
            val ctor = UserHandle::class.java.getDeclaredConstructor(Int::class.javaPrimitiveType)
            ctor.isAccessible = true
            ctor.newInstance(userId)
        }.getOrNull()
    }

    @SuppressLint("BlockedPrivateApi", "PrivateApi")
    private fun fetchTopRunningTaskInfo(): ActivityManager.RunningTaskInfo? {
        ensureHiddenApiExemptions()
        val atmService = runCatching {
            val atmClass = Class.forName("android.app.ActivityTaskManager")
            val getService = getDeclaredMethodsSafe(atmClass).firstOrNull { m ->
                m.name == "getService" && m.parameterTypes.isEmpty()
            } ?: return null
            getService.isAccessible = true
            getService.invoke(null)
        }.getOrNull() ?: return null

        val methods = buildList {
            addAll(getDeclaredMethodsSafe(atmService.javaClass))
            atmService.javaClass.interfaces.forEach { iface ->
                addAll(getDeclaredMethodsSafe(iface))
            }
        }
            .filter { it.name == "getTasks" }
            .distinctBy { it.parameterTypes.joinToString(",") { c -> c.name } }
            .sortedBy { it.parameterTypes.size }

        for (method in methods) {
            val args = buildGetTasksArgs(method.parameterTypes) ?: continue
            val list = runCatching {
                method.isAccessible = true
                method.invoke(atmService, *args) as? List<*>
            }.getOrNull()
            val top = list?.firstOrNull() as? ActivityManager.RunningTaskInfo
            if (top != null) return top
        }
        return null
    }

    /**
     * Returns the number of active HyperOS freeform stacks on display 0 via
     * `MiuiFreeFormManager.getAllFreeFormStackInfosOnDisplay(0)`, or `-1` if unavailable.
     */
    private fun getFreeFormStackCount(): Int {
        ensureHiddenApiExemptions()
        val candidates = arrayOf(
            CLASS_MIUI_FREEFORM_MANAGER,
            "android.app.MiuiFreeFormManager"
        )
        for (className in candidates) {
            val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue
            val method = findExactStaticMethod(
                clazz = clazz,
                name = "getAllFreeFormStackInfosOnDisplay",
                matcher = { p -> p.size == 1 && isIntType(p[0]) }
            ) ?: continue
            val res = runCatching {
                method.isAccessible = true
                method.invoke(null, 0)
            }.getOrNull()
            if (res is List<*>) {
                return res.size
            }
            // Method existed and returned null -> 0 active freeform stacks
            return 0
        }
        return -1
    }

    /**
     * Checks whether the current top running task is in `WINDOWING_MODE_FREEFORM` (5).
     */
    private fun isForegroundTaskInFreeform(): Boolean {
        val task = fetchTopRunningTaskInfo() ?: return false
        // 1. Direct RunningTaskInfo.getWindowingMode() if exposed
        val directMode = runCatching {
            val m = getDeclaredMethodsSafe(task.javaClass).firstOrNull {
                it.name == "getWindowingMode" && it.parameterTypes.isEmpty()
            } ?: return@runCatching -1
            m.isAccessible = true
            (m.invoke(task) as? Int) ?: -1
        }.getOrDefault(-1)
        if (directMode == WINDOWING_MODE_FREEFORM) return true

        // 2. TaskInfo.configuration.windowConfiguration.getWindowingMode()
        val cfgMode = runCatching {
            val cfgField = task.javaClass.getField("configuration")
            val cfg = cfgField.get(task) ?: return@runCatching -1
            val wcField = cfg.javaClass.getField("windowConfiguration")
            val wc = wcField.get(cfg) ?: return@runCatching -1
            val m = wc.javaClass.getMethod("getWindowingMode")
            (m.invoke(wc) as? Int) ?: -1
        }.getOrDefault(-1)
        return cfgMode == WINDOWING_MODE_FREEFORM
    }

    private fun buildGetTasksArgs(paramTypes: Array<Class<*>>): Array<Any?>? {
        if (paramTypes.isEmpty()) return null
        var intIndex = 0
        val args = arrayOfNulls<Any>(paramTypes.size)
        for (i in paramTypes.indices) {
            val type = paramTypes[i]
            when {
                isIntType(type) -> {
                    args[i] = when (intIndex++) {
                        0 -> 1 // maxNum = 1
                        else -> -1 // displayId = INVALID_DISPLAY (all displays)
                    }
                }
                isBooleanType(type) -> {
                    args[i] = false
                }
                else -> return null
            }
        }
        return args
    }

    private fun buildMoveTaskArgs(
        paramTypes: Array<Class<*>>,
        ctx: Context?,
        taskId: Int
    ): Array<Any?>? {
        if (paramTypes.isEmpty()) return null

        // Direct (Bundle) or (Context, Bundle)
        if (paramTypes.size == 1 && paramTypes[0] == Bundle::class.java) {
            return arrayOf(createMoveTaskBundle(taskId))
        }
        if (paramTypes.size == 2 &&
            Context::class.java.isAssignableFrom(paramTypes[0]) &&
            paramTypes[1] == Bundle::class.java
        ) {
            val safeCtx = ctx ?: return null
            return arrayOf(safeCtx, createMoveTaskBundle(taskId))
        }

        // (Int), (Int, Int), (Int, Int, Int), or prefixed with Context
        var startIdx = 0
        val args = arrayOfNulls<Any>(paramTypes.size)
        if (Context::class.java.isAssignableFrom(paramTypes[0])) {
            val safeCtx = ctx ?: return null
            args[0] = safeCtx
            startIdx = 1
        }

        val intCount = paramTypes.size - startIdx
        if (intCount !in 1..3) return null
        for (i in startIdx until paramTypes.size) {
            if (!isIntType(paramTypes[i])) return null
            args[i] = if (i == startIdx) taskId else 0
        }
        return args
    }

    private fun createMoveTaskBundle(taskId: Int): Bundle {
        return Bundle().apply {
            putInt("taskId", taskId)
            putInt("miui.freeform.taskId", taskId)
            putInt("windowingMode", 5)
        }
    }

    private fun obtainSingletonInstance(clazz: Class<*>): Any? {
        val factoryNames = arrayOf("getInstance", "getService")
        for (name in factoryNames) {
            val instance = runCatching {
                val m = getDeclaredMethodsSafe(clazz).firstOrNull {
                    it.name == name && it.parameterTypes.isEmpty() && Modifier.isStatic(it.modifiers)
                } ?: return@runCatching null
                m.isAccessible = true
                m.invoke(null)
            }.getOrNull()
            if (instance != null) return instance
        }
        return null
    }

    private fun appendClassDump(
        sb: StringBuilder,
        className: String,
        nameFilter: ((String) -> Boolean)?
    ) {
        val clazz = runCatching { Class.forName(className) }.getOrNull()
        if (clazz == null) {
            sb.appendLine("[$className] NOT FOUND")
            return
        }
        sb.appendLine("[$className]")
        val methods = getDeclaredMethodsSafe(clazz)
            .filter { nameFilter == null || nameFilter(it.name) }
            .sortedWith(compareBy<Method> { it.name }.thenByDescending { it.parameterTypes.size })
        for (m in methods) {
            val params = m.parameterTypes.joinToString(", ") { it.simpleName }
            val staticTag = if (Modifier.isStatic(m.modifiers)) "static " else ""
            sb.appendLine("  ${staticTag}${m.returnType.simpleName} ${m.name}($params)")
        }

        val fields = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.getStaticFields(clazz)
            } else {
                clazz.declaredFields.filter { Modifier.isStatic(it.modifiers) }
            }
        }.getOrElse { emptyList() }

        val interestingFields = fields.filter { f ->
            val l = f.name.lowercase()
            nameFilter == null || nameFilter(f.name) || l.contains("miui") || l.contains("freeform")
        }.sortedBy { it.name }

        for (f in interestingFields) {
            val value = runCatching {
                f.isAccessible = true
                f.get(null)?.toString() ?: "null"
            }.getOrDefault("<unreadable>")
            val truncated = if (value.length > 80) value.take(80) + "..." else value
            sb.appendLine("  const ${f.type.simpleName} ${f.name} = $truncated")
        }
        sb.appendLine()
    }

    private fun dumpCurrentFreeFormStackInfo(): String {
        val candidates = arrayOf(
            CLASS_MIUI_FREEFORM_MANAGER,
            "android.app.MiuiFreeFormManager",
            CLASS_MIUI_MULTI_WINDOW_UTILS
        )
        val out = StringBuilder()
        for (className in candidates) {
            val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue
            val methods = getDeclaredMethodsSafe(clazz).filter {
                it.name.contains("FreeFormStackInfo", ignoreCase = true)
            }.sortedBy { it.name }

            for (method in methods) {
                val p = method.parameterTypes
                val argLabel: String
                val args: Array<Any?> = when {
                    p.isEmpty() -> {
                        argLabel = ""
                        emptyArray()
                    }
                    p.size == 1 && isIntType(p[0]) -> {
                        argLabel = "0"
                        arrayOf(0)
                    }
                    p.size == 1 && Context::class.java.isAssignableFrom(p[0]) && sysCtx != null -> {
                        argLabel = "sysCtx"
                        arrayOf(sysCtx)
                    }
                    else -> continue
                }
                val target = if (Modifier.isStatic(method.modifiers)) null else obtainSingletonInstance(clazz)
                val formatted = runCatching {
                    method.isAccessible = true
                    formatFreeFormStackResult(method.invoke(target, *args))
                }.getOrElse { t ->
                    "ERR: ${formatThrowable(t)}"
                }
                out.appendLine("$className.${method.name}($argLabel): $formatted")
            }
        }
        return if (out.isEmpty()) "(none available)" else out.toString().trim()
    }

    private fun formatFreeFormStackResult(result: Any?): String {
        if (result == null) return "null"
        if (result !is List<*>) return result.toString()
        if (result.isEmpty()) return "[]"
        return result.joinToString(prefix = "[\n  ", separator = ",\n  ", postfix = "\n]") { item ->
            if (item == null) {
                "null"
            } else {
                val baseStr = item.toString()
                val extras = extractInterestingStackFields(item)
                if (extras.isNotEmpty()) "$baseStr { $extras }" else baseStr
            }
        }
    }

    private fun extractInterestingStackFields(info: Any): String {
        val fields = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.getInstanceFields(info.javaClass)
            } else {
                info.javaClass.declaredFields.filter { !Modifier.isStatic(it.modifiers) }
            }
        }.getOrElse { emptyList() }

        val parts = mutableListOf<String>()
        for (field in fields) {
            val lower = field.name.lowercase()
            if (lower.contains("scale") ||
                lower.contains("bound") ||
                lower.contains("package") ||
                lower.contains("windowstate") ||
                lower.contains("stackid") ||
                lower.contains("taskid")
            ) {
                val value = runCatching {
                    field.isAccessible = true
                    field.get(info)?.toString() ?: "null"
                }.getOrDefault("<unreadable>")
                parts.add("${field.name}=$value")
            }
        }
        return parts.joinToString(", ")
    }

    private fun readSystemProperty(key: String): String {
        return runCatching {
            val sp = Class.forName("android.os.SystemProperties")
            val get = sp.getDeclaredMethod("get", String::class.java, String::class.java)
            get.isAccessible = true
            (get.invoke(null, key, "") as? String).orEmpty()
        }.getOrDefault("")
    }

    private fun getDeclaredMethodsSafe(clazz: Class<*>): List<Method> {
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                HiddenApiBypass.getDeclaredMethods(clazz).filterIsInstance<Method>()
            } else {
                clazz.declaredMethods.toList()
            }
        }.getOrElse { emptyList() }
    }

    private fun isBooleanType(clazz: Class<*>): Boolean =
        clazz == Boolean::class.javaPrimitiveType || clazz == Boolean::class.javaObjectType

    private fun isIntType(clazz: Class<*>): Boolean =
        clazz == Int::class.javaPrimitiveType || clazz == Int::class.javaObjectType

    private fun isAllowedCommand(cmd: String): Boolean {
        if (cmd in ALLOWED_COMMANDS) return true
        return FREEFORM_LAUNCH_REGEX.matches(cmd)
    }

    private fun executeInternal(command: String): Pair<Int, String> {
        return executeProcessBuilder(ProcessBuilder("sh", "-c", command))
    }

    private fun executeArgs(vararg args: String): Pair<Int, String> {
        return executeProcessBuilder(ProcessBuilder(*args))
    }

    private fun executeProcessBuilder(builder: ProcessBuilder): Pair<Int, String> {
        var process: Process? = null
        return try {
            process = builder
                .redirectErrorStream(true)
                .start()

            val outputBuilder = StringBuilder(256)
            val readerThread = Thread {
                runCatching {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        var charsRead = 0
                        val buffer = CharArray(256)
                        while (charsRead < MAX_OUTPUT_CHARS) {
                            val count = reader.read(buffer)
                            if (count <= 0) break
                            val toAppend = minOf(count, MAX_OUTPUT_CHARS - charsRead)
                            outputBuilder.append(buffer, 0, toAppend)
                            charsRead += toAppend
                        }
                    }
                }
            }.apply {
                isDaemon = true
                start()
            }

            val completed = process.waitFor(COMMAND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (!completed) {
                runCatching { process.destroyForcibly() }
                runCatching { readerThread.join(150L) }
                Pair(EXIT_TIMEOUT, "Command timed out after ${COMMAND_TIMEOUT_MS}ms")
            } else {
                runCatching { readerThread.join(250L) }
                Pair(process.exitValue(), outputBuilder.toString().trim())
            }
        } catch (t: Throwable) {
            runCatching { process?.destroyForcibly() }
            Pair(EXIT_EXCEPTION, t.message ?: t.javaClass.simpleName)
        }
    }

    companion object {
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val MAX_OUTPUT_CHARS = 1024
        private const val MAX_TRACE_CHARS = 4096
        private const val MIUI_FLAG_FREEFORM = 2
        private const val WINDOWING_MODE_FREEFORM = 5
        private const val DEFAULT_TRACE_PLACEHOLDER = "(no freeform execution recorded yet)"
        private const val TRACE_PERSIST_PATH = "/data/local/tmp/.edgeflow_freeform_trace"

        private const val CLASS_MIUI_MULTI_WINDOW_UTILS = "android.util.MiuiMultiWindowUtils"
        private const val CLASS_MIUI_FREEFORM_MANAGER = "miui.app.MiuiFreeFormManager"

        private const val FORCE_REBIND_DELAY_MS = 180L
        private const val DEAD_MAN_REVIVE_DELAY_MS = 250L
        private const val A11Y_SENSOR_COOLDOWN_MS = 650L
        private const val OWN_ACCESSIBILITY_CLASS_FULL = "com.edgeflow.service.EdgeAccessibilityService"
        private const val OWN_ACCESSIBILITY_CLASS_SHORT = ".service.EdgeAccessibilityService"

        const val EXIT_COMMAND_NOT_ALLOWED = -2
        const val EXIT_TIMEOUT = -3
        const val EXIT_EXCEPTION = -4

        internal val ACCESSIBILITY_SERVICES_REGEX = Regex("^[A-Za-z0-9._/$:]+$")

        private val FREEFORM_LAUNCH_REGEX = Regex(
            "^am start(?:-activity)? --windowingMode 5(?: -f 0x[0-9a-fA-F]{1,8})? -n [A-Za-z0-9._]+/[A-Za-z0-9._$]+$"
        )

        /**
         * Validates the raw output of `settings get secure enabled_accessibility_services` to
         * block shell-injection characters before manipulating or writing the list back.
         * Empty string and literal `"null"` (unset in Android Settings) are treated as valid empty lists.
         */
        fun isValidAccessibilityServicesRaw(raw: String?): Boolean {
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
                return true
            }
            return ACCESSIBILITY_SERVICES_REGEX.matches(trimmed)
        }

        private fun ownComponentFull(appPackage: String = BuildConfig.APPLICATION_ID): String =
            "$appPackage/$OWN_ACCESSIBILITY_CLASS_FULL"

        private fun ownComponentShort(appPackage: String = BuildConfig.APPLICATION_ID): String =
            "$appPackage/$OWN_ACCESSIBILITY_CLASS_SHORT"

        private fun isOwnAccessibilityComponent(
            entry: String,
            appPackage: String = BuildConfig.APPLICATION_ID
        ): Boolean {
            return entry.equals(ownComponentFull(appPackage), ignoreCase = true) ||
                entry.equals(ownComponentShort(appPackage), ignoreCase = true)
        }

        /**
         * Pure helper checking whether Hinata's own AccessibilityService is present in
         * `enabled_accessibility_services` and `accessibility_enabled != 0`.
         */
        fun isOwnAccessibilityEnabledInList(
            rawServices: String?,
            accessibilityEnabled: Int = 1,
            appPackage: String = BuildConfig.APPLICATION_ID
        ): Boolean {
            if (accessibilityEnabled == 0) return false
            val trimmed = rawServices?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
                return false
            }
            return trimmed.split(':').any { entry ->
                isOwnAccessibilityComponent(entry.trim(), appPackage)
            }
        }

        /**
         * Parses a validated colon-separated service list and removes ONLY Hinata's own component
         * (full and short forms), strictly preserving all other enabled AccessibilityServices.
         * Returns `null` if [raw] contains disallowed characters.
         */
        fun buildAccessibilityServicesWithoutSelf(
            raw: String?,
            appPackage: String = BuildConfig.APPLICATION_ID
        ): String? {
            if (!isValidAccessibilityServicesRaw(raw)) return null
            val trimmed = raw?.trim().orEmpty()
            if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
                return ""
            }
            return trimmed
                .split(':')
                .asSequence()
                .filter { it.isNotEmpty() }
                .filterNot { isOwnAccessibilityComponent(it, appPackage) }
                .distinct()
                .joinToString(":")
        }

        /**
         * Parses a validated colon-separated service list and appends Hinata's own component at the
         * end if not already present, strictly preserving all other enabled AccessibilityServices.
         * Returns `null` if [raw] contains disallowed characters.
         */
        fun buildAccessibilityServicesWithSelf(
            raw: String?,
            appPackage: String = BuildConfig.APPLICATION_ID
        ): String? {
            if (!isValidAccessibilityServicesRaw(raw)) return null
            val trimmed = raw?.trim().orEmpty()
            val entries = if (trimmed.isEmpty() || trimmed.equals("null", ignoreCase = true)) {
                mutableListOf()
            } else {
                trimmed.split(':').filter { it.isNotEmpty() }.distinct().toMutableList()
            }
            if (entries.none { isOwnAccessibilityComponent(it, appPackage) }) {
                entries.add(ownComponentFull(appPackage))
            }
            return entries.joinToString(":")
        }

        private val ALLOWED_COMMANDS: Set<String> = setOf(
            // HyperOS / MIUI 3-button & gesture line
            ShizukuShellExecutor.CMD_FORCE_FSG_HIDE,
            ShizukuShellExecutor.CMD_FORCE_FSG_RESTORE,
            ShizukuShellExecutor.CMD_HIDE_GESTURE_LINE_ON,
            ShizukuShellExecutor.CMD_HIDE_GESTURE_LINE_OFF,
            ShizukuShellExecutor.CMD_OVERLAY_GESTURAL,
            ShizukuShellExecutor.CMD_OVERLAY_THREE_BUTTON,
            ShizukuShellExecutor.CMD_GET_FREEFORM_SUPPORT,
            ShizukuShellExecutor.CMD_ENABLE_FREEFORM_SUPPORT,
            // Spec 63: All-OEM Back gesture suppression, NavHide, One UI hint, nav mode query
            ShizukuShellExecutor.CMD_BACK_INSET_LEFT_ZERO,
            ShizukuShellExecutor.CMD_BACK_INSET_LEFT_NEG,
            ShizukuShellExecutor.CMD_BACK_INSET_LEFT_DELETE,
            ShizukuShellExecutor.CMD_BACK_INSET_LEFT_ONE,
            ShizukuShellExecutor.CMD_BACK_INSET_RIGHT_ZERO,
            ShizukuShellExecutor.CMD_BACK_INSET_RIGHT_NEG,
            ShizukuShellExecutor.CMD_BACK_INSET_RIGHT_DELETE,
            ShizukuShellExecutor.CMD_BACK_INSET_RIGHT_ONE,
            ShizukuShellExecutor.CMD_NAVHIDE_DISABLE_FOR_SETUP_TRUE,
            ShizukuShellExecutor.CMD_NAVHIDE_DISABLE_FOR_SETUP_FALSE,
            ShizukuShellExecutor.CMD_NAVHIDE_DISABLE_HOME_RECENTS,
            ShizukuShellExecutor.CMD_NAVHIDE_DISABLE_HOME_RECENTS_BACK,
            ShizukuShellExecutor.CMD_NAVHIDE_DISABLE_NONE,
            ShizukuShellExecutor.CMD_SAMSUNG_GESTURE_HINT_OFF,
            ShizukuShellExecutor.CMD_SAMSUNG_GESTURE_HINT_ON,
            ShizukuShellExecutor.CMD_GET_NAVIGATION_MODE,
            // Spec 64: ColorOS 16 / OriginOS 6 Google Assistant CTS repair & restore
            ShizukuShellExecutor.CMD_CTS_SET_ASSISTANT,
            ShizukuShellExecutor.CMD_CTS_SET_VOICE_INTERACTION,
            ShizukuShellExecutor.CMD_CTS_SET_VOICE_RECOGNITION,
            ShizukuShellExecutor.CMD_CTS_ADD_ASSISTANT_ROLE,
            ShizukuShellExecutor.CMD_CTS_DELETE_ASSISTANT,
            ShizukuShellExecutor.CMD_CTS_DELETE_VOICE_INTERACTION,
            ShizukuShellExecutor.CMD_CTS_DELETE_VOICE_RECOGNITION
        )
    }
}
