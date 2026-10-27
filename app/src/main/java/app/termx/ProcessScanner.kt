package app.termx

import java.io.File

data class SystemProcess(val pid: Int, val ppid: Int, val name: String, val cmdline: String, val state: Char, val rssKb: Long, val terminalChild: Boolean)

object ProcessScanner {
    private val interpreters = setOf("node", "python", "python3", "ruby", "perl", "java", "env", "sh", "bash")
    private val sessionShells = setOf("bash", "sh", "ash", "dash", "zsh", "fish", "libproot.so", "proot")
    fun parseStatus(text: String): Map<String, String> = text.lineSequence().mapNotNull { line ->
        val colon = line.indexOf(':'); if (colon <= 0) null else line.substring(0, colon).trim() to line.substring(colon + 1).trim()
    }.toMap()
    fun realUid(status: Map<String, String>): Int? = status["Uid"]?.split(WHITESPACE)?.firstOrNull()?.toIntOrNull()
    fun parentPid(status: Map<String, String>): Int? = status["PPid"]?.toIntOrNull()
    fun rssKb(status: Map<String, String>): Long = status["VmRSS"]?.split(WHITESPACE)?.firstOrNull()?.toLongOrNull() ?: 0L
    fun state(status: Map<String, String>): Char = status["State"]?.firstOrNull() ?: '?'
    fun parseCmdline(bytes: ByteArray): String = String(bytes, Charsets.UTF_8).split('\u0000').filter { it.isNotEmpty() }.joinToString(" ").trim()
    fun groupKey(cmdline: String, kernelName: String): String {
        val words = cmdline.trim().split(WHITESPACE).filter { it.isNotEmpty() }
        val first = words.firstOrNull().orEmpty().substringAfterLast('/')
        if (first in interpreters) words.drop(1).firstOrNull { !it.startsWith("-") }?.let { return it.substringAfterLast('/').ifBlank { first } }
        return first.ifBlank { kernelName }
    }
    fun isSessionShell(process: SystemProcess): Boolean = process.terminalChild && (process.name in sessionShells || process.cmdline.contains("libproot.so"))
    fun markTerminalChildren(processes: List<SystemProcess>, appPid: Int): List<SystemProcess> {
        val parentOf = processes.associate { it.pid to it.ppid }
        fun descends(pid: Int): Boolean {
            var current = pid
            repeat(MAX_DEPTH) { val parent = parentOf[current] ?: return false; if (parent == appPid) return true; if (parent <= 1) return false; current = parent }
            return false
        }
        return processes.map { it.copy(terminalChild = descends(it.pid)) }
    }
    fun killable(processes: List<SystemProcess>, packageName: String, myPid: Int): List<SystemProcess> =
        processes.filter { it.pid != myPid && !it.cmdline.contains(packageName) && !isSessionShell(it) }
    fun scan(procRoot: File, myUid: Int, myPid: Int): List<SystemProcess> {
        val entries = procRoot.listFiles() ?: return emptyList()
        val found = entries.mapNotNull { dir ->
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            if (pid == myPid) return@mapNotNull null
            val status = runCatching { parseStatus(File(dir, "status").readText()) }.getOrNull() ?: return@mapNotNull null
            if (realUid(status) != myUid) return@mapNotNull null
            val kernelName = status["Name"] ?: "unknown"
            val cmdline = runCatching { parseCmdline(File(dir, "cmdline").readBytes()) }.getOrDefault("")
            SystemProcess(pid, parentPid(status) ?: 0, groupKey(cmdline, kernelName), cmdline.ifBlank { "[$kernelName]" }, state(status), rssKb(status), false)
        }
        return markTerminalChildren(found, myPid).sortedBy { it.pid }
    }
    private val WHITESPACE = Regex("\\s+")
    private const val MAX_DEPTH = 64
}
