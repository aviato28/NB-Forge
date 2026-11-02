package com.namanbansal.forge

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.namanbansal.forge.data.Settings
import com.namanbansal.forge.ui.ForgeRoot
import com.namanbansal.forge.ui.theme.ForgeTheme
import com.namanbansal.forge.ui.theme.ForgeTheme as T

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        val app = ForgeApp.from(this)
        if (savedInstanceState == null) app.updates.autoCheckIfDue()
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
                ForgeRoot(app = app, settings = settings)
            }
        }
    }

    override fun onResume() {
        super.onResume()
        ForgeApp.from(this).updates.onResume()
    }
}

