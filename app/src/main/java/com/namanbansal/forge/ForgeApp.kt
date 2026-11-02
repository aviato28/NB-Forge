package com.namanbansal.forge

import android.app.Application
import android.content.Context
import com.namanbansal.forge.data.SettingsRepository
import com.namanbansal.forge.update.UpdateManager
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/** App-wide singletons (no DI framework by design). */
class ForgeApp : Application() {
    val http: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", "Forge/${BuildConfig.VERSION_NAME} (Android)").build())
            }
            .build()
    }
    val settings: SettingsRepository by lazy { SettingsRepository(this) }
    val updates: UpdateManager by lazy { UpdateManager(this, settings, http) }

    companion object {
        fun from(context: Context): ForgeApp = context.applicationContext as ForgeApp
    }
}
