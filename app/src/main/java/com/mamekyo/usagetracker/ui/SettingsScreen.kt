package com.mamekyo.usagetracker.ui

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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mamekyo.usagetracker.BuildConfig
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.DisplayMode
import com.mamekyo.usagetracker.domain.Format

@Composable
fun SettingsScreen(state: AppState, vm: MainViewModel, refreshing: Boolean) {
    val settings = state.settings
    val now = System.currentTimeMillis()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        SettingsCard("百分比顯示") {
            RadioRow(settings.displayMode == DisplayMode.REMAINING, "剩餘的 %", "例：剩餘 70%，進度條顯示剩下的額度") {
                vm.setDisplayMode(DisplayMode.REMAINING)
            }
            RadioRow(settings.displayMode == DisplayMode.USED, "已使用的 %", "例：已使用 30%，進度條顯示用掉的額度") {
                vm.setDisplayMode(DisplayMode.USED)
            }
        }

        SettingsCard("自動更新") {
            listOf(15, 30, 60, 120).forEach { minutes ->
                val label = if (minutes < 60) "每 $minutes 分鐘" else "每 ${minutes / 60} 小時"
                RadioRow(settings.refreshMinutes == minutes, label) { vm.setRefreshMinutes(minutes) }
            }
            Text(
                "Android 限制背景更新最短 15 分鐘，省電模式下可能延後。上次更新：" +
                    if (state.lastRefreshAt > 0) Format.clock(state.lastRefreshAt, now) else "尚未更新",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = vm::refresh, enabled = !refreshing) { Text(if (refreshing) "更新中…" else "立即更新") }
        }

        SettingsCard("主畫面小工具") {
            Text(
                "在主畫面長按空白處 → 小工具 → UsageTracker，拖曳到主畫面後選擇要顯示的內容：" +
                    "全部、單一供應商（多帳號合併）或單一帳號。之後長按小工具即可重新設定。",
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        SettingsCard("關於") {
            Text(
                "所有帳號與登入權杖只儲存在這支手機上，權杖以 Android Keystore 加密，不會上傳到其他伺服器。\n\n" +
                    "用量資料來自 OpenAI Codex 與 Claude Code 使用的非公開介面，官方調整時可能暫時失效。\n\n" +
                    "版本 ${BuildConfig.VERSION_NAME}",
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
