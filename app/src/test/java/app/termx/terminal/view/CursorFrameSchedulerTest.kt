package app.termx.terminal.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CursorFrameSchedulerTest {
    @Test
    fun idleCursorWithExpiredTrailDoesNotRequestAnotherFrame() {
        assertFalse(CursorFrameScheduler.shouldScheduleNextFrame(false, false))
    }

    @Test
    fun movingCursorRequestsAnotherFrame() {
        assertTrue(CursorFrameScheduler.shouldScheduleNextFrame(true, false))
    }

    @Test
    fun visibleTrailRequestsFramesUntilItFades() {
        assertTrue(CursorFrameScheduler.shouldScheduleNextFrame(false, true))
    }

    @Test
    fun expiredTrailDoesNotKeepFrameLoopAlive() {
        val sampleTimes = longArrayOf(10L, 20L, 30L)

        assertFalse(CursorFrameScheduler.hasUnexpiredTrail(100L, sampleTimes, 0, 3, 25L))
        assertFalse(CursorFrameScheduler.shouldScheduleNextFrame(
            false,
            CursorFrameScheduler.hasUnexpiredTrail(100L, sampleTimes, 0, 3, 25L),
        ))
    }

    @Test
    fun recentTrailSampleRemainsAnimated() {
        val sampleTimes = longArrayOf(10L, 20L, 95L)

        assertTrue(CursorFrameScheduler.hasUnexpiredTrail(100L, sampleTimes, 0, 3, 25L))
    }
}
