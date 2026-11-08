package app.termx.terminal.view;

/** Pure frame-scheduling policy for smooth cursor and trail animations. */
final class CursorFrameScheduler {

    private CursorFrameScheduler() {}

    static boolean shouldScheduleNextFrame(boolean cursorAnimating, boolean trailAnimating) {
        return cursorAnimating || trailAnimating;
    }

    static boolean hasUnexpiredTrail(long now, long[] sampleTimes, int head, int count, long durationNanos) {
        if (count <= 0 || durationNanos <= 0 || sampleTimes.length == 0) return false;
        for (int i = 0; i < count; i++) {
            int idx = (head - 1 - i + sampleTimes.length) % sampleTimes.length;
            if (now - sampleTimes[idx] < durationNanos) return true;
        }
        return false;
    }
}
