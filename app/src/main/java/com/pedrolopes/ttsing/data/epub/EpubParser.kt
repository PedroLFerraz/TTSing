package com.pedrolopes.ttsing.data.epub

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.parser.Parser
import java.io.Closeable
import java.io.File
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * Parses an EPUB (2 or 3) from a local file. EPUBs are ZIP containers; all XML/XHTML
 * inside is parsed with Jsoup so this class is plain-JVM testable.
 */
class EpubParser(file: File) : Closeable {

    private val zip = ZipFile(file)

    private val entriesByPath: Map<String, ZipEntry> = zip.entries().asSequence()
        .filterNot { it.isDirectory }
        .associateBy { it.name.removePrefix("/") }

    private val entriesLowercase: Map<String, ZipEntry> =
        entriesByPath.entries.associate { it.key.lowercase() to it.value }

    fun entryExists(path: String): Boolean = findEntry(path) != null

    fun readEntry(path: String): ByteArray? {
        val entry = findEntry(path) ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun parseBook(): EpubBook {
        val container = parseXml("META-INF/container.xml")
            ?: throw EpubFormatException("META-INF/container.xml not found")
        val opfPath = container.select("rootfile").firstOrNull()?.attr("full-path")
            ?.let { EpubPaths.percentDecode(it) }
            ?.takeIf { it.isNotEmpty() }
            ?: throw EpubFormatException("No rootfile declared in container.xml")
        val opf = parseXml(opfPath) ?: throw EpubFormatException("OPF document not found: $opfPath")
        val opfDir = EpubPaths.parentDir(opfPath)

        val title = opf.getElementsByTag("dc:title").firstOrNull()?.text()?.trim().orEmpty()
        val author = opf.getElementsByTag("dc:creator").firstOrNull()?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }
        val language = opf.getElementsByTag("dc:language").firstOrNull()?.text()?.trim()
            ?.takeIf { it.isNotEmpty() }

        val manifest = mutableMapOf<String, ManifestItem>()
        for (item in opf.select("manifest > item")) {
            val id = item.attr("id")
            if (id.isEmpty()) continue
            manifest[id] = ManifestItem(
                href = item.attr("href"),
                mediaType = item.attr("media-type"),
                properties = item.attr("properties"),
            )
        }

        val spine = mutableListOf<SpineItem>()
        val spineElement = opf.select("spine").firstOrNull()
        for (itemref in opf.select("spine > itemref")) {
            val idref = itemref.attr("idref")
            val item = manifest[idref] ?: continue
            val zipPath = EpubPaths.resolve(opfDir, item.href)
            if (entryExists(zipPath)) spine.add(SpineItem(idref, zipPath))
        }
        if (spine.isEmpty()) throw EpubFormatException("EPUB has an empty spine")

        val coverPath = findCover(opf, manifest, opfDir)
        val toc = parseToc(opf, manifest, opfDir, spineElement?.attr("toc"), spine)
            .ifEmpty { spine.mapIndexed { i, _ -> TocEntry("${i + 1}", i) } }

        return EpubBook(
            title = title.ifEmpty { "Untitled" },
            author = author,
            language = language,
            spine = spine,
            toc = toc,
            coverPath = coverPath,
        )
    }

    fun loadChapter(book: EpubBook, spineIndex: Int, locale: Locale = book.locale()): Chapter {
        val spineItem = book.spine.getOrNull(spineIndex)
            ?: throw EpubFormatException("Spine index $spineIndex out of bounds")
        val entry = findEntry(spineItem.zipPath)
            ?: throw EpubFormatException("Missing chapter entry: ${spineItem.zipPath}")
        val loader = ChapterLoader(locale, ::entryExists)
        val blocks = zip.getInputStream(entry).use { loader.parse(it, spineItem.zipPath) }
        val title = book.toc.firstOrNull { it.spineIndex == spineIndex }?.title
            ?: (blocks.firstOrNull { it is Block.Text && it.kind != Block.Text.Kind.PARAGRAPH } as? Block.Text)?.text
        return Chapter(spineIndex, title, blocks)
    }

