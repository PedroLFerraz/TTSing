package com.pedrolopes.ttsing.data.epub

import java.io.ByteArrayOutputStream

internal object EpubPaths {

    /** Resolves [href] (relative, possibly percent-encoded, possibly with fragment) against [baseDir]. */
    fun resolve(baseDir: String, href: String): String {
        val decoded = percentDecode(href.substringBefore('#'))
        if (decoded.isEmpty()) return baseDir
        val combined = if (baseDir.isEmpty()) decoded else "$baseDir/$decoded"
        val parts = ArrayDeque<String>()
        for (segment in combined.split('/')) {
            when (segment) {
                "", "." -> {}
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    fun parentDir(path: String): String = path.substringBeforeLast('/', "")

    fun percentDecode(value: String): String {
        if (!value.contains('%')) return value
        val bytes = ByteArrayOutputStream()
        var i = 0
        while (i < value.length) {
            val c = value[i]
            if (c == '%' && i + 2 < value.length) {
                val hex = value.substring(i + 1, i + 3).toIntOrNull(16)
                if (hex != null) {
                    bytes.write(hex)
                    i += 3
                    continue
                }
            }
            bytes.write(c.toString().toByteArray(Charsets.UTF_8))
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }
}
