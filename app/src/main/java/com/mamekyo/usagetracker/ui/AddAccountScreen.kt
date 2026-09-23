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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mamekyo.usagetracker.data.Provider

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
                title = { Text("新增 ${provider.displayName} 帳號") },
                navigationIcon = {
                    IconButton(onClick = close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
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
                        Text("使用無痕分頁登入", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "不沿用瀏覽器已登入的帳號，方便加入第二個帳號（需 Chrome 較新版本）。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = ephemeral, onCheckedChange = { ephemeral = it })
                }

                authUrl?.let { url ->
                    TextButton(onClick = { Clipboard.copy(context, "login url", url) }) {
                        Text("複製登入網址（可貼到其他瀏覽器開啟）")
                    }
                }
            }
        }
    }
}

@Composable
private fun ClaudeSteps(vm: LoginViewModel, status: LoginViewModel.Status, ephemeral: Boolean) {
    val context = LocalContext.current
    var code by rememberSaveable { mutableStateOf("") }
    val busy = status is LoginViewModel.Status.Working

    StepCard("1", "開啟 Claude 登入頁面", "登入 Claude 帳號後，在授權頁按「Authorize」。") {
        Button(onClick = { Browser.open(context, vm.startClaude(), ephemeral) }, enabled = !busy) {
            Text("開啟登入頁面")
        }
    }
    StepCard("2", "貼上授權碼", "授權後頁面會顯示一段授權碼，按下複製並回到這裡貼上。") {
        OutlinedTextField(
            value = code,
            onValueChange = { code = it },
            label = { Text("授權碼") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { Clipboard.paste(context)?.let { code = it.trim() } }) { Text("從剪貼簿貼上") }
            Spacer(Modifier.weight(1f))
            Button(onClick = { vm.submitClaudeCode(code) }, enabled = code.isNotBlank() && !busy) { Text("完成登入") }
        }
    }
}

@Composable
private fun OpenAiSteps(vm: LoginViewModel, status: LoginViewModel.Status, ephemeral: Boolean) {
    val context = LocalContext.current
    var callbackUrl by rememberSaveable { mutableStateOf("") }
    val busy = status is LoginViewModel.Status.Working

    StepCard("A", "瀏覽器登入（建議）", "在瀏覽器登入 ChatGPT 帳號，完成後帳號會自動加入。") {
        Button(
            onClick = { vm.startOpenAiBrowser()?.let { Browser.open(context, it, ephemeral) } },
            enabled = !busy,
        ) { Text("開啟登入頁面") }
        if (status is LoginViewModel.Status.Waiting) {
            Text(
                "若登入後瀏覽器顯示「無法連上這個網站」，請複製網址列中以 http://localhost:1455 開頭的完整網址，貼到下方：",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = callbackUrl,
                onValueChange = { callbackUrl = it },
                label = { Text("回呼網址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { Clipboard.paste(context)?.let { callbackUrl = it.trim() } }) { Text("從剪貼簿貼上") }
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { vm.submitOpenAiCallback(callbackUrl) }, enabled = callbackUrl.isNotBlank()) {
                    Text("送出網址")
                }
            }
        }
    }
    StepCard(
        "B",
        "裝置代碼登入",
        "瀏覽器登入不順時可改用此方式：取得代碼後到驗證頁面輸入。若出現錯誤，可能需先在 ChatGPT 網頁版「設定 → 安全性」啟用 Codex 裝置代碼登入。",
    ) {
        OutlinedButton(onClick = vm::startOpenAiDevice, enabled = !busy) { Text("取得裝置代碼") }
    }
}

@Composable
private fun StatusPanel(status: LoginViewModel.Status, ephemeral: Boolean, onDone: () -> Unit) {
    val context = LocalContext.current
    when (status) {
        LoginViewModel.Status.Idle -> Unit
        is LoginViewModel.Status.Waiting -> ProgressCard(status.message)
        is LoginViewModel.Status.Working -> ProgressCard(status.message)
        is LoginViewModel.Status.DeviceCode -> Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("在驗證頁面輸入以下代碼：", style = MaterialTheme.typography.bodyMedium)
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
                    }) { Text("複製並開啟驗證頁") }
                    OutlinedButton(onClick = { Clipboard.copy(context, "device code", status.code) }) { Text("複製代碼") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                    Text("等待授權中（代碼 15 分鐘內有效）", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        is LoginViewModel.Status.Success -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("已加入帳號", style = MaterialTheme.typography.titleMedium)
                Text(
                    listOfNotNull(status.account.displayName, status.account.plan).joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = onDone) { Text("完成") }
            }
        }
        is LoginViewModel.Status.Failed -> Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Text(
                status.message,
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
