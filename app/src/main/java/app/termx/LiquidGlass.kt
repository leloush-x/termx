package app.termx

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/**
 * Backdrop glass that reads as real glass: the layer behind is blurred and made vibrant,
 * then a soft vertical gradient is painted on top (a brighter rim at the top fading to a
 * near-transparent base) with a thin lit edge. On Android versions without runtime render
 * effects this still degrades to a blurred tinted surface.
 */
internal fun Modifier.termxLiquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    tint: Color = Color.White.copy(alpha = 0.075f),
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(16.dp.toPx())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lens(
                refractionHeight = 14.dp.toPx(),
                refractionAmount = 18.dp.toPx(),
                chromaticAberration = true,
            )
        }
    },
    onDrawSurface = {
        drawRect(
            Brush.verticalGradient(
                0f to tint.copy(alpha = (tint.alpha + 0.06f).coerceAtMost(1f)),
                0.5f to tint,
                1f to tint.copy(alpha = (tint.alpha * 0.55f).coerceAtMost(1f)),
            ),
        )
    },
).border(1.dp, Color.White.copy(alpha = 0.18f), shape)
