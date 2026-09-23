package com.mamekyo.usagetracker.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.mamekyo.usagetracker.data.AlertRule
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.DisplayMode
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.domain.Alerts
import com.mamekyo.usagetracker.domain.UsageRepository
import com.mamekyo.usagetracker.work.RefreshWorker
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val store = Store.get(app)
    private val repository = UsageRepository.get(app)

    val state: StateFlow<AppState> = store.state
    val refreshing: StateFlow<Boolean> = repository.refreshing

    fun refresh() {
        viewModelScope.launch { repository.refreshAll() }
    }

    /** Called when the app comes to the foreground; avoids hammering the endpoints on quick app switches. */
    fun refreshIfStale() {
        if (System.currentTimeMillis() - state.value.lastRefreshAt > 60_000) refresh()
    }

    fun setDisplayMode(mode: DisplayMode) = updateAndPublish {
        it.copy(settings = it.settings.copy(displayMode = mode))
    }

    fun setRefreshMinutes(minutes: Int) {
        viewModelScope.launch {
            store.update { it.copy(settings = it.settings.copy(refreshMinutes = minutes)) }
            RefreshWorker.schedule(getApplication(), minutes)
        }
    }

    fun rename(accountId: String, nickname: String) = updateAndPublish { state ->
        state.copy(accounts = state.accounts.map { if (it.id == accountId) it.copy(nickname = nickname.trim().ifBlank { null }) else it })
    }

    fun setIncludeInMerge(accountId: String, include: Boolean) = updateAndPublish { state ->
        state.copy(accounts = state.accounts.map { if (it.id == accountId) it.copy(includeInMerge = include) else it })
    }

    fun removeAccount(accountId: String) {
        viewModelScope.launch {
            store.removeAccount(accountId)
            repository.publish()
        }
    }

    fun saveRule(rule: AlertRule) = updateAndPublish { state ->
        val exists = state.rules.any { it.id == rule.id }
        state.copy(
            rules = if (exists) state.rules.map { if (it.id == rule.id) rule else it } else state.rules + rule,
            // Editing a rule re-arms it.
            firedAlerts = state.firedAlerts.filterNot { it.startsWith("${rule.id}|") }.toSet(),
        )
    }

    fun setRuleEnabled(ruleId: String, enabled: Boolean) = updateAndPublish { state ->
        state.copy(rules = state.rules.map { if (it.id == ruleId) it.copy(enabled = enabled) else it })
    }

    fun deleteRule(ruleId: String) = updateAndPublish { state ->
        state.copy(
            rules = state.rules.filterNot { it.id == ruleId },
            firedAlerts = state.firedAlerts.filterNot { it.startsWith("$ruleId|") }.toSet(),
        )
    }

    fun sendTestNotification() = Alerts.sendTest(getApplication())

    fun onNotificationPermissionChanged() {
        viewModelScope.launch { repository.publish() }
    }

    private fun updateAndPublish(transform: (AppState) -> AppState) {
        viewModelScope.launch {
            store.update(transform)
            repository.publish()
        }
    }
}
