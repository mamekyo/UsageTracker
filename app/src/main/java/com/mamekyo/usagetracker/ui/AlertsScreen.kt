package com.mamekyo.usagetracker.ui

import android.Manifest
import android.content.Context
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.data.AlertRule
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.domain.Aggregator
import com.mamekyo.usagetracker.domain.Alerts
import com.mamekyo.usagetracker.i18n.Texts
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
                        Text(stringResource(R.string.notif_permission_off), color = MaterialTheme.colorScheme.onErrorContainer)
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
                        }) { Text(stringResource(R.string.allow_notifications)) }
                    }
                }
            }
        }
        item(key = "intro") {
            Text(
                stringResource(R.string.alerts_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (state.rules.isEmpty()) {
            item(key = "empty") {
                Text(
                    stringResource(R.string.alerts_empty),
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
            TextButton(onClick = vm::sendTestNotification) { Text(stringResource(R.string.send_test_notification)) }
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

private fun windowLabel(c: Context, rule: AlertRule, state: AppState, now: Long): String {
    val key = rule.windowKey ?: return c.getString(R.string.all_limits)
    return Aggregator.views(rule.source, state, now).firstOrNull()?.windows?.firstOrNull { it.key == key }
        ?.let { Texts.window(c, it) } ?: key
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
    val c = LocalContext.current
    val windows = Aggregator.views(rule.source, state, now).firstOrNull()?.windows.orEmpty()
        .filter { rule.windowKey == null || it.key == rule.windowKey }
    val lowest = windows.minByOrNull { it.remainingPercent }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 12.dp, end = 8.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(Texts.source(c, rule.source, state), style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${windowLabel(c, rule, state, now)} · " +
                            stringResource(R.string.rule_threshold, rule.thresholdRemaining, 100 - rule.thresholdRemaining),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    lowest?.let {
                        Text(
                            stringResource(R.string.rule_current, Texts.window(c, it), it.remainingPercent.roundToInt()),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Switch(checked = rule.enabled, onCheckedChange = onToggle)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onEdit) { Text(stringResource(R.string.action_edit)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}

@Composable
private fun RuleEditorDialog(state: AppState, initial: AlertRule?, onSave: (AlertRule) -> Unit, onDismiss: () -> Unit) {
    val c = LocalContext.current
    val sources = buildList {
        Provider.entries.forEach { provider ->
            if (state.accounts.any { it.provider == provider }) add(Source.Merged(provider))
        }
        state.accounts.forEach { add(Source.Single(it.id)) }
    }
    if (sources.isEmpty()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.rule_new)) },
            text = { Text(stringResource(R.string.rule_need_account)) },
            confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_ok)) } },
        )
        return
    }

    var source by remember { mutableStateOf(initial?.source?.takeIf { it in sources } ?: sources.first()) }
    var windowKey by remember { mutableStateOf(initial?.windowKey) }
    var threshold by remember { mutableFloatStateOf((initial?.thresholdRemaining ?: 20).toFloat()) }
    val now = System.currentTimeMillis()
    val windows = Aggregator.views(source, state, now).firstOrNull()?.windows.orEmpty()
    val windowOptions = buildList<Pair<String?, String>> {
        add(null to c.getString(R.string.all_limits))
        windows.forEach { add(it.key to Texts.window(c, it)) }
        windowKey?.let { key -> if (windows.none { it.key == key }) add(key to key) }
    }
    val value = threshold.roundToInt()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial == null) R.string.rule_new else R.string.rule_edit)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Text(stringResource(R.string.rule_target), style = MaterialTheme.typography.titleSmall)
                sources.forEach { option ->
                    RadioRow(selected = option == source, title = Texts.source(c, option, state)) {
                        if (option != source) windowKey = null
                        source = option
                    }
                }
                Text(stringResource(R.string.rule_limit), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                windowOptions.forEach { (key, label) ->
                    RadioRow(selected = key == windowKey, title = label) { windowKey = key }
                }
                Text(stringResource(R.string.rule_threshold_title), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 12.dp))
                Text(stringResource(R.string.rule_threshold_desc, value, 100 - value), style = MaterialTheme.typography.bodyMedium)
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
            }) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
