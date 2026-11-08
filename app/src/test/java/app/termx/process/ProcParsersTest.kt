package app.termx.process

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProcParsersTest {
    @Test
    fun parsesStatWithSpacesAndParenthesesInCommand() {
        val fields = MutableList(20) { "0" }
        fields[0] = "S"
        fields[1] = "44"
        fields[11] = "100"
        fields[12] = "25"
        fields[19] = "9876"
        val parsed = ProcStat.parse(123, "123 (worker (busy loop)) ${fields.joinToString(" ")}")
        assertNotNull(parsed)
        assertEquals("worker (busy loop)", parsed?.command)
        assertEquals(44, parsed?.parentPid)
        assertEquals(125L, parsed?.cpuTicks)
        assertEquals(9876L, parsed?.startTicks)
        assertNull(ProcStat.parse(123, "broken"))
    }

    @Test
    fun readsUidAndTcpPortFixtures() {
        assertEquals(10234, ProcParsers.uidFromStatus("Name:\ttermx\nUid:\t10234\t10234\t10234\t10234\n"))
        val table = """  sl  local_address rem_address   st tx_queue rx_queue tr tm->when retrnsmt   uid  timeout inode
           0: 0100007F:1F90 00000000:0000 0A 00000000:00000000 00:00000000 00000000 10234 0 987654
        """.trimIndent()
        assertEquals(listOf(PortEntry("tcp", 8080, "0A", 10234, 987654)), ProcParsers.readPortTable(table, "tcp"))
    }

    @Test
    fun readsRealClockTicksAndPageSizeFromAuxv() {
        val data = ByteBuffer.allocate(48).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(17).putLong(100)
            .putLong(6).putLong(16384)
            .putLong(0).putLong(0)
            .array()
        assertEquals(mapOf(17 to 100L, 6 to 16384L), ProcParsers.auxvValues(data, 8, true))
    }

    @Test
    fun cpuPercentageUsesJiffyDeltaAndRealClockRate() {
        assertEquals(100.0, ProcParsers.cpuPercent(100, 200, 1000, 100)!!, 0.001)
        assertNull(ProcParsers.cpuPercent(200, 100, 1000, 100))
    }

    @Test
    fun protectsPidsAndRejectsUidOrStartTimeChanges() {
        assertTrue(ProcessSafety.maySignal(1000, 1000, 42, 88, 88, setOf(1, 2)))
        assertFalse(ProcessSafety.maySignal(1001, 1000, 42, 88, 88, emptySet()))
        assertFalse(ProcessSafety.maySignal(1000, 1000, 42, 88, 89, emptySet()))
        assertFalse(ProcessSafety.maySignal(1000, 1000, 2, 88, 88, setOf(2)))
    }
}
