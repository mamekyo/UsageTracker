package com.mamekyo.usagetracker.ui

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.mamekyo.usagetracker.i18n.Locales
import com.mamekyo.usagetracker.ui.theme.UsageTrackerTheme

class MainActivity : ComponentActivity() {
    private val viewModel: MainViewModel by viewModels()

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(newBase)
        Locales.overrideConfiguration(newBase)?.let(::applyOverrideConfiguration)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            UsageTrackerTheme {
                AppRoot(viewModel)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshIfStale()
    }
}
