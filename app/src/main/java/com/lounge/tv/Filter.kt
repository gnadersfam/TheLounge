package com.lounge.tv

/** One live channel. [tag] is the country tag (UK, US, ...), [epg] the guide id. */
data class Chan(val id: String, val name: String, val cat: String, val tag: String, val epg: String, val icon: String = "")

/**
 * English-only detection and "tidy list" rules, ported from the PC app's Live TV tab (livetv.py):
 * hide placeholder / Amazon channels, hide UK regional copies (keep one Scotland version), UK first.
 */
object Filter {
    private val IC = RegexOption.IGNORE_CASE

    val ENGLISH_TAGS = mapOf(
        "UK" to "UK", "GB" to "UK", "US" to "US", "USA" to "US", "CA" to "CA", "CAN" to "CA",
        "AU" to "AU", "AUS" to "AU", "IE" to "IE", "IRL" to "IE", "NZ" to "NZ", "EN" to "EN"
    )
    private val ENGLISH_WORDS = listOf(
        Regex("""united kingdom|britain|british|\bengland\b|scotland|\bwales\b""") to "UK",
        Regex("""united states|\bamerica""") to "US",
        Regex("""\bcanad""") to "CA",
        Regex("""\baustral""") to "AU",
        Regex("""ireland|\birish\b""") to "IE",
        Regex("""new zealand""") to "NZ",
        Regex("""\benglish\b""") to "EN"
    )
    private val FOREIGN_TAGS = ("FR DE ES IT PT NL BE TR AR PL RO RU GR SE NO DK FI IN PK AL RS HR BG HU CZ SK IL " +
        "IR BR MX LAT LATAM EX YU").split(" ").toSet()
    private val FOREIGN_WORDS = Regex(
        """french|german|spanish|italian|portug|dutch|turk|arab|polish|\broman(?:ia|ian)\b|russian|greek|swed|norweg|""" +
            """danish|finn(?:ish|land)|hindi|urdu|punjab|tamil|bangla|albania|serb|croat|bulgar|hungar|czech|slovak|hebrew|""" +
            """persian|farsi|brazil|latin|korea|japan|chinese|vietnam|thai\b|filipin|africa|balkan|exyu""", IC
    )
    val COUNTRY_ORDER = mapOf("UK" to 0, "IE" to 1, "US" to 2, "CA" to 3, "AU" to 4, "NZ" to 5, "EN" to 6)

    private val TOKEN_RE = Regex("""\b[A-Za-z]{2,5}\b""")
    private val NAME_START_RE = Regex("""^\s*[\[\(\{|]*\s*([A-Za-z]{2,6})\s*[\]\)\}|:\-]""")
    private val NAME_END_RE = Regex("""[\[\(\{]([A-Za-z]{2,6})[\]\)\}]\s*\z""")

    fun tagFrom(text: String?): String {
        if (text.isNullOrEmpty()) return ""
        var eng = ""
        var foreign = false
        for (m in TOKEN_RE.findAll(text)) {
            val tok = m.value
            if (tok != tok.uppercase()) continue
            if (tok in ENGLISH_TAGS && eng.isEmpty()) {
                eng = ENGLISH_TAGS[tok]!!
            } else if (tok in FOREIGN_TAGS) {
                foreign = true
            }
        }
        val low = text.lowercase()
        if (eng.isEmpty()) {
            for ((re, code) in ENGLISH_WORDS) {
                if (re.containsMatchIn(low)) {
                    eng = code
                    break
                }
            }
        }
        if (eng.isNotEmpty()) return eng
        if (foreign || FOREIGN_WORDS.containsMatchIn(low)) return "other"
        return ""
    }

    fun nameTag(name: String): String {
        val parts = ArrayList<String>()
        NAME_START_RE.find(name)?.let { parts.add(it.groupValues[1]) }
        NAME_END_RE.find(name)?.let { parts.add(it.groupValues[1]) }
        return tagFrom(parts.joinToString(" "))
    }

    fun channelTag(name: String, category: String): String {
        val nt = nameTag(name)
        val ct = tagFrom(category)
        if (nt == "UK" && ct == "other") return "other" // 'GB|' is also Gabon: the category settles it
        return if (nt.isNotEmpty()) nt else ct
    }

    fun isEnglish(tag: String): Boolean = tag in COUNTRY_ORDER

