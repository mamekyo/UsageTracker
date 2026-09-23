package com.mamekyo.usagetracker.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mamekyo.usagetracker.data.AlertRule
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.domain.Aggregator
import com.mamekyo.usagetracker.domain.Alerts
import java.util.UUID
import kotlin.math.roundToInt

@Composable
fun AlertsScreen(state: AppState, vm: MainViewModel, showNewRule: Boolean, onNewRuleClosed: () -> Unit) {
    val context = LocalContext.current
    var permissionCheck by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AlertRule?>(null) }
    LifecycleResumeEffect(Unit) {
        permissionCheck++
        onPauseOrDispose { }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        permissionCheck++
        vm.onNotificationPermissionChanged()
    }
    val allowed = remember(permissionCheck) { Alerts.canNotify(context) }
    val now = System.currentTimeMillis()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (!allowed) {
            item(key = "permission") {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("通知權限尚未開啟，提醒將無法顯示。", color = MaterialTheme.colorScheme.onErrorContainer)
                        Button(onClick = {
                            val needsRuntime = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                                PackageManager.PERMISSION_GRANTED
                            if (needsRuntime) {
                                launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                            } else {
                                context.startActivity(
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                                )
                            }
                        }) { Text("允許通知") }
                    }
                }
            }
        }
        item(key = "intro") {
            Text(
                "剩餘用量降到門檻以下時發送通知。同一個限制在回升到門檻以上（例如重置）前只會通知一次。檢查頻率與自動更新頻率相同。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.rules.isEmpty()) {
            item(key = "empty") {
                Text(
                    "尚未設定提醒，點右下角「新增提醒」。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 24.dp),
                )
            }
        }
        items(state.rules, key = { it.id }) { rule ->
            RuleCard(
                rule = rule,
                state = state,
                now = now,
                onToggle = { vm.setRuleEnabled(rule.id, it) },
                onEdit = { editing = rule },
                onDelete = { vm.deleteRule(rule.id) },
            )
        }
        item(key = "test") {
            TextButton(onClick = vm::sendTestNotification) { Text("發送測試通知") }
        }
    }

    if (showNewRule || editing != null) {
        RuleEditorDialog(
            state = state,
            initial = editing,
            onSave = {
                vm.saveRule(it)
                editing = null
                onNewRuleClosed()
            },
            onDismiss = {
                editing = null
                onNewRuleClosed()
            },
        )
    }
}

private fun windowLabel(rule: AlertRule, state: AppState, now: Long): String {
    val key = rule.windowKey ?: return "所有限制"
    return Aggregator.views(rule.source, state, now).firstOrNull()?.windows?.firstOrNull { it.key == key }?.label ?: key
}

@Composable
private fun RuleCard(
    rule: AlertRule,
    state: AppState,
    now: Long,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val windows = Aggregator.views(rule.source, state, now).firstOrNull()?.windows.orEmpty()
        .filter { rule.windowKey == null || it.key == rule.windowKey }
    val lowest = windows.minByOrNull { it.remainingPercent }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Aggregator.sourceLabel(rule.source, state), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${windowLabel(rule, state, now)} · 剩餘 ≤ ${rule.thresholdRemaining}%（已使用 ≥ ${100 - rule.thresholdRemaining}%）",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    lowest?.let {
                        Text(
                            "目前：${it.label} 剩餘 ${it.remainingPercent.roundToInt()}%",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) { Text("編輯") }
                TextButton(onClick = onDelete) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(state: AppState, initial: AlertRule?, onSave: (AlertRule) -> Unit, onDismiss: () -> Unit) {
    val sources = buildList {
        Provider.entries.forEach { provider ->
            if (state.accounts.any { it.provider == provider }) add(Source.Merged(provider))
        }
        state.accounts.forEach { add(Source.Single(it.id)) }
    }
    if (sources.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("新增提醒") },
            text = { Text("請先到「帳號」頁新增帳號。") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        )
        return
    }

    var source by remember { mutableStateOf(initial?.source?.takeIf { it in sources } ?: sources.first()) }
    var windowKey by remember { mutableStateOf(initial?.windowKey) }
    var threshold by remember { mutableFloatStateOf((initial?.thresholdRemaining ?: 20).toFloat()) }
    val now = System.currentTimeMillis()
    val windows = Aggregator.views(source, state, now).firstOrNull()?.windows.orEmpty()
    val windowOptions = buildList<Pair<String?, String>> {
        add(null to "所有限制")
        windows.forEach { add(it.key to it.label) }
        windowKey?.let { key -> if (windows.none { it.key == key }) add(key to key) }
    }
    val value = threshold.roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial == null) "新增提醒" else "編輯提醒") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text("對象", style = MaterialTheme.typography.titleSmall)
                sources.forEach { option ->
                    RadioRow(selected = option == source, title = Aggregator.sourceLabel(option, state)) {
                        if (option != source) windowKey = null
                        source = option
                    }
                }
                Text("限制", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                windowOptions.forEach { (key, label) ->
                    RadioRow(selected = key == windowKey, title = label) { windowKey = key }
                }
                Text("門檻", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text("剩餘 ≤ $value%（已使用 ≥ ${100 - value}%）時通知", style = MaterialTheme.typography.bodyMedium)
                Slider(value = threshold, onValueChange = { threshold = it }, valueRange = 5f..95f, steps = 17)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    AlertRule(
                        id = initial?.id ?: UUID.randomUUID().toString(),
                        source = source,
                        windowKey = windowKey,
                        thresholdRemaining = value,
                        enabled = initial?.enabled ?: true,
                    ),
                )
            }) { Text("儲存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
