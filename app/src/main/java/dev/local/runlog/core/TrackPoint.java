package dev.local.runlog.core;

import java.util.Locale;
import java.util.Objects;

/** A raw location observation relative to the instant the run started. */
public final class TrackPoint {
    public final long elapsedMs;
    public final double latitudeDeg;
    public final double longitudeDeg;
    public final float accuracyM;

    public TrackPoint(long elapsedMs, double latitudeDeg, double longitudeDeg, float accuracyM) {
        this.elapsedMs = elapsedMs;
        this.latitudeDeg = latitudeDeg;
        this.longitudeDeg = longitudeDeg;
        this.accuracyM = accuracyM;
    }

    public boolean isFinite() {
        return elapsedMs >= 0
                && Double.isFinite(latitudeDeg)
                && Double.isFinite(longitudeDeg)
                && Float.isFinite(accuracyM)
                && latitudeDeg >= -90.0 && latitudeDeg <= 90.0
                && longitudeDeg >= -180.0 && longitudeDeg <= 180.0;
    }

    @Override public boolean equals(Object obj) {
        if (!(obj instanceof TrackPoint other)) return false;
        return elapsedMs == other.elapsedMs
                && Double.compare(latitudeDeg, other.latitudeDeg) == 0
                && Double.compare(longitudeDeg, other.longitudeDeg) == 0
                && Float.compare(accuracyM, other.accuracyM) == 0;
    }

    @Override public int hashCode() {
        return Objects.hash(elapsedMs, latitudeDeg, longitudeDeg, accuracyM);
    }

    @Override public String toString() {
        return String.format(Locale.US, "TrackPoint{%d, %.7f, %.7f, ±%.1fm}",
                elapsedMs, latitudeDeg, longitudeDeg, accuracyM);
    }
}
