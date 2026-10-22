package com.lounge.tv

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PushbackInputStream
import java.util.zip.GZIPInputStream

class EpgProg(val start: Long, val stop: Long, val title: String, val desc: String = "")

/** The TV guide, reduced to the channels and hours we show (the provider's full file can be 100 MB). */
class EpgData(val built: Long, val progs: Map<String, List<EpgProg>>) {
    fun forChannel(id: String): List<EpgProg> = progs[id] ?: emptyList()

    fun at(id: String, t: Long): EpgProg? {
        for (p in forChannel(id)) if (p.start <= t && t < p.stop) return p
        return null
    }

    /** (on now or null, next few programmes). */
    fun nowNext(id: String, now: Long, nNext: Int = 3): Pair<EpgProg?, List<EpgProg>> {
        var cur: EpgProg? = null
        val nxt = ArrayList<EpgProg>()
        for (p in forChannel(id)) {
            if (p.start <= now && now < p.stop) cur = p
            else if (p.start > now) {
                nxt.add(p)
                if (nxt.size >= nNext) break
            }
        }
        return Pair(cur, nxt)
    }

    fun toJson(): Any = mapOf(
        "built" to built,
        "progs" to progs.mapValues { e -> e.value.map { listOf(it.start, it.stop, it.title, it.desc) } }
    )

    companion object {
        val EMPTY = EpgData(0L, emptyMap())

        fun fromJson(o: Any?): EpgData {
            val m = o.obj()
            val raw = m["progs"].obj()
            if (raw.isEmpty()) return EMPTY
            val out = HashMap<String, List<EpgProg>>(raw.size)
            for ((k, v) in raw) {
                out[k] = v.arr().map {
                    val a = it.arr()
                    EpgProg(a[0].long(), a[1].long(), a[2].str(), a.getOrNull(3).str())
                }
            }
            return EpgData(m["built"].long(), out)
        }
    }
}

object Epg {
    const val BACK = 3 * 3600L
    const val AHEAD = 26 * 3600L
    const val TITLE_MAX = 70
    const val DESC_MAX = 260

    // ---------------------------------------------------------------- times like "20261022140000 +0100"
    private fun daysFromCivil(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = (if (y >= 0) y else y - 399) / 400
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146097L + doe - 719468L
    }

    /** Unix seconds, or null if the text is not a guide time. A missing zone means UTC. */
    fun parseTime(s: String?): Long? {
        val t = (s ?: "").trim()
        if (t.length < 12) return null
        for (i in 0 until 12) if (!t[i].isDigit()) return null
        val y = t.substring(0, 4).toInt()
        val mo = t.substring(4, 6).toInt()
        val d = t.substring(6, 8).toInt()
        val h = t.substring(8, 10).toInt()
        val mi = t.substring(10, 12).toInt()
        var se = 0
        var idx = 12
        if (t.length >= 14 && t[12].isDigit() && t[13].isDigit()) {
            se = t.substring(12, 14).toInt()
            idx = 14
        }
        if (mo !in 1..12 || d !in 1..31 || h > 24 || mi > 59 || se > 60) return null
        var off = 0L
        val rest = t.substring(idx).trim()
        if (rest.length >= 5 && (rest[0] == '+' || rest[0] == '-') && rest.substring(1, 5).all { it.isDigit() }) {
            val oh = rest.substring(1, 3).toInt()
            val om = rest.substring(3, 5).toInt()
            off = (oh * 3600 + om * 60).toLong()
            if (rest[0] == '-') off = -off
        }
        return daysFromCivil(y, mo, d) * 86400L + h * 3600L + mi * 60L + se - off
    }

