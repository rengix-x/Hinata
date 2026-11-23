package com.edgeflow.service

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.widget.Toast
import com.edgeflow.service.shizuku.ShizukuShellExecutor
import com.example.R
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Native Google Circle to Search trigger supporting:
 * - Direct `IVoiceInteractionManagerService.showSessionFromSession` invocation with accurate
 *   `Void` / `Boolean` / `Int` return handling across Android 14–16 & CN ROMs
 *   (HyperOS / ColorOS 16 / realme UI / OriginOS 6).
 * - Optional external MiCTS (`com.parallelc.micts`) fallback if installed.
 * - Optional Shizuku auto-repair of `voice_interaction_service` on CN ROMs where Google Assistant
 *   is reset by the OEM.
 */
object CircleToSearchTrigger {

    private val bypassInitialized = AtomicBoolean(false)
    private val mainHandler: Handler by lazy { Handler(Looper.getMainLooper()) }
    private val repairExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EdgeFlow-CtsRepair").apply { isDaemon = true }
    }

    private const val VOICE_INTERACTION_SERVICE = "voiceinteraction"
    private const val GOOGLE_APP_PACKAGE = "com.google.android.googlequicksearchbox"
    private const val MICTS_PACKAGE = "com.parallelc.micts"
    private const val FLAG_SHOW_WITH_ASSIST = 1
    private const val FLAG_SHOW_WITH_SCREENSHOT = 2
    private const val FLAG_SHOW_WITH_CONVERSATION = 4
    private const val SHOW_SESSION_FLAGS =
        FLAG_SHOW_WITH_ASSIST or FLAG_SHOW_WITH_SCREENSHOT or FLAG_SHOW_WITH_CONVERSATION // 7

    fun ensureBypass() {
        if (bypassInitialized.compareAndSet(false, true)) {
            runCatching {
                HiddenApiBypass.addHiddenApiExemptions("")
            }
        }
    }

    /**
     * Triggers native Google Circle to Search, optionally trying external MiCTS and Shizuku
     * Google Assistant auto-repair on CN ROMs before showing a discreet guide Toast.
     */
    fun trigger(
        context: Context,
        languageTag: String = "",
        autoRepairWithShizuku: Boolean = true
    ): Boolean {
        ensureBypass()

        if (tryNativeShowSession(context)) {
            return true
        }

        // Optional external MiCTS fallback if installed
        if (tryLaunchExternalMicts(context)) {
            return true
        }

        val appCtx = context.applicationContext
        val canAttemptShizukuRepair = autoRepairWithShizuku &&
            ShizukuShellExecutor.isReady() &&
            isPackageInstalled(appCtx, GOOGLE_APP_PACKAGE)

        if (canAttemptShizukuRepair) {
            repairExecutor.execute {
                val repaired = ShizukuShellExecutor.enableGoogleAssistantForCts(appCtx).success
                mainHandler.post {
                    if (repaired && tryNativeShowSession(context)) {
                        return@post
                    }
                    showAssistantGuideToast(context, languageTag)
                }
            }
            return true
        }

        showAssistantGuideToast(context, languageTag)
        return false
    }

    private fun tryNativeShowSession(context: Context): Boolean {
        return try {
            val serviceManagerClass = Class.forName("android.os.ServiceManager")
            val getServiceMethod = serviceManagerClass.getMethod("getService", String::class.java)
            val binder = getServiceMethod.invoke(null, VOICE_INTERACTION_SERVICE) as? IBinder
                ?: return false

            val stubClass = Class.forName(
                "com.android.internal.app.IVoiceInteractionManagerService\$Stub"
            )
            val asInterfaceMethod = stubClass.getMethod("asInterface", IBinder::class.java)
            val voiceService = asInterfaceMethod.invoke(null, binder) ?: return false

            val argsBundle = Bundle().apply {
                putLong("invocation_time_ms", SystemClock.elapsedRealtime())
                putInt("omni.entry_point", 1)
                putBoolean("micts_trigger", true)
            }

            invokeShowSessionFromSession(context, voiceService, argsBundle)
        } catch (_: Throwable) {
            false
        }
    }

    private fun invokeShowSessionFromSession(
        context: Context,
        voiceService: Any,
        argsBundle: Bundle
    ): Boolean {
        val methods = voiceService.javaClass.methods
            .filter { it.name == "showSessionFromSession" }
            .sortedByDescending { it.parameterCount }

        val contextTag = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching { context.attributionTag }.getOrNull()
        } else {
            null
        }
        val attributionCandidates = linkedSetOf<String?>(
            "hyperOS_home",
            "com.android.systemui",
            contextTag,
            null
        )

        for (method in methods) {
            when (method.parameterCount) {
                4 -> {
                    for (tag in attributionCandidates) {
                        val result = runCatching {
                            val invoked = method.invoke(
                                voiceService,
                                null,
                                argsBundle,
                                SHOW_SESSION_FLAGS,
                                tag
                            )
                            isSuccessfulShowSessionReturn(invoked, method.returnType)
                        }.getOrDefault(false)
                        if (result) return true
                    }
                }

                3 -> {
                    val result = runCatching {
                        val invoked = method.invoke(
                            voiceService,
                            null,
                            argsBundle,
                            SHOW_SESSION_FLAGS
                        )
                        isSuccessfulShowSessionReturn(invoked, method.returnType)
                    }.getOrDefault(false)
                    if (result) return true
                }
            }
        }
        return false
    }

    internal fun isSuccessfulShowSessionReturn(invoked: Any?, returnType: Class<*>): Boolean {
        return when {
            returnType == Void.TYPE || returnType == Void::class.java -> true
            invoked is Boolean -> invoked
            invoked is Int -> invoked >= 0
            else -> invoked != null
        }
    }

    private fun tryLaunchExternalMicts(context: Context): Boolean {
        val appCtx = context.applicationContext
        if (!isPackageInstalled(appCtx, MICTS_PACKAGE)) return false
        return runCatching {
            val launchIntent = appCtx.packageManager.getLaunchIntentForPackage(MICTS_PACKAGE)
                ?: return false
            launchIntent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
            context.startActivity(launchIntent)
            true
        }.getOrDefault(false)
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return runCatching {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageInfo(packageName, 0)
            true
        }.getOrDefault(false)
    }

    private fun showAssistantGuideToast(context: Context, languageTag: String) {
        val appCtx = context.applicationContext
        mainHandler.post {
            runCatching {
                val localized = LocalizedContextHelper.forTag(appCtx, languageTag)
                Toast.makeText(
                    appCtx,
                    localized.getString(R.string.toast_circle_to_search_fallback),
                    Toast.LENGTH_SHORT
                ).show()
            }
        }
    }
}
