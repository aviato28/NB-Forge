package com.namanbansal.forge.ui.screens

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.namanbansal.forge.ui.components.ForgeCard
import com.namanbansal.forge.ui.components.ListRow
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeType

private data class Lib(val name: String, val owner: String, val licence: String, val asset: String)

private val libs = listOf(
    Lib("Inter typeface", "The Inter Project Authors", "SIL Open Font License 1.1", "licenses/Inter-OFL.txt"),
    Lib("Dancing Script typeface (signatures)", "The Dancing Script Project Authors", "SIL Open Font License 1.1", "licenses/DancingScript-OFL.txt"),
    Lib("Caveat typeface (signatures)", "The Caveat Project Authors", "SIL Open Font License 1.1", "licenses/Caveat-OFL.txt"),
    Lib("Sacramento typeface (signatures)", "Brian J. Bonislawsky DBA Astigmatic", "SIL Open Font License 1.1", "licenses/Sacramento-OFL.txt"),
    Lib("Noto Sans, Noto Serif, Noto Sans Mono typefaces (editing PDF text)", "The Noto Project Authors", "SIL Open Font License 1.1", "licenses/Noto-OFL.txt"),
    Lib("Jetpack Compose & AndroidX", "The Android Open Source Project", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    Lib("Kotlin & kotlinx.serialization / coroutines", "JetBrains s.r.o.", "Apache License 2.0", "licenses/Apache-2.0.txt"),
    Lib("OkHttp & Okio", "Square, Inc.", "Apache License 2.0", "licenses/Apache-2.0.txt"),
)

@Composable
fun LicensesScreen(onBack: () -> Unit) {
    val c = ForgeTheme.colors
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars)) {
        TopBar("Open-source licences", onBack = onBack)
        LazyColumn(contentPadding = PaddingValues(16.dp)) {
            items(libs) { lib ->
                var open by remember { mutableStateOf(false) }
                val text = remember(lib.asset) {
                    runCatching { context.assets.open(lib.asset).bufferedReader().use { it.readText() } }.getOrDefault("")
                }
                ForgeCard(Modifier.fillMaxWidth().padding(bottom = 10.dp).animateContentSize()) {
                    ListRow(lib.name, subtitle = "${lib.owner} · ${lib.licence}", showDivider = open, onClick = { open = !open })
                    if (open) {
                        Text(
                            text, style = ForgeType.Small.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp, lineHeight = 15.sp),
                            color = c.text2, modifier = Modifier.padding(12.dp),
                        )
                    }
                }
            }
        }
    }
}