    // ---------------------------------------------------------------- reading the big file
    private val ATTR_RE = Regex("""([A-Za-z_:][\w:.-]*)\s*=\s*(?:"([^"]*)"|'([^']*)')""")
    private val TITLE_RE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.DOT_MATCHES_ALL))
    private val DESC_RE = Regex("""<desc[^>]*>(.*?)</desc>""", setOf(RegexOption.DOT_MATCHES_ALL))

    fun unescape(s0: String): String {
        var s = s0
        if (s.contains("<![CDATA[")) s = s.replace("<![CDATA[", "").replace("]]>", "")
        if (!s.contains('&')) return s.trim()
        val sb = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '&') {
                val semi = s.indexOf(';', i)
                if (semi in (i + 2)..(i + 10)) {
                    val ent = s.substring(i + 1, semi)
                    val rep: String? = when {
                        ent == "amp" -> "&"
                        ent == "lt" -> "<"
                        ent == "gt" -> ">"
                        ent == "quot" -> "\""
                        ent == "apos" -> "'"
                        ent.startsWith("#x") || ent.startsWith("#X") -> ent.substring(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                        ent.startsWith("#") -> ent.substring(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                        else -> null
                    }
                    if (rep != null) {
                        sb.append(rep)
                        i = semi + 1
                        continue
                    }
                }
            }
            sb.append(c)
            i++
        }
        return sb.toString().trim()
    }

    class GuideEmpty : Exception("The TV guide is empty.")

    /**
     * Reads an XMLTV guide from a stream (gzip is detected by itself) and keeps only programmes of [wanted]
     * channels inside the time window. [progress] gets the number of programmes seen so far.
     */
    fun read(raw: InputStream, wanted: Set<String>, now: Long, progress: (Int) -> Unit = {}): EpgData {
        val pb = PushbackInputStream(raw, 2)
        val b0 = pb.read()
        val b1 = pb.read()
        if (b1 >= 0) pb.unread(b1)
        if (b0 >= 0) pb.unread(b0)
        val src: InputStream = if (b0 == 0x1f && b1 == 0x8b) GZIPInputStream(pb, 1 shl 16) else pb
        val reader = BufferedReader(InputStreamReader(src, Charsets.UTF_8), 1 shl 16)

        val lo = now - BACK
        val hi = now + AHEAD
        val out = HashMap<String, ArrayList<EpgProg>>()
        var total = 0
        val buf = StringBuilder()
        var pos = 0
        val chunk = CharArray(1 shl 16)
        var eof = false

        // Reads more text; the part already used is dropped first so the buffer stays small.
        fun more() {
            if (pos > 0) {
                buf.delete(0, pos)
                pos = 0
            }
            val n = reader.read(chunk)
            if (n < 0) eof = true else buf.append(chunk, 0, n)
        }

        while (true) {
            val st = buf.indexOf("<programme", pos)
            if (st < 0) {
                if (eof) break
                // keep only a short tail in case the tag is split across two chunks
                pos = Math.max(pos, buf.length - 16)
                more()
                continue
            }
            pos = st
            val gt = buf.indexOf(">", pos)
            if (gt < 0) {
                if (eof) break
                more()
                continue
            }
            val selfClosing = gt > pos && buf[gt - 1] == '/'
            var end = gt + 1
            if (!selfClosing) {
                val close = buf.indexOf("</programme>", gt)
                if (close < 0) {
                    if (eof) break
                    more()
                    continue
                }
                end = close + "</programme>".length
            }
            val head = buf.substring(pos, gt + 1)
            val block = if (selfClosing) head else buf.substring(pos, end)
            pos = end
            total++
            if (total % 5000 == 0) progress(total)

            var ch = ""
            var start: Long? = null
            var stop: Long? = null
            for (m in ATTR_RE.findAll(head)) {
                val v = m.groupValues[2].ifEmpty { m.groupValues[3] }
                when (m.groupValues[1]) {
                    "channel" -> ch = v.trim().lowercase()
                    "start" -> start = parseTime(v)
                    "stop" -> stop = parseTime(v)
                }
            }
            if (ch.isEmpty() || ch !in wanted) continue
            val s = start ?: continue
            val e = stop ?: continue
            if (e < lo || s > hi) continue
            val title = TITLE_RE.find(block)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
            if (title.isEmpty()) continue
            val desc = DESC_RE.find(block)?.groupValues?.get(1)?.let { unescape(it) } ?: ""
            out.getOrPut(ch) { ArrayList() }.add(EpgProg(s, e, title.take(TITLE_MAX), desc.take(DESC_MAX)))
        }
        if (total == 0) throw GuideEmpty()
        val result = HashMap<String, List<EpgProg>>(out.size)
        for ((k, v) in out) result[k] = v.sortedBy { it.start }
        return EpgData(now, result)
    }
}
