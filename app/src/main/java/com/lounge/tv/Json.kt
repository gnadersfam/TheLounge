package com.lounge.tv

/**
 * A tiny JSON reader/writer with no Android parts, so it can be tested on a computer.
 * Parsed values are: Map<String, Any?>, List<Any?>, String, Double, Boolean or null.
 */
object Json {
    class JsonError(msg: String) : Exception(msg)

    fun parse(text: String): Any? {
        val p = Parser(text)
        p.ws()
        val v = p.value()
        p.ws()
        if (p.i < text.length) throw JsonError("Extra text after the end at ${p.i}")
        return v
    }

    fun parseOrNull(text: String?): Any? = try {
        if (text.isNullOrBlank()) null else parse(text)
    } catch (e: JsonError) {
        null
    } catch (e: StackOverflowError) {
        null
    }

    private class Parser(val s: String) {
        var i = 0

        fun ws() {
            while (i < s.length && (s[i] == ' ' || s[i] == '\n' || s[i] == '\r' || s[i] == '\t' || s[i] == '﻿')) i++
        }

        fun value(): Any? {
            if (i >= s.length) throw JsonError("Unexpected end")
            return when (val c = s[i]) {
                '{' -> obj()
                '[' -> arr()
                '"' -> str()
                't' -> lit("true", true)
                'f' -> lit("false", false)
                'n' -> lit("null", null)
                else -> if (c == '-' || c.isDigit()) num() else throw JsonError("Unexpected '$c' at $i")
            }
        }

        fun lit(word: String, v: Any?): Any? {
            if (!s.startsWith(word, i)) throw JsonError("Bad value at $i")
            i += word.length
            return v
        }

        fun num(): Double {
            val st = i
            if (s[i] == '-') i++
            while (i < s.length && (s[i].isDigit() || s[i] == '.' || s[i] == 'e' || s[i] == 'E' || s[i] == '+' || s[i] == '-')) i++
            return s.substring(st, i).toDoubleOrNull() ?: throw JsonError("Bad number at $st")
        }

        fun str(): String {
            i++ // opening quote
            val sb = StringBuilder()
            while (true) {
                if (i >= s.length) throw JsonError("Unterminated string")
                val c = s[i++]
                when (c) {
                    '"' -> return sb.toString()
                    '\\' -> {
                        if (i >= s.length) throw JsonError("Bad escape")
                        when (val e = s[i++]) {
                            'n' -> sb.append('\n')
                            't' -> sb.append('\t')
                            'r' -> sb.append('\r')
                            'b' -> sb.append('\b')
                            'f' -> sb.append('\u000C')
                            'u' -> {
                                if (i + 4 > s.length) throw JsonError("Bad unicode escape")
                                sb.append(s.substring(i, i + 4).toInt(16).toChar())
                                i += 4
                            }
                            else -> sb.append(e)
                        }
                    }
                    else -> sb.append(c)
                }
            }
        }

        fun arr(): List<Any?> {
            i++
            val out = ArrayList<Any?>()
            ws()
            if (i < s.length && s[i] == ']') { i++; return out }
            while (true) {
                ws()
                out.add(value())
                ws()
                if (i >= s.length) throw JsonError("Unterminated array")
                if (s[i] == ',') { i++; continue }
                if (s[i] == ']') { i++; return out }
                throw JsonError("Expected , or ] at $i")
            }
        }

        fun obj(): Map<String, Any?> {
            i++
            val out = LinkedHashMap<String, Any?>()
            ws()
            if (i < s.length && s[i] == '}') { i++; return out }
            while (true) {
                ws()
                if (i >= s.length || s[i] != '"') throw JsonError("Expected key at $i")
                val k = str()
                ws()
                if (i >= s.length || s[i] != ':') throw JsonError("Expected : at $i")
                i++
                ws()
                out[k] = value()
                ws()
                if (i >= s.length) throw JsonError("Unterminated object")
                if (s[i] == ',') { i++; continue }
                if (s[i] == '}') { i++; return out }
                throw JsonError("Expected , or } at $i")
            }
        }
    }

    // ---------------------------------------------------------------- writing
    fun write(v: Any?): String {
        val sb = StringBuilder()
        put(sb, v)
        return sb.toString()
    }

    private fun put(sb: StringBuilder, v: Any?) {
        when (v) {
            null -> sb.append("null")
            is String -> quote(sb, v)
            is Boolean -> sb.append(v.toString())
            is Int, is Long -> sb.append(v.toString())
            is Double -> if (v.isNaN() || v.isInfinite()) sb.append("null") else if (v == Math.floor(v) && Math.abs(v) < 1e15) sb.append(v.toLong()) else sb.append(v.toString())
            is Float -> put(sb, v.toDouble())
            is Map<*, *> -> {
                sb.append('{')
                var first = true
                for ((k, x) in v) {
                    if (!first) sb.append(',')
                    first = false
                    quote(sb, k.toString())
                    sb.append(':')
                    put(sb, x)
                }
                sb.append('}')
            }
            is Iterable<*> -> {
                sb.append('[')
                var first = true
                for (x in v) {
                    if (!first) sb.append(',')
                    first = false
                    put(sb, x)
                }
                sb.append(']')
            }
            is Array<*> -> put(sb, v.toList())
            else -> quote(sb, v.toString())
        }
    }

    private fun quote(sb: StringBuilder, s: String) {
        sb.append('"')
        for (c in s) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append(String.format("\\u%04x", c.code)) else sb.append(c)
            }
        }
        sb.append('"')
    }
}

// ---------------------------------------------------------------- friendly accessors
@Suppress("UNCHECKED_CAST")
fun Any?.obj(): Map<String, Any?> = (this as? Map<String, Any?>) ?: emptyMap()
fun Any?.arr(): List<Any?> = (this as? List<Any?>) ?: emptyList()
fun Any?.str(): String = when (this) {
    null -> ""
    is String -> this
    is Double -> if (this == Math.floor(this) && Math.abs(this) < 1e15) this.toLong().toString() else this.toString()
    else -> this.toString()
}
fun Any?.num(default: Double = 0.0): Double = when (this) {
    is Double -> this
    is Number -> this.toDouble()
    is String -> this.toDoubleOrNull() ?: default
    else -> default
}
fun Any?.int(default: Int = 0): Int = when (this) {
    is Number -> this.toInt()
    is String -> this.toDoubleOrNull()?.toInt() ?: default
    else -> default
}
fun Any?.long(default: Long = 0L): Long = when (this) {
    is Number -> this.toLong()
    is String -> this.toDoubleOrNull()?.toLong() ?: default
    else -> default
}
fun Any?.bool(): Boolean = when (this) {
    is Boolean -> this
    is String -> this == "1" || this.equals("true", true)
    is Number -> this.toInt() != 0
    else -> false
}
