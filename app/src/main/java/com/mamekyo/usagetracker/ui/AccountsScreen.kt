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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.data.Account
import com.mamekyo.usagetracker.data.AccountUsage
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.domain.Aggregator
import com.mamekyo.usagetracker.i18n.Texts

@Composable
fun AccountsScreen(state: AppState, vm: MainViewModel, onReauth: (Provider) -> Unit) {
    var renaming by remember { mutableStateOf<Account?>(null) }
    var deleting by remember { mutableStateOf<Account?>(null) }

    if (state.accounts.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.accounts_empty),
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
            title = { Text(stringResource(R.string.action_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.rename_label)) },
                    placeholder = { Text(account.email ?: "") },
                    singleLine = true,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.rename(account.id, name)
                    renaming = null
                }) { Text(stringResource(R.string.action_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }

    deleting?.let { account ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_body, Texts.accountName(LocalContext.current, account))) },
            confirmButton = {
                TextButton(onClick = {
                    vm.removeAccount(account.id)
                    deleting = null
                }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text(stringResource(R.string.action_cancel)) } },
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
    val c = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderDot(account.provider)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        Texts.accountName(c, account),
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
                Text(stringResource(R.string.error_reauth), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(Modifier.height(8.dp))
            val tracked = Aggregator.accountView(account, usage, System.currentTimeMillis()).windows
                .joinToString(stringResource(R.string.list_separator)) { Texts.window(c, it) }
            Text(
                if (tracked.isEmpty()) stringResource(R.string.tracked_limits_none) else stringResource(R.string.tracked_limits, tracked),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.include_in_merge), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Switch(checked = account.includeInMerge, onCheckedChange = onToggleMerge)
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onRename) { Text(stringResource(R.string.action_rename)) }
                TextButton(onClick = onReauth) { Text(stringResource(R.string.action_relogin)) }
                TextButton(onClick = onDelete) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            }
        }
    }
}
