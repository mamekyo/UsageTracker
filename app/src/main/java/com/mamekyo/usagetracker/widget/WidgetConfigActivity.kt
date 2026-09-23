package com.mamekyo.usagetracker.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.data.WidgetStyle
import com.mamekyo.usagetracker.domain.Aggregator
import com.mamekyo.usagetracker.ui.RadioRow
import com.mamekyo.usagetracker.ui.theme.UsageTrackerTheme
import kotlinx.coroutines.launch

/** Lets the user pick what a widget shows: everything, one provider merged, or a single account. */
class WidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val appWidgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        setResult(RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val store = Store.get(this)
        enableEdgeToEdge()
        setContent {
            UsageTrackerTheme {
                val state by store.state.collectAsStateWithLifecycle()
                var selectedKey by rememberSaveable { mutableStateOf(key(state.widgets[appWidgetId] ?: Source.All)) }
                var style by rememberSaveable { mutableStateOf(state.widgetStyles[appWidgetId] ?: WidgetStyle.BARS) }
                val options = buildList {
                    add(Source.All)
                    Provider.entries.forEach { provider ->
                        if (state.accounts.any { it.provider == provider }) add(Source.Merged(provider))
                    }
                    state.accounts.forEach { add(Source.Single(it.id)) }
                }
                val selected = options.firstOrNull { key(it) == selectedKey } ?: Source.All

                Scaffold(topBar = { TopAppBar(title = { Text("設定小工具") }) }) { padding ->
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(padding)
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        SectionTitle("顯示內容")
                        options.forEach { option ->
                            RadioRow(
                                selected = option == selected,
                                title = Aggregator.sourceLabel(option, state),
                                subtitle = when (option) {
                                    Source.All -> "每家各自合併所有帳號"
                                    is Source.Merged -> "${state.accounts.count { it.provider == option.provider && it.includeInMerge }} 個帳號平均"
                                    is Source.Single -> state.accounts.firstOrNull { it.id == option.accountId }?.plan
                                },
                            ) { selectedKey = key(option) }
                        }
                        if (state.accounts.isEmpty()) {
                            Text(
                                "尚未新增帳號。小工具會先顯示提示，登入後自動更新。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 8.dp),
                            )
                        }
                        SectionTitle("顯示樣式")
                        RadioRow(style == WidgetStyle.BARS, "長條", "每個限制一列：進度條、% 數與重置時間") {
                            style = WidgetStyle.BARS
                        }
                        RadioRow(style == WidgetStyle.RINGS, "圓餅", "多個限制並排成一行，適合較小的小工具") {
                            style = WidgetStyle.RINGS
                        }
                        Button(onClick = { save(appWidgetId, selected, style) }, modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
                            Text("完成")
                        }
                    }
                }
            }
        }
    }

    private fun key(source: Source): String = when (source) {
        Source.All -> "all"
        is Source.Merged -> "merged:${source.provider.name}"
        is Source.Single -> "account:${source.accountId}"
    }

    @Composable
    private fun SectionTitle(text: String) {
        Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    }

    private fun save(appWidgetId: Int, source: Source, style: WidgetStyle) {
        lifecycleScope.launch {
            Store.get(this@WidgetConfigActivity).update {
                it.copy(widgets = it.widgets + (appWidgetId to source), widgetStyles = it.widgetStyles + (appWidgetId to style))
            }
            runCatching {
                val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                UsageWidget().update(this@WidgetConfigActivity, glanceId)
            }
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
            finish()
        }
    }
}
