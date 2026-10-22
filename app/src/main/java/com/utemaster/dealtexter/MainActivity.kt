package com.utemaster.dealtexter

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import com.utemaster.dealtexter.ui.HomeScreen
import com.utemaster.dealtexter.ui.SettingsScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            TexterTheme {
                var showSettings by rememberSaveable { mutableStateOf(false) }
                BackHandler(enabled = showSettings) { showSettings = false }
                if (showSettings) {
                    SettingsScreen(onBack = { showSettings = false })
                } else {
                    HomeScreen(onOpenSettings = { showSettings = true })
                }
            }
        }
    }
}

@Composable
private fun TexterTheme(content: @Composable () -> Unit) {
    val accent = Color(0xFFF2A900)
    val scheme = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = accent, onPrimary = Color(0xFF1B1B1B))
    } else {
        lightColorScheme(primary = Color(0xFF1B1B1B), secondary = accent)
    }
    MaterialTheme(colorScheme = scheme, content = content)
}
