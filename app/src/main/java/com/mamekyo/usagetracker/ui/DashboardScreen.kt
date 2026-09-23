package com.mamekyo.usagetracker.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.domain.Aggregator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    state: AppState,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onAddAccount: () -> Unit,
    onReauth: (Provider) -> Unit,
) {
    val now by rememberNow()
    val mode = state.settings.displayMode

    PullToRefreshBox(isRefreshing = refreshing, onRefresh = onRefresh, modifier = Modifier.fillMaxSize()) {
        if (state.accounts.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(stringResource(R.string.dashboard_empty_title), style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.dashboard_empty_body),
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
                Button(onClick = onAddAccount) { Text(stringResource(R.string.fab_add_account)) }
            }
            return@PullToRefreshBox
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Provider.entries.forEach { provider ->
                val accounts = state.accounts.filter { it.provider == provider }
                if (accounts.isEmpty()) return@forEach
                item(key = "header-$provider") {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                        ProviderDot(provider)
                        Spacer(Modifier.width(8.dp))
                        Text(provider.displayName, style = MaterialTheme.typography.titleLarge)
                    }
                }
                if (accounts.count { it.includeInMerge } > 1) {
                    Aggregator.mergedView(provider, state, now)?.let { merged ->
                        item(key = "merged-$provider") { UsageCard(merged, mode, now) }
                    }
                }
                items(accounts, key = { it.id }) { account ->
                    UsageCard(
                        view = Aggregator.accountView(account, state.usage[account.id], now),
                        mode = mode,
                        now = now,
                        onReauth = { onReauth(account.provider) },
                    )
                }
            }
            item(key = "footer") {
                Text(
                    stringResource(R.string.dashboard_footer),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                )
            }
        }
    }
}
