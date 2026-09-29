package dev.local.runlog.core;

import org.junit.Test;
import static org.junit.Assert.*;

public class GeoTest {
    @Test public void identicalPointsAreZero() {
        assertEquals(0.0, Geo.haversineMeters(45, -73, 45, -73), 1e-9);
    }

    @Test public void oneThousandthDegreeLatitudeIsAbout111m() {
        assertEquals(111.195, Geo.haversineMeters(0, 0, 0.001, 0), 0.15);
    }

    @Test public void equatorOneDegreeLongitudeIsAbout111195m() {
        assertEquals(111195.0, Geo.haversineMeters(0, 0, 0, 1), 50.0);
    }
}
