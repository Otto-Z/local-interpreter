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
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat

// 兼容常量（深色基准）；新 UI 优先使用 AppColors 以自动适配深浅主题对比度。
val Accent = Color(0xFF3FE0B0)
val AccentDim = Color(0xFF2B8E72)
val Danger = Color(0xFFF26B6B)
val Warn = Color(0xFFF0B856)

@Immutable
data class AppSemanticColors(
    val isDark: Boolean,
    val accent: Color,
    val onAccent: Color,
    val accentSoft: Color,
    val accentBorder: Color,
    val secondary: Color,
    val warn: Color,
    val warnSoft: Color,
    val danger: Color,
    val onDanger: Color,
    val dangerSoft: Color,
    val surfaceElevated: Color,
    val surfaceSubtle: Color,
    val border: Color,
    val borderSubtle: Color,
)

private val DarkSemanticColors = AppSemanticColors(
    isDark = true,
    accent = Color(0xFF3FE0B0),
    onAccent = Color(0xFF04261D),
    accentSoft = Color(0xFF13362C),
    accentBorder = Color(0xFF28735D),
    secondary = Color(0xFF7AA6FF),
    warn = Color(0xFFF0B856),
    warnSoft = Color(0xFF332814),
    danger = Color(0xFFF26B6B),
    onDanger = Color(0xFF2C0808),
    dangerSoft = Color(0xFF381618),
    surfaceElevated = Color(0xFF141820),
    surfaceSubtle = Color(0xFF1B212C),
    border = Color(0xFF2B3442),
    borderSubtle = Color(0xFF202733),
)

private val LightSemanticColors = AppSemanticColors(
    isDark = false,
    accent = Color(0xFF0B8765),
    onAccent = Color(0xFFFFFFFF),
    accentSoft = Color(0xFFE2F6EE),
    accentBorder = Color(0xFF7FD1B6),
    secondary = Color(0xFF3461D1),
    warn = Color(0xFFB57208),
    warnSoft = Color(0xFFFEF4E0),
    danger = Color(0xFFCC3838),
    onDanger = Color(0xFFFFFFFF),
    dangerSoft = Color(0xFFFDEAEA),
    surfaceElevated = Color(0xFFFFFFFF),
    surfaceSubtle = Color(0xFFEEF2F6),
    border = Color(0xFFD2DAE4),
    borderSubtle = Color(0xFFE3E8EF),
)

val LocalAppColors = staticCompositionLocalOf { DarkSemanticColors }

val AppColors: AppSemanticColors
    @Composable get() = LocalAppColors.current

private val DarkScheme = darkColorScheme(
    primary = DarkSemanticColors.accent,
    onPrimary = DarkSemanticColors.onAccent,
    primaryContainer = DarkSemanticColors.accentSoft,
    onPrimaryContainer = Color(0xFFB9F7E4),
    secondary = DarkSemanticColors.secondary,
    onSecondary = Color(0xFF071A3A),
    background = Color(0xFF0A0D12),
    onBackground = Color(0xFFEDF0F4),
    surface = DarkSemanticColors.surfaceElevated,
    onSurface = Color(0xFFEDF0F4),
    surfaceVariant = DarkSemanticColors.surfaceSubtle,
    onSurfaceVariant = Color(0xFF9BA5B4),
    outline = DarkSemanticColors.border,
    outlineVariant = DarkSemanticColors.borderSubtle,
    error = DarkSemanticColors.danger,
    onError = DarkSemanticColors.onDanger,
    errorContainer = DarkSemanticColors.dangerSoft,
    onErrorContainer = Color(0xFFFFDADA),
)

private val LightScheme = lightColorScheme(
    primary = LightSemanticColors.accent,
    onPrimary = LightSemanticColors.onAccent,
    primaryContainer = LightSemanticColors.accentSoft,
    onPrimaryContainer = Color(0xFF043B2C),
    secondary = LightSemanticColors.secondary,
    onSecondary = Color.White,
    background = Color(0xFFF4F6F9),
    onBackground = Color(0xFF14181F),
    surface = LightSemanticColors.surfaceElevated,
    onSurface = Color(0xFF14181F),
    surfaceVariant = LightSemanticColors.surfaceSubtle,
    onSurfaceVariant = Color(0xFF5A6472),
    outline = LightSemanticColors.border,
    outlineVariant = LightSemanticColors.borderSubtle,
    error = LightSemanticColors.danger,
    onError = LightSemanticColors.onDanger,
    errorContainer = LightSemanticColors.dangerSoft,
    onErrorContainer = Color(0xFF5A1111),
)

private val AppTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = (-0.3).sp),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 23.sp, lineHeight = 31.sp, letterSpacing = (-0.2).sp),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 13.5.sp, lineHeight = 19.sp),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium, fontSize = 12.sp, lineHeight = 16.sp),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp),
)

@Composable
fun AppTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) {
        "light" -> false
        "system" -> isSystemInDarkTheme()
        else -> true
    }
    val semantics = if (dark) DarkSemanticColors else LightSemanticColors
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = view.context.findActivity()?.window
            if (window != null) {
                val controller = WindowCompat.getInsetsController(window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(
        colorScheme = if (dark) DarkScheme else LightScheme,
        typography = AppTypography,
    ) {
        CompositionLocalProvider(
            LocalAppColors provides semantics,
            LocalContentColor provides MaterialTheme.colorScheme.onSurface,
        ) {
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
