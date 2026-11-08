package app.termx.process

data class ProcStat(
    val pid: Int,
    val command: String,
    val state: Char,
    val parentPid: Int,
    val userTicks: Long,
    val systemTicks: Long,
    val startTicks: Long,
) {
    val cpuTicks: Long get() = userTicks + systemTicks

    companion object {
        /** Parses /proc/[pid]/stat, whose comm field may contain spaces and parentheses. */
        fun parse(pid: Int, line: String): ProcStat? = runCatching {
            val open = line.indexOf('(')
            val close = line.lastIndexOf(')')
            require(open >= 0 && close > open)
            val parsedPid = line.substring(0, open).trim().toInt()
            require(parsedPid == pid)
            val command = line.substring(open + 1, close)
            val fields = line.substring(close + 1).trim().split(Regex("\\s+"))
            require(fields.size > 19)
            ProcStat(
                pid = pid,
                command = command,
                state = fields[0].single(),
                parentPid = fields[1].toInt(),
                userTicks = fields[11].toLong(),
                systemTicks = fields[12].toLong(),
                startTicks = fields[19].toLong(),
            )
        }.getOrNull()
    }
}

data class PortEntry(
    val protocol: String,
    val localPort: Int,
    val state: String,
    val uid: Int,
    val inode: Long,
    val ownerPid: Int? = null,
)

object ProcParsers {
    fun uidFromStatus(status: String): Int? = status.lineSequence()
        .firstOrNull { it.startsWith("Uid:") }
        ?.substringAfter(':')?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.toIntOrNull()

    fun readPortTable(text: String, protocol: String): List<PortEntry> = text.lineSequence().drop(1).mapNotNull { line ->
        runCatching {
            val fields = line.trim().split(Regex("\\s+"))
            val local = fields[1].substringAfter(':')
            val port = local.toInt(16)
            val state = fields[3]
            val uid = fields[7].toInt()
            val inode = fields[9].toLong()
            if (port !in 1..65535 || inode <= 0) null else PortEntry(protocol, port, state, uid, inode)
        }.getOrNull()
    }.toList()

    /** Linux AT_CLKTCK and AT_PAGESZ entries from /proc/self/auxv, using native word size/order. */
    fun auxvValues(bytes: ByteArray, wordSize: Int, littleEndian: Boolean): Map<Int, Long> {
        require(wordSize == 4 || wordSize == 8)
        val order = if (littleEndian) java.nio.ByteOrder.LITTLE_ENDIAN else java.nio.ByteOrder.BIG_ENDIAN
        val buffer = java.nio.ByteBuffer.wrap(bytes).order(order)
        val result = mutableMapOf<Int, Long>()
        while (buffer.remaining() >= wordSize * 2) {
            val key = readWord(buffer, wordSize)
            val value = readWord(buffer, wordSize)
            if (key == 0L) break
            if (key == 17L || key == 6L) result[key.toInt()] = value
        }
        return result
    }

    private fun readWord(buffer: java.nio.ByteBuffer, wordSize: Int): Long =
        if (wordSize == 8) buffer.long else buffer.int.toLong() and 0xffffffffL

    fun cpuPercent(previousTicks: Long, currentTicks: Long, elapsedMillis: Long, clockTicks: Long): Double? {
        if (clockTicks <= 0 || elapsedMillis <= 0 || currentTicks < previousTicks) return null
        return ((currentTicks - previousTicks).toDouble() / clockTicks * 1000.0 / elapsedMillis * 100.0)
            .coerceAtLeast(0.0)
    }
}

data class ProcessRow(
    val pid: Int,
    val parentPid: Int,
    val sessionId: String,
    val command: String,
    val state: Char,
    val cpuPercent: Double?,
    val residentBytes: Long?,
    val startTicks: Long,
    val isProtected: Boolean,
)

data class ProcessPortSnapshot(
    val processes: List<ProcessRow>,
    val ports: List<PortEntry>,
    val portTier: String,
    val clockTicks: Long?,
    val appRamBytes: Long?,
    val availableMemoryBytes: Long?,
    val batteryPercent: Int?,
)

object ProcessSafety {
    fun maySignal(uid: Int, expectedUid: Int, pid: Int, expectedStartTicks: Long, currentStartTicks: Long?, protectedPids: Set<Int>): Boolean =
        pid > 1 && uid == expectedUid && pid !in protectedPids && currentStartTicks == expectedStartTicks
}
