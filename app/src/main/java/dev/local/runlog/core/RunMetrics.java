package dev.local.runlog.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * All important run computations live here and have no Android dependencies.
 * Raw points are retained; filtering only affects derived measurements.
 */
public final class RunMetrics {
    private RunMetrics() {}

    public static final float MAX_ACCEPTED_ACCURACY_M = 30.0f;
    public static final double MAX_PLAUSIBLE_RUNNING_SPEED_MPS = 12.0; // 43.2 km/h
    public static final double MIN_SEGMENT_M = 0.75;

    public static final class Sample {
        public final TrackPoint point;
        public final double cumulativeDistanceM;

        Sample(TrackPoint point, double cumulativeDistanceM) {
            this.point = point;
            this.cumulativeDistanceM = cumulativeDistanceM;
        }
    }

    public static final class Result {
        public final long durationMs;
        public final double distanceM;
        public final double averageSpeedKmh;
        public final double paceSecPerKm;
        public final Double distanceAt10MinM;
        public final Double distanceAt20MinM;
        public final Double best1KmSec;
        public final Double secondHalfSpeedChangePct;
        public final List<Double> oneKmSplitSeconds;
        public final List<Sample> acceptedTrack;
        public final int rawPointCount;
        public final int acceptedPointCount;

        Result(long durationMs,
               double distanceM,
               double averageSpeedKmh,
               double paceSecPerKm,
               Double distanceAt10MinM,
               Double distanceAt20MinM,
               Double best1KmSec,
               Double secondHalfSpeedChangePct,
               List<Double> oneKmSplitSeconds,
               List<Sample> acceptedTrack,
               int rawPointCount) {
            this.durationMs = durationMs;
            this.distanceM = distanceM;
            this.averageSpeedKmh = averageSpeedKmh;
            this.paceSecPerKm = paceSecPerKm;
            this.distanceAt10MinM = distanceAt10MinM;
            this.distanceAt20MinM = distanceAt20MinM;
            this.best1KmSec = best1KmSec;
            this.secondHalfSpeedChangePct = secondHalfSpeedChangePct;
            this.oneKmSplitSeconds = Collections.unmodifiableList(oneKmSplitSeconds);
            this.acceptedTrack = Collections.unmodifiableList(acceptedTrack);
            this.rawPointCount = rawPointCount;
            this.acceptedPointCount = acceptedTrack.size();
        }
    }

    public static Result analyze(List<TrackPoint> rawPoints, long durationMs) {
        long safeDurationMs = Math.max(0L, durationMs);
        List<Sample> track = filterAndAccumulate(rawPoints);
        double distanceM = track.isEmpty() ? 0.0 : track.get(track.size() - 1).cumulativeDistanceM;
        double durationSec = safeDurationMs / 1000.0;
        double speedKmh = durationSec > 0.0 ? (distanceM / durationSec) * 3.6 : 0.0;
        double paceSecPerKm = distanceM > 0.0 ? durationSec / (distanceM / 1000.0) : Double.POSITIVE_INFINITY;

        Double d10 = safeDurationMs >= 10 * 60_000L ? distanceAtElapsed(track, 10 * 60_000L) : null;
        Double d20 = safeDurationMs >= 20 * 60_000L ? distanceAtElapsed(track, 20 * 60_000L) : null;
        Double best1k = distanceM >= 1000.0 ? bestWindowSeconds(track, 1000.0) : null;
        Double halfChange = secondHalfSpeedChange(track, safeDurationMs, distanceM);
        List<Double> splits = splitSeconds(track, 1000.0);

        return new Result(safeDurationMs, distanceM, speedKmh, paceSecPerKm,
                d10, d20, best1k, halfChange, splits, track,
                rawPoints == null ? 0 : rawPoints.size());
    }

    public static List<Sample> filterAndAccumulate(List<TrackPoint> rawPoints) {
        List<Sample> out = new ArrayList<>();
        if (rawPoints == null || rawPoints.isEmpty()) return out;

        TrackPoint last = null;
        double cumulative = 0.0;
        for (TrackPoint p : rawPoints) {
            if (!isIndividuallyUsable(p)) continue;
            if (last == null) {
                out.add(new Sample(p, 0.0));
                last = p;
                continue;
            }
            if (p.elapsedMs <= last.elapsedMs) continue;
            double distance = Geo.haversineMeters(last.latitudeDeg, last.longitudeDeg,
                    p.latitudeDeg, p.longitudeDeg);
            double dtSec = (p.elapsedMs - last.elapsedMs) / 1000.0;
            double speedMps = distance / dtSec;
            if (!Double.isFinite(speedMps) || speedMps > MAX_PLAUSIBLE_RUNNING_SPEED_MPS) {
                continue; // typical GPS teleport
            }
            if (distance < MIN_SEGMENT_M) {
                continue; // tiny jitter; keep the old anchor so real movement can accumulate
            }
            cumulative += distance;
            out.add(new Sample(p, cumulative));
            last = p;
        }
        return out;
    }

