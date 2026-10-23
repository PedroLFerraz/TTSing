package com.pedrolopes.ttsing.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pedrolopes.ttsing.desktop.ui.theme.Ink
import com.pedrolopes.ttsing.desktop.ui.theme.monoLabel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.skia.Image
import java.io.File
import java.util.Collections
import javax.swing.JFileChooser

/** The design's metadata voice: uppercase mono, widely tracked. */
@Composable
fun MonoText(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = Ink.Muted,
    size: Float = 11f,
    weight: FontWeight = FontWeight.Normal,
) {
    Text(
        text = text.uppercase(),
        style = monoLabel(size = size, weight = weight, color = color),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

@Composable
fun Hairline(modifier: Modifier = Modifier, color: Color = Ink.Hairline, inset: Dp = 0.dp) {
    Box(modifier.padding(horizontal = inset).fillMaxWidth().height(1.dp).background(color))
}

/** Decoded images by key, so covers don't decode again every time the grid scrolls. */
private val decoded = Collections.synchronizedMap(object : LinkedHashMap<String, ImageBitmap>(64, 0.75f, true) {
    override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>) = size > 200
})

/** [load]'s bytes as an image, decoded off the main thread; null while loading or if they aren't one. */
@Composable
fun rememberImage(key: String, load: suspend () -> ByteArray?): ImageBitmap? {
    val image by produceState(decoded[key], key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching { load()?.let { Image.makeFromEncoded(it).toComposeImageBitmap() } }.getOrNull()
                ?.also { decoded[key] = it }
        }
    }
    return image
}

@Composable
fun rememberFileImage(path: String?): ImageBitmap? =
    if (path == null) null else rememberImage("file:$path") { File(path).takeIf { it.isFile }?.readBytes() }

/**
 * Asks for a folder: the desktop's own dialog through `zenity` when it is installed (GTK, so it
 * looks like the rest of the desktop and knows its bookmarks), Swing's otherwise. Blocking.
 */
fun chooseFolder(start: File?): File? {
    val zenity = runCatching {
        val args = mutableListOf("zenity", "--file-selection", "--directory", "--title=Choose your books folder")
        start?.let { args.add("--filename=${it.absolutePath}/") }
        val process = ProcessBuilder(args).start()
        val chosen = process.inputStream.bufferedReader().readText().trim()
        process.waitFor() to chosen
    }.getOrNull()
    if (zenity != null) return zenity.second.takeIf { zenity.first == 0 && it.isNotEmpty() }?.let(::File)
    val chooser = JFileChooser(start).apply {
        fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
        dialogTitle = "Choose your books folder"
    }
    return if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile else null
}

/** Opens [file]'s folder in the desktop's file manager. */
fun showInFileManager(file: File) {
    runCatching { ProcessBuilder("xdg-open", (file.parentFile ?: file).absolutePath).start() }
}
