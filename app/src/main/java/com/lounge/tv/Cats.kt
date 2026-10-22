package com.lounge.tv

/**
 * Category order and hidden list for the Live TV guide. Pure logic, no Android parts.
 * The saved order is what the user arranged; categories the provider adds later go to the bottom.
 */
object Cats {
    /** All categories in the user's order: saved ones first (if they still exist), then new ones in default order. */
    fun fullOrder(defaultOrder: List<String>, saved: List<String>): List<String> {
        val exists = defaultOrder.toHashSet()
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (c in saved) if (c in exists && seen.add(c)) out.add(c)
        for (c in defaultOrder) if (seen.add(c)) out.add(c)
        return out
    }

    fun visible(full: List<String>, hidden: Set<String>): List<String> = full.filter { it !in hidden }

    /** Moves the item at [from] to position [to] (both inside the list); returns a new list. */
    fun move(list: List<String>, from: Int, to: Int): List<String> {
        if (from !in list.indices || to !in list.indices || from == to) return list
        val out = ArrayList(list)
        val x = out.removeAt(from)
        out.add(to, x)
        return out
    }
}