    private static boolean isIndividuallyUsable(TrackPoint p) {
        return p != null && p.isFinite() && p.accuracyM >= 0.0f && p.accuracyM <= MAX_ACCEPTED_ACCURACY_M;
    }

    public static double distanceAtElapsed(List<Sample> track, long elapsedMs) {
        if (track == null || track.isEmpty() || elapsedMs <= track.get(0).point.elapsedMs) return 0.0;
        Sample last = track.get(track.size() - 1);
        if (elapsedMs >= last.point.elapsedMs) return last.cumulativeDistanceM;

        for (int i = 1; i < track.size(); i++) {
            Sample b = track.get(i);
            if (elapsedMs <= b.point.elapsedMs) {
                Sample a = track.get(i - 1);
                long dt = b.point.elapsedMs - a.point.elapsedMs;
                if (dt <= 0) return a.cumulativeDistanceM;
                double f = (elapsedMs - a.point.elapsedMs) / (double) dt;
                return lerp(a.cumulativeDistanceM, b.cumulativeDistanceM, clamp01(f));
            }
        }
        return last.cumulativeDistanceM;
    }

    public static double elapsedAtDistanceMs(List<Sample> track, double targetDistanceM) {
        if (track == null || track.isEmpty()) return Double.NaN;
        if (targetDistanceM <= 0.0) return track.get(0).point.elapsedMs;
        Sample last = track.get(track.size() - 1);
        if (targetDistanceM > last.cumulativeDistanceM) return Double.NaN;

        for (int i = 1; i < track.size(); i++) {
            Sample b = track.get(i);
            if (targetDistanceM <= b.cumulativeDistanceM) {
                Sample a = track.get(i - 1);
                double dd = b.cumulativeDistanceM - a.cumulativeDistanceM;
                if (dd <= 0.0) return a.point.elapsedMs;
                double f = (targetDistanceM - a.cumulativeDistanceM) / dd;
                return lerp(a.point.elapsedMs, b.point.elapsedMs, clamp01(f));
            }
        }
        return last.point.elapsedMs;
    }

    public static List<Double> splitSeconds(List<Sample> track, double splitDistanceM) {
        List<Double> result = new ArrayList<>();
        if (track == null || track.size() < 2 || splitDistanceM <= 0.0) return result;
        double total = track.get(track.size() - 1).cumulativeDistanceM;
        double previousTimeMs = elapsedAtDistanceMs(track, 0.0);
        for (double target = splitDistanceM; target <= total + 1e-9; target += splitDistanceM) {
            double t = elapsedAtDistanceMs(track, target);
            if (!Double.isFinite(t)) break;
            result.add((t - previousTimeMs) / 1000.0);
            previousTimeMs = t;
        }
        return result;
    }

    public static Double bestWindowSeconds(List<Sample> track, double windowDistanceM) {
        if (track == null || track.size() < 2 || windowDistanceM <= 0.0) return null;
        double total = track.get(track.size() - 1).cumulativeDistanceM;
        if (total < windowDistanceM) return null;

        double bestMs = Double.POSITIVE_INFINITY;
        for (Sample start : track) {
            double target = start.cumulativeDistanceM + windowDistanceM;
            if (target > total) break;
            double endMs = elapsedAtDistanceMs(track, target);
            if (Double.isFinite(endMs)) {
                bestMs = Math.min(bestMs, endMs - start.point.elapsedMs);
            }
        }
        return Double.isFinite(bestMs) ? bestMs / 1000.0 : null;
    }

    private static Double secondHalfSpeedChange(List<Sample> track, long durationMs, double totalDistanceM) {
        if (durationMs < 20_000L || totalDistanceM <= 0.0 || track == null || track.size() < 2) return null;
        long halfMs = durationMs / 2L;
        double firstM = distanceAtElapsed(track, halfMs);
        double secondM = Math.max(0.0, totalDistanceM - firstM);
        double halfSec = halfMs / 1000.0;
        double remainingSec = (durationMs - halfMs) / 1000.0;
        if (firstM <= 0.0 || halfSec <= 0.0 || remainingSec <= 0.0) return null;
        double firstSpeed = firstM / halfSec;
        double secondSpeed = secondM / remainingSec;
        return ((secondSpeed / firstSpeed) - 1.0) * 100.0;
    }

    private static double clamp01(double x) { return Math.max(0.0, Math.min(1.0, x)); }
    private static double lerp(double a, double b, double f) { return a + (b - a) * f; }
}
