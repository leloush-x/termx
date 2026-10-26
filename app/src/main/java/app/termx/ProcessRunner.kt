package app.termx

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lightweight foreground/background process runner for the terminal.
 *
 * Foreground processes are just commands typed into the active PTY (already supported by the
 * terminal itself). Background processes are detached from the PTY: their stdout/stderr go to a
 * log file under the app's cache dir and their PID is tracked so they can be inspected or
 * killed later. This mirrors how `termux-job-runner` / `nohup ... &` work without requiring a
 * separate daemon.
 */
object ProcessRunner {
    data class BackgroundJob(val pid: Long, val command: String, val logFile: File, val startedAt: Long)

    private val jobs = ConcurrentHashMap<Long, BackgroundJob>()

    /** Returns the list of currently tracked background jobs. */
    fun list(): List<BackgroundJob> = jobs.values.toList()

    /**
     * Launches [command] in the background via the shell, detached from any TTY.
     * stdout and stderr are appended to a per-job log file. Returns the job (with PID) on
     * success, or null if the process could not be started.
     */
    fun startBackground(context: Context, command: String): BackgroundJob? {
        if (command.isBlank()) return null
        val logDir = File(context.cacheDir, "termx-jobs").apply { mkdirs() }
        val logFile = File(logDir, "job-${System.currentTimeMillis()}.log")
        return try {
            val pb = ProcessBuilder("/bin/sh", "-c", "$command >${logFile.absolutePath} 2>&1 & echo \$!")
            pb.directory(context.filesDir)
            val process = pb.start()
            val pidText = process.inputStream.bufferedReader().readText().trim()
            val pid = pidText.toLongOrNull() ?: process.pid()
            val job = BackgroundJob(pid, command, logFile, System.currentTimeMillis())
            jobs[pid] = job
            process.waitFor()
            job
        } catch (e: Exception) {
            null
        }
    }

    /** Sends SIGTERM to a tracked background job and removes it from tracking. */
    fun stop(pid: Long): Boolean {
        val job = jobs.remove(pid) ?: return false
        return try {
            Runtime.getRuntime().exec(arrayOf("kill", "-TERM", pid.toString())).waitFor() == 0
        } catch (e: Exception) {
            false
        }
    }

    /** Stops every tracked background job. */
    fun stopAll(): Int {
        var count = 0
        jobs.keys.toList().forEach { pid -> if (stop(pid)) count++ }
        return count
    }

    /** Reads the tail of a job's log file (up to [maxBytes]). */
    fun readLog(job: BackgroundJob, maxBytes: Int = 8192): String {
        if (!job.logFile.exists()) return ""
        val size = job.logFile.length().toInt()
        val start = (size - maxBytes).coerceAtLeast(0)
        return job.logFile.readBytes().copyOfRange(start, size).toString(Charsets.UTF_8)
    }
}
