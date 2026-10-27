package app.termx

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@Composable
internal fun BackgroundProcessesScreen(wallpaperUri: String?, amoledMode: Boolean, showWallpaper: Boolean, onBack: () -> Unit) {
    val packageName = LocalContext.current.packageName
    val myPid = remember { android.os.Process.myPid() }
    val myUid = remember { android.os.Process.myUid() }
    val panel = RoundedCornerShape(24.dp)
    val backdrop = rememberLayerBackdrop()
    var refreshTick by remember { mutableIntStateOf(0) }
    val scanned by produceState<List<SystemProcess>?>(null, refreshTick) {
        value = withContext(Dispatchers.IO) { ProcessScanner.scan(File("/proc"), myUid, myPid) }
    }
    val all = scanned.orEmpty()
    val sessions = all.filter { ProcessScanner.isSessionShell(it) }
    val groups = ProcessScanner.killable(all, packageName, myPid).groupBy { it.name }.toSortedMap()
    Box(Modifier.fillMaxSize().then(settingsPageFill(amoledMode, wallpaperUri, showWallpaper))) {
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            Column(Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 14.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("‹", Modifier.clickable(onClick = onBack).padding(horizontal = 12.dp, vertical = 4.dp), color = Color.White, fontSize = 30.sp)
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Background Processes", color = Color.White, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                        Text(if (scanned == null) "Reading processes…" else "${groups.values.sumOf { it.size }} process(es) in ${groups.size} group(s)", color = Color(0xFF9CE6D8), style = MaterialTheme.typography.bodySmall)
                    }
                    Text("Refresh", Modifier.clip(RoundedCornerShape(8.dp)).clickable { refreshTick++ }.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFF9CE6D8), style = MaterialTheme.typography.labelMedium)
                }
                if (sessions.isNotEmpty()) Column(Modifier.fillMaxWidth().clip(panel).termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = .075f)).padding(18.dp)) {
                    Text("TERMINAL SESSIONS", color = Color.White.copy(alpha = .54f), style = MaterialTheme.typography.labelSmall, letterSpacing = 1.25.sp)
                    Text("Shells stay open. Close a session from the terminal.", Modifier.padding(top = 4.dp), color = Color.White.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
                    sessions.forEach { ProcessRow(it) }
                }
                if (scanned != null && groups.isEmpty()) Text("No background processes right now. Start Codex or any command in a terminal and it appears here.", color = Color.White.copy(alpha = .62f), style = MaterialTheme.typography.bodySmall)
                groups.forEach { (name, processes) ->
                    Column(Modifier.fillMaxWidth().clip(panel).termxLiquidGlass(backdrop, panel, Color.White.copy(alpha = .075f)).padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(name, color = Color.White, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                            Spacer(Modifier.weight(1f))
                            Text("${processes.size} · ${processes.sumOf { it.rssKb } / 1024} MB", color = Color.White.copy(alpha = .62f), style = MaterialTheme.typography.labelSmall)
                            Spacer(Modifier.width(8.dp))
                            Text("Stop", Modifier.clip(RoundedCornerShape(8.dp)).clickable { processes.forEach { ProcessControl.signal(it.pid) }; refreshTick++ }.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFFFF91AA), style = MaterialTheme.typography.labelMedium)
                            Text("Force", Modifier.clip(RoundedCornerShape(8.dp)).clickable { processes.forEach { ProcessControl.signal(it.pid, true) }; refreshTick++ }.padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFFFF91AA), style = MaterialTheme.typography.labelMedium)
                        }
                        Spacer(Modifier.height(8.dp))
                        processes.forEach { process -> ProcessRow(process, "Stop") { ProcessControl.signal(process.pid); refreshTick++ } }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProcessRow(process: SystemProcess, action: String? = null, onAction: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("PID ${process.pid} · ${process.rssKb / 1024} MB", color = Color.White, style = MaterialTheme.typography.bodySmall)
            Text(process.cmdline, color = Color.White.copy(alpha = .62f), style = MaterialTheme.typography.labelSmall, maxLines = 1)
        }
        if (action != null && onAction != null) Text(action, Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onAction).padding(horizontal = 8.dp, vertical = 4.dp), color = Color(0xFFFF91AA), style = MaterialTheme.typography.labelSmall)
    }
}
