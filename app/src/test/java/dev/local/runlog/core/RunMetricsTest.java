package dev.local.runlog.core;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.*;

public class RunMetricsTest {
    private static final float ACC = 4.0f;

    @Test public void emptyRunIsZero() {
        RunMetrics.Result r = RunMetrics.analyze(List.of(), 60_000);
        assertEquals(0.0, r.distanceM, 0.0);
        assertEquals(0.0, r.averageSpeedKmh, 0.0);
        assertTrue(Double.isInfinite(r.paceSecPerKm));
    }

    @Test public void rejectsPoorAccuracyPoint() {
        List<TrackPoint> p = List.of(
                new TrackPoint(0, 0, 0, ACC),
                new TrackPoint(2000, 0.00005, 0, 80),
                new TrackPoint(4000, 0.0001, 0, ACC));
        RunMetrics.Result r = RunMetrics.analyze(p, 4000);
        assertEquals(2, r.acceptedPointCount);
        assertEquals(11.12, r.distanceM, 0.2);
    }

    @Test public void rejectsGpsTeleportByImplausibleSpeed() {
        List<TrackPoint> p = List.of(
                new TrackPoint(0, 0, 0, ACC),
                new TrackPoint(2000, 0.01, 0, ACC),
                new TrackPoint(4000, 0.00005, 0, ACC));
        RunMetrics.Result r = RunMetrics.analyze(p, 4000);
        assertEquals(2, r.acceptedPointCount);
        assertEquals(5.56, r.distanceM, 0.2);
    }

    @Test public void ignoresOutOfOrderTimestamp() {
        List<TrackPoint> p = List.of(
                new TrackPoint(1000, 0, 0, ACC),
                new TrackPoint(900, 0.00002, 0, ACC),
                new TrackPoint(3000, 0.00004, 0, ACC));
        RunMetrics.Result r = RunMetrics.analyze(p, 3000);
        assertEquals(2, r.acceptedPointCount);
    }

    @Test public void straightRunAtUserExampleProducesAbout887Kmh() {
        double speedKmh = 8.87;
        long duration = 23 * 60_000L;
        List<TrackPoint> points = straightNorth(speedKmh / 3.6, duration, 2000);
        RunMetrics.Result r = RunMetrics.analyze(points, duration);
        assertEquals(3400.17, r.distanceM, 3.0);
        assertEquals(8.87, r.averageSpeedKmh, 0.02);
        assertEquals(23 * 60.0 / 3.40017, r.paceSecPerKm, 1.0);
    }

    @Test public void fixedTimeDistanceInterpolates() {
        long duration = 20 * 60_000L;
        List<TrackPoint> points = straightNorth(2.5, duration, 2000); // 9 km/h
        RunMetrics.Result r = RunMetrics.analyze(points, duration);
        assertNotNull(r.distanceAt10MinM);
        assertNotNull(r.distanceAt20MinM);
        assertEquals(1500.0, r.distanceAt10MinM, 3.0);
        assertEquals(3000.0, r.distanceAt20MinM, 3.0);
    }

    @Test public void oneKmSplitsAreAboutExpectedTime() {
        List<TrackPoint> points = straightNorth(2.5, 1_000_000L, 2000);
        RunMetrics.Result r = RunMetrics.analyze(points, 1_000_000L);
        assertTrue(r.oneKmSplitSeconds.size() >= 2);
        assertEquals(400.0, r.oneKmSplitSeconds.get(0), 1.0);
        assertEquals(400.0, r.oneKmSplitSeconds.get(1), 1.0);
    }

    @Test public void best1kOnConstantSpeedEqualsSplit() {
        List<TrackPoint> points = straightNorth(2.5, 600_000L, 2000);
        RunMetrics.Result r = RunMetrics.analyze(points, 600_000L);
        assertNotNull(r.best1KmSec);
        assertEquals(400.0, r.best1KmSec, 1.0);
    }

    @Test public void secondHalfChangeNearZeroAtConstantSpeed() {
        List<TrackPoint> points = straightNorth(2.5, 600_000L, 2000);
        RunMetrics.Result r = RunMetrics.analyze(points, 600_000L);
        assertNotNull(r.secondHalfSpeedChangePct);
        assertEquals(0.0, r.secondHalfSpeedChangePct, 0.2);
    }