    private fun findCover(
        opf: Document,
        manifest: Map<String, ManifestItem>,
        opfDir: String,
    ): String? {
        // EPUB 3: manifest item flagged as cover-image
        manifest.values.firstOrNull { it.properties.contains("cover-image") }?.let {
            val path = EpubPaths.resolve(opfDir, it.href)
            if (entryExists(path)) return path
        }
        // EPUB 2: <meta name="cover" content="item-id"/>
        val coverId = opf.select("meta[name=cover]").firstOrNull()?.attr("content")
        if (!coverId.isNullOrEmpty()) {
            manifest[coverId]?.let {
                val path = EpubPaths.resolve(opfDir, it.href)
                if (entryExists(path)) return path
            }
        }
        // Heuristic fallback: an image manifest item whose id or href mentions "cover"
        manifest.entries
            .firstOrNull { (id, item) ->
                item.mediaType.startsWith("image/") &&
                    (id.contains("cover", ignoreCase = true) || item.href.contains("cover", ignoreCase = true))
            }
            ?.let {
                val path = EpubPaths.resolve(opfDir, it.value.href)
                if (entryExists(path)) return path
            }
        return null
    }

    private fun parseToc(
        opf: Document,
        manifest: Map<String, ManifestItem>,
        opfDir: String,
        ncxId: String?,
        spine: List<SpineItem>,
    ): List<TocEntry> {
        val spineIndexByPath = spine.withIndex().associate { (i, item) -> item.zipPath to i }

        // EPUB 3 nav document
        manifest.values.firstOrNull { it.properties.split(' ').contains("nav") }?.let { navItem ->
            val navPath = EpubPaths.resolve(opfDir, navItem.href)
            val navDoc = parseHtml(navPath)
            if (navDoc != null) {
                val navDir = EpubPaths.parentDir(navPath)
                val navElement = navDoc.select("nav").firstOrNull { el ->
                    el.attr("epub:type") == "toc" || el.attr("role") == "doc-toc"
                } ?: navDoc.select("nav").firstOrNull()
                if (navElement != null) {
                    val entries = navElement.select("a[href]").mapNotNull { a ->
                        val target = EpubPaths.resolve(navDir, a.attr("href"))
                        val index = spineIndexByPath[target] ?: return@mapNotNull null
                        val label = a.text().trim()
                        if (label.isEmpty()) null else TocEntry(label, index)
                    }
                    val deduped = dedupeBySpineIndex(entries)
                    if (deduped.isNotEmpty()) return deduped
                }
            }
        }

        // EPUB 2 NCX
        val ncxItem = ncxId?.let { manifest[it] }
            ?: manifest.values.firstOrNull { it.mediaType == "application/x-dtbncx+xml" }
        if (ncxItem != null) {
            val ncxPath = EpubPaths.resolve(opfDir, ncxItem.href)
            val ncxDoc = parseXml(ncxPath)
            if (ncxDoc != null) {
                val ncxDir = EpubPaths.parentDir(ncxPath)
                val entries = ncxDoc.select("navPoint").mapNotNull { navPoint ->
                    val label = navPoint.select("navLabel > text").firstOrNull()?.text()?.trim()
                    val src = navPoint.select("content").firstOrNull()?.attr("src")
                    if (label.isNullOrEmpty() || src.isNullOrEmpty()) return@mapNotNull null
                    val index = spineIndexByPath[EpubPaths.resolve(ncxDir, src)] ?: return@mapNotNull null
                    TocEntry(label, index)
                }
                return dedupeBySpineIndex(entries)
            }
        }
        return emptyList()
    }

    private fun dedupeBySpineIndex(entries: List<TocEntry>): List<TocEntry> {
        val seen = mutableSetOf<Int>()
        return entries.filter { seen.add(it.spineIndex) }
    }

    private fun findEntry(path: String): ZipEntry? {
        val clean = path.removePrefix("/")
        return entriesByPath[clean] ?: entriesLowercase[clean.lowercase()]
    }

    private fun parseXml(path: String): Document? {
        val entry = findEntry(path) ?: return null
        return zip.getInputStream(entry).use { Jsoup.parse(it, null, "", Parser.xmlParser()) }
    }

    private fun parseHtml(path: String): Document? {
        val entry = findEntry(path) ?: return null
        return zip.getInputStream(entry).use { Jsoup.parse(it, null, "") }
    }

    override fun close() {
        zip.close()
    }

    private data class ManifestItem(
        val href: String,
        val mediaType: String,
        val properties: String,
    )
}

class EpubFormatException(message: String) : Exception(message)
