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

    /** "Brian Ward(Author)", "Ana Lima (Org.)": the role is the catalogue's, not the name's. */
    private val role = Regex("""\s*\((author|editor|eds?\.?|translator|illustrator|autor|autora|org\.?|organizador|tradutor)\)""", RegexOption.IGNORE_CASE)

    fun author(raw: String?): String? {
        val cleaned = raw?.replace(role, "")?.trim().orEmpty()
        return when {
            cleaned.isEmpty() -> null
            cleaned.contains("://") || cleaned.startsWith("www.", ignoreCase = true) -> null
            cleaned.lowercase() in placeholderAuthors -> null
            // Several authors are often stored as "Camille Fournier;Ian Nowland", and
            // catalogues write each one "Reis, Joe": read out, that is "Joe Reis".
            else -> cleaned.split(';').map { it.trim() }.filter { it.isNotEmpty() }.joinToString(", ") { name ->
                val parts = name.split(',').map { it.trim() }
                val inverted = parts.size == 2 && parts.all { part -> part.isNotEmpty() && part.split(' ').size <= 3 } &&
                    !parts[1].contains('&') && !parts[1].contains(" and ")
                if (inverted) "${parts[1]} ${parts[0]}" else name
            }
        }
    }

    /**
     * The file's declared language if it is a language tag. Some producers write junk here,
     * or an encrypted file's string comes out garbled ("HÓ"), and a bad tag would pick the
     * wrong voice.
     */
    fun language(raw: String?): String? =
        raw?.trim()?.replace('_', '-')?.takeIf { it.matches(Regex("[A-Za-z]{2,3}(-[A-Za-z0-9]{1,8})*")) }

    private val apparatus = Regex(
        "(copyright( page)?|copyrights|(table of )?contents|brief contents|contents in detail|detailed contents|" +
            "(general |subject )?index|colophon|about the colophon|sum[áa]rio|[íi]ndice( remissivo)?|ficha catalogr[áa]fica)",
        RegexOption.IGNORE_CASE,
    )

    /**
     * A section nobody reads straight through — the copyright page, the contents, the index,
     * the colophon — going by its bookmark title.
     */
    fun isApparatus(sectionTitle: String): Boolean =
        apparatus.matches(sectionTitle.trim().trimEnd('.', ':').replace(Regex("\\s+"), " "))
}
