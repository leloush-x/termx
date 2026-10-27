package app.termx

import org.junit.Assert.assertTrue
import org.junit.Test

class FakeProcfsTest {
    @Test
    fun statContentHasOneCpuRowPerProcessor() {
        val content = FakeProcfs.statContent(4, 1000L)
        val lines = content.lines()
        val cpuRows = lines.filter { it.startsWith("cpu") && it[3].isDigit() }
        assertTrue("expected 4 cpuN rows, got ${cpuRows.size}", cpuRows.size == 4)
    }

    @Test
    fun statContentAggregateIsColumnSum() {
        val content = FakeProcfs.statContent(4, 1000L)
        val lines = content.lines()
        val aggregate = lines.first { it.startsWith("cpu ") }.split(" ").map { it.toLongOrNull() ?: 0L }
        val firstRow = lines.first { it.startsWith("cpu0 ") }.split(" ").map { it.toLongOrNull() ?: 0L }
        // aggregate column 1 = 4 * row column 1
        assertTrue("aggregate should be 4x the per-cpu row", aggregate[1] == firstRow[1] * 4)
    }

    @Test
    fun statContentIncludesBtime() {
        val content = FakeProcfs.statContent(2, 12345L)
        assertTrue(content.contains("btime 12345"))
    }

    @Test
    fun uptimeContentFormatsSeconds() {
        val content = FakeProcfs.uptimeContent(100.5)
        assertTrue(content.startsWith("100.50"))
    }

    @Test
    fun cpuCountParsesProcCpuinfo() {
        val cpuinfo = "processor\t: 0\nprocessor\t: 1\nprocessor\t: 2\n"
        assertTrue(FakeProcfs.cpuCount(cpuinfo) == 3)
    }

    @Test
    fun cpuCountFallsBackWhenEmpty() {
        assertTrue(FakeProcfs.cpuCount("") >= 1)
    }
}
