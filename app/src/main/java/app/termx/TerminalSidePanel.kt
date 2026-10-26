package app.termx

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.Backdrop

@Composable
internal fun BoxScope.TerminalSidePanel(
    backdrop: Backdrop,
    distroName: String,
    workingDirectory: String,
    accent: Color,
    onTerminal: () -> Unit,
    onSettings: () -> Unit,
    onClose: () -> Unit,
) {
    Box(
        Modifier.fillMaxWidth().fillMaxHeight()
            .background(Color.Black.copy(alpha = 0.52f))
            .clickable(onClick = onClose),
    )

    val panelShape = RoundedCornerShape(topEnd = 30.dp, bottomEnd = 30.dp)
    Column(
        Modifier.align(Alignment.CenterStart).fillMaxHeight().width(292.dp)
            .termxLiquidGlass(backdrop, panelShape, Color(0xE6101118))
            .padding(horizontal = 22.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp),
    ) {
        Text("TERM X", color = Color.White.copy(alpha = 0.74f), style = MaterialTheme.typography.labelMedium,
            letterSpacing = 2.1.sp)
        Spacer(Modifier.size(36.dp))
        Text("RUNNING", color = Color.White.copy(alpha = 0.45f), style = MaterialTheme.typography.labelSmall,
            letterSpacing = 1.5.sp)
        Spacer(Modifier.size(10.dp))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onTerminal).padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(10.dp).background(accent, CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(distroName, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(workingDirectory, color = Color.White.copy(alpha = 0.62f), style = MaterialTheme.typography.bodySmall,
                    maxLines = 1)
            }
        }
        Spacer(Modifier.size(24.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.12f)))
        Spacer(Modifier.size(16.dp))
        Row(
            Modifier.fillMaxWidth().clickable(onClick = onSettings).padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("⚙", color = accent, fontSize = 18.sp)
            Spacer(Modifier.width(14.dp))
            Text("Settings", color = Color.White, style = MaterialTheme.typography.titleSmall)
        }
        Spacer(Modifier.weight(1f))
        Text("No root · Just Linux", color = Color.White.copy(alpha = 0.42f), style = MaterialTheme.typography.bodySmall)
    }
}
