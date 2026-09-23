package com.mamekyo.usagetracker.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.glance.LocalSize
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.CircularProgressIndicator
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
import com.mamekyo.usagetracker.domain.UsageRepository
import com.mamekyo.usagetracker.domain.UsageView
import com.mamekyo.usagetracker.domain.WindowView
import com.mamekyo.usagetracker.i18n.Locales
import com.mamekyo.usagetracker.i18n.Texts
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
        val repository = UsageRepository.get(context)
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        provideContent {
            val state by store.state.collectAsState()
            val refreshing by repository.refreshing.collectAsState()
            val language by Locales.revision.collectAsState()
            // A running session outlives language changes; rebuild the tree so every text is resolved again.
            key(language) {
                val texts = remember { Locales.wrap(context) }
                CompositionLocalProvider(LocalTexts provides texts) {
                    WidgetContent(
                        state = state,
                        source = state.widgets[appWidgetId] ?: Source.All,
                        style = state.widgetStyles[appWidgetId] ?: WidgetStyle.BARS,
                        refreshing = refreshing,
                    )
                }
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
        val ids = appWidgetIds.toSet()
        UsageTrackerApp.scope.launch {
            store.update { state -> state.copy(widgets = state.widgets - ids, widgetStyles = state.widgetStyles - ids) }
        }
    }
}

class RefreshAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val repository = UsageRepository.get(context)
        // A refresh already in flight is showing its spinner and will clear it when done.
        if (repository.refreshing.value) return
        repository.markRefreshRequested()
        UsageWidget.updateAll(context)
        RefreshWorker.refreshNow(context)
    }
}

/** Context used for widget text, carrying the app language. */
private val LocalTexts = staticCompositionLocalOf<Context> { error("LocalTexts not provided") }

// The widget always uses a white card, independent of the system dark theme.
private val TextPrimary = ColorProvider(Color(0xFF1F2328))
private val TextSecondary = ColorProvider(Color(0xFF6B7280))
private val RefreshAccent = Color(0xFF3B82F6)

private val HeaderHeight = 28.dp
private val CompactHeaderHeight = 24.dp
private val SectionHeight = 20.dp
private val BarRowHeight = 32.dp

@Composable
private fun WidgetContent(state: AppState, source: Source, style: WidgetStyle, refreshing: Boolean) {
    val c = LocalTexts.current
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
        Header(state, source, views, style, now, compact, refreshing)
        when {
            views.isEmpty() -> Text(
                text = c.getString(if (state.accounts.isEmpty()) R.string.widget_no_accounts else R.string.widget_account_missing),
                style = TextStyle(color = TextSecondary, fontSize = 12.sp),
                modifier = GlanceModifier.fillMaxWidth().padding(top = 6.dp).clickable(actionStartActivity<MainActivity>()),
            )
            style == WidgetStyle.RINGS -> RingsBody(views, state.settings.displayMode, now, size, compact)
            else -> BarsBody(views, state.settings.displayMode, now, size, compact)
        }
    }
}

