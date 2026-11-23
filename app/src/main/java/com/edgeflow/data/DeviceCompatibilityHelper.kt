package com.edgeflow.data

import android.os.Build

/**
 * OEM ROM families for tailored Shizuku navigation control and Chinese-ROM compatibility guidance.
 */
enum class DeviceFamily {
    XIAOMI_HYPEROS,
    REALME_OPPO_ONEPLUS,
    VIVO_ORIGIN_OS,
    SAMSUNG_ONEUI,
    STANDARD_ANDROID
}

/**
 * Strict OS capability descriptor distinguishing genuine Xiaomi HyperOS / MIUI firmware from
 * non-HyperOS Android ROMs (including AOSP/Lineage custom ROMs running on Xiaomi hardware).
 */
data class OemCapabilities(
    val deviceFamily: DeviceFamily,
    val isHyperOsOrMiui: Boolean,
    val hyperOsVersionName: String,
    val supportsHyperOsRecentsCompat: Boolean,
    val supportsHyperOsAutoStartSettings: Boolean,
    val supportsHyperOsSecondSpaceTrick: Boolean,
    val supportsHyperOsGestureLineToggle: Boolean,
    val supportsHyperOsNativeFloatingWindow: Boolean,
    val supportsStandardFreeformWithShizuku: Boolean,
    val isChineseRomCtsRepairRelevant: Boolean
)

/**
 * Pure-Kotlin OEM family & capability detector (zero Compose dependency; usable in both resident
 * service and isolated `:settings` process).
 */
object DeviceCompatibilityHelper {

    @Volatile
    private var cachedCapabilities: OemCapabilities? = null

    fun resolveCapabilities(): OemCapabilities {
        cachedCapabilities?.let { return it }
        val hyperOsProp = readSystemProperty("ro.mi.os.version.name")
        val miuiProp = readSystemProperty("ro.miui.ui.version.name")
        val hasMiuiWindowClass = hasClass("android.util.MiuiMultiWindowUtils") ||
            hasClass("miui.app.MiuiFreeFormManager")
        return resolveCapabilities(
            manufacturer = Build.MANUFACTURER.orEmpty(),
            brand = Build.BRAND.orEmpty(),
            hyperOsProp = hyperOsProp,
            miuiProp = miuiProp,
            hasMiuiWindowClass = hasMiuiWindowClass
        ).also { cachedCapabilities = it }
    }

    /**
     * Pure-JVM testable resolver verifying genuine HyperOS / MIUI firmware presence before
     * enabling HyperOS-exclusive capabilities.
     */
    fun resolveCapabilities(
        manufacturer: String,
        brand: String,
        hyperOsProp: String,
        miuiProp: String,
        hasMiuiWindowClass: Boolean
    ): OemCapabilities {
        val rawFamily = detectDeviceFamily(manufacturer, brand)
        val cleanHyperOs = hyperOsProp.trim()
        val cleanMiui = miuiProp.trim()
        val isHyperOsOrMiui = cleanHyperOs.isNotEmpty() || cleanMiui.isNotEmpty() || hasMiuiWindowClass

        val effectiveFamily = when {
            isHyperOsOrMiui -> DeviceFamily.XIAOMI_HYPEROS
            rawFamily == DeviceFamily.XIAOMI_HYPEROS -> DeviceFamily.STANDARD_ANDROID
            else -> rawFamily
        }

        val resolvedVersion = when {
            cleanHyperOs.isNotEmpty() -> cleanHyperOs
            cleanMiui.isNotEmpty() -> cleanMiui
            isHyperOsOrMiui -> "HyperOS / MIUI"
            else -> ""
        }

        return OemCapabilities(
            deviceFamily = effectiveFamily,
            isHyperOsOrMiui = isHyperOsOrMiui,
            hyperOsVersionName = resolvedVersion,
            supportsHyperOsRecentsCompat = isHyperOsOrMiui,
            supportsHyperOsAutoStartSettings = isHyperOsOrMiui,
            supportsHyperOsSecondSpaceTrick = isHyperOsOrMiui,
            supportsHyperOsGestureLineToggle = isHyperOsOrMiui,
            supportsHyperOsNativeFloatingWindow = isHyperOsOrMiui,
            supportsStandardFreeformWithShizuku = true,
            isChineseRomCtsRepairRelevant = isHyperOsOrMiui ||
                effectiveFamily == DeviceFamily.REALME_OPPO_ONEPLUS ||
                effectiveFamily == DeviceFamily.VIVO_ORIGIN_OS
        )
    }

    fun detectDeviceFamily(): DeviceFamily {
        return resolveCapabilities().deviceFamily
    }

    fun detectDeviceFamily(manufacturer: String, brand: String): DeviceFamily {
        val m = manufacturer.trim().lowercase()
        val b = brand.trim().lowercase()
        val combined = "$m $b"
        return when {
            combined.contains("xiaomi") ||
                combined.contains("redmi") ||
                combined.contains("poco") -> DeviceFamily.XIAOMI_HYPEROS

            combined.contains("oppo") ||
                combined.contains("realme") ||
                combined.contains("oneplus") -> DeviceFamily.REALME_OPPO_ONEPLUS

            combined.contains("vivo") ||
                combined.contains("iqoo") -> DeviceFamily.VIVO_ORIGIN_OS

            combined.contains("samsung") -> DeviceFamily.SAMSUNG_ONEUI

            else -> DeviceFamily.STANDARD_ANDROID
        }
    }

    fun isChineseRomFamily(family: DeviceFamily = detectDeviceFamily()): Boolean {
        return family == DeviceFamily.XIAOMI_HYPEROS ||
            family == DeviceFamily.REALME_OPPO_ONEPLUS ||
            family == DeviceFamily.VIVO_ORIGIN_OS
    }

    private fun readSystemProperty(key: String): String {
        return runCatching {
            val clazz = Class.forName("android.os.SystemProperties")
            val getMethod = clazz.getMethod("get", String::class.java, String::class.java)
            (getMethod.invoke(null, key, "") as? String).orEmpty().trim()
        }.getOrDefault("")
    }

    private fun hasClass(className: String): Boolean {
        return runCatching {
            Class.forName(className, false, DeviceCompatibilityHelper::class.java.classLoader)
            true
        }.getOrDefault(false)
    }
}
