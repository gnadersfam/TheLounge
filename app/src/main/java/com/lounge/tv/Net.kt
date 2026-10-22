package com.lounge.tv

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/** Small web helper with no Android parts. Every failure is an IOException with a readable message. */
object Net {
    const val UA = "Mozilla/5.0 (The Lounge)"

    class HttpError(val code: Int, msg: String) : IOException(msg)

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

    fun form(fields: Map<String, String>): String =
        fields.entries.joinToString("&") { enc(it.key) + "=" + enc(it.value) }

    private fun open(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Int,
        method: String = "GET",
        body: ByteArray? = null
    ): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 20000
        c.readTimeout = timeoutMs
        c.requestMethod = method
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", UA)
        c.setRequestProperty("Accept-Encoding", "gzip")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        if (body != null) {
            c.doOutput = true
            c.outputStream.use { it.write(body) }
        }
        return c
    }

    private fun readAll(c: HttpURLConnection, stream: InputStream): ByteArray {
        val raw = if ((c.contentEncoding ?: "").equals("gzip", true)) GZIPInputStream(stream) else stream
        val out = ByteArrayOutputStream()
        raw.use {
            val buf = ByteArray(16 * 1024)
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        }
        return out.toByteArray()
    }

    /** Fetches bytes; throws HttpError for a non-2xx answer. */
    fun bytes(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30000,
        method: String = "GET",
        body: ByteArray? = null
    ): ByteArray {
        val c = try {
            open(url, headers, timeoutMs, method, body)
        } catch (e: IOException) {
            throw IOException(e.message ?: "Connection problem")
        }
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val msg = try {
                    c.errorStream?.let { String(readAll(c, it), Charsets.UTF_8).take(300) } ?: ""
                } catch (e: Exception) {
                    ""
                }
                throw HttpError(code, "The server answered HTTP $code" + if (msg.isNotBlank()) ": $msg" else "")
            }
            return readAll(c, c.inputStream)
        } finally {
            c.disconnect()
        }
    }

    fun text(
        url: String,
        headers: Map<String, String> = emptyMap(),
        timeoutMs: Int = 30000
    ): String = String(bytes(url, headers, timeoutMs), Charsets.UTF_8)

    fun json(url: String, headers: Map<String, String> = emptyMap(), timeoutMs: Int = 30000): Any? {
        val t = text(url, headers, timeoutMs)
        return try {
            Json.parse(t)
        } catch (e: Json.JsonError) {
            throw IOException("The server sent something unexpected")
        }
    }

    fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val h = HashMap(headers)
        h["Content-Type"] = "application/x-www-form-urlencoded"
        return String(bytes(url, h, 30000, "POST", form(fields).toByteArray()), Charsets.UTF_8)
    }
}
