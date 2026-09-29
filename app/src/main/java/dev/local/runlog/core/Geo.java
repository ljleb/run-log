package dev.local.runlog.core;

/** Pure geodesic helpers; deliberately Android-free so they are easy to unit test. */
public final class Geo {
    private Geo() {}

    // IUGG mean Earth radius. For a few-kilometre run, spherical-vs-ellipsoidal error is negligible.
    public static final double EARTH_RADIUS_M = 6_371_008.8;

    public static double haversineMeters(double lat1Deg, double lon1Deg,
                                         double lat2Deg, double lon2Deg) {
        double lat1 = Math.toRadians(lat1Deg);
        double lat2 = Math.toRadians(lat2Deg);
        double dLat = lat2 - lat1;
        double dLon = Math.toRadians(lon2Deg - lon1Deg);
        double sinLat = Math.sin(dLat / 2.0);
        double sinLon = Math.sin(dLon / 2.0);
        double a = sinLat * sinLat + Math.cos(lat1) * Math.cos(lat2) * sinLon * sinLon;
        double c = 2.0 * Math.atan2(Math.sqrt(a), Math.sqrt(Math.max(0.0, 1.0 - a)));
        return EARTH_RADIUS_M * c;
    }
}
