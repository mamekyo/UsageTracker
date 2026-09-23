package com.mamekyo.usagetracker.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamekyo.usagetracker.data.Account
import com.mamekyo.usagetracker.data.AccountUsage
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.Provider

@Composable
fun AccountsScreen(state: AppState, vm: MainViewModel, onReauth: (Provider) -> Unit) {
    var renaming by remember { mutableStateOf<Account?>(null) }
    var deleting by remember { mutableStateOf<Account?>(null) }

    if (state.accounts.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                "尚未新增帳號。\n點右下角「新增帳號」登入 OpenAI 或 Claude。",
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            items(state.accounts, key = { it.id }) { account ->
                AccountCard(
                    account = account,
                    usage = state.usage[account.id],
                    onToggleMerge = { vm.setIncludeInMerge(account.id, it) },
                    onRename = { renaming = account },
                    onReauth = { onReauth(account.provider) },
                    onDelete = { deleting = account },
                )
            }
        }
    }

    renaming?.let { account ->
        var name by remember(account.id) { mutableStateOf(account.nickname.orEmpty()) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重新命名") },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("顯示名稱") },
                    placeholder = { Text(account.email ?: "") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(account.id, name)
                    renaming = null
                }) { Text("儲存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }

    deleting?.let { account ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("刪除帳號？") },
            text = { Text("將從本機移除「${account.displayName}」的登入資訊、用量紀錄以及只針對此帳號的提醒。") },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeAccount(account.id)
                    deleting = null
                }) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } },
        )
    }
}

@Composable
private fun AccountCard(
    account: Account,
    usage: AccountUsage?,
    onToggleMerge: (Boolean) -> Unit,
    onRename: () -> Unit,
    onReauth: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderDot(account.provider)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        account.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val details = listOfNotNull(
                        account.provider.displayName + (account.plan?.let { " $it" } ?: ""),
                        account.email.takeIf { account.nickname != null },
                    )
                    Text(
                        details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (account.needsReauth) {
                Spacer(Modifier.height(8.dp))
                Text("登入已失效，請重新登入", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            val tracked = usage?.windows.orEmpty().joinToString("、") { it.label }
            Text(
                if (tracked.isEmpty()) "可追蹤的限制：尚未取得" else "可追蹤的限制：$tracked",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("納入合併計算", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = account.includeInMerge, onCheckedChange = onToggleMerge)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRename) { Text("重新命名") }
                TextButton(onClick = onReauth) { Text("重新登入") }
                TextButton(onClick = onDelete) { Text("刪除", color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
