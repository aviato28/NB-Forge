package com.namanbansal.forge

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.lifecycle.lifecycleScope
import com.namanbansal.forge.core.FileResolver
import com.namanbansal.forge.core.IncomingIntents
import com.namanbansal.forge.core.PickedFile
import kotlinx.coroutines.launch
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namanbansal.forge.data.Settings
import com.namanbansal.forge.ui.ForgeRoot
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeTheme as T

class MainActivity : ComponentActivity() {
    /** Files another app sent us; shown in the "What do you want to do?" sheet until handled. */
    private val incoming = mutableStateOf<List<PickedFile>>(emptyList())
    /** True when the files came from "Open with" (ACTION_VIEW): a single PDF then opens straight in the viewer. */
    private val incomingIsView = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val app = ForgeApp.from(this)
        if (savedInstanceState == null) {
            app.updates.autoCheckIfDue()
            handleIncoming(intent)
        }
        setContent {
            val settings by app.settings.settings.collectAsStateWithLifecycle(initialValue = Settings())
            ForgeTheme(mode = settings.theme) {
                val dark = T.colors.isDark
                LaunchedEffect(dark) {
                    enableEdgeToEdge(
                        statusBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                        navigationBarStyle = if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
                        else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                    )
                }
                ForgeRoot(app = app, settings = settings, incoming = incoming.value, incomingIsView = incomingIsView.value, onIncomingHandled = { incoming.value = emptyList() })
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncoming(intent)
    }

    private fun handleIncoming(intent: Intent?) {
        val uris = IncomingIntents.uris(intent)
        if (uris.isEmpty()) return
        lifecycleScope.launch {
            // Read name/size right away while the sender's temporary read grant is certainly alive.
            val files = IncomingIntents.accept(uris.map { FileResolver.resolve(this@MainActivity, it) })
            if (files.isNotEmpty()) {
                incomingIsView.value = intent?.action == Intent.ACTION_VIEW
                incoming.value = files
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ForgeApp.from(this).updates.onResume()
    }
}