@Composable
private fun Header(
    state: AppState,
    source: Source,
    views: List<UsageView>,
    style: WidgetStyle,
    now: Long,
    compact: Boolean,
    refreshing: Boolean,
) {
    val c = LocalTexts.current
    val provider = if (source is Source.All) null else views.firstOrNull()?.provider
    val meta = if (refreshing) {
        c.getString(R.string.widget_updating)
    } else {
        listOfNotNull(
            Texts.modeWord(c, state.settings.displayMode).takeIf { style == WidgetStyle.RINGS },
            Format.clock(state.lastRefreshAt, now).takeIf { state.lastRefreshAt > 0 },
        ).joinToString(" · ")
    }
    val buttonSize = if (compact) 22.dp else 26.dp
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(if (compact) CompactHeaderHeight else HeaderHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        provider?.let {
            Dot(it)
            Spacer(GlanceModifier.width(6.dp))
        }
        Text(
            text = headerTitle(c, source, state, views),
            style = TextStyle(color = TextPrimary, fontSize = if (compact) 12.sp else 13.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
            modifier = GlanceModifier.defaultWeight().clickable(actionStartActivity<MainActivity>()),
        )
        if (!refreshing && views.any { Texts.viewError(c, it) != null }) {
            Text(text = "⚠ ", style = TextStyle(color = ColorProvider(Level.CRITICAL.color()), fontSize = 11.sp))
        }
        if (meta.isNotEmpty()) {
            Text(text = meta, style = TextStyle(color = TextSecondary, fontSize = 10.sp), maxLines = 1)
        }
        if (refreshing) {
            // Spinning indicator replaces the button until the refresh finishes.
            Box(modifier = GlanceModifier.size(buttonSize).padding(5.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = GlanceModifier.fillMaxSize(), color = ColorProvider(RefreshAccent))
            }
        } else {
            Image(
                provider = ImageProvider(R.drawable.ic_refresh),
                contentDescription = c.getString(R.string.action_refresh),
                colorFilter = ColorFilter.tint(TextSecondary),
                modifier = GlanceModifier.size(buttonSize).padding(4.dp).clickable(actionRunCallback<RefreshAction>()),
            )
        }
    }
}

private fun headerTitle(c: Context, source: Source, state: AppState, views: List<UsageView>): String {
    val view = views.firstOrNull() ?: return Texts.source(c, source, state)
    return when (source) {
        Source.All -> c.getString(R.string.widget_title_all)
        is Source.Merged -> Texts.compactTitle(c, view)
        is Source.Single -> Texts.viewTitle(c, view)
    }
}

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
            text = Texts.compactTitle(LocalTexts.current, view),
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

@Composable
private fun NoData(view: UsageView) {
    val c = LocalTexts.current
    Text(Texts.viewError(c, view) ?: c.getString(R.string.widget_no_data), style = TextStyle(color = TextSecondary, fontSize = 11.sp))
}

/** Rows spread evenly over the widget when they fit; otherwise the list scrolls. */
@Composable
private fun ColumnScope.BarsBody(views: List<UsageView>, mode: DisplayMode, now: Long, size: DpSize, compact: Boolean) {
    val c = LocalTexts.current
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
                if (view.windows.isEmpty()) NoData(view)
                view.windows.forEach { window ->
                    Box(modifier = GlanceModifier.fillMaxWidth().defaultWeight(), contentAlignment = Alignment.CenterStart) {
                        BarRow(window, mode, now, scale)
                    }
                }
                val error = Texts.viewError(c, view)
                if (error != null && view.windows.isNotEmpty()) ErrorLine(error)
            }
        }
    } else {
        LazyColumn(modifier = GlanceModifier.fillMaxSize()) {
            views.forEach { view ->
                if (sections) item { SectionHeader(view) }
                if (view.windows.isEmpty()) item { NoData(view) }
                items(view.windows) { window -> BarRow(window, mode, now, 1f) }
                val error = Texts.viewError(c, view)
                if (error != null && view.windows.isNotEmpty()) item { ErrorLine(error) }
            }
        }
    }
}

@Composable
private fun BarRow(window: WindowView, mode: DisplayMode, now: Long, scale: Float) {
    val c = LocalTexts.current
    val percent = Format.shownPercent(window.usedPercent, mode)
    val color = Format.level(window.usedPercent).color()
    Column(modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).clickable(actionStartActivity<MainActivity>())) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(text = Texts.window(c, window), style = TextStyle(color = TextPrimary, fontSize = (13 * scale).sp), maxLines = 1)
            Text(
                text = Texts.resetText(c, window.resetsAt, now, short = true),
                style = TextStyle(color = TextSecondary, fontSize = (10 * scale).sp),
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight().padding(start = 6.dp, end = 6.dp),
            )
            Text(
                text = Texts.percent(c, percent, mode),
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
                if (view.windows.isEmpty()) NoData(view)
                view.windows.forEach { window ->
                    Gauge(window, mode, now, ring, showReset, GlanceModifier.defaultWeight())
                }
            }
        }
    }
}

@Composable
private fun Gauge(window: WindowView, mode: DisplayMode, now: Long, ring: Dp, showReset: Boolean, modifier: GlanceModifier) {
    val c = LocalTexts.current
    val label = Texts.window(c, window)
    val percent = Format.shownPercent(window.usedPercent, mode)
    val color = Format.level(window.usedPercent).color()
    val px = (ring.value * c.resources.displayMetrics.density).roundToInt().coerceIn(48, 240)
    Column(
        modifier = modifier.clickable(actionStartActivity<MainActivity>()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(modifier = GlanceModifier.size(ring), contentAlignment = Alignment.Center) {
            Image(
                provider = ImageProvider(ringBitmap(percent / 100f, color.toArgb(), px)),
                contentDescription = "$label ${Texts.percent(c, percent, mode)}",
                modifier = GlanceModifier.size(ring),
            )
            Text(
                text = "$percent%",
                style = TextStyle(color = TextPrimary, fontSize = (ring.value * 0.24f).sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
        Text(text = label, style = TextStyle(color = TextPrimary, fontSize = 11.sp), maxLines = 1)
        if (showReset) {
            Text(
                text = Texts.resetText(c, window.resetsAt, now, short = true),
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
