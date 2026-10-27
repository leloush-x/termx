package app.termx

import android.os.SystemClock
import java.io.File

/**
 * Termux-style fake /proc sysdata generator.
 *
 * Ports the rules from Termux's `sysdata.py` so that tools like htop/top work inside proot on
 * Android, where the kernel restricts /proc/stat, /proc/meminfo, etc. for non-system apps.
 *
 * The content functions are pure (no I/O) so they can be unit-tested. [writeSysdata] performs
 * the actual file writes using atomic temp-file + rename.
 */
object FakeProcfs {

    /** Number of processors, parsed from /proc/cpuinfo or falling back to availableProcessors(). */
    fun cpuCount(cpuinfo: String): Int {
        val count = cpuinfo.lines().count { it.trim().startsWith("processor") }
        return if (count > 0) count else Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
    }

    /**
     * Generates /proc/stat content.
     * - One `cpuN` row per processor.
     * - Aggregate `cpu` line = column-wise sum of the cpuN rows.
     * - `btime` = wall-clock now minus elapsedRealtime, so stat and uptime agree.
     */
    fun statContent(cpuCount: Int, btime: Long): String {
        val perCpu = listOf(100L, 0L, 50L, 900L, 0L, 0L, 0L, 0L, 0L, 0L)
        val rows = (0 until cpuCount).map { i -> "cpu$i " + perCpu.joinToString(" ") }
        val aggregate = "cpu " + perCpu.map { it * cpuCount }.joinToString(" ")
        return buildString {
            appendLine(aggregate)
            rows.forEach { appendLine(it) }
            appendLine("intr 123456 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0")
            appendLine("ctxt 789012")
            appendLine("btime $btime")
            appendLine("processes 345")
            appendLine("procs_running 1")
            appendLine("procs_blocked 0")
            appendLine("softirq 123456 0 0 0 0 0 0 0 0 0 0")
        }
    }

    /** Generates /proc/uptime content using the same uptime value as btime. */
    fun uptimeContent(uptimeSeconds: Double): String {
        return "%.2f %.2f".format(uptimeSeconds, uptimeSeconds * 0.8)
    }

    /** Generates /proc/loadavg content. */
    fun loadavgContent(): String {
        return "0.00 0.00 0.00 1/100 1234"
    }

    /** Generates /proc/version content. */
    fun versionContent(): String {
        return "Linux version 5.10.0 (termx@localhost) (gcc 12.0) #1 SMP PREEMPT"
    }

    /** Generates /proc/vmstat content. */
    fun vmstatContent(): String {
        return buildString {
            appendLine("nr_free_pages 100000")
            appendLine("nr_zone_inactive_anon 50000")
            appendLine("nr_zone_active_anon 50000")
            appendLine("nr_zone_inactive_file 25000")
            appendLine("nr_zone_active_file 25000")
            appendLine("pgpgin 12345")
            appendLine("pgpgout 67890")
            appendLine("pswpin 0")
            appendLine("pswpout 0")
        }
    }

    /** Generates /proc/meminfo content. */
    fun meminfoContent(): String {
        return buildString {
            appendLine("MemTotal:       4096000 kB")
            appendLine("MemFree:        2048000 kB")
            appendLine("MemAvailable:   3072000 kB")
            appendLine("Buffers:         102400 kB")
            appendLine("Cached:         1024000 kB")
            appendLine("SwapTotal:       1048576 kB")
            appendLine("SwapFree:        1048576 kB")
        }
    }

    /** Generates /proc/cpuinfo content for [count] processors. */
    fun cpuinfoContent(count: Int): String {
        return (0 until count).joinToString("\n") { i ->
            "processor\t: $i\nvendor_id\t: GenuineIntel\ncpu family\t: 6\nmodel name\t: Virtual CPU\n"
        }
    }

    /**
     * Writes all sysdata files into [dir] (mode 0700) using atomic temp-file + rename.
     * Returns the list of file names written.
     */
    fun writeSysdata(dir: File): List<String> {
        dir.mkdirs()
        // Set directory permissions (best effort on Android/Linux)
        dir.setReadable(true, false)
        dir.setExecutable(true, false)
        dir.setWritable(true, true)

        val nowSeconds = System.currentTimeMillis() / 1000
        val uptimeSeconds = (SystemClock.elapsedRealtime() / 1000.0)
        val btime = nowSeconds - (SystemClock.elapsedRealtime() / 1000)

        val count = try {
            val cpuinfo = File("/proc/cpuinfo")
            if (cpuinfo.canRead()) cpuCount(cpuinfo.readText()) else Runtime.getRuntime().availableProcessors()
        } catch (e: Exception) {
            Runtime.getRuntime().availableProcessors()
        }

        val files = mapOf(
            "stat" to statContent(count, btime),
            "uptime" to uptimeContent(uptimeSeconds),
            "loadavg" to loadavgContent(),
            "version" to versionContent(),
            "vmstat" to vmstatContent(),
            "meminfo" to meminfoContent(),
            "cpuinfo" to cpuinfoContent(count),
        )

        val written = mutableListOf<String>()
        for ((name, content) in files) {
            val target = File(dir, name)
            val temp = File(dir, ".$name.tmp")
            try {
                temp.writeText(content)
                temp.setReadable(true, false)
                // Atomic rename
                if (target.exists()) target.delete()
                temp.renameTo(target)
                written.add(name)
            } catch (e: Exception) {
                temp.delete()
            }
        }
        return written
    }

    /** Checks if a file is a regular file (not symlink, not directory). */
    fun isRegularFile(f: File): Boolean {
        return f.exists() && f.isFile && !java.nio.file.Files.isSymbolicLink(f.toPath())
    }

    /** Returns the link count of a file, or -1 if unavailable. */
    fun linkCount(f: File): Int {
        return try {
            java.nio.file.Files.getAttribute(f.toPath(), "unix:nlink") as? Int ?: -1
        } catch (e: Exception) {
            -1
        }
    }
}
