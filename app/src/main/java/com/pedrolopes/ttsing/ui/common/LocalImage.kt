package com.pedrolopes.ttsing.ui.common

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val bitmapCache = object : LruCache<String, ImageBitmap>(24) {}

/**
 * Loads and decodes an image lazily off the main thread, with a small in-memory cache.
 * [key] identifies the image; [loadBytes] is only invoked on a cache miss.
 */
@Composable
fun LocalImage(
    key: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    loadBytes: suspend () -> ByteArray?,
) {
    val image by produceState<ImageBitmap?>(initialValue = bitmapCache.get(key), key1 = key) {
        if (value != null) return@produceState
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bytes = loadBytes() ?: return@runCatching null
                val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.RGB_565 }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
            }.getOrNull()
        }?.also { bitmapCache.put(key, it) }
    }

    val current = image
    if (current != null) {
        Image(
            bitmap = current,
            contentDescription = contentDescription,
            modifier = modifier,
            contentScale = contentScale,
        )
    } else {
        Box(modifier = modifier.then(Modifier), content = {})
    }
}

internal fun clearImageCache() = bitmapCache.evictAll()
