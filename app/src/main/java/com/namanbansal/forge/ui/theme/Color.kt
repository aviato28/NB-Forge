package com.namanbansal.forge.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

val Ember = Color(0xFFFF6A1A)
val EmberPressed = Color(0xFFE0560C)
val Charcoal = Color(0xFF18181B)

/** Forge design tokens (docs/brand/mockups.html). */
@Immutable
data class ForgeColors(
    val accent: Color,
    val accentPressed: Color,
    val tint: Color,
    val bg: Color,
    val surface: Color,
    val surface2: Color,
    val border: Color,
    val text: Color,
    val text2: Color,
    val isDark: Boolean,
)

val LightForgeColors = ForgeColors(
    accent = Ember,
    accentPressed = EmberPressed,
    tint = Color(0xFFFFF1E8),
    bg = Color(0xFFFAFAFA),
    surface = Color(0xFFFFFFFF),
    surface2 = Color(0xFFF2F2F3),
    border = Color(0xFFE6E6E8),
    text = Color(0xFF111113),
    text2 = Color(0xFF6B6B73),
    isDark = false,
)

val DarkForgeColors = ForgeColors(
    accent = Ember,
    accentPressed = EmberPressed,
    tint = Color(0xFF3A2216),
    bg = Color(0xFF0E0E10),
    surface = Color(0xFF18181B),
    surface2 = Color(0xFF222226),
    border = Color(0xFF2C2C31),
    text = Color(0xFFF4F4F5),
    text2 = Color(0xFF9A9AA3),
    isDark = true,
)

val LocalForgeColors = staticCompositionLocalOf { LightForgeColors }
