package com.namanbansal.forge.core.util

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.text.DateFormat
import java.util.Date

/** Rates relative to [base] (which is always present as 1.0) published on [date] and fetched at [fetchedAt] (epoch ms). */
data class CurrencyRates(val base: String, val date: String, val rates: Map<String, Double>, val fetchedAt: Long) {
    fun convert(amount: Double, from: String, to: String): Double? {
        val f = rates[from] ?: return null
        val t = rates[to] ?: return null
        if (f == 0.0) return null
        return amount / f * t
    }
    val codes: List<String> get() = rates.keys.sorted()
}

class CurrencyException(message: String) : Exception(message)

object CurrencyParse {
    private val json = Json { ignoreUnknownKeys = true }

    /** Parses a frankfurter.app response: `{"amount":1.0,"base":"EUR","date":"2026-11-10","rates":{"USD":1.08,...}}`. */
    fun parse(body: String, fetchedAt: Long): CurrencyRates {
        try {
            val obj = json.parseToJsonElement(body).jsonObject
            val base = obj["base"]?.jsonPrimitive?.content ?: throw CurrencyException("The rates response had no base currency")
            val date = obj["date"]?.jsonPrimitive?.content.orEmpty()
            val rates = (obj["rates"] as? JsonObject ?: throw CurrencyException("The rates response had no rates"))
                .mapNotNull { (k, v) -> v.jsonPrimitive.doubleOrNull?.takeIf { it > 0 }?.let { k to it } }.toMap().toMutableMap()
            if (rates.isEmpty()) throw CurrencyException("The rates response was empty")
            rates[base] = 1.0
            return CurrencyRates(base, date, rates, fetchedAt)
        } catch (e: CurrencyException) { throw e } catch (e: Exception) { throw CurrencyException("The rates response could not be read") }
    }

    fun toCache(r: CurrencyRates): String = Json.encodeToString(
        kotlinx.serialization.json.JsonObject.serializer(),
        JsonObject(mapOf(
            "base" to kotlinx.serialization.json.JsonPrimitive(r.base), "date" to kotlinx.serialization.json.JsonPrimitive(r.date),
            "fetchedAt" to kotlinx.serialization.json.JsonPrimitive(r.fetchedAt),
            "rates" to JsonObject(r.rates.mapValues { kotlinx.serialization.json.JsonPrimitive(it.value) }),
        )),
    )

    fun fromCache(text: String): CurrencyRates? = try {
        val fetched = json.parseToJsonElement(text).jsonObject["fetchedAt"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        parse(text, fetched)
    } catch (_: Exception) { null }
}

/** Result of [CurrencyRepository.load]. [offline] = the network failed and saved rates are shown instead. */
data class RatesResult(val rates: CurrencyRates, val offline: Boolean, val error: String? = null)

/**
 * Rates from https://api.frankfurter.app (ECB reference rates, no key). Rates are cached in [cacheFile] with a timestamp;
 * a fresh cache is reused for [maxAgeMs], and if the network fails the cache is returned flagged as offline.
 */
class CurrencyRepository(
    private val cacheFile: File,
    private val fetch: suspend (url: String) -> String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val maxAgeMs: Long = 6 * 3600_000L,
) {
    suspend fun load(forceRefresh: Boolean = false): RatesResult {
        val cached = readCache()
        val now = clock()
        if (!forceRefresh && cached != null && now - cached.fetchedAt in 0 until maxAgeMs) return RatesResult(cached, offline = false)
        return try {
            val fresh = CurrencyParse.parse(fetch(URL), now)
            writeCache(fresh)
            RatesResult(fresh, offline = false)
        } catch (e: Exception) {
            if (cached != null) RatesResult(cached, offline = true, error = e.message ?: "No connection")
            else throw CurrencyException("No internet connection and no saved rates yet. Connect once to download them.")
        }
    }

    private fun readCache(): CurrencyRates? = try { if (cacheFile.exists()) CurrencyParse.fromCache(cacheFile.readText()) else null } catch (_: Exception) { null }

    private fun writeCache(r: CurrencyRates) {
        try {
            cacheFile.parentFile?.mkdirs()
            val tmp = File(cacheFile.path + ".tmp")
            tmp.writeText(CurrencyParse.toCache(r))
            if (!tmp.renameTo(cacheFile)) { cacheFile.writeText(tmp.readText()); tmp.delete() }
        } catch (_: Exception) { /* cache is a convenience; ignore disk problems */ }
    }

    companion object {
        const val URL = "https://api.frankfurter.app/latest"
        fun asOf(rates: CurrencyRates, now: Long): String {
            val ageH = ((now - rates.fetchedAt) / 3_600_000L).coerceAtLeast(0)
            val age = when { ageH < 1 -> "just now"; ageH < 48 -> "${ageH} h ago"; else -> "${ageH / 24} days ago" }
            val published = rates.date.ifBlank { DateFormat.getDateInstance().format(Date(rates.fetchedAt)) }
            return "ECB rates of $published · updated $age"
        }
    }
}

object CurrencyInfo {
    fun name(code: String): String = try { java.util.Currency.getInstance(code).displayName } catch (_: Exception) { code }
    fun symbol(code: String): String = try { java.util.Currency.getInstance(code).getSymbol(java.util.Locale.getDefault()) } catch (_: Exception) { code }
    fun matches(code: String, query: String): Boolean {
        val q = query.trim()
        return q.isEmpty() || code.contains(q, ignoreCase = true) || name(code).contains(q, ignoreCase = true)
    }
}
