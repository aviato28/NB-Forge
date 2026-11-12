package com.namanbansal.forge.core.share

/** FileProvider caches its path roots per authority in a static map; Robolectric gives every test a new cache dir. */
object FileProviderReset {
    fun clear() {
        try {
            val f = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
            f.isAccessible = true
            (f.get(null) as MutableMap<*, *>).clear()
        } catch (_: Throwable) { /* newer FileProvider versions: nothing cached to clear */ }
    }
}
