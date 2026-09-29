import dev.local.runlog.core.Geo;
import dev.local.runlog.core.RunClock;
import dev.local.runlog.core.RunMetrics;
import dev.local.runlog.core.TrackPoint;

import java.util.ArrayList;
import java.util.List;

/** No JUnit dependency: used to sanity-check the same production core in this source bundle. */
public final class CoreSelfTest {
    private static int checks = 0;
    public static void main(String[] args) {
        near(Geo.haversineMeters(0, 0, 0.001, 0), 111.195, 0.15, "latitude distance");
        near(Geo.haversineMeters(0, 0, 0, 0), 0, 1e-9, "zero distance");

        long duration = 23 * 60_000L;
        RunMetrics.Result user = RunMetrics.analyze(straight(8.87 / 3.6, duration), duration);
        near(user.distanceM, 3400.17, 3.0, "3.4 km synthetic distance");
        near(user.averageSpeedKmh, 8.87, 0.02, "synthetic average speed");
        near(user.paceSecPerKm, 405.86, 1.2, "synthetic pace");
        near(user.distanceAt10MinM, 1478.33, 3.0, "10 min distance");
        near(user.distanceAt20MinM, 2956.67, 3.0, "20 min distance");

        List<TrackPoint> bad = List.of(
                new TrackPoint(0, 0, 0, 4),
                new TrackPoint(2000, 0.01, 0, 4),
                new TrackPoint(4000, 0.00005, 0, 4));
        RunMetrics.Result filtered = RunMetrics.analyze(bad, 4000);
        eq(filtered.acceptedPointCount, 2, "teleport rejected");

        List<TrackPoint> poor = List.of(
                new TrackPoint(0, 0, 0, 4),
                new TrackPoint(2000, 0.00005, 0, 100),
                new TrackPoint(4000, 0.0001, 0, 4));
        eq(RunMetrics.analyze(poor, 4000).acceptedPointCount, 2, "poor accuracy rejected");

        List<TrackPoint> c = straight(2.5, 600_000L);
        RunMetrics.Result constant = RunMetrics.analyze(c, 600_000L);
        near(constant.best1KmSec, 400.0, 1.0, "best 1k");
        near(constant.oneKmSplitSeconds.get(0), 400.0, 1.0, "1k split");
        near(constant.secondHalfSpeedChangePct, 0.0, 0.2, "half change");

        eqLong(RunClock.elapsedMs(100_000L, 112_500L, 10_000L), 12_500L, "live clock advances");
        eqLong(RunClock.elapsedMs(100_000L, 110_000L, 15_000L), 15_000L, "live clock never regresses");
        eqLong(RunClock.elapsedMs(0L, 999_999L, 23_000L), 23_000L, "inactive clock preserves elapsed");
        near(RunClock.averageSpeedKmh(1000.0, 400_000L), 9.0, 0.0001, "live average speed");

        System.out.println("CoreSelfTest: " + checks + " checks passed");
    }

    static List<TrackPoint> straight(double speedMps, long durationMs) {
        List<TrackPoint> out = new ArrayList<>();
        for (long t = 0; t <= durationMs; t += 2000) {
            double m = speedMps * t / 1000.0;
            out.add(new TrackPoint(t, Math.toDegrees(m / Geo.EARTH_RADIUS_M), 0, 4));
        }
        return out;
    }
    static void near(Double actual, double expected, double tol, String name) {
        if (actual == null || Math.abs(actual - expected) > tol) fail(name + ": expected " + expected + " got " + actual);
        checks++;
    }
    static void near(double actual, double expected, double tol, String name) { near(Double.valueOf(actual), expected, tol, name); }
    static void eq(int actual, int expected, String name) { if (actual != expected) fail(name + ": expected " + expected + " got " + actual); checks++; }
    static void eqLong(long actual, long expected, String name) { if (actual != expected) fail(name + ": expected " + expected + " got " + actual); checks++; }
    static void fail(String s) { throw new AssertionError(s); }
}
