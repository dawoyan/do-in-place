package com.davoyans.doinplace

import android.content.Context
import android.content.res.Configuration
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import java.util.Locale

object AppLocaleManager {

    fun applyLanguage(code: String) {
        val locales = when (code) {
            "hy"     -> LocaleListCompat.forLanguageTags("hy")
            "ru"     -> LocaleListCompat.forLanguageTags("ru")
            "en"     -> LocaleListCompat.forLanguageTags("en")
            else     -> LocaleListCompat.getEmptyLocaleList()  // "system" or blank
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    fun currentCode(): String {
        val locales = AppCompatDelegate.getApplicationLocales()
        if (locales.isEmpty) return "system"
        val tag = locales[0]?.toLanguageTag() ?: return "system"
        return when {
            tag.startsWith("hy") -> "hy"
            tag.startsWith("ru") -> "ru"
            tag.startsWith("en") -> "en"
            else -> "system"
        }
    }

    // Wraps context with saved locale (used in attachBaseContext for pre-API-33 compat)
    fun wrapContext(base: Context, code: String): Context {
        if (code == "system" || code.isBlank()) return base
        val locale = Locale.forLanguageTag(code)
        Locale.setDefault(locale)
        val config = Configuration(base.resources.configuration)
        config.setLocale(locale)
        return base.createConfigurationContext(config)
    }
}
