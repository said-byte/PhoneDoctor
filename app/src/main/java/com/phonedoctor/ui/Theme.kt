package com.phonedoctor.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Тёплая «бумажная» палитра: приложение про спокойствие, а не про неоновый дашборд.
 */
private val Light = lightColorScheme(
    primary = Color(0xFFB4573A),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF3DCD2),
    onPrimaryContainer = Color(0xFF3A1A10),
    secondary = Color(0xFF4A6B5D),
    onSecondary = Color(0xFFFFFFFF),
    background = Color(0xFFF6F1E7),
    onBackground = Color(0xFF241E18),
    surface = Color(0xFFFFFBF4),
    onSurface = Color(0xFF241E18),
    surfaceVariant = Color(0xFFEDE5D8),
    onSurfaceVariant = Color(0xFF5E564B),
    outline = Color(0xFFCFC5B6),
    error = Color(0xFFA33A26),
)

@Composable
fun PhoneDoctorTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = Light, content = content)
}
