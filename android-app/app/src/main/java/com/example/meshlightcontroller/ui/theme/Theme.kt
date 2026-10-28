package com.example.meshlightcontroller.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private val LightColors = lightColorScheme(
    primary = Forest, onPrimary = Color.White, primaryContainer = Color(0xFFDDEBE0), onPrimaryContainer = Color(0xFF183D2C),
    secondary = Color(0xFF656A59), onSecondary = Color.White, secondaryContainer = Color(0xFFE7E9DA), onSecondaryContainer = Ink,
    tertiary = Clay, tertiaryContainer = Color(0xFFF3E1D7), onTertiaryContainer = Color(0xFF563323),
    background = Ivory, onBackground = Ink, surface = Ivory, onSurface = Ink,
    surfaceVariant = Color(0xFFE8EAE2), onSurfaceVariant = Color(0xFF465147),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFF0F0E9),
    surfaceContainer = Color(0xFFECEDE5), surfaceContainerHigh = Color(0xFFE6E8DF),
    surfaceContainerHighest = Color(0xFFE0E3D8), outline = Color(0xFF7B847B), outlineVariant = Color(0xFFD7DBD0)
)
private val DarkColors = darkColorScheme(
    primary = ForestLight, onPrimary = Color(0xFF123A29), primaryContainer = Color(0xFF284C3B), onPrimaryContainer = Color(0xFFDDEBE0),
    secondary = Color(0xFFC5CBB7), onSecondary = Color(0xFF2D3529),
    secondaryContainer = Color(0xFF424B3D), onSecondaryContainer = Color(0xFFE5EBE1),
    tertiary = Color(0xFFEDB69B), tertiaryContainer = Color(0xFF563B2C), onTertiaryContainer = Color(0xFFFFDAC5),
    background = Night, onBackground = Color(0xFFE5EBE1), surface = Night, onSurface = Color(0xFFE5EBE1),
    surfaceVariant = Color(0xFF3F4940), onSurfaceVariant = Color(0xFFD0D8C9),
    surfaceContainerLowest = Color(0xFF101611), surfaceContainerLow = Color(0xFF1C241E),
    surfaceContainer = Color(0xFF222C24), surfaceContainerHigh = Color(0xFF2C362E),
    surfaceContainerHighest = Color(0xFF374139), outline = Color(0xFF8B978B), outlineVariant = Color(0xFF414C42)
)

@Composable
fun MeshLightControllerTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit
) {
    val colors = if (dynamicColor && Build.VERSION.SDK_INT >= 31) {
        if (darkTheme) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
    } else if (darkTheme) DarkColors else LightColors
    MaterialTheme(colors, typography = Typography, shapes = Shapes(
        small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(20.dp),
        large = RoundedCornerShape(24.dp), extraLarge = RoundedCornerShape(28.dp)), content = content)
}
