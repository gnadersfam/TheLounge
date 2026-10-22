package com.lounge.tv

/** Language and genre rules for movies and shows, ported from the PC app's Movies & Shows tab (vod.py). */
object Vod {
    private val LANG_CODE_RE = Regex("""\[([A-Za-z0-9\-]{1,8})\]\s*$""")
    private val NOT_NATIVE_RE = Regex(
        """\bsub(?:s|bed|titles?)?\b|\bensub\b|\beng\s*sub|dubbed|french|italian|turkish|korean|german|""" +
            """spanish|hindi|arabic|latin|japanese|chinese|bollywood|nollywood|portugu""",
        RegexOption.IGNORE_CASE
    )
    private val FOREIGN_SUFFIX = setOf(
        "JP", "TR", "KR", "FR", "IT", "MX", "ES", "DE", "CN", "TH", "IN", "PK", "BR", "RU", "PL", "NL", "SE",
        "NO", "DK", "FI", "GR", "AR", "IR", "HK", "TW", "PH", "ID", "VN", "SO", "SA", "EG", "UA", "CZ", "HU"
    )
    private val SUFFIX_RE = Regex("""\(([A-Za-z]{2,3})\)\s*$""")
    private val LEADING_TAG_RE = Regex("""^\s*[\[\(\{|]*\s*[A-Za-z]{2,8}\s*[\]\)\}|:\-]+\s*""")
    private val TRAILING_BRACKET_RE = Regex("""\s*\[[^\]]*\]\s*$""")

    /**
     * Country tag for a movie/show ('UK', 'US', ..., 'EN' for English with no country), 'other', or '' if unknown.
     * The language code in the category's [..] is trusted over anything in the title.
     */
    fun tag(name: String, cat: String): String {
        val m = LANG_CODE_RE.find(cat) ?: return Filter.channelTag(name, cat)
        val code = m.groupValues[1].uppercase()
        val base = Filter.ENGLISH_TAGS[code] ?: return "other"
        val body = LANG_CODE_RE.replace(cat, "")
        if (NOT_NATIVE_RE.containsMatchIn(body)) return "other"
        val suffix = SUFFIX_RE.find(name)?.groupValues?.get(1)?.uppercase() ?: ""
        if (suffix in FOREIGN_SUFFIX) return "other"
        if (base == "EN") {
            val refined = Filter.ENGLISH_TAGS[suffix]
            if (refined != null && refined != "EN") return refined
            val r2 = Filter.tagFrom(body)
            if (r2 in Filter.COUNTRY_ORDER) return r2
        }
        return base
    }

    /** Title without a leading country tag ('EN - Heat (1995)' -> 'Heat (1995)'). */
    fun displayName(name: String): String {
        var n = name
        if (Filter.nameTag(n).isNotEmpty()) n = LEADING_TAG_RE.replaceFirst(n, "")
        return n.trim().ifEmpty { name.trim() }
    }

    private fun titleCase(s: String): String {
        val sb = StringBuilder()
        var startWord = true
        for (ch in s) {
            if (ch.isLetter()) {
                sb.append(if (startWord) ch.uppercaseChar() else ch.lowercaseChar())
                startWord = false
            } else {
                sb.append(ch)
                startWord = true
            }
        }
        return sb.toString()
    }

    /** Genre = the category the provider files a title under ("EN | ACTION [EN]" -> "Action"). */
    fun genreOf(cat: String): String {
        var c = TRAILING_BRACKET_RE.replace(cat, "")
        if ('|' in c) c = c.split('|').last() else if (" - " in c) c = c.split(" - ").last()
        c = titleCase(c.trim())
        return c.ifEmpty { "Other" }
    }
}
