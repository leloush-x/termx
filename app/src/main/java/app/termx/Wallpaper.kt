package app.termx

import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a wallpaper off the main thread.
 *
 * Blur is applied with [blur] (RenderEffect, API 31+). Below that the bitmap is decoded with a
 * smaller sample size so the up-scaled draw reads as soft instead of sharp, since [blur] is a
 * no-op before API 31.
 */
@Composable
internal fun rememberWallpaperBitmap(uri: String?, blur: Float): ImageBitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(uri, blur) {
        if (uri.isNullOrBlank()) {
            bitmap = null
            return@LaunchedEffect
        }
        bitmap = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                // Cap the decode so a 50 MP photo does not cost 200 MB of heap.
                var sample = 1
                while (longEdge / (sample * 2) >= 1600) sample *= 2
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S && blur > 0f) {
                    // Pre-12 has no RenderEffect; a heavier down-sample stands in for the blur.
                    sample *= 1 + (blur / 6f).toInt()
                }
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                }?.asImageBitmap()
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * Draws a wallpaper behind a screen, with a dim scrim so text stays legible.
 *
 * The scrim is what the blur slider complements: blur softens detail, dim controls contrast.
 */
@Composable
internal fun WallpaperLayer(
    bitmap: ImageBitmap?,
    radius: Float,
    dim: Float,
    modifier: Modifier = Modifier,
) {
    if (bitmap == null) return
    Box(modifier.fillMaxSize()) {
        Image(
            bitmap = bitmap,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            alpha = 1f - dim.coerceIn(0f, 1f) * 0.75f,
            modifier = Modifier.fillMaxSize().let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && radius > 0f) it.blur(radius.dp) else it
            },
        )
        // Extra black wash on top; keeps text readable even on a bright photo at low blur.
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim.coerceIn(0f, 1f) * 0.45f)))
    }
}
