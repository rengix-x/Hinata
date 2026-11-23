package com.edgeflow.settings

import android.app.Activity
import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import java.util.Locale

enum class AppThemeMode(val key: String) {
    SYSTEM("system"),
    DARK("dark"),
    LIGHT("light");

    companion object {
        fun fromKey(key: String?): AppThemeMode =
            entries.firstOrNull { it.key == key } ?: SYSTEM
    }
}

enum class AppLanguageOption(
    val tag: String,
    val nativeLabel: String
) {
    SYSTEM("", ""),
    ENGLISH("en", "English"),
    JAPANESE("ja", "日本語"),
    CHINESE_SIMPLIFIED("zh-CN", "简体中文"),
    HINDI("hi", "हिन्दी");

    companion object {
        fun fromTag(tag: String?): AppLanguageOption {
            if (tag.isNullOrBlank()) return SYSTEM
            val normalized = tag.replace('_', '-').lowercase(Locale.ROOT)
            return when {
                normalized.startsWith("ja") -> JAPANESE
                normalized.startsWith("zh") -> CHINESE_SIMPLIFIED
                normalized.startsWith("hi") -> HINDI
                normalized.startsWith("en") -> ENGLISH
                else -> SYSTEM
            }
        }
    }
}

data class SettingsAppearancePrefs(
    val themeMode: AppThemeMode = AppThemeMode.SYSTEM,
    val dynamicColorEnabled: Boolean = true,
    val language: AppLanguageOption = AppLanguageOption.SYSTEM,
    val hasCompletedOnboarding: Boolean = false
)

/**
 * UI-only preferences stored strictly for the Settings UI process (`:settings`),
 * leaving `com.edgeflow.data.ConfigStore` and gesture service keys completely untouched.
 */
object SettingsUiPreferences {
    private const val PREFS_NAME = "edgeflow_settings_ui_prefs"
    private const val KEY_THEME_MODE = "ui_theme_mode"
    private const val KEY_DYNAMIC_COLOR = "ui_dynamic_color"
    private const val KEY_LEGACY_LOCALE_TAG = "ui_legacy_locale_tag"
    private const val KEY_ONBOARDING_COMPLETED = "ui_onboarding_completed"
    private const val KEY_CC_FLOAT_NOTICE_DISMISSED = "ui_cc_float_notice_dismissed"
    private const val KEY_EVER_ENABLED_ACCESSIBILITY = "ui_ever_enabled_accessibility"

    fun hasEverEnabledAccessibility(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_EVER_ENABLED_ACCESSIBILITY, false)
    }

    fun saveHasEverEnabledAccessibility(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EVER_ENABLED_ACCESSIBILITY, enabled)
            .apply()
    }

    fun hasDismissedCcFloatNotice(context: Context): Boolean {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_CC_FLOAT_NOTICE_DISMISSED, false)
    }

    fun saveCcFloatNoticeDismissed(context: Context, dismissed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_CC_FLOAT_NOTICE_DISMISSED, dismissed)
            .apply()
    }

    fun load(context: Context): SettingsAppearancePrefs {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val themeMode = AppThemeMode.fromKey(prefs.getString(KEY_THEME_MODE, AppThemeMode.SYSTEM.key))
        val dynamicColor = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        val language = readCurrentLanguage(context)
        val onboardingCompleted = prefs.getBoolean(KEY_ONBOARDING_COMPLETED, false)
        return SettingsAppearancePrefs(
            themeMode = themeMode,
            dynamicColorEnabled = dynamicColor,
            language = language,
            hasCompletedOnboarding = onboardingCompleted
        )
    }

    fun saveOnboardingCompleted(context: Context, completed: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ONBOARDING_COMPLETED, completed)
            .apply()
    }

    fun saveThemeMode(context: Context, mode: AppThemeMode) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_THEME_MODE, mode.key)
            .apply()
    }

    fun saveDynamicColor(context: Context, enabled: Boolean) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_DYNAMIC_COLOR, enabled)
            .apply()
    }

    fun readCurrentLanguage(context: Context): AppLanguageOption {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = runCatching {
                context.getSystemService(LocaleManager::class.java)?.applicationLocales
            }.getOrNull()
            if (locales != null && !locales.isEmpty) {
                return AppLanguageOption.fromTag(locales.get(0)?.toLanguageTag())
            }
            return AppLanguageOption.SYSTEM
        }
        val storedTag = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LEGACY_LOCALE_TAG, "")
        return AppLanguageOption.fromTag(storedTag)
    }

    fun applyLanguage(activity: Activity, option: AppLanguageOption) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val localeManager = activity.getSystemService(LocaleManager::class.java)
            if (localeManager != null) {
                localeManager.applicationLocales = if (option == AppLanguageOption.SYSTEM) {
                    LocaleList.getEmptyLocaleList()
                } else {
                    LocaleList.forLanguageTags(option.tag)
                }
                return
            }
        }
        activity.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_LEGACY_LOCALE_TAG, option.tag)
            .apply()
        activity.recreate()
    }

    /**
     * Wraps base context on Android 10-12 (API 29-32) where `LocaleManager` is unavailable.
     * On Android 13+ (API 33+), the OS handles Per-App Language automatically.
     */
    fun wrapContextForLegacyLocale(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return base
        }
        val tag = base.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_LEGACY_LOCALE_TAG, "")
            .orEmpty()
        if (tag.isBlank()) return base

        val locale = Locale.forLanguageTag(tag)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        config.setLocales(LocaleList(locale))
        return base.createConfigurationContext(config)
    }
}
