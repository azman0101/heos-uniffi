package com.example.heosdemo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val HeosDark = darkColorScheme(
    primary = Color(0xFF8AB4F8),
    onPrimary = Color(0xFF062E6F),
    secondaryContainer = Color(0xFF2B3A55),
    onSecondaryContainer = Color(0xFFD6E3FF),
    background = Color(0xFF0F1115),
    onBackground = Color(0xFFE6E8EC),
    surface = Color(0xFF0F1115),
    onSurface = Color(0xFFE6E8EC),
    surfaceVariant = Color(0xFF1B1F27),
    onSurfaceVariant = Color(0xFFB4BAC6),
    surfaceContainerHighest = Color(0xFF232833),
    outline = Color(0xFF4A5160),
    error = Color(0xFFFF8A80),
)

/** L'application est volontairement toujours en sombre (écoute au salon, le soir). */
@Composable
fun HeosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HeosDark, content = content)
}
