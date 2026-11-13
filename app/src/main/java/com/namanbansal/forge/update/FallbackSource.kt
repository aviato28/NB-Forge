package com.namanbansal.forge.update

/** Tries [primary] first; if it fails or has nothing, asks [fallback]. */
class FallbackSource(
    private val primary: UpdateSource,
    private val fallback: UpdateSource,
) : UpdateSource {
    override suspend fun latest(channel: Channel): UpdateInfo? {
        val first = try {
            primary.latest(channel)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
        return first ?: fallback.latest(channel)
    }
}