    @Test public void tinyJitterDoesNotAccumulate() {
        List<TrackPoint> p = new ArrayList<>();
        p.add(new TrackPoint(0, 0, 0, ACC));
        // ~0.22m oscillation, less than deadband.
        for (int i = 1; i <= 100; i++) {
            double lat = (i % 2 == 0 ? 0.000002 : 0.0);
            p.add(new TrackPoint(i * 2000L, lat, 0, ACC));
        }
        RunMetrics.Result r = RunMetrics.analyze(p, 200_000L);
        assertEquals(0.0, r.distanceM, 0.01);
    }

    @Test public void rawPointCountPreservedDespiteFiltering() {
        List<TrackPoint> p = List.of(
                new TrackPoint(0, 0, 0, ACC),
                new TrackPoint(2000, 0.01, 0, ACC),
                new TrackPoint(4000, 0.00002, 0, ACC));
        RunMetrics.Result r = RunMetrics.analyze(p, 4000);
        assertEquals(3, r.rawPointCount);
        assertEquals(2, r.acceptedPointCount);
    }


    @Test public void shortRunHasNoFixedTwentyMinuteMetric() {
        RunMetrics.Result r = RunMetrics.analyze(straightNorth(2.5, 5 * 60_000L, 2000), 5 * 60_000L);
        assertNull(r.distanceAt10MinM);
        assertNull(r.distanceAt20MinM);
    }

    @Test public void invalidCoordinateIsRejected() {
        List<TrackPoint> p = List.of(
                new TrackPoint(0, 0, 0, ACC),
                new TrackPoint(2000, 91, 0, ACC),
                new TrackPoint(4000, 0.00005, 0, ACC));
        RunMetrics.Result r = RunMetrics.analyze(p, 4000);
        assertEquals(2, r.acceptedPointCount);
    }

    @Test public void secondHalfFasterProducesPositiveChange() {
        List<TrackPoint> p = new ArrayList<>();
        long half = 300_000L;
        for (long t = 0; t <= half; t += 2000) {
            double m = 2.0 * t / 1000.0;
            p.add(new TrackPoint(t, Math.toDegrees(m / Geo.EARTH_RADIUS_M), 0, ACC));
        }
        double firstHalfDistance = 2.0 * half / 1000.0;
        for (long t = half + 2000; t <= 600_000L; t += 2000) {
            double m = firstHalfDistance + 3.0 * (t - half) / 1000.0;
            p.add(new TrackPoint(t, Math.toDegrees(m / Geo.EARTH_RADIUS_M), 0, ACC));
        }
        RunMetrics.Result r = RunMetrics.analyze(p, 600_000L);
        assertNotNull(r.secondHalfSpeedChangePct);
        assertEquals(50.0, r.secondHalfSpeedChangePct, 1.0);
    }

    @Test public void distanceAtElapsedClampsAfterTrackEnd() {
        List<TrackPoint> p = straightNorth(2.5, 60_000L, 2000);
        List<RunMetrics.Sample> track = RunMetrics.filterAndAccumulate(p);
        double end = track.get(track.size() - 1).cumulativeDistanceM;
        assertEquals(end, RunMetrics.distanceAtElapsed(track, 600_000L), 0.001);
    }

    private static List<TrackPoint> straightNorth(double speedMps, long durationMs, long stepMs) {
        List<TrackPoint> result = new ArrayList<>();
        for (long t = 0; t <= durationMs; t += stepMs) {
            double distanceM = speedMps * t / 1000.0;
            double latDeg = Math.toDegrees(distanceM / Geo.EARTH_RADIUS_M);
            result.add(new TrackPoint(t, latDeg, 0.0, ACC));
        }
        if (result.get(result.size() - 1).elapsedMs != durationMs) {
            double distanceM = speedMps * durationMs / 1000.0;
            result.add(new TrackPoint(durationMs, Math.toDegrees(distanceM / Geo.EARTH_RADIUS_M), 0.0, ACC));
        }
        return result;
    }
}
