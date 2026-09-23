package com.mamekyo.usagetracker.ui

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.mamekyo.usagetracker.data.DisplayMode
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.domain.Format
import com.mamekyo.usagetracker.domain.UsageView
import com.mamekyo.usagetracker.domain.WindowView
import com.mamekyo.usagetracker.ui.theme.brandColor
import com.mamekyo.usagetracker.ui.theme.color
import kotlinx.coroutines.delay

/** Current time, ticking every [periodMillis] so countdowns stay live. */
@Composable
fun rememberNow(periodMillis: Long = 30_000): State<Long> = produceState(System.currentTimeMillis()) {
    while (true) {
        delay(periodMillis)
        value = System.currentTimeMillis()
    }
}

@Composable
fun ProviderDot(provider: Provider, modifier: Modifier = Modifier) {
    Box(modifier.size(10.dp).clip(CircleShape).background(provider.brandColor()))
}

@Composable
fun UsageCard(
    view: UsageView,
    mode: DisplayMode,
    now: Long,
    modifier: Modifier = Modifier,
    onReauth: (() -> Unit)? = null,
) {
    Card(modifier = modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProviderDot(view.provider)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        view.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    view.subtitle?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    Format.updatedText(view.fetchedAt, now),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (view.windows.isEmpty() && view.error == null) {
                Spacer(Modifier.height(12.dp))
                Text("尚無用量資料", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            view.windows.forEach { window ->
                Spacer(Modifier.height(12.dp))
                WindowRow(window, mode, now, showAccountCount = view.accountCount > 1)
            }
            view.error?.let { error ->
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "⚠ $error",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f),
                    )
                    if (view.needsReauth && onReauth != null) {
                        TextButton(onClick = onReauth) { Text("重新登入") }
                    }
                }
            }
        }
    }
}

@Composable
fun WindowRow(window: WindowView, mode: DisplayMode, now: Long, showAccountCount: Boolean = false) {
    val percent = Format.shownPercent(window.usedPercent, mode)
    val color = Format.level(window.usedPercent).color()
    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(window.label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
            Text(
                "${Format.modeWord(mode)} $percent%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = color,
            )
        }
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(
            progress = { percent / 100f },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = color.copy(alpha = 0.2f),
            strokeCap = StrokeCap.Round,
            gapSize = 0.dp,
            drawStopIndicator = {},
        )
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(
                Format.resetText(window.resetsAt, now),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (showAccountCount) {
                Text(
                    "${window.accountCount} 個帳號平均",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun RadioRow(selected: Boolean, title: String, subtitle: String? = null, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected, onClick = onClick, role = Role.RadioButton)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null)
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            subtitle?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

object Browser {
    /** Opens [url] in a Custom Tab; ephemeral tabs keep no cookies, which makes adding a second account easy. */
    fun open(context: Context, url: String, ephemeral: Boolean) {
        val intent = CustomTabsIntent.Builder()
            .setShowTitle(true)
            .apply { if (ephemeral) setEphemeralBrowsingEnabled(true) }
            .build()
        try {
            intent.launchUrl(context, url.toUri())
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(context, "找不到可用的瀏覽器", Toast.LENGTH_LONG).show()
        }
    }
}

object Clipboard {
    fun copy(context: Context, label: String, text: String) {
        context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(label, text))
        Toast.makeText(context, "已複製", Toast.LENGTH_SHORT).show()
    }

    fun paste(context: Context): String? =
        context.getSystemService(ClipboardManager::class.java).primaryClip
            ?.takeIf { it.itemCount > 0 }
            ?.getItemAt(0)
            ?.coerceToText(context)
            ?.toString()
}
