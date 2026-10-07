package com.pedrolopes.ttsing.ui.common

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The cache is sized by memory, not by count: one full-page figure outweighs a hundred thumbnails. */
private val bitmapCache = object : LruCache<String, Bitmap>(imageCacheBytes(Runtime.getRuntime().maxMemory())) {
    override fun sizeOf(key: String, value: Bitmap): Int = value.allocationByteCount
}

/** An eighth of the heap, as the Android docs suggest for an image cache. */
internal fun imageCacheBytes(maxHeapBytes: Long): Int = (maxHeapBytes / 8).coerceIn(1L, Int.MAX_VALUE.toLong()).toInt()

/**
 * Key for an image inside a book. The path alone is not unique: Calibre names every EPUB's
 * first picture `images/00001.jpeg`, so two books would show each other's. Also used for a
 * reflowed PDF's figures.
 */
internal fun bookImageKey(bookId: String, path: String): String = "$bookId|$path"

/**
 * The largest power-of-two subsampling that still leaves the picture at least [targetWidth] by
 * [targetHeight] pixels, so it is never decoded bigger than it is drawn but never blurry either.
 * A target of zero or less (size not known) means no subsampling.
 */
internal fun sampleSizeFor(sourceWidth: Int, sourceHeight: Int, targetWidth: Int, targetHeight: Int): Int {
    if (sourceWidth <= 0 || sourceHeight <= 0 || targetWidth <= 0 || targetHeight <= 0) return 1
    var sample = 1
    while (sourceWidth / (sample * 2) >= targetWidth && sourceHeight / (sample * 2) >= targetHeight) sample *= 2
    return sample
}

/** What the cache is keyed by: the image and the size asked for, since the same one can be drawn at two sizes. */
internal fun cacheKeyFor(key: String, targetWidth: Int, targetHeight: Int): String = "$key@${targetWidth}x$targetHeight"

private fun decode(bytes: ByteArray, targetWidth: Int, targetHeight: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    val opts = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.RGB_565
        inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, targetWidth, targetHeight)
    }
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)
}

/**
 * Loads and decodes an image lazily off the main thread, with an in-memory cache bounded in
 * bytes. [key] identifies the image (unique across everything the app shows — see
 * [bookImageKey]); [loadBytes] is only invoked on a cache miss. The picture is decoded no
 * larger than the space it is drawn in.
 */
@Composable
fun LocalImage(
    key: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    loadBytes: suspend () -> ByteArray?,
) {
    BoxWithConstraints(modifier = modifier) {
        // An unbounded side (a list measuring its content) is not a size to decode for.
        val targetWidth = if (constraints.hasBoundedWidth) constraints.maxWidth else 0
        val targetHeight = if (constraints.hasBoundedHeight) constraints.maxHeight else 0
        val cacheKey = cacheKeyFor(key, targetWidth, targetHeight)
        val image by produceState<ImageBitmap?>(bitmapCache.get(cacheKey)?.asImageBitmap(), cacheKey) {
            if (value != null) return@produceState
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val bytes = loadBytes() ?: return@runCatching null
                    decode(bytes, targetWidth, targetHeight)
                }.getOrNull()
            } ?: return@produceState
            bitmapCache.put(cacheKey, bitmap)
            value = bitmap.asImageBitmap()
        }
        image?.let {
            Image(
                bitmap = it,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                contentScale = contentScale,
            )
        }
    }
}

internal fun clearImageCache() = bitmapCache.evictAll()
