package com.namanbansal.forge.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.data.ThemeMode

object ForgeShapes {
    val Card = RoundedCornerShape(20.dp)
    val Button = RoundedCornerShape(14.dp)
    val Chip = RoundedCornerShape(14.dp)
    val Search = RoundedCornerShape(18.dp)
    val IconBox = RoundedCornerShape(14.dp)
    val IconBoxSmall = RoundedCornerShape(12.dp)
}

object ForgeTheme {
    val colors: ForgeColors @Composable get() = LocalForgeColors.current
}

@Composable
fun ForgeTheme(mode: ThemeMode = ThemeMode.System, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    val c = if (dark) DarkForgeColors else LightForgeColors
    val scheme = if (dark) {
        darkColorScheme(
            primary = c.accent, onPrimary = Color.White, primaryContainer = c.tint, onPrimaryContainer = c.accent,
            secondary = c.accent, onSecondary = Color.White,
            background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surface2, onSurfaceVariant = c.text2, surfaceContainer = c.surface,
            surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface2,
            surfaceContainerLowest = c.bg, outline = c.border, outlineVariant = c.border,
        )
    } else {
        lightColorScheme(
            primary = c.accent, onPrimary = Color.White, primaryContainer = c.tint, onPrimaryContainer = c.accent,
            secondary = c.accent, onSecondary = Color.White,
            background = c.bg, onBackground = c.text, surface = c.surface, onSurface = c.text,
            surfaceVariant = c.surface2, onSurfaceVariant = c.text2, surfaceContainer = c.surface,
            surfaceContainerLow = c.surface, surfaceContainerHigh = c.surface2, surfaceContainerHighest = c.surface2,
            surfaceContainerLowest = c.bg, outline = c.border, outlineVariant = c.border,
        )
    }
    CompositionLocalProvider(LocalForgeColors provides c) {
        MaterialTheme(
            colorScheme = scheme,
            typography = ForgeTypography,
            shapes = Shapes(
                small = ForgeShapes.Chip,
                medium = ForgeShapes.Button,
                large = ForgeShapes.Card,
                extraLarge = RoundedCornerShape(28.dp),
            ),
            content = content,
        )
    }
}
