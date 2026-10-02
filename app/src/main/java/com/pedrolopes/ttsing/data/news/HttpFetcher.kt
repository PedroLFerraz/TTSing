package com.pedrolopes.ttsing.data.news

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream

/**
 * Minimal HTTP GET for feeds and article pages.
 *
 * Uses [HttpURLConnection] rather than pulling in a networking library: this makes two kinds
 * of plain GET request and needs no connection pooling, interceptors or caching beyond what
 * the article table already provides.
 */
object HttpFetcher {

    /**
     * Many publishers reject the default Java user agent outright, so identify as a normal
     * browser; without this a good share of sites answer 403 and the article never loads.
     */
    private const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0 Mobile Safari/537.36 TTSing/1.0"

    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    /** Guards against a mis-typed URL pointing at something enormous. */
    private const val MAX_BYTES = 5 * 1024 * 1024

    /** Cross-protocol redirects (http -> https) are not followed automatically. */
    private const val MAX_REDIRECTS = 5

    sealed interface Result {
        data class Success(val body: String, val finalUrl: String) : Result
        /** [status] is the HTTP status when the server answered at all, null for network errors. */
        data class Failure(val message: String, val status: Int? = null) : Result {
            /**
             * The server refused for good (not found, forbidden, bot-blocked) rather than
             * hiccupping. Worth remembering so the same page isn't retried on every refresh.
             */
            val isPermanent: Boolean get() = status != null && status in 400..499 && status != 408 && status != 429
        }
    }

    suspend fun get(url: String): Result = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_REDIRECTS) {
            when (val step = fetchOnce(current)) {
                is Step.Body -> return@withContext Result.Success(step.text, current)
                is Step.Redirect -> current = step.location
                is Step.Error -> return@withContext Result.Failure(step.message, step.status)
            }
        }
        Result.Failure("Too many redirects")
    }

    /** Raw bytes, for article images. Null on any failure — a missing photo is not an error. */
    suspend fun getBytes(url: String, maxBytes: Int = MAX_BYTES): ByteArray? = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_REDIRECTS) {
            val connection = runCatching { URL(current).openConnection() as HttpURLConnection }
                .getOrNull() ?: return@withContext null
            try {
                connection.apply {
                    requestMethod = "GET"
                    connectTimeout = CONNECT_TIMEOUT_MS
                    readTimeout = READ_TIMEOUT_MS
                    instanceFollowRedirects = false
                    setRequestProperty("User-Agent", USER_AGENT)
                    setRequestProperty("Accept", "image/*,*/*;q=0.8")
                }
                val status = connection.responseCode
                if (status in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return@withContext null
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (status !in 200..299) return@withContext null
                return@withContext connection.inputStream.use { it.readBounded(maxBytes) }
            } catch (_: Exception) {
                return@withContext null
            } finally {
                runCatching { connection.disconnect() }
            }
        }
        null
    }

    private fun InputStream.readBounded(maxBytes: Int): ByteArray? {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            out.write(buffer, 0, read)
            // Refuse rather than truncate: half an image decodes to nothing useful.
            if (out.size() > maxBytes) return null
        }
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    private sealed interface Step {
        data class Body(val text: String) : Step
        data class Redirect(val location: String) : Step
        data class Error(val message: String, val status: Int? = null) : Step
    }

    private fun fetchOnce(url: String): Step {
        val connection = try {
            (URL(url).openConnection() as HttpURLConnection)
        } catch (e: Exception) {
            return Step.Error(e.message ?: "Bad URL")
        }

        return try {
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept-Encoding", "gzip")
                setRequestProperty(
                    "Accept",
                    "text/html,application/xhtml+xml,application/xml,application/rss+xml;q=0.9,*/*;q=0.8",
                )
            }

            val status = connection.responseCode
            if (status in 300..399) {
                val location = connection.getHeaderField("Location")
                    ?: return Step.Error("Redirect without a target")
                // Location may be relative.
                return Step.Redirect(URL(URL(url), location).toString())
            }
            if (status !in 200..299) return Step.Error("HTTP $status", status)

            val bytes = decoded(connection).use { it.readBoundedBytes() }
            Step.Body(decodeText(bytes, connection.contentType))
        } catch (e: Exception) {
            Step.Error(e.message ?: e::class.java.simpleName)
        } finally {
            runCatching { connection.disconnect() }
        }
    }

    private fun decoded(connection: HttpURLConnection): InputStream {
        val raw = connection.inputStream
        return if (connection.contentEncoding?.contains("gzip", ignoreCase = true) == true) {
            GZIPInputStream(raw)
        } else {
            raw
        }
    }

    /**
     * Text of a response body in the charset it was sent in: the Content-Type header's if it
     * names one, else whatever the document declares about itself, else UTF-8.
     *
     * The document's own declaration matters because plenty of feeds are served with a bare
     * `text/xml` header and say `<?xml … encoding="ISO-8859-1"?>` inside — common on older
     * Brazilian news sites. Decoding those as UTF-8 turned every "Política" into "Pol�tica".
     */
    internal fun decodeText(bytes: ByteArray, contentType: String?): String {
        val name = charsetOf(contentType) ?: sniffCharset(bytes) ?: "UTF-8"
        return String(bytes, charset(name))
    }

    /** Charset from the Content-Type header, or null when it names none (or a bogus one). */
    private fun charsetOf(contentType: String?): String? =
        contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', '\'', ' ')
            ?.takeIf { isSupported(it) }

    /**
     * The charset a document declares in its first bytes: an XML prolog's `encoding="…"`, or an
     * HTML `<meta charset>` / `http-equiv` content type. Read as Latin-1, which maps every byte
     * to a character, so the ASCII declaration is found whatever the real encoding is.
     */
    private fun sniffCharset(bytes: ByteArray): String? {
        val head = String(bytes, 0, minOf(bytes.size, SNIFF_BYTES), Charsets.ISO_8859_1)
        val declared = XML_ENCODING.find(head)?.groupValues?.get(1)
            ?: META_CHARSET.find(head)?.groupValues?.get(1)
        return declared?.takeIf { isSupported(it) }
    }

    private const val SNIFF_BYTES = 2048
    private val XML_ENCODING = Regex("""<\?xml[^>]*\bencoding\s*=\s*["']([A-Za-z0-9._-]+)["']""")
    private val META_CHARSET = Regex("""<meta[^>]+charset\s*=\s*["']?([A-Za-z0-9._-]+)""", RegexOption.IGNORE_CASE)

    private fun isSupported(name: String): Boolean = runCatching { charset(name) }.isSuccess

    private fun charset(name: String) = java.nio.charset.Charset.forName(name)

    private fun InputStream.readBoundedBytes(): ByteArray {
        val buffer = ByteArray(16 * 1024)
        val out = java.io.ByteArrayOutputStream()
        var total = 0
        while (true) {
            val read = read(buffer)
            if (read < 0) break
            total += read
            if (total > MAX_BYTES) break
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }
}
