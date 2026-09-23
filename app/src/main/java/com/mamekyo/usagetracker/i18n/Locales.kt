package com.mamekyo.usagetracker.i18n

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.annotation.StringRes
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.data.Store
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class AppLanguage(val tag: String, @param:StringRes val labelRes: Int) {
    SYSTEM("", R.string.language_system),
    ZH_TW("zh-TW", R.string.language_zh_tw),
    ZH_CN("zh-CN", R.string.language_zh_cn),
    JA("ja", R.string.language_ja),
    EN("en", R.string.language_en),
    ;

    companion object {
        fun fromTag(tag: String?): AppLanguage {
            if (tag.isNullOrBlank()) return SYSTEM
            val locale = Locale.forLanguageTag(tag)
            return when (locale.language) {
                "zh" -> if (locale.script == "Hant" || locale.country in setOf("TW", "HK", "MO")) ZH_TW else ZH_CN
                "ja" -> JA
                "en" -> EN
                else -> SYSTEM
            }
        }
    }
}

/**
 * Per-app language. Android 13+ stores it in the system (also editable under Settings → Apps → Language);
 * older versions keep it in the app's own settings and apply it by wrapping contexts.
 */
object Locales {
    private val _revision = MutableStateFlow(0)

    /** Bumped on every app language change so long-lived compositions (widget sessions) re-resolve their text. */
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun notifyChanged() = _revision.update { it + 1 }

    fun current(context: Context): AppLanguage =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val locales = context.getSystemService(LocaleManager::class.java).applicationLocales
            if (locales.isEmpty) AppLanguage.SYSTEM else AppLanguage.fromTag(locales[0].toLanguageTag())
        } else {
            AppLanguage.fromTag(Store.get(context).state.value.settings.language)
        }

    /** Applies [language]. Returns true when the caller must recreate its activity itself (before Android 13). */
    suspend fun set(context: Context, language: AppLanguage): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales =
                if (language == AppLanguage.SYSTEM) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(language.tag)
            return false
        }
        Store.get(context).update { it.copy(settings = it.settings.copy(language = language.tag)) }
        return true
    }

    /**
     * Locale override for an activity (pass to `applyOverrideConfiguration` in `attachBaseContext`), or null
     * when none is needed: Android 13+ applies the per-app locale itself.
     */
    fun overrideConfiguration(context: Context): Configuration? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return null
        val tag = Store.get(context).state.value.settings.language
        if (tag.isBlank()) return null
        return Configuration().apply { setLocales(LocaleList.forLanguageTags(tag)) }
    }

    /** Context whose resources use the app language, for non-activity code (widgets, notifications, workers). */
    fun wrap(context: Context): Context {
        val override = overrideConfiguration(context) ?: return context
        val config = Configuration(context.resources.configuration).apply { setLocales(override.locales) }
        return context.createConfigurationContext(config)
    }
}
