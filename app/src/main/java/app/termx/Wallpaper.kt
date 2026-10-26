package app.termx

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Decodes a wallpaper off the main thread into a [Bitmap] that already carries the requested
 * blur (via down-sampling, which works on every API level) and dim (a black scrim).
 *
 * The result is meant to be handed to [android.view.Window.setBackgroundDrawable] so the image
 * becomes the window's own background: it is then full-screen and completely immune to IME
 * resize, which is the only reliable way to stop a background from "moving" when the keyboard
 * opens.
 */
@Composable
internal fun rememberWallpaperBitmap(uri: String?, blur: Float, dim: Float): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri, blur, dim) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri, blur, dim) {
        bitmap = withContext(Dispatchers.IO) {
            if (uri.isNullOrBlank()) return@withContext null
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                val longEdge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                // Down-sample for both memory safety and a soft (blurred) look. The blur slider
                // (0..32) drives how aggressively we down-sample.
                var sample = 1
                while (longEdge / (sample * 2) >= 1600) sample *= 2
                if (blur > 0f) sample *= 1 + (blur / 4f).toInt().coerceAtLeast(0)
                sample = sample.coerceAtLeast(1)
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = context.contentResolver.openInputStream(Uri.parse(uri))?.use {
                    BitmapFactory.decodeStream(it, null, options)
                } ?: return@withContext null
                // Apply dim: draw a black scrim over the bitmap so the result is self-contained.
                val out = Bitmap.createBitmap(decoded.width, decoded.height, Bitmap.Config.ARGB_8888)
                val canvas = Canvas(out)
                canvas.drawBitmap(decoded, 0f, 0f, null)
                if (dim > 0f) {
                    val scrim = Paint().apply { color = Color.argb((dim * 0.6f * 255).toInt().coerceIn(0, 255), 0, 0, 0) }
                    canvas.drawRect(Rect(0, 0, out.width, out.height), scrim)
                }
                decoded.recycle()
                out
            }.getOrNull()
        }
    }
    return bitmap
}

/**
 * Legacy Compose layer kept for callers that still reference it. With the window-background
 * approach it is no longer used; left as a no-op-safe helper.
 */
@Composable
internal fun WallpaperLayer(
    bitmap: ImageBitmap?,
    radius: Float,
    dim: Float,
    modifier: Modifier = Modifier,
) {
    // No-op: wallpaper is now applied via the window background drawable.
}
