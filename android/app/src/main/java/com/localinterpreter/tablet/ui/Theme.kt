package com.localinterpreter.tablet.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val Accent = Color(0xFF4FD1A5)
val AccentDim = Color(0xFF2F8F74)
val Danger = Color(0xFFF07070)
val Warn = Color(0xFFE0B85C)

private val DarkScheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF06281F),
    primaryContainer = Color(0xFF153B31),
    onPrimaryContainer = Color(0xFFB9F4E0),
    secondary = Color(0xFF7AA2FF),
    onSecondary = Color(0xFF071A3A),
    background = Color(0xFF0B0D10),
    onBackground = Color(0xFFE8EAED),
    surface = Color(0xFF0F1216),
    onSurface = Color(0xFFE8EAED),
    surfaceVariant = Color(0xFF1B1F26),
    onSurfaceVariant = Color(0xFF9AA3AF),
    outline = Color(0xFF2B313A),
    outlineVariant = Color(0xFF22272F),
    error = Danger,
    onError = Color(0xFF2B0A0A),
    errorContainer = Color(0xFF3B1414),
    onErrorContainer = Color(0xFFFFD9D9),
)

private val LightScheme = lightColorScheme(
    primary = Color(0xFF0E8A67),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFC9F2E4),
    onPrimaryContainer = Color(0xFF053A2B),
    secondary = Color(0xFF4368C9),
    background = Color(0xFFF6F7F9),
    onBackground = Color(0xFF171A1F),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF171A1F),
    surfaceVariant = Color(0xFFEDF0F4),
    onSurfaceVariant = Color(0xFF5E6673),
    outline = Color(0xFFD7DCE4),
    outlineVariant = Color(0xFFE4E8EE),
    error = Color(0xFFC0392B),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 30.sp, lineHeight = 38.sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 26.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 14.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 14.sp),
)

@Composable
fun AppTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onSurface) {
            content()
        }
    }
}

fun Context.findActivity(): Activity? {
    var ctx = this
    while (ctx is ContextWrapper) {
        if (ctx is Activity) return ctx
        ctx = ctx.baseContext
    }
    return null
}
