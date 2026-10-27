package app.termx

import org.junit.Assert.*
import org.junit.Test

class ProcessScannerTest {
    @Test fun parsesStatusAndCmdline() {
        val status = ProcessScanner.parseStatus("Name:\tnode\nPPid:\t12\nUid:\t1000\t1000\nVmRSS:\t512 kB\nState:\tS (sleeping)")
        assertEquals(1000, ProcessScanner.realUid(status)); assertEquals(12, ProcessScanner.parentPid(status))
        assertEquals(512L, ProcessScanner.rssKb(status)); assertEquals('S', ProcessScanner.state(status))
        assertEquals("node /usr/bin/codex", ProcessScanner.parseCmdline("node\u0000/usr/bin/codex\u0000".toByteArray()))
        assertEquals("codex", ProcessScanner.groupKey("node /usr/bin/codex", "node"))
    }

    @Test fun killableFiltersSelfPackageAndSessionShell() {
        val list = listOf(
            SystemProcess(1, 0, "bash", "bash", 'S', 1, true),
            SystemProcess(2, 1, "codex", "node codex", 'S', 20, true),
            SystemProcess(3, 1, "termx", "app.termx", 'S', 1, false),
            SystemProcess(4, 1, "sleep", "sleep 10", 'S', 1, false),
            SystemProcess(99, 1, "self", "self", 'S', 1, false),
        )
        assertEquals(listOf(2, 4), ProcessScanner.killable(list, "app.termx", 99).map { it.pid })
    }
}
