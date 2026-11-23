package com.edgeflow.service

import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import java.util.Locale

/**
 * Resolves a localized [Context] inside the resident default AccessibilityService process
 * based on the cross-process synced [com.edgeflow.data.EdgeConfig.appLanguageTag].
 *
 * Keeps zero Jetpack Compose dependencies and handles empty/system tags gracefully.
 */
object LocalizedContextHelper {

    fun forTag(context: Context, tag: String): Context {
        val clean = tag.trim()
        if (clean.isEmpty()) return context
        return runCatching {
            val locale = Locale.forLanguageTag(clean)
            if (locale.language.isNullOrEmpty()) return context
            val config = Configuration(context.resources.configuration)
            config.setLocale(locale)
            config.setLocales(LocaleList(locale))
            context.createConfigurationContext(config)
        }.getOrDefault(context)
    }
}
