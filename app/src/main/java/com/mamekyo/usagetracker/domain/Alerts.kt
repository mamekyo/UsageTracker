package com.mamekyo.usagetracker.domain

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.mamekyo.usagetracker.R
import com.mamekyo.usagetracker.data.AlertRule
import com.mamekyo.usagetracker.data.AppState
import com.mamekyo.usagetracker.data.Store
import com.mamekyo.usagetracker.ui.MainActivity
import kotlin.math.roundToInt

object Alerts {
    private const val CHANNEL_ID = "usage_alerts"

    data class Trigger(val rule: AlertRule, val view: UsageView, val window: WindowView, val firedKey: String)

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL_ID, "用量提醒", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "剩餘用量低於設定門檻時通知"
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun canNotify(context: Context): Boolean {
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return granted && NotificationManagerCompat.from(context).areNotificationsEnabled()
    }

    /**
     * Pure rule evaluation. Returns the windows that newly crossed their threshold and the set of keys
     * that remain at/below threshold (so they are not re-notified until usage recovers, e.g. after a reset).
     */
    fun evaluate(state: AppState, now: Long): Pair<List<Trigger>, Set<String>> {
        val triggers = mutableListOf<Trigger>()
        val below = mutableSetOf<String>()
        for (rule in state.rules) {
            if (!rule.enabled) continue
            val view = Aggregator.views(rule.source, state, now).firstOrNull() ?: continue
            for (window in view.windows) {
                if (rule.windowKey != null && window.key != rule.windowKey) continue
                val key = "${rule.id}|${window.key}"
                if (window.remainingPercent <= rule.thresholdRemaining) {
                    below += key
                    if (key !in state.firedAlerts) triggers += Trigger(rule, view, window, key)
                }
            }
        }
        return triggers to below
    }

    suspend fun evaluate(context: Context) {
        val store = Store.get(context)
        val now = System.currentTimeMillis()
        val (triggers, below) = evaluate(store.state.value, now)
        val allowed = canNotify(context)
        val notified = if (allowed) triggers.onEach { post(context, it, now) }.map { it.firedKey }.toSet() else emptySet()
        // Keys that could not be shown yet stay armed so they fire once notifications are allowed.
        val pending = triggers.map { it.firedKey }.toSet() - notified
        store.update { it.copy(firedAlerts = below - pending) }
    }

    private fun post(context: Context, trigger: Trigger, now: Long) {
        val window = trigger.window
        val remaining = window.remainingPercent.roundToInt().coerceIn(0, 100)
        val title = "${trigger.view.title}：${window.label}用量偏低"
        val text = "剩餘 $remaining%（門檻 ${trigger.rule.thresholdRemaining}%），${Format.resetText(window.resetsAt, now)}"
        val intent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_usage)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(intent)
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(trigger.firedKey.hashCode(), notification)
        } catch (_: SecurityException) {
            // Permission revoked between the check and the post.
        }
    }

    fun sendTest(context: Context) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_usage)
            .setContentTitle("UsageTracker 測試通知")
            .setContentText("通知設定正常運作")
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(1, notification)
        } catch (_: SecurityException) {
        }
    }
}
