package com.namanbansal.forge.ui.toolkit

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.namanbansal.forge.ui.theme.Charcoal
import com.namanbansal.forge.ui.theme.Ember
import com.namanbansal.forge.ui.theme.ForgeShapes
import com.namanbansal.forge.ui.theme.ForgeType
import kotlinx.coroutines.launch

/** App-wide snackbar host state; provided by ForgeRoot. Falls back to a private state in previews/tests. */
val LocalForgeSnackbar = compositionLocalOf { SnackbarHostState() }

/** Themed snackbar host: charcoal pill with ember action, same in light and dark. */
@Composable
fun ForgeSnackbarHost(state: SnackbarHostState, modifier: Modifier = Modifier) {
    SnackbarHost(state, modifier.padding(horizontal = 16.dp, vertical = 8.dp)) { data ->
        Snackbar(
            snackbarData = data,
            shape = ForgeShapes.Chip,
            containerColor = Charcoal,
            contentColor = Color.White,
            actionColor = Ember,
        )
    }
}

/** Returns a function that shows a short message in the app snackbar. */
@Composable
fun rememberSnack(): (String) -> Unit {
    val host = LocalForgeSnackbar.current
    val scope = rememberCoroutineScope()
    return remember(host, scope) {
        { message: String ->
            scope.launch {
                host.currentSnackbarData?.dismiss()
                host.showSnackbar(message, duration = SnackbarDuration.Short)
            }
            Unit
        }
    }
}
