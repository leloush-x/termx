package app.termx

import android.system.Os
import android.system.OsConstants
import java.io.File

object ProcessControl {
    fun signal(pid: Int, force: Boolean = false): Boolean = runCatching { Os.kill(pid, if (force) OsConstants.SIGKILL else OsConstants.SIGTERM) }.isSuccess
    fun stopAll(packageName: String, myPid: Int, myUid: Int, procRoot: File = File("/proc")): Int =
        ProcessScanner.killable(ProcessScanner.scan(procRoot, myUid, myPid), packageName, myPid).count { signal(it.pid) }
}
