package com.mamekyo.usagetracker

import android.app.Application
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.domain.Alerts
import com.mamekyo.usagetracker.work.RefreshWorker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class UsageTrackerApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Alerts.createChannel(this)
        RefreshWorker.schedule(this, Store.get(this).state.value.settings.refreshMinutes)
    }

    companion object {
        /** Process-wide scope for fire-and-forget work that must outlive a screen (e.g. receivers). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
