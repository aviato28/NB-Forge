package com.namanbansal.forge.core.util

import kotlin.random.Random

class RandomError(message: String) : Exception(message)

/** Number, coin, dice and list picking with an injectable [random] (seeded in tests). */
class Randomiser(private val random: Random = Random.Default) {
    fun numbers(min: Long, max: Long, count: Int = 1, unique: Boolean = false): List<Long> {
        if (min > max) throw RandomError("The lowest number is higher than the highest")
        if (count !in 1..1000) throw RandomError("Choose between 1 and 1000 numbers")
        val range = max - min + 1
        if (unique && count > range) throw RandomError("Only $range different numbers fit between $min and $max")
        if (!unique) return List(count) { random.nextLong(min, max + 1) }
        if (range <= 100_000) return (min..max).shuffled(random).take(count)
        val seen = LinkedHashSet<Long>()
        while (seen.size < count) seen += random.nextLong(min, max + 1)
        return seen.toList()
    }

    fun coin(): Boolean = random.nextBoolean() // true = heads

    fun dice(sides: Int, count: Int = 1): List<Int> {
        if (sides !in 2..1000) throw RandomError("A die needs between 2 and 1000 sides")
        if (count !in 1..100) throw RandomError("Roll between 1 and 100 dice")
        return List(count) { random.nextInt(1, sides + 1) }
    }

    fun pick(items: List<String>): String {
        val clean = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) throw RandomError("Add at least one item to pick from")
        return clean[random.nextInt(clean.size)]
    }

    fun shuffle(items: List<String>): List<String> {
        val clean = items.map { it.trim() }.filter { it.isNotEmpty() }
        if (clean.isEmpty()) throw RandomError("Add at least one item to shuffle")
        return clean.shuffled(random)
    }

    companion object { fun parseItems(text: String): List<String> = text.lines().map { it.trim() }.filter { it.isNotEmpty() } }
}
