package com.namanbansal.forge.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.R

val Inter = FontFamily(
    Font(R.font.inter_regular, FontWeight.Normal),
    Font(R.font.inter_medium, FontWeight.Medium),
    Font(R.font.inter_semibold, FontWeight.SemiBold),
    Font(R.font.inter_bold, FontWeight.Bold),
    Font(R.font.inter_extrabold, FontWeight.ExtraBold),
)

/** Forge type scale: Display 32/800, Title 22/700, Body 15/400, Label 13/600. */
object ForgeType {
    val Display = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 32.sp, lineHeight = 38.sp, letterSpacing = (-0.02).em)
    val Title = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 22.sp, lineHeight = 28.sp)
    val Section = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp)
    val Body = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 15.sp, lineHeight = 22.sp)
    val BodyMedium = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp)
    val Item = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 14.sp, lineHeight = 18.sp)
    val Label = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 16.sp)
    val Small = TextStyle(fontFamily = Inter, fontWeight = FontWeight.Normal, fontSize = 12.sp, lineHeight = 16.sp)
    val Tiny = TextStyle(fontFamily = Inter, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 14.sp)
    /** Home brand header: bold FORGE wordmark. */
    val Brand = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp, lineHeight = 24.sp, letterSpacing = 2.sp)
    val Wordmark = TextStyle(fontFamily = Inter, fontWeight = FontWeight.ExtraBold, fontSize = 15.sp, letterSpacing = 0.08.em)
}

val ForgeTypography = Typography(
    displayLarge = ForgeType.Display,
    displayMedium = ForgeType.Display,
    displaySmall = ForgeType.Display,
    headlineLarge = ForgeType.Title,
    headlineMedium = ForgeType.Title,
    headlineSmall = ForgeType.Title,
    titleLarge = ForgeType.Title,
    titleMedium = ForgeType.Section,
    titleSmall = ForgeType.Item,
    bodyLarge = ForgeType.Body,
    bodyMedium = ForgeType.Body,
    bodySmall = ForgeType.Small,
    labelLarge = ForgeType.Label,
    labelMedium = ForgeType.Label,
    labelSmall = ForgeType.Tiny,
)
