package com.mamekyo.usagetracker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.ColorUtils
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
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
import androidx.glance.layout.ColumnScope
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
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.data.Source
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.data.WidgetStyle
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
import kotlin.math.roundToInt

class UsageWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val store = Store.get(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val state by store.state.collectAsState()
            WidgetContent(
                state = state,
                source = state.widgets[appWidgetId] ?: Source.All,
                style = state.widgetStyles[appWidgetId] ?: WidgetStyle.BARS,
            )
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
        val ids = appWidgetIds.toSet()
        UsageTrackerApp.scope.launch {
            store.update { state -> state.copy(widgets = state.widgets - ids, widgetStyles = state.widgetStyles - ids) }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        RefreshWorker.refreshNow(context)
    }
}

// The widget always uses a white card, independent of the system dark theme.
private val TextPrimary = ColorProvider(Color(0xFF1F2328))
private val TextSecondary = ColorProvider(Color(0xFF6B7280))

private val HeaderHeight = 28.dp
private val CompactHeaderHeight = 24.dp
private val SectionHeight = 20.dp
private val BarRowHeight = 32.dp

@Composable
private fun WidgetContent(state: AppState, source: Source, style: WidgetStyle) {
    val now = System.currentTimeMillis()
    val views = Aggregator.views(source, state, now)
    val size = LocalSize.current
    val compact = size.height < 110.dp

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_background))
            .cornerRadius(16.dp)
            .padding(horizontal = 12.dp, vertical = if (compact) 6.dp else 10.dp),
    ) {
        Header(state, source, views, style, now, compact)
        when {
            views.isEmpty() -> Text(
                text = if (state.accounts.isEmpty()) "尚未新增帳號，點此開啟 App 登入" else "所選帳號已不存在，請重新設定小工具",
                style = TextStyle(color = TextSecondary, fontSize = 12.sp),
                modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(actionStartActivity<MainActivity>()),
            )
            style == WidgetStyle.RINGS -> RingsBody(views, state.settings.displayMode, now, size, compact)
            else -> BarsBody(views, state.settings.displayMode, now, size, compact)
        }
    }
}

@Composable
private fun Header(state: AppState, source: Source, views: List<UsageView>, style: WidgetStyle, now: Long, compact: Boolean) {
    val provider = if (source is Source.All) null else views.firstOrNull()?.provider
    val meta = listOfNotNull(
        Format.modeWord(state.settings.displayMode).takeIf { style == WidgetStyle.RINGS },
        Format.clock(state.lastRefreshAt, now).takeIf { state.lastRefreshAt > 0 },
    ).joinToString(" · ")
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(if (compact) CompactHeaderHeight else HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        provider?.let {
            Dot(it)
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(
            text = headerTitle(source, state, views),
            style = TextStyle(color = TextPrimary, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
        )
        if (views.any { it.error != null }) {
            Text(text = "⚠ ", style = TextStyle(color = ColorProvider(Level.CRITICAL.color()), fontSize = 11.sp))
        }
        if (meta.isNotEmpty()) {
            Text(text = meta, style = TextStyle(color = TextSecondary, fontSize = 10.sp), maxLines = 1)
        }
        Image(
            provider = ImageProvider(R.drawable.ic_refresh),
            contentDescription = "重新整理",
            colorFilter = ColorFilter.tint(TextSecondary),
            modifier = GlanceModifier.size(if (compact) 22.dp else 26.dp).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
        )
    }
}

private fun headerTitle(source: Source, state: AppState, views: List<UsageView>): String {
    val view = views.firstOrNull() ?: return Aggregator.sourceLabel(source, state)
    return when (source) {
        Source.All -> "AI 用量"
        is Source.Merged -> if (view.accountCount > 1) {
            "${view.provider.displayName} · ${view.accountCount} 帳號合併"
        } else {
            "${view.provider.displayName} · ${view.subtitle}"
        }
        is Source.Single -> view.title
    }
}

private fun sectionTitle(view: UsageView): String =
    if (view.accountCount > 1) "${view.provider.displayName} · ${view.accountCount} 帳號合併" else "${view.provider.displayName} · ${view.subtitle}"

@Composable
private fun Dot(provider: Provider) {
    Box(modifier = GlanceModifier.size(8.dp).cornerRadius(4.dp).background(ColorProvider(provider.brandColor()))) {}
}

@Composable
private fun SectionHeader(view: UsageView) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(SectionHeight), verticalAlignment = Alignment.CenterVertically) {
        Dot(view.provider)
        Spacer(GlanceModifier.width(6.dp))
        Text(
            text = sectionTitle(view),
            style = TextStyle(color = TextPrimary, fontSize = 11.sp, fontWeight = FontWeight.Medium),
            maxLines = 1,
        )
    }
}

@Composable
private fun ErrorLine(message: String) {
    Text(
        text = "⚠ $message",
        style = TextStyle(color = ColorProvider(Level.CRITICAL.color()), fontSize = 10.sp),
        maxLines = 1,
    )
}

