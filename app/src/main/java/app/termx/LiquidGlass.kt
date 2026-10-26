package app.termx

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy

/** Backdrop glass with a tinted fallback on Android versions without render effects. */
internal fun Modifier.termxLiquidGlass(
    backdrop: Backdrop,
    shape: Shape,
    tint: Color = Color.White.copy(alpha = 0.075f),
): Modifier = drawBackdrop(
    backdrop = backdrop,
    shape = { shape },
    effects = {
        vibrancy()
        blur(12.dp.toPx())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            lens(
                refractionHeight = 10.dp.toPx(),
                refractionAmount = 15.dp.toPx(),
                chromaticAberration = true,
            )
        }
    },
    onDrawSurface = { drawRect(tint) },
).border(1.dp, Color.White.copy(alpha = 0.16f), shape)
