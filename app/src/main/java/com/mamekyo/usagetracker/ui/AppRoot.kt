package com.mamekyo.usagetracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mamekyo.usagetracker.data.Provider

private enum class Tab(val label: String, val title: String, val icon: ImageVector) {
    USAGE("用量", "AI 用量", Icons.Filled.Home),
    ACCOUNTS("帳號", "帳號管理", Icons.Filled.AccountCircle),
    ALERTS("通知", "用量提醒", Icons.Filled.Notifications),
    SETTINGS("設定", "設定", Icons.Filled.Settings),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(vm: MainViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val refreshing by vm.refreshing.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(Tab.USAGE) }
    var adding by rememberSaveable { mutableStateOf<Provider?>(null) }
    var pickingProvider by rememberSaveable { mutableStateOf(false) }
    var editingRule by rememberSaveable { mutableStateOf(false) }

    adding?.let { provider ->
        AddAccountScreen(provider = provider, onClose = { adding = null })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(tab.title) },
                actions = {
                    if (tab == Tab.USAGE) {
                        IconButton(onClick = vm::refresh, enabled = !refreshing) {
                            if (refreshing) {
                                CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Filled.Refresh, contentDescription = "重新整理")
                            }
                        }
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { item ->
                    NavigationBarItem(
                        selected = tab == item,
                        onClick = { tab = item },
                        icon = { Icon(item.icon, contentDescription = null) },
                        label = { Text(item.label) },
                    )
                }
            }
        },
        floatingActionButton = {
            when (tab) {
                Tab.ACCOUNTS -> ExtendedFloatingActionButton(
                    onClick = { pickingProvider = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新增帳號") },
                )
                Tab.ALERTS -> ExtendedFloatingActionButton(
                    onClick = { editingRule = true },
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("新增提醒") },
                )
                else -> Unit
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (tab) {
                Tab.USAGE -> DashboardScreen(
                    state = state,
                    refreshing = refreshing,
                    onRefresh = vm::refresh,
                    onAddAccount = { pickingProvider = true },
                    onReauth = { adding = it },
                )
                Tab.ACCOUNTS -> AccountsScreen(state = state, vm = vm, onReauth = { adding = it })
                Tab.ALERTS -> AlertsScreen(
                    state = state,
                    vm = vm,
                    showNewRule = editingRule,
                    onNewRuleClosed = { editingRule = false },
                )
                Tab.SETTINGS -> SettingsScreen(state = state, vm = vm, refreshing = refreshing)
            }
        }
    }

    if (pickingProvider) {
        ProviderPickerDialog(
            onPick = {
                pickingProvider = false
                adding = it
            },
            onDismiss = { pickingProvider = false },
        )
    }
}

@Composable
private fun ProviderPickerDialog(onPick: (Provider) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增哪一家的帳號？") },
        text = {
            Column {
                listOf(
                    Triple(Provider.OPENAI, "OpenAI（ChatGPT）", "Plus / Pro 訂閱：5 小時與每週限制"),
                    Triple(Provider.CLAUDE, "Claude", "Pro / Max 訂閱：5 小時、每週及 Fable 等模型限制"),
                ).forEach { (provider, title, description) ->
                    ListItem(
                        headlineContent = { Text(title) },
                        supportingContent = { Text(description) },
                        leadingContent = { ProviderDot(provider) },
                        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                        modifier = Modifier.clickable { onPick(provider) },
                    )
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
