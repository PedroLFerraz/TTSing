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
        data class Failure(val message: String) : Result
    }

    suspend fun get(url: String): Result = withContext(Dispatchers.IO) {
        var current = url
        repeat(MAX_REDIRECTS) {
            when (val step = fetchOnce(current)) {
                is Step.Body -> return@withContext Result.Success(step.text, current)
                is Step.Redirect -> current = step.location
                is Step.Error -> return@withContext Result.Failure(step.message)
            }
        }
        Result.Failure("Too many redirects")
    }

    private sealed interface Step {
        data class Body(val text: String) : Step
        data class Redirect(val location: String) : Step
        data class Error(val message: String) : Step
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
            if (status !in 200..299) return Step.Error("HTTP $status")

            val stream = decoded(connection)
            val charset = charsetOf(connection.contentType)
            Step.Body(stream.use { it.readBoundedText(charset) })
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

    /** Charset from the Content-Type header; UTF-8 is the right guess when absent. */
    private fun charsetOf(contentType: String?): String {
        val declared = contentType
            ?.split(';')
            ?.map { it.trim() }
            ?.firstOrNull { it.startsWith("charset=", ignoreCase = true) }
            ?.substringAfter('=')
            ?.trim('"', ' ')
        return declared?.takeIf { runCatching { charset(it) }.isSuccess } ?: "UTF-8"
    }

    private fun charset(name: String) = java.nio.charset.Charset.forName(name)

    private fun InputStream.readBoundedText(charsetName: String): String {
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
        return String(out.toByteArray(), charset(charsetName))
    }
}
