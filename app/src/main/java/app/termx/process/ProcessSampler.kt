package app.termx.process

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Process
import android.os.SystemClock
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files

data class ProcessRoot(val sessionId: String, val pid: Int)

/** All /proc, fd, memory, and localhost reads happen on the caller's IO dispatcher. */
class ProcessSampler(private val context: Context) {
    private data class Previous(val ticks: Long, val timeMillis: Long)

    private var previous = emptyMap<Pair<Int, Long>, Previous>()
    private var lastSocketScan: Long? = null
    private var cachedPorts = emptyList<PortEntry>()
    private var cachedTier = "Tier 1: /proc/net"
    private var lastFallbackProbe = 0L
    private var fallbackPorts = emptyList<PortEntry>()

    fun sample(roots: List<ProcessRoot>, appUid: Int, checkpoint: () -> Unit = {}) : ProcessPortSnapshot {
        val now = SystemClock.elapsedRealtime()
        val auxv = readAuxvValues()
        val clockTicks = auxv[17]
        val pageSize = auxv[6]
        val processFiles = File("/proc").listFiles().orEmpty()
            .filter { file -> file.name.all { it.isDigit() } }
            .take(MAX_PROC_ENTRIES)
        val all = processFiles.mapNotNull { file ->
            checkpoint()
            val pid = file.name.toIntOrNull() ?: return@mapNotNull null
            val uid = ProcParsers.uidFromStatus(readText(File(file, "status"))) ?: return@mapNotNull null
            if (uid != appUid) return@mapNotNull null
            val stat = ProcStat.parse(pid, readText(File(file, "stat"))) ?: return@mapNotNull null
            val residentPages = readText(File(file, "statm")).split(Regex("\\s+")).getOrNull(1)?.toLongOrNull()
            val commandLine = readText(File(file, "cmdline")).replace('\u0000', ' ').trim()
                .filterNot { it.isISOControl() }.take(MAX_COMMAND_CHARS).ifBlank { stat.command }
            RawProcess(stat, residentPages?.let { pages -> pageSize?.let { pages * it } }, commandLine)
        }
        val byParent = all.groupBy { it.stat.parentPid }
        val appPid = Process.myPid()
        val effectiveRoots = roots.filter { it.pid > 0 }.toMutableList().apply { add(ProcessRoot("TermX", appPid)) }
        val sessionByPid = mutableMapOf<Int, String>()
        effectiveRoots.forEach { root ->
            if (all.any { it.stat.pid == root.pid }) sessionByPid[root.pid] = root.sessionId
        }
        val pending = java.util.ArrayDeque(effectiveRoots.map { it.pid to it.sessionId })
        while (pending.isNotEmpty()) {
            val (parent, sessionId) = pending.removeFirst()
            byParent[parent].orEmpty().forEach { child ->
                if (sessionByPid.putIfAbsent(child.stat.pid, sessionId) == null) pending.addLast(child.stat.pid to sessionId)
            }
        }

        val protectedPids = effectiveRoots.mapTo(mutableSetOf()) { it.pid } + appPid
        val nextPrevious = mutableMapOf<Pair<Int, Long>, Previous>()
        val allByPid = all.associateBy { it.stat.pid }
        val rows = sessionByPid.mapNotNull { (pid, sessionId) ->
            val raw = allByPid[pid] ?: return@mapNotNull null
            val stat = raw.stat
            val key = pid to stat.startTicks
            val sample = Previous(stat.cpuTicks, now)
            val old = previous[key]
            nextPrevious[key] = sample
            val cpu = if (old != null && clockTicks != null) {
                ProcParsers.cpuPercent(old.ticks, sample.ticks, now - old.timeMillis, clockTicks)
            } else null
            ProcessRow(
                pid, stat.parentPid, sessionId, raw.commandLine, stat.state, cpu, raw.residentBytes,
                stat.startTicks, pid in protectedPids,
            )
        }.sortedByDescending { it.cpuPercent ?: -1.0 }.take(MAX_VISIBLE_ROWS)
        previous = nextPrevious

        checkpoint()
        if (lastSocketScan == null || now - requireNotNull(lastSocketScan) >= SOCKET_SCAN_INTERVAL_MS) {
            val table = readSocketTables()
            if (table != null) {
                val owned = table.filter { it.uid == appUid && (it.protocol == "udp" || it.state == "0A") }
                val owners = findOwnerPids(owned.map { it.inode }.toSet(), all.map { it.stat.pid }, checkpoint)
                cachedPorts = owned.map { entry -> entry.copy(ownerPid = owners[entry.inode]) }
                    .distinctBy { Triple(it.protocol, it.localPort, it.inode) }
                    .sortedWith(compareBy({ it.localPort }, { it.protocol }))
                cachedTier = "Tier 1: /proc/net"
            } else {
                if (now - lastFallbackProbe >= FALLBACK_PROBE_INTERVAL_MS) {
                    fallbackPorts = probeCommonLocalPorts(checkpoint)
                    lastFallbackProbe = now
                }
                cachedPorts = fallbackPorts
                cachedTier = "Tier 3: common localhost probes; process owner unavailable"
            }
            lastSocketScan = now
        }
        val appPss = runCatching {
            val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            manager.getProcessMemoryInfo(intArrayOf(appPid)).firstOrNull()?.totalPss?.toLong()?.times(1024)
        }.getOrNull()
        val battery = runCatching {
            val manager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY).takeIf { it in 0..100 }
        }.getOrNull()
        return ProcessPortSnapshot(
            rows, cachedPorts, cachedTier,
            clockTicks, appPss, readAvailableMemory(), battery,
        )
    }

    private data class RawProcess(val stat: ProcStat, val residentBytes: Long?, val commandLine: String)

    private fun readSocketTables(): List<PortEntry>? {
        val paths = listOf("tcp", "tcp6", "udp", "udp6")
        val results = mutableListOf<PortEntry>()
        var readable = false
        paths.forEach { name ->
            val file = File("/proc/net/$name")
            try {
                results += ProcParsers.readPortTable(file.readText(), name.substringBefore('6'))
                readable = true
            } catch (_: Exception) {
                // Android can deny /proc/net by policy; the limited localhost tier remains available.
            }
        }
        return if (readable) results else null
    }

    private fun findOwnerPids(inodes: Set<Long>, pids: List<Int>, checkpoint: () -> Unit): Map<Long, Int> {
        if (inodes.isEmpty()) return emptyMap()
        val remaining = inodes.toMutableSet()
        val owners = mutableMapOf<Long, Int>()
        for (pid in pids) {
            checkpoint()
            if (remaining.isEmpty()) break
            for (descriptor in File("/proc/$pid/fd").listFiles().orEmpty()) {
                checkpoint()
                val link = runCatching { Files.readSymbolicLink(descriptor.toPath()).toString() }.getOrNull()
                if (link != null && link.startsWith("socket:[") && link.endsWith(']')) {
                    val inode = link.substring(8, link.length - 1).toLongOrNull()
                    if (inode != null && remaining.remove(inode)) owners[inode] = pid
                }
            }
        }
        return owners
    }

    private fun probeCommonLocalPorts(checkpoint: () -> Unit): List<PortEntry> = COMMON_PORTS.mapNotNull { port ->
        checkpoint()
        runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", port), 45)
                PortEntry("tcp", port, "open", Process.myUid(), -1)
            }
        }.getOrNull()
    }

    private fun readAuxvValues(): Map<Int, Long> = runCatching {
        val bytes = File("/proc/self/auxv").readBytes()
        ProcParsers.auxvValues(bytes, if (Process.is64Bit()) 8 else 4, ByteOrder.nativeOrder() == ByteOrder.LITTLE_ENDIAN)
    }.getOrDefault(emptyMap())

    private fun readAvailableMemory(): Long? = runCatching {
        val line = File("/proc/meminfo").useLines { lines -> lines.firstOrNull { it.startsWith("MemAvailable:") } }
            ?: return null
        line.substringAfter(':').trim().substringBefore(' ').toLong() * 1024
    }.getOrNull()

    private fun readText(file: File): String = runCatching { file.readText() }.getOrDefault("")

    companion object {
        private const val MAX_PROC_ENTRIES = 4096
        private const val MAX_VISIBLE_ROWS = 256
        private const val MAX_COMMAND_CHARS = 256
        private const val SOCKET_SCAN_INTERVAL_MS = 5_000L
        private const val FALLBACK_PROBE_INTERVAL_MS = 15_000L
        private val COMMON_PORTS = (listOf(22, 53, 80, 443, 3000, 3001, 4000, 5000, 5173, 8000, 8080, 8081, 8443, 9000, 9222) +
            (3000..3010) + (8000..8010)).distinct()
    }
}
