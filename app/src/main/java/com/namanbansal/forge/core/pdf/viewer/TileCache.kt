package com.namanbansal.forge.core.pdf.viewer

/**
 * Least-recently-used map bounded by bytes. [trim] never evicts keys in `pinned` (the tiles on screen), so the budget can
 * be exceeded only by what is actually visible. Evicted values go to [onEvict] (the bitmap pool).
 */
class TileCache<V : Any>(private val budgetBytes: Long, private val sizeOf: (V) -> Long, private val onEvict: (V) -> Unit = {}) {
    private val map = LinkedHashMap<TileKey, V>(64, 0.75f, true)
    private var bytes = 0L

    @Synchronized fun get(key: TileKey): V? = map[key]
    @Synchronized fun contains(key: TileKey) = map.containsKey(key)
    @Synchronized fun totalBytes(): Long = bytes
    @Synchronized fun size(): Int = map.size

    @Synchronized fun put(key: TileKey, value: V, pinned: Set<TileKey> = emptySet()) {
        map.put(key, value)?.let { bytes -= sizeOf(it); if (it !== value) onEvict(it) }
        bytes += sizeOf(value)
        trim(pinned)
    }

    @Synchronized fun trim(pinned: Set<TileKey>) {
        if (bytes <= budgetBytes) return
        val it = map.entries.iterator()
        while (it.hasNext() && bytes > budgetBytes) {
            val e = it.next()
            if (e.key in pinned) continue
            bytes -= sizeOf(e.value)
            it.remove()
            onEvict(e.value)
        }
    }

    @Synchronized fun clear() {
        map.values.forEach(onEvict)
        map.clear(); bytes = 0
    }
}
