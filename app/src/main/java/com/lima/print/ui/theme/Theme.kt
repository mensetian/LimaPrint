package com.lima.print.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme = darkColorScheme(
    primary = LimaVioletLight,
    onPrimary = LimaSurfaceDark,
    primaryContainer = LimaVioletDark,
    onPrimaryContainer = Color.White,
    secondary = LimaPurple,
    tertiary = LimaTeal,
    background = LimaSurfaceDark,
    onBackground = LimaOnSurfaceDark,
    surface = LimaSurfaceDark,
    onSurface = LimaOnSurfaceDark,
    surfaceVariant = LimaSurfaceDarkHigh,
    onSurfaceVariant = LimaOnSurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary = LimaViolet,
    onPrimary = Color.White,
    primaryContainer = LimaVioletContainer,
    onPrimaryContainer = LimaVioletDark,
    secondary = LimaPurple,
    tertiary = LimaTeal,
    background = LimaSurfaceLight,
    onBackground = LimaOnSurfaceLight,
    surface = LimaSurfaceLight,
    onSurface = LimaOnSurfaceLight
)

/**
 * Tema de la app.
 *
 * Sin color dinámico a propósito: en Android 12+ el sistema teñiría la app con
 * el fondo de pantalla del celular y LimaPrint dejaría de verse como LIMA, que
 * es justamente lo que se busca acá.
 */
@Composable
fun LimaPrintTheme(
    darkTheme: Boolean = androidx.compose.foundation.isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme,
        typography = Typography,
        content = content
    )
}