/** Rows spread evenly over the widget when they fit; otherwise the list scrolls. */
@Composable
private fun ColumnScope.BarsBody(views: List<UsageView>, mode: DisplayMode, now: Long, size: DpSize, compact: Boolean) {
    val sections = views.size > 1
    val rows = views.sumOf { it.windows.size.coerceAtLeast(1) }
    val available = size.height - (if (compact) 12.dp else 20.dp) - (if (compact) CompactHeaderHeight else HeaderHeight)
    val needed = BarRowHeight * rows + (if (sections) SectionHeight * views.size else 0.dp)
    // Tall widgets: enlarge text and bars instead of leaving wide gaps between rows.
    val scale = (available.value / needed.value).coerceIn(1f, 1.4f)

    if (needed <= available) {
        Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
            views.forEach { view ->
                if (sections) SectionHeader(view)
                if (view.windows.isEmpty()) {
                    Text(view.error ?: "尚無資料，請稍候更新", style = TextStyle(color = TextSecondary, fontSize = 11.sp))
                }
                view.windows.forEach { window ->
                    Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.CenterStart) {
                        BarRow(window, mode, now, scale)
                    }
                }
                if (view.error != null && view.windows.isNotEmpty()) ErrorLine(view.error)
            }
        }
    } else {
        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            views.forEach { view ->
                if (sections) item { SectionHeader(view) }
                if (view.windows.isEmpty()) {
                    item { Text(view.error ?: "尚無資料，請稍候更新", style = TextStyle(color = TextSecondary, fontSize = 11.sp)) }
                }
                items(view.windows) { window -> BarRow(window, mode, now, 1f) }
                if (view.error != null && view.windows.isNotEmpty()) item { ErrorLine(view.error) }
            }
        }
    }
}

@Composable
private fun BarRow(window: WindowView, mode: DisplayMode, now: Long, scale: Float) {
    val percent = Format.shownPercent(window.usedPercent, mode)
    val color = Format.level(window.usedPercent).color()
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(actionStartActivity<MainActivity>())) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = window.label, style = TextStyle(color = TextPrimary, fontSize = (13 * scale).sp), maxLines = 1)
            Text(
                text = Format.resetText(window.resetsAt, now, short = true),
                style = TextStyle(color = TextSecondary, fontSize = (10 * scale).sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().padding(start = 6.dp),
            )
            Text(
                text = "${Format.modeWord(mode)} $percent%",
                style = TextStyle(color = ColorProvider(color), fontSize = (13 * scale).sp, fontWeight = FontWeight.Bold),
            )
        }
        Spacer(GlanceModifier.height((3 * scale).dp))
        LinearProgressIndicator(
            progress = percent / 100f,
            modifier = GlanceModifier.fillMaxWidth().height((6 * scale).dp),
            color = ColorProvider(color),
            backgroundColor = ColorProvider(color.copy(alpha = 0.2f)),
        )
    }
}

/** One row of ring gauges per provider, sized to the space available. */
@Composable
private fun ColumnScope.RingsBody(views: List<UsageView>, mode: DisplayMode, now: Long, size: DpSize, compact: Boolean) {
    val sections = views.size > 1
    val header = if (compact) CompactHeaderHeight else HeaderHeight
    val perSection = (size.height - (if (compact) 12.dp else 20.dp) - header) / views.size - (if (sections) SectionHeight else 0.dp)
    val perGauge = (size.width - 24.dp) / views.maxOf { it.windows.size }.coerceAtLeast(1)
    val showReset = perSection >= 84.dp
    val captions = if (showReset) 28.dp else 15.dp
    val ring = minOf(perSection - captions - 2.dp, perGauge - 12.dp, 88.dp).coerceAtLeast(28.dp)

    Column(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
        views.forEach { view ->
            if (sections) SectionHeader(view)
            Row(
                modifier = GlanceModifier.fillMaxWidth().defaultWeight(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (view.windows.isEmpty()) {
                    Text(view.error ?: "尚無資料，請稍候更新", style = TextStyle(color = TextSecondary, fontSize = 11.sp))
                }
                view.windows.forEach { window ->
                    Gauge(window, mode, now, ring, showReset, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun Gauge(window: WindowView, mode: DisplayMode, now: Long, ring: Dp, showReset: Boolean, modifier: GlanceModifier) {
    val context = LocalContext.current
    val percent = Format.shownPercent(window.usedPercent, mode)
    val color = Format.level(window.usedPercent).color()
    val px = (ring.value * context.resources.displayMetrics.density).roundToInt().coerceIn(48, 240)
    Column(
        modifier = modifier.clickable(actionStartActivity<MainActivity>()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.size(ring), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(ringBitmap(percent / 100f, color.toArgb(), px)),
                contentDescription = "${window.label} ${Format.modeWord(mode)} $percent%",
                modifier = GlanceModifier.size(ring),
            )
            Text(
                text = "$percent%",
                style = TextStyle(color = TextPrimary, fontSize = (ring.value * 0.24f).sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Text(text = window.label, style = TextStyle(color = TextPrimary, fontSize = 11.sp), maxLines = 1)
        if (showReset) {
            Text(
                text = Format.resetText(window.resetsAt, now, short = true),
                style = TextStyle(color = TextSecondary, fontSize = 9.sp),
                maxLines = 1,
            )
        }
    }
}

/** Donut gauge: tinted track plus a clockwise arc from 12 o'clock covering [fraction]. */
private fun ringBitmap(fraction: Float, color: Int, sizePx: Int): Bitmap {
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val stroke = sizePx * 0.14f
    val inset = stroke / 2f
    val bounds = RectF(inset, inset, sizePx - inset, sizePx - inset)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = stroke
    }
    paint.color = ColorUtils.setAlphaComponent(color, 0x38)
    canvas.drawArc(bounds, 0f, 360f, false, paint)
    val sweep = 360f * fraction.coerceIn(0f, 1f)
    if (sweep > 0f) {
        paint.color = color
        paint.strokeCap = if (sweep < 360f) Paint.Cap.ROUND else Paint.Cap.BUTT
        canvas.drawArc(bounds, -90f, sweep, false, paint)
    }
    return bitmap
}
