package com.mamekyo.usagetracker.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.LinearProgressIndicator
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.UsageTrackerApp
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.DisplayMode
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.domain.Aggregator
import com.mamekyo.usagetracker.domain.Format
import com.mamekyo.usagetracker.domain.Level
import com.mamekyo.usagetracker.domain.UsageView
import com.mamekyo.usagetracker.domain.WindowView
import com.mamekyo.usagetracker.ui.MainActivity
import com.mamekyo.usagetracker.ui.theme.brandColor
import com.mamekyo.usagetracker.ui.theme.color
import com.mamekyo.usagetracker.work.RefreshWorker
import kotlinx.coroutines.launch

class UsageWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store.get(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val state by store.state.collectAsState()
            GlanceTheme {
                WidgetContent(state, state.widgets[appWidgetId] ?: Source.All)
            }
        }
    }

    companion object {
        suspend fun updateAll(context: Context) = UsageWidget().updateAll(context)
    }
}

class UsageWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = UsageWidget()

    override fun onDeleted(context: Context, appWidgetIds: IntArray) {
        super.onDeleted(context, appWidgetIds)
        val store = Store.get(context)
        UsageTrackerApp.scope.launch {
            store.update { state -> state.copy(widgets = state.widgets - appWidgetIds.toSet()) }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.refreshNow(context)
    }
}

@Composable
private fun WidgetContent(state: AppState, source: Source) {
    val now = System.currentTimeMillis()
    val views = Aggregator.views(source, state, now)
    val mode = state.settings.displayMode
    val compact = LocalSize.current.height < 100.dp
    val openApp = actionStartActivity<MainActivity>()

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(GlanceTheme.colors.widgetBackground)
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = headerTitle(source, state, views),
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().clickable(openApp),
            )
            if (!compact && state.lastRefreshAt > 0) {
                Text(
                    text = Format.clock(state.lastRefreshAt, now),
                    style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                )
            }
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = "重新整理",
                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSurfaceVariant),
                modifier = GlanceModifier.size(28.dp).padding(5.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }

        if (views.isEmpty()) {
            Text(
                text = if (state.accounts.isEmpty()) "尚未新增帳號，點此開啟 App 登入" else "所選帳號已不存在，請重新設定小工具",
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 12.sp),
                modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp).clickable(openApp),
            )
            return@Column
        }

        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            views.forEach { view ->
                if (views.size > 1) {
                    item { SectionHeader(view) }
                }
                if (view.windows.isEmpty()) {
                    item {
                        Text(
                            text = view.error ?: "尚無資料，請稍候更新",
                            style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 11.sp),
                            modifier = GlanceModifier.padding(vertical = 4.dp),
                        )
                    }
                } else {
                    items(view.windows) { window -> WindowRow(window, mode, now, compact) }
                    if (view.error != null && !compact) {
                        item {
                            Text(
                                text = "⚠ ${view.error}",
                                style = TextStyle(color = ColorProvider(Level.CRITICAL.color()), fontSize = 10.sp),
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun headerTitle(source: Source, state: AppState, views: List<UsageView>): String = when (source) {
    Source.All -> "AI 用量"
    is Source.Merged -> views.firstOrNull()?.let { if (it.accountCount > 1) "${it.title} · ${it.accountCount}" else it.title }
        ?: Aggregator.sourceLabel(source, state)
    is Source.Single -> views.firstOrNull()?.let { "${it.provider.displayName} · ${it.title}" }
        ?: Aggregator.sourceLabel(source, state)
}

@Composable
private fun SectionHeader(view: UsageView) {
    Row(modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(ColorProvider(view.provider.brandColor()))) {}
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = view.title + (view.subtitle?.let { " · $it" } ?: ""),
            style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun WindowRow(window: WindowView, mode: DisplayMode, now: Long, compact: Boolean) {
    val percent = Format.shownPercent(window.usedPercent, mode)
    val color = Format.level(window.usedPercent).color()
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 3.dp).clickable(actionStartActivity<MainActivity>())) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = window.label,
                style = TextStyle(color = GlanceTheme.colors.onSurface, fontSize = 12.sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
            )
            Text(
                text = "${Format.modeWord(mode)} $percent%",
                style = TextStyle(color = ColorProvider(color), fontSize = 12.sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height(3.dp))
        LinearProgressIndicator(
            progress = percent / 100f,
            modifier = GlanceModifier.fillMaxWidth().height(6.dp),
            color = ColorProvider(color),
            backgroundColor = ColorProvider(color.copy(alpha = 0.25f)),
        )
        if (!compact) {
            Text(
                text = Format.resetText(window.resetsAt, now),
                style = TextStyle(color = GlanceTheme.colors.onSurfaceVariant, fontSize = 10.sp),
                modifier = GlanceModifier.padding(top = 2.dp),
            )
        }
    }
}
