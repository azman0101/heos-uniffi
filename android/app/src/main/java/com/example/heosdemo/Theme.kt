package com.example.heosdemo

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Graphite = Color(0xFF17181A)
private val Raised = Color(0xFF34363A)
private val OffWhite = Color(0xFFECECE8)

private val HeosDark = darkColorScheme(
    primary = OffWhite,
    onPrimary = Graphite,
    secondaryContainer = Raised,
    onSecondaryContainer = OffWhite,
    background = Graphite,
    onBackground = OffWhite,
    surface = Graphite,
    onSurface = OffWhite,
    surfaceVariant = Raised,
    onSurfaceVariant = Color(0xFF9C9EA3),
    surfaceContainerHighest = Raised,
    inverseSurface = OffWhite,
    inverseOnSurface = Graphite,
    outline = Color(0xFF5A5C60),
    outlineVariant = Raised,
    error = Color(0xFFF2B8B5),
)

/** L'application est volontairement toujours en sombre (écoute au salon, le soir). */
@Composable
fun HeosTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = HeosDark, content = content)
}
