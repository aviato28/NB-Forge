package com.namanbansal.forge.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

internal val UpdateJson = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

/** GET [url]; returns the body, or null on 404. Maps failures to [UpdateException]. */
internal suspend fun OkHttpClient.getText(url: String, headers: Map<String, String> = emptyMap()): String? =
    withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }.build()
        try {
            newCall(req).execute().use { resp ->
                when {
                    resp.code == 404 -> null
                    resp.code == 403 || resp.code == 429 ->
                        throw UpdateException(UpdateException.Kind.RateLimited, "Update server is busy (rate limited). Try again later.")
                    !resp.isSuccessful -> throw UpdateException(UpdateException.Kind.Http, "Update check failed (HTTP ${resp.code}).")
                    else -> resp.body.string()
                }
            }
        } catch (e: IOException) {
            throw UpdateException(UpdateException.Kind.Offline, "Couldn't reach the update server. Check your connection.", e)
        }
    }
