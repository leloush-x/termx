package app.termx.process

import android.system.Os
import android.system.OsConstants
import android.util.Log
import java.io.File

/** Sends signals only to an app-UID process whose PID start time still matches the selected row. */
object SafeProcessTerminator {
    fun terminate(pid: Int, expectedStartTicks: Long, expectedUid: Int, protectedPids: Set<Int>, checkpoint: () -> Unit = {}): String {
        checkpoint()
        if (!maySignal(pid, expectedStartTicks, expectedUid, protectedPids)) return "Process changed or is protected; left it untouched."
        try {
            Os.kill(pid, OsConstants.SIGTERM)
        } catch (failure: Exception) {
            Log.w(TAG, "SIGTERM failed for PID $pid", failure)
            return "Could not stop process: ${failure.javaClass.simpleName}."
        }
        repeat(20) {
            checkpoint()
            Thread.sleep(50)
            val current = readIdentity(pid)
            if (current == null) return "Process stopped."
            if (current.first != expectedStartTicks || current.second != expectedUid) {
                return "PID changed while stopping; left it untouched."
            }
        }
        checkpoint()
        if (!maySignal(pid, expectedStartTicks, expectedUid, protectedPids)) {
            return "Process changed or is protected; left it untouched."
        }
        return try {
            Os.kill(pid, OsConstants.SIGKILL)
            "Process force-stopped after it ignored the stop request."
        } catch (failure: Exception) {
            Log.w(TAG, "SIGKILL failed for PID $pid", failure)
            "Could not force-stop process: ${failure.javaClass.simpleName}."
        }
    }

    private fun maySignal(pid: Int, expectedStartTicks: Long, expectedUid: Int, protectedPids: Set<Int>): Boolean {
        val identity = readIdentity(pid) ?: return false
        return ProcessSafety.maySignal(identity.second, expectedUid, pid, expectedStartTicks, identity.first, protectedPids)
    }

    private fun readIdentity(pid: Int): Pair<Long, Int>? {
        if (pid <= 1) return null
        val directory = File("/proc/$pid")
        val stat = ProcStat.parse(pid, runCatching { File(directory, "stat").readText() }.getOrNull() ?: return null) ?: return null
        val uid = ProcParsers.uidFromStatus(runCatching { File(directory, "status").readText() }.getOrNull() ?: return null) ?: return null
        return stat.startTicks to uid
    }

    private const val TAG = "TermXProcessStop"
}
