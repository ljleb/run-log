package dev.local.runlog.core;

/** Pure helpers for deriving live run timing values from a monotonic clock. */
public final class RunClock {
    private RunClock() {}

    /**
     * Returns live elapsed time without ever moving backwards from a previously
     * observed elapsed value. Inputs are monotonic-clock milliseconds.
     */
    public static long elapsedMs(long startElapsedRealtimeMs, long nowElapsedRealtimeMs,
                                 long previouslyObservedElapsedMs) {
        long previous = Math.max(0L, previouslyObservedElapsedMs);
        if (startElapsedRealtimeMs <= 0L) return previous;
        long live = Math.max(0L, nowElapsedRealtimeMs - startElapsedRealtimeMs);
        return Math.max(previous, live);
    }

    public static double averageSpeedKmh(double distanceM, long elapsedMs) {
        if (!Double.isFinite(distanceM) || distanceM <= 0.0 || elapsedMs <= 0L) return 0.0;
        return (distanceM / 1000.0) / (elapsedMs / 3_600_000.0);
    }
}
