package com.edgeflow.service

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import androidx.annotation.Keep
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

/**
 * Dynamic brute-force builder for HyperOS / MIUI native scaled floating window [Bundle] options.
 *
 * Instead of hardcoding legacy 2–4 parameter signatures, inspects EVERY declared
 * `getActivityOptions` overload on `android.util.MiuiMultiWindowUtils` (and fallback
 * `miui.util.MiuiMultiWindowUtils`) sorted descending by parameter count so HyperOS 3's
 * 5+ parameter scaled freeform overloads (e.g. `(Context, String, boolean, int x, int y)`)
 * are matched and invoked first.
 *
 * Pure Kotlin / Android framework only — ZERO Jetpack Compose imports.
 */
@Keep
object MiuiFreeformOptions {

    private const val MIUI_FLAG_FREEFORM = 2

    private val UTILS_CLASS_NAMES = arrayOf(
        "android.util.MiuiMultiWindowUtils",
        "miui.util.MiuiMultiWindowUtils"
    )

    private const val CLASS_MIUI_FREEFORM_MANAGER = "miui.app.MiuiFreeFormManager"
    private const val CLASS_MIUI_MULTI_WINDOW_UTILS = "android.util.MiuiMultiWindowUtils"

    @Volatile
    private var hiddenApiExempted = false

