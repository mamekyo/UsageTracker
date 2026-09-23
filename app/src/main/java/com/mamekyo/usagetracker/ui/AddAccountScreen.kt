package com.mamekyo.usagetracker.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.i18n.Texts

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddAccountScreen(provider: Provider, onClose: () -> Unit, vm: LoginViewModel = viewModel()) {
    val status by vm.status.collectAsStateWithLifecycle()
    val authUrl by vm.authUrl.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var ephemeral by rememberSaveable { mutableStateOf(true) }
    val close = {
        vm.reset()
        onClose()
    }
    BackHandler(onBack = close)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.add_account_title, provider.displayName)) },
                navigationIcon = {
                    IconButton(onClick = close) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            StatusPanel(status, ephemeral, onDone = close)

            if (status !is LoginViewModel.Status.Success) {
                when (provider) {
                    Provider.CLAUDE -> ClaudeSteps(vm, status, ephemeral)
                    Provider.OPENAI -> OpenAiSteps(vm, status, ephemeral)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.ephemeral_title), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.ephemeral_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ephemeral, onCheckedChange = { ephemeral = it })
                }

                authUrl?.let { url ->
                    TextButton(onClick = { Clipboard.copy(context, "login url", url) }) {
                        Text(stringResource(R.string.copy_login_url))
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaudeSteps(vm: LoginViewModel, status: LoginViewModel.Status, ephemeral: Boolean) {
    BrowserLoginCard(
        step = "1",
        description = stringResource(R.string.claude_login_desc),
        fallbackHint = stringResource(R.string.claude_fallback_hint),
        status = status,
        ephemeral = ephemeral,
        onStart = vm::startClaude,
        onSubmit = vm::submitClaudeCode,
    )
}

@Composable
private fun OpenAiSteps(vm: LoginViewModel, status: LoginViewModel.Status, ephemeral: Boolean) {
    BrowserLoginCard(
        step = "A",
        description = stringResource(R.string.openai_login_desc),
        fallbackHint = stringResource(R.string.openai_fallback_hint),
        status = status,
        ephemeral = ephemeral,
        onStart = vm::startOpenAiBrowser,
        onSubmit = vm::submitOpenAiCallback,
    )
    StepCard(
        "B",
        stringResource(R.string.device_login_title),
        stringResource(R.string.device_login_desc),
    ) {
        OutlinedButton(onClick = vm::startOpenAiDevice, enabled = status !is LoginViewModel.Status.Working) {
            Text(stringResource(R.string.get_device_code))
        }
    }
}

/** Opens the provider's login page; the loopback redirect finishes the login, pasting is the fallback. */
@Composable
private fun BrowserLoginCard(
    step: String,
    description: String,
    fallbackHint: String,
    status: LoginViewModel.Status,
    ephemeral: Boolean,
    onStart: () -> String?,
    onSubmit: (String) -> Unit,
) {
    val context = LocalContext.current
    var pasted by rememberSaveable { mutableStateOf("") }
    val busy = status is LoginViewModel.Status.Working

    StepCard(step, stringResource(R.string.browser_login_title), description) {
        Button(onClick = { onStart()?.let { Browser.open(context, it, ephemeral) } }, enabled = !busy) {
            Text(stringResource(R.string.open_login_page))
        }
        if (status is LoginViewModel.Status.Waiting) {
            Text(fallbackHint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(
                value = pasted,
                onValueChange = { pasted = it },
                label = { Text(stringResource(R.string.url_or_code)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { Clipboard.paste(context)?.let { pasted = it.trim() } }) { Text(stringResource(R.string.action_paste)) }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { onSubmit(pasted) }, enabled = pasted.isNotBlank()) { Text(stringResource(R.string.action_submit)) }
            }
        }
    }
}

@Composable
private fun StatusPanel(status: LoginViewModel.Status, ephemeral: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    when (status) {
        LoginViewModel.Status.Idle -> Unit
        is LoginViewModel.Status.Waiting -> ProgressCard(stringResource(status.message))
        is LoginViewModel.Status.Working -> ProgressCard(stringResource(status.message))
        is LoginViewModel.Status.DeviceCode -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.device_code_prompt), style = MaterialTheme.typography.bodyMedium)
                SelectionContainer {
                    Text(
                        status.code,
                        style = MaterialTheme.typography.headlineMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        Clipboard.copy(context, "device code", status.code)
                        Browser.open(context, status.url, ephemeral)
                    }) { Text(stringResource(R.string.copy_and_open_verify)) }
                    OutlinedButton(onClick = { Clipboard.copy(context, "device code", status.code) }) {
                        Text(stringResource(R.string.copy_code))
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.device_waiting), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is LoginViewModel.Status.Success -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.account_added), style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(Texts.accountName(context, status.account), status.account.plan).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
            }
        }
        is LoginViewModel.Status.Failed -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                Texts.error(context, status.error),
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun ProgressCard(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun StepCard(step: String, title: String, description: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$step. $title", style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}
