package com.mamekyo.usagetracker

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import android.os.LocaleList
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.domain.Alerts
import com.mamekyo.usagetracker.i18n.Locales
import com.mamekyo.usagetracker.widget.UsageWidget
import com.mamekyo.usagetracker.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class UsageTrackerApp : Application() {
    private var locales: LocaleList? = null

    override fun onCreate() {
        super.onCreate()
        locales = resources.configuration.locales
        Alerts.createChannel(this)
        RefreshWorker.schedule(this, Store.get(this).state.value.settings.refreshMinutes)
    }

    /** System language changes and, on Android 13+, per-app language changes arrive here. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (newConfig.locales != locales) {
            locales = newConfig.locales
            onLanguageChanged(this)
        }
    }

    companion object {
        /** Process-wide scope for fire-and-forget work that must outlive a screen (e.g. receivers). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Re-renders text shown outside the app's activities in the new language. */
        fun onLanguageChanged(context: Context) {
            val app = context.applicationContext
            Locales.notifyChanged()
            Alerts.createChannel(app)
            scope.launch { runCatching { UsageWidget.updateAll(app) } }
        }
    }
}
