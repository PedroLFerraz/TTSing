package com.pedrolopes.ttsing.data.pdf

/**
 * PDF title/author fields are filled in by whatever program made the file, and are often
 * junk: "Microsoft Word - draft3.docx", an author of "User", a converter's own URL. The library
 * shows these in large type, so anything that reads as tool noise is dropped in favour of the
 * file name.
 */
object PdfMetadata {

    private val officePrefix = Regex("^Microsoft\\s+(Word|PowerPoint|Excel)\\s*-\\s*", RegexOption.IGNORE_CASE)
    private val fileExtension = Regex("\\.(docx?|pptx?|xlsx?|odt|rtf|pdf|indd|tex|dvi|ps|txt|html?)$", RegexOption.IGNORE_CASE)
    private val placeholderAuthors = setOf("user", "admin", "administrator", "owner", "unknown", "author", "anonymous")

    fun title(raw: String?, fallback: String): String {
        val cleaned = raw?.trim()
            ?.replace(officePrefix, "")
            ?.replace(fileExtension, "")
            ?.trim()
            .orEmpty()
        return when {
            cleaned.isEmpty() -> fallback
            cleaned.contains("://") -> fallback
            // "untitled", "Document1", "tmp1234" — a title no one chose.
            cleaned.matches(Regex("(?i)(untitled|document|doc|tmp|temp)[ _-]?\\d*")) -> fallback
            else -> cleaned
        }
    }

    fun author(raw: String?): String? {
        val cleaned = raw?.trim().orEmpty()
        return when {
            cleaned.isEmpty() -> null
            cleaned.contains("://") || cleaned.startsWith("www.", ignoreCase = true) -> null
            cleaned.lowercase() in placeholderAuthors -> null
            // Several authors are often stored as "Camille Fournier;Ian Nowland".
            else -> cleaned.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ")
        }
    }
}
