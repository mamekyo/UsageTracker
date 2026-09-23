package com.mamekyo.usagetracker.ui

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.mamekyo.usagetracker.BuildConfig
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.UsageTrackerApp
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.DisplayMode
import com.mamekyo.usagetracker.domain.Format
import com.mamekyo.usagetracker.i18n.AppLanguage
import com.mamekyo.usagetracker.i18n.Locales
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(state: AppState, vm: MainViewModel, refreshing: Boolean) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val settings = state.settings
    val now = System.currentTimeMillis()
    var language by remember { mutableStateOf(Locales.current(context)) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard(stringResource(R.string.settings_display)) {
            RadioRow(
                settings.displayMode == DisplayMode.REMAINING,
                stringResource(R.string.display_remaining),
                stringResource(R.string.display_remaining_desc),
            ) { vm.setDisplayMode(DisplayMode.REMAINING) }
            RadioRow(
                settings.displayMode == DisplayMode.USED,
                stringResource(R.string.display_used),
                stringResource(R.string.display_used_desc),
            ) { vm.setDisplayMode(DisplayMode.USED) }
        }

        SettingsCard(stringResource(R.string.settings_language)) {
            AppLanguage.entries.forEach { option ->
                RadioRow(language == option, stringResource(option.labelRes)) {
                    if (option == language) return@RadioRow
                    language = option
                    scope.launch {
                        // Android 13+ recreates the activity itself; older versions need it done here.
                        if (Locales.set(context, option)) {
                            UsageTrackerApp.onLanguageChanged(context)
                            (context as? Activity)?.recreate()
                        }
                    }
                }
            }
        }

        SettingsCard(stringResource(R.string.settings_refresh)) {
            listOf(15, 30, 60, 120).forEach { minutes ->
                val label = if (minutes < 60) {
                    pluralStringResource(R.plurals.refresh_every_minutes, minutes, minutes)
                } else {
                    pluralStringResource(R.plurals.refresh_every_hours, minutes / 60, minutes / 60)
                }
                RadioRow(settings.refreshMinutes == minutes, label) { vm.setRefreshMinutes(minutes) }
            }
            Text(
                stringResource(
                    R.string.refresh_note,
                    if (state.lastRefreshAt > 0) Format.clock(state.lastRefreshAt, now) else stringResource(R.string.not_updated),
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = vm::refresh, enabled = !refreshing) {
                Text(stringResource(if (refreshing) R.string.refreshing else R.string.refresh_now))
            }
        }

        SettingsCard(stringResource(R.string.settings_widget)) {
            Text(stringResource(R.string.widget_help), style = MaterialTheme.typography.bodyMedium)
        }

        SettingsCard(stringResource(R.string.settings_about)) {
            Text(
                stringResource(R.string.about_text, BuildConfig.VERSION_NAME),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 4.dp))
            content()
        }
    }
}