    fun ensureHiddenApiExemptions() {
        if (hiddenApiExempted) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching { HiddenApiBypass.addHiddenApiExemptions("") }
        }
        hiddenApiExempted = true
    }

    /**
     * Builds a non-empty native MIUI / HyperOS scaled floating window [Bundle].
     *
     * Priority order (verified on HyperOS 3.0 / Android 16):
     * 1. `miui.app.MiuiFreeFormManager.getActivityOptions(context, packageName, true, false)` (4-arg)
     * 2. `android.util.MiuiMultiWindowUtils.getActivityOptions(context, packageName, true, false)` (4-arg)
     * 3. `android.util.MiuiMultiWindowUtils.getActivityOptions(context, packageName, true)` (3-arg)
     * 4. Generic overload probe for older/variant MIUI builds.
     */
    fun build(
        context: Context,
        packageName: String,
        anchorX: Int,
        anchorY: Int
    ): Bundle? {
        if (packageName.isBlank()) return null
        ensureHiddenApiExemptions()

        // 1. Verified 4-arg MiuiFreeFormManager.getActivityOptions(Context, String, boolean, boolean)
        invokeExactFourArgGetActivityOptions(CLASS_MIUI_FREEFORM_MANAGER, context, packageName)?.let {
            return it
        }

        // 2. Verified 4-arg MiuiMultiWindowUtils.getActivityOptions(Context, String, boolean, boolean)
        invokeExactFourArgGetActivityOptions(CLASS_MIUI_MULTI_WINDOW_UTILS, context, packageName)?.let {
            return it
        }

        // 3. Verified 3-arg MiuiMultiWindowUtils.getActivityOptions(Context, String, boolean)
        invokeExactThreeArgGetActivityOptions(CLASS_MIUI_MULTI_WINDOW_UTILS, context, packageName)?.let {
            return it
        }

        // 4. Generic fallback probe across MIUI utils classes
        for (className in UTILS_CLASS_NAMES) {
            val clazz = runCatching { Class.forName(className) }.getOrNull() ?: continue
            val overloads = getDeclaredMethodsSafe(clazz)
                .filter { it.name == "getActivityOptions" }
                .sortedByDescending { it.parameterTypes.size }

            for (method in overloads) {
                val args = mapArgumentsForTypes(
                    paramTypes = method.parameterTypes,
                    context = context,
                    packageName = packageName,
                    anchorX = anchorX,
                    anchorY = anchorY
                ) ?: continue

                val bundle = runCatching {
                    method.isAccessible = true
                    unwrapBundle(method.invoke(null, *args))
                }.getOrNull()

                if (bundle != null && !bundle.isEmpty) {
                    return bundle
                }
            }
        }
        return null
    }

    private fun invokeExactFourArgGetActivityOptions(
        className: String,
        context: Context,
        packageName: String
    ): Bundle? {
        val clazz = runCatching { Class.forName(className) }.getOrNull() ?: return null
        val method = getDeclaredMethodsSafe(clazz).firstOrNull { m ->
            m.name == "getActivityOptions" &&
                m.parameterTypes.size == 4 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[1] == String::class.java &&
                isBooleanType(m.parameterTypes[2]) &&
                isBooleanType(m.parameterTypes[3])
        } ?: return null

        val bundle = runCatching {
            method.isAccessible = true
            unwrapBundle(method.invoke(null, context, packageName, true, false))
        }.getOrNull()
        return bundle?.takeIf { !it.isEmpty }
    }

    private fun invokeExactThreeArgGetActivityOptions(
        className: String,
        context: Context,
        packageName: String
    ): Bundle? {
        val clazz = runCatching { Class.forName(className) }.getOrNull() ?: return null
        val method = getDeclaredMethodsSafe(clazz).firstOrNull { m ->
            m.name == "getActivityOptions" &&
                m.parameterTypes.size == 3 &&
                Context::class.java.isAssignableFrom(m.parameterTypes[0]) &&
                m.parameterTypes[1] == String::class.java &&
                isBooleanType(m.parameterTypes[2])
        } ?: return null

        val bundle = runCatching {
            method.isAccessible = true
            unwrapBundle(method.invoke(null, context, packageName, true))
        }.getOrNull()
        return bundle?.takeIf { !it.isEmpty }
    }

    private fun unwrapBundle(result: Any?): Bundle? {
        return when (result) {
            is ActivityOptions -> result.toBundle()
            is Bundle -> result
            else -> null
        }
    }

    /**
     * Maps method parameter types to arguments for `MiuiMultiWindowUtils.getActivityOptions`.
     *
     * Rules:
     * - `Context` subtype -> [context]
     * - `String` -> 1st = [packageName], 2nd+ = `null`
     * - `Boolean` (primitive or boxed) -> 1st = `true` (noCheck / isFreeform), 2nd+ = `false` (isMiniFreeformMode = false)
     * - `Int` (primitive or boxed) -> 1st = [anchorX], 2nd = [anchorY], 3rd+ = `0`
     * - `Rect` -> `null`
     * - Any unknown parameter type -> returns `null` so the overload is safely skipped.
     */
    internal fun mapArgumentsForTypes(
        paramTypes: Array<Class<*>>,
        context: Context?,
        packageName: String,
        anchorX: Int,
        anchorY: Int
    ): Array<Any?>? {
        if (paramTypes.isEmpty()) return null

        val args = arrayOfNulls<Any>(paramTypes.size)
        var stringIdx = 0
        var boolIdx = 0
        var intIdx = 0

        for (i in paramTypes.indices) {
            val type = paramTypes[i]
            when {
                Context::class.java.isAssignableFrom(type) -> {
                    if (context == null) return null
                    args[i] = context
                }
                type == String::class.java -> {
                    args[i] = if (stringIdx++ == 0) packageName else null
                }
                isBooleanType(type) -> {
                    args[i] = (boolIdx++ == 0)
                }
                isIntType(type) -> {
                    args[i] = when (intIdx++) {
                        0 -> anchorX
                        1 -> anchorY
                        else -> 0
                    }
                }
                type == Rect::class.java -> {
                    args[i] = null
                }
                else -> return null
            }
        }
        return args
    }

    /**
     * Applies MIUI's optional freeform hint flag (`addMiuiFlags(2)` / `setMiuiFlags(2)`) if present.
     */
    fun applyMiuiFreeformFlags(intent: Intent) {
        ensureHiddenApiExemptions()
        val methods = getDeclaredMethodsSafe(Intent::class.java)
        for (methodName in arrayOf("addMiuiFlags", "setMiuiFlags")) {
            val method = methods.firstOrNull { m ->
                m.name == methodName &&
                    m.parameterTypes.size == 1 &&
                    isIntType(m.parameterTypes[0])
            } ?: continue
            val ok = runCatching {
                method.isAccessible = true
                method.invoke(intent, MIUI_FLAG_FREEFORM)
                true
            }.getOrDefault(false)
            if (ok) return
        }
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
}
