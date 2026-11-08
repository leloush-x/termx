package app.termx.session

import android.os.Process
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class SessionProcess(
    val pid: Int,
    val parentPid: Int,
    val uid: Int,
    val command: String,
    val startTimeTicks: Long,
)

/** Process-tree inspection stays in the TermX UID and degrades to an empty result on procfs denial. */
object AgentProcessProbe {
    suspend fun findWatchedDescendants(
        rootPid: Int,
        watchedNames: Set<String>,
        expectedUid: Int = Process.myUid(),
    ): List<SessionProcess> = withContext(Dispatchers.IO) {
        if (rootPid <= 0 || watchedNames.isEmpty()) return@withContext emptyList()
        val all = readProcessTable(expectedUid)
        val byParent = all.groupBy(SessionProcess::parentPid)
        val byPid = all.associateBy(SessionProcess::pid)
        val descendants = ArrayDeque<Int>().apply { add(rootPid) }
        val visited = HashSet<Int>()
        val matched = mutableListOf<SessionProcess>()
        while (descendants.isNotEmpty()) {
            val pid = descendants.removeFirst()
            if (!visited.add(pid)) continue
            val process = byPid[pid] ?: continue
            if (process.pid != rootPid && matches(process.command, watchedNames)) matched += process
            byParent[pid].orEmpty().forEach { descendants.add(it.pid) }
        }
        matched
    }

    internal fun parseStat(pid: Int, line: String): Pair<Int, Long>? {
        val close = line.lastIndexOf(')')
        if (close < 0 || close + 2 >= line.length) return null
        val fields = line.substring(close + 2).trim().split(Regex("\\s+"))
        // The remaining fields begin at Linux stat field 3 (state): ppid=field 4,
        // starttime=field 22, so their indexes are 1 and 19.
        val parentPid = fields.getOrNull(1)?.toIntOrNull() ?: return null
        val startTicks = fields.getOrNull(19)?.toLongOrNull() ?: return null
        return parentPid to startTicks
    }

    internal fun parseUid(status: String): Int? = status.lineSequence()
        .firstOrNull { it.startsWith("Uid:") }
        ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()

    internal fun matches(command: String, watchedNames: Set<String>): Boolean {
        val names = watchedNames.map(String::lowercase).toSet()
        return command.split('\u0000', '\n', ' ', '\t').any { token ->
            val normalized = token.substringAfterLast('/').lowercase().removeSuffix(".js")
            normalized in names || names.any { normalized.startsWith("$it-") }
        }
    }

    private suspend fun readProcessTable(uid: Int): List<SessionProcess> {
        val result = ArrayList<SessionProcess>()
        val proc = File("/proc")
        val entries = runCatching { proc.listFiles().orEmpty() }.getOrDefault(emptyArray())
        for (entry in entries) {
            currentCoroutineContext().ensureActive()
            val pid = entry.name.toIntOrNull() ?: continue
            val process = runCatching {
                val (parent, start) = parseStat(pid, File(entry, "stat").readText()) ?: return@runCatching null
                val processUid = parseUid(File(entry, "status").readText()) ?: return@runCatching null
                if (processUid != uid) return@runCatching null
                val commandLine = File(entry, "cmdline").readBytes()
                    .toString(Charsets.UTF_8).trimEnd('\u0000')
                val comm = File(entry, "comm").readText().trim()
                SessionProcess(pid, parent, processUid, commandLine.ifBlank { comm }, start)
            }.getOrNull()
            if (process != null) result += process
        }
        return result
    }
}

/** Wake-lock source of truth: manual hold always wins; otherwise only a live watched agent holds it. */
object KeepAlivePolicy {
    fun shouldHold(manualKeepAlive: Boolean, watchedAgentPids: Collection<Int>): Boolean =
        manualKeepAlive || watchedAgentPids.isNotEmpty()

    fun pollingIntervalMillis(sessionCount: Int, hasWatchedAgent: Boolean): Long = when {
        sessionCount <= 0 -> 0L
        hasWatchedAgent -> 2_000L
        else -> 10_000L
    }
}

/** Exponential restart delay with a fixed-window crash-loop stop. */
class WatchedCommandBackoff(
    private val maxFailures: Int = 5,
    private val windowMillis: Long = 5 * 60_000L,
    private val maxDelayMillis: Long = 60_000L,
) {
    private val failures = ArrayDeque<Long>()

    fun nextDelayMillis(nowMillis: Long): Long? {
        while (failures.isNotEmpty() && nowMillis - failures.first() >= windowMillis) failures.removeFirst()
        if (failures.size >= maxFailures) return null
        failures.addLast(nowMillis)
        val exponent = failures.size - 1
        return (1_000L shl exponent.coerceAtMost(30)).coerceAtMost(maxDelayMillis)
    }

    fun clear() = failures.clear()
}
