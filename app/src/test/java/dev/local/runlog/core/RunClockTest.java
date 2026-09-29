package dev.local.runlog.core;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public class RunClockTest {
    @Test public void elapsedAdvancesBetweenGpsFixes() {
        assertEquals(12_500L, RunClock.elapsedMs(100_000L, 112_500L, 10_000L));
    }

    @Test public void elapsedNeverMovesBackward() {
        assertEquals(15_000L, RunClock.elapsedMs(100_000L, 110_000L, 15_000L));
    }

    @Test public void noActiveStartKeepsKnownElapsed() {
        assertEquals(23_000L, RunClock.elapsedMs(0L, 999_999L, 23_000L));
    }

    @Test public void averageSpeedUsesLiveElapsedTime() {
        assertEquals(9.0, RunClock.averageSpeedKmh(1000.0, 400_000L), 0.0001);
    }
}
