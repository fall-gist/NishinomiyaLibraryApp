package com.fallgist.nishinomiyalibrary.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

private fun colorSchemeFrom(colors: AppColors, dark: Boolean) = if (dark) {
    darkColorScheme(
        primary = colors.green,
        onPrimary = colors.paper,
        background = colors.paper,
        onBackground = colors.ink,
        surface = colors.card,
        onSurface = colors.ink,
        surfaceVariant = colors.chipBg,
        onSurfaceVariant = colors.ink2,
        outline = colors.line,
        error = colors.alert,
        onError = colors.paper,
    )
} else {
    lightColorScheme(
        primary = colors.green,
        onPrimary = colors.card,
        background = colors.paper,
        onBackground = colors.ink,
        surface = colors.card,
        onSurface = colors.ink,
        surfaceVariant = colors.chipBg,
        onSurfaceVariant = colors.ink2,
        outline = colors.line,
        error = colors.alert,
        onError = colors.card,
    )
}

@Composable
fun NishinomiyaLibraryTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val appColors = if (darkTheme) DarkAppColors else LightAppColors
    CompositionLocalProvider(LocalAppColors provides appColors) {
        MaterialTheme(
            colorScheme = colorSchemeFrom(appColors, darkTheme),
            typography = AppTypography,
            content = content,
        )
    }
}
