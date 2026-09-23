package com.mamekyo.usagetracker.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.mamekyo.usagetracker.data.Provider
import com.mamekyo.usagetracker.domain.Level

@Composable
fun UsageTrackerTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary = Color(0xFF7FD8BE), secondary = Color(0xFFF0A58A))
        else -> lightColorScheme(primary = Color(0xFF0E7C63), secondary = Color(0xFFB65B3C))
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun Provider.brandColor(): Color = when (this) {
    Provider.OPENAI -> Color(0xFF10A37F)
    Provider.CLAUDE -> Color(0xFFD97757)
}

fun Level.color(): Color = when (this) {
    Level.GOOD -> Color(0xFF2E9D4F)
    Level.WARN -> Color(0xFFD17D00)
    Level.CRITICAL -> Color(0xFFD93025)
}
