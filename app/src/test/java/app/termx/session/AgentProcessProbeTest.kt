package app.termx.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentProcessProbeTest {
    @Test fun parsesProcStatWithSpacesAndParenthesesInComm() {
        val tail = (3..22).joinToString(" ") { field ->
            when (field) { 4 -> "123"; 22 -> "98765"; else -> "1" }
        }
        assertEquals(123 to 98765L, AgentProcessProbe.parseStat(8, "8 (agent (worker)) $tail"))
        assertNull(AgentProcessProbe.parseStat(8, "not stat"))
    }

    @Test fun matchesConfiguredCommandTokensWithoutMatchingSubstrings() {
        val watched = setOf("codex", "opencode", "claude")
        assertTrue(AgentProcessProbe.matches("node\u0000/opt/bin/codex\u0000", watched))
        assertTrue(AgentProcessProbe.matches("/usr/bin/opencode-cli", watched))
        assertFalse(AgentProcessProbe.matches("/tmp/not-codex-helper", watched))
    }

    @Test fun parsesTabSeparatedProcStatusUid() {
        assertEquals(10234, AgentProcessProbe.parseUid("Name:\tcodex\nUid:\t10234\t10234\t10234\t10234\n"))
    }

    @Test fun manualHoldWinsAndAgentDetectionReleasesAfterExit() {
        assertFalse(KeepAlivePolicy.shouldHold(false, emptyList()))
        assertTrue(KeepAlivePolicy.shouldHold(false, listOf(44)))
        assertTrue(KeepAlivePolicy.shouldHold(true, emptyList()))
        assertFalse(KeepAlivePolicy.shouldHold(false, emptyList()))
        assertEquals(0L, KeepAlivePolicy.pollingIntervalMillis(0, false))
        assertEquals(2_000L, KeepAlivePolicy.pollingIntervalMillis(1, true))
        assertEquals(10_000L, KeepAlivePolicy.pollingIntervalMillis(1, false))
    }

    @Test fun backoffCapsAndStopsCrashLoopUntilWindowExpires() {
        val backoff = WatchedCommandBackoff(maxFailures = 3, windowMillis = 10_000)
        assertEquals(1_000L, backoff.nextDelayMillis(0))
        assertEquals(2_000L, backoff.nextDelayMillis(1))
        assertEquals(4_000L, backoff.nextDelayMillis(2))
        assertNull(backoff.nextDelayMillis(3))
        assertEquals(1_000L, backoff.nextDelayMillis(10_002))
    }

    @Test fun exponentialBackoffCapsAtOneMinute() {
        val backoff = WatchedCommandBackoff(maxFailures = 10, maxDelayMillis = 60_000)
        val delays = (0..7).map { backoff.nextDelayMillis(it.toLong()) }
        assertEquals(listOf(1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 32_000L, 60_000L, 60_000L), delays)
    }
}