    // ------------------------------------------------------------ tidy list
    private val JUNK_RE = Regex(
        """\bno\s+(?:live\s+)?events?\b|no\s+event\s+stream|not\s+streaming|\bno\s+stream|""" +
            """event\s+(?:offline|not\s+started|ended)|amazon|prime\s*video|primevideo""", IC
    )
    private val QUALITY_RE = Regex("""\b(?:uhd|fhd|hd|sd|4k|hevc|h\.?265|raw|50\s*fps|60\s*fps|1080p?|720p?)\b""", IC)
    private val SCOT_RE = Regex("""\bscotland\b|\bscottish\b|\bstv\b""", IC)
    private val REGION_RE = Regex(
        """\b(?:london|england|scotland|scottish|wales|welsh|cymru|northern\s+ireland|n\.?\s?ireland|\bni\b|""" +
            """north\s+west|north\s+east|south\s+west|south\s+east|north|south|east|west|midlands|east\s+midlands|""" +
            """west\s+midlands|yorkshire|anglia|border|central|meridian|tyne\s+tees|granada|westcountry|west\s+country|""" +
            """channel\s+islands|stv|utv|hd\s+london)\b""", IC
    )
    private val NEUTRAL_REGION_RE = Regex("""\b(?:london|england)\b""", IC)
    private val DIVIDER_RE = Regex("""^\W*[#=*_~\-]{3,}|[#=*_~]{4,}\W*\z""")
    private val CAT_PRIORITY = listOf(
        Regex("""general|entertain|terrestrial""", IC), Regex("""news""", IC), Regex("""sport""", IC),
        Regex("""movie|cinema|film""", IC), Regex("""kids|child|cartoon""", IC), Regex("""document|music|lifestyle""", IC)
    )
    private val LATE_CAT_RE = Regex("""ppv|replay|event|24/7|raw|vod|premium""", IC)
    private val SPORT_RE = Regex("""sport""", IC)
    private val UK_LEAD_RE = Regex("""^\s*[\[\(\{|]*\s*(?:UK|GB)\s*[\]\)\}|:\-]+""", IC)
    private val UK_TAIL_RE = Regex("""[\[\(\{]\s*(?:UK|GB)\s*[\]\)\}]""", IC)
    private val NON_ALNUM_RE = Regex("""[^a-z0-9+]+""")

    private fun isJunk(name: String, cat: String): Boolean =
        JUNK_RE.containsMatchIn(name) || JUNK_RE.containsMatchIn(cat) || DIVIDER_RE.containsMatchIn(name)

    fun catRank(category: String): Int {
        if (LATE_CAT_RE.containsMatchIn(category) && !SPORT_RE.containsMatchIn(category)) return CAT_PRIORITY.size + 1
        for ((i, re) in CAT_PRIORITY.withIndex()) if (re.containsMatchIn(category)) return i
        return CAT_PRIORITY.size
    }

    private fun quality(name: String): Int {
        val n = name.uppercase()
        return when {
            "4K" in n || "UHD" in n -> 3
            "FHD" in n -> 3
            "HD" in n || "HEVC" in n -> 2
            "RAW" in n || " SD" in n -> 0
            else -> 1
        }
    }

    private fun baseName(name: String): String {
        var n = UK_LEAD_RE.replace(name, " ")
        n = UK_TAIL_RE.replace(n, " ")
        n = QUALITY_RE.replace(n, " ")
        n = REGION_RE.replace(n, " ")
        return NON_ALNUM_RE.replace(n.lowercase(), " ").trim()
    }

    private fun dropIds(rows: List<Chan>): Set<String> {
        val drop = HashSet<String>()
        val groups = HashMap<String, MutableList<Chan>>()
        for (r in rows) {
            if (isJunk(r.name, r.cat)) {
                drop.add(r.id)
                continue
            }
            if (r.tag == "UK") {
                val b = baseName(r.name)
                if (b.isNotEmpty()) groups.getOrPut(b) { ArrayList() }.add(r)
            }
        }
        val best = compareBy<Chan>({ quality(it.name) }, { -it.name.length })
        for (members in groups.values) {
            if (members.size < 2) continue
            val regional = members.filter { REGION_RE.containsMatchIn(it.name) }
            if (regional.isEmpty()) continue
            val national = members.filter { it !in regional }
            val scot = regional.filter { SCOT_RE.containsMatchIn(it.name) }
            val others = regional.filter { it !in scot }
            val keep = ArrayList<Chan>()
            if (scot.isNotEmpty()) keep.add(scot.maxWithOrNull(best)!!)
            if (national.isEmpty() && others.isNotEmpty()) {
                val pref = others.filter { NEUTRAL_REGION_RE.containsMatchIn(it.name) }.ifEmpty { others }
                keep.add(pref.maxWithOrNull(best)!!)
            }
            for (r in regional) if (r !in keep) drop.add(r.id)
        }
        return drop
    }

    /** Hide junk and regional copies, then sort: UK first, then everyday categories, then A-Z. */
    fun tidy(rows: List<Chan>): List<Chan> {
        val drop = dropIds(rows)
        return rows.filter { it.id !in drop }.sortedWith(
            compareBy<Chan>(
                { COUNTRY_ORDER[it.tag] ?: 9 },
                { catRank(it.cat) },
                { !(it.name.trim().firstOrNull()?.isLetter() ?: false) },
                { it.name.lowercase() }
            )
        )
    }

    /**
     * Default category order for the guide: UK first, then Ireland, US...; inside a country the everyday
     * categories first (General, News, Sport...), then A-Z.
     */
    fun categoryOrder(rows: List<Chan>): List<String> {
        val country = HashMap<String, Int>()
        for (r in rows) {
            if (r.cat.isEmpty()) continue
            val c = COUNTRY_ORDER[r.tag] ?: 9
            val prev = country[r.cat]
            if (prev == null || c < prev) country[r.cat] = c
        }
        return country.keys.sortedWith(compareBy<String>({ country[it] ?: 9 }, { catRank(it) }, { it.lowercase() }))
    }
}
