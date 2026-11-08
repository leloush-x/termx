package app.termx.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WatchedCommandTest {
    @Test fun parsesExplicitRegistrationsAndRejectsMultilineOrDuplicateEntries() {
        val result = WatchedCommandRegistry.parse("httpd=python -m http.server 8080\nworker=node app.js")
        assertTrue(result.isSuccess)
        assertEquals(2, result.getOrThrow().size)
        assertTrue(WatchedCommandRegistry.parse("httpd=python\nhttpd=busybox httpd").isFailure)
        assertTrue(WatchedCommandRegistry.parse("worker=echo ok\nrm -rf /").isSuccess.not())
        assertTrue(WatchedCommandRegistry.parse("bad name=command").isFailure)
    }

    @Test fun explicitStopPreventsFurtherAutomaticRestarts() {
        val state = WatchedCommandRuntime(startupGraceMillis = 10)
        assertTrue(state.enable(0))
        assertFalse(state.update(false, 9))
        assertFalse(state.update(false, 10)) // Schedule the first retry at +1 second.
        assertFalse(state.update(false, 1_009))
        assertTrue(state.update(false, 1_010))
        assertFalse(state.update(true, 1_011))
        state.disable()
        assertFalse(state.update(false, 50_000))
    }

    @Test fun restartAttemptsUseBackoffAndCrashLoopGuard() {
        val state = WatchedCommandRuntime(
            backoff = WatchedCommandBackoff(maxFailures = 2, windowMillis = 60_000),
            startupGraceMillis = 0,
        )
        state.enable(0)
        assertFalse(state.update(false, 0))
        assertTrue(state.update(false, 1_000))
        assertFalse(state.update(false, 1_000))
        assertTrue(state.update(false, 3_000))
        assertFalse(state.update(false, 3_000))
        assertTrue(state.gaveUp)
        assertFalse(state.update(false, 100_000))
    }
}
