package com.lounge.tv

/** Matches team names between sources. The football-data.co.uk spelling is the canonical one ("Man United"). */
class TeamNames(canonical: Collection<String>) {
    private val names: List<String> = canonical.toSortedSet().toList()
    private val norms: LinkedHashMap<String, String> = LinkedHashMap<String, String>().also { m -> names.forEach { m[it] = norm(it) } }
    private val cache = HashMap<String, String?>()

    fun canon(name: String): String? {
        if (cache.containsKey(name)) return cache[name]
        val r = match(name)
        cache[name] = r
        return r
    }

    private fun match(name: String): String? {
        if (norms.containsKey(name)) return name
        val target = norm(name)
        for ((c, cn) in norms) if (cn == target) return c
        val ttoks = target.split(" ").filter { it.isNotEmpty() }.toSet()
        val subset = norms.filter { (_, cn) -> ttoks.containsAll(cn.split(" ").filter { it.isNotEmpty() }.toSet()) }.keys
        if (subset.isNotEmpty()) return subset.maxByOrNull { norms.getValue(it).split(" ").filter { s -> s.isNotEmpty() }.size }
        // closest spelling (same idea as Python difflib.get_close_matches, cutoff 0.8)
        var best: String? = null
        var bestScore = 0.0
        for (cn in norms.values.toList()) {
            val sc = ratio(cn, target)
            if (sc >= 0.8 && (best == null || sc > bestScore || (sc == bestScore && cn > best))) { best = cn; bestScore = sc }
        }
        if (best != null) for ((c, cn) in norms) if (cn == best) return c
        return null
    }

    companion object {
        private val REPL = listOf(
            "manchester" to "man", "nottingham" to "nott'm", "wolverhampton wanderers" to "wolves",
            "west bromwich albion" to "west brom", "queens park rangers" to "qpr", "tottenham hotspur" to "tottenham",
            "brighton & hove albion" to "brighton", "brighton and hove albion" to "brighton",
            "sheffield wednesday" to "sheffield weds", "leeds united" to "leeds"
        )
        private val FC = Regex("\\b(a?fc)\\b")
        private val JUNK = Regex("[^a-z0-9' ]+")
        private val SPACES = Regex("\\s+")

        fun norm(name: String): String {
            var n = name.lowercase().trim()
            n = FC.replace(n, " ")
            for ((a, b) in REPL) n = n.replace(a, b)
            n = JUNK.replace(n, " ")
            return SPACES.replace(n, " ").trim()
        }

        /** Ratcliff/Obershelp similarity, the same measure as Python's difflib SequenceMatcher.ratio(). */
        fun ratio(a: String, b: String): Double {
            val t = a.length + b.length
            if (t == 0) return 1.0
            return 2.0 * matching(a, 0, a.length, b, 0, b.length) / t
        }

        private fun matching(a: String, alo: Int, ahi: Int, b: String, blo: Int, bhi: Int): Int {
            if (alo >= ahi || blo >= bhi) return 0
            var bi = alo; var bj = blo; var bk = 0
            val w = bhi - blo
            var prev = IntArray(w + 1)
            for (i in alo until ahi) {
                val cur = IntArray(w + 1)
                for (j in 0 until w) {
                    if (a[i] == b[blo + j]) {
                        val k = prev[j] + 1
                        cur[j + 1] = k
                        if (k > bk) { bk = k; bi = i - k + 1; bj = blo + j - k + 1 }
                    }
                }
                prev = cur
            }
            if (bk == 0) return 0
            return bk + matching(a, alo, bi, b, blo, bj) + matching(a, bi + bk, ahi, b, bj + bk, bhi)
        }
    }
}
