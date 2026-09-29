# RunLog — design specification

## Goal
A small, privacy-first Android running logger whose primary interaction is exactly two taps: **Start Run** and **Stop**. It measures the run from periodic GPS positions, keeps history on-device, derives comparable progress metrics, graphs them, and supports portable backup/restore.

## Non-goals
- Social features, accounts, leaderboards, badges, coaching, route sharing, ads, cloud sync.
- Heart-rate or wearable integration.
- Medical/aerobic-fitness diagnosis.
- Live maps. They add clutter without improving the requested measurement workflow.

## Interaction model

### Runs (home)
- Reverse-chronological list.
- Each row: date, distance, elapsed time, average pace.
- A large **Start Run** button remains at the bottom.
- Tapping a run opens details and 1 km splits.

### Active run
The normal app chrome disappears. The screen shows only:
- elapsed time,
- measured distance,
- current whole-run average speed,
- a very large central **Stop** button.

The phone may be locked immediately after starting. A foreground service continues GPS recording. The service supplies Android’s required foreground-service notification object. This prototype deliberately does not request notification permission, so on Android 13+ the notification may be absent from the notification drawer; reopening the app still shows the full-screen Stop control.

### Progress
A single simple line graph with a metric selector:
1. average speed,
2. total distance,
3. duration,
4. distance after exactly 10 minutes,
5. distance after exactly 20 minutes,
6. best 1 km.

The fixed-time distances are particularly useful here: they compare performance at the same elapsed time even while endurance-to-exhaustion is changing rapidly.

### Data
- Export backup.
- Import/merge backup.
- Delete all local data.
- Plain statement that the app has no Internet permission.
- Warning that backup files contain precise route coordinates.

## Location collection
- GPS/GNSS provider only (`LocationManager.GPS_PROVIDER`).
- Request approximately one update every 2 seconds and 2 metres.
- Raw observations are retained so metrics can be recomputed later.
- Uses a `location` foreground service. The service is started only from the visible Activity after precise-location permission has been granted.
- No background-location permission is requested.
- No Internet permission exists in the manifest.

Why 2 seconds: at ~8.9 km/h a runner moves ~4.9 m between samples, which is frequent enough to resolve pace changes while reducing the tendency of 1 Hz GPS noise to inflate path distance.

## Distance filtering
Derived distance uses a deliberately simple, testable pipeline:
1. reject malformed points;
2. reject reported horizontal accuracy worse than 30 m;
3. reject non-increasing timestamps;
4. reject segments implying >12 m/s (43.2 km/h), which are almost certainly GPS teleports for this app;
5. ignore sub-0.75 m segments as jitter while retaining the previous anchor;
6. sum haversine distances between accepted points.

The filter does **not** delete raw observations from storage/export.

## Derived metrics
- elapsed time
- accepted path distance
- average speed (km/h)
- average pace (min/km)
- distance at exactly 10:00 and 20:00, linearly interpolated between GPS samples
- best rolling 1 km time
- interpolated 1 km splits
- second-half speed change (%) as a simple pacing/fade indicator
- accepted vs raw GPS point count

No VO2max is calculated: GPS-only maximal runs do not justify presenting that as a precise physiological measurement.

## Persistence and failure handling
- Each completed run is an app-private JSON file.
- During a run, each raw GPS point is appended immediately to an app-private temporary CSV file. This means a UI crash does not erase the whole run.
- If the process/service is unexpectedly terminated, next launch recovers the run through its last stored GPS observation and marks it recovered.
- Run files are written atomically via a temporary file + rename.

## Backup format
One human-readable JSON document:
- `schemaVersion`
- export timestamp
- array of runs
  - stable UUID
  - wall-clock start time
  - elapsed duration
  - recovery flag
  - raw points `[elapsedMs, latitude, longitude, accuracyM]`

Import merges by UUID and skips duplicates. Android's Storage Access Framework chooses the destination/source, so no broad storage permission is needed.

## Privacy properties
- No `INTERNET` permission.
- No account.
- No analytics/telemetry SDK.
- No cloud APIs.
- No advertising ID.
- No background-location permission.
- Raw route data remains in app-private storage unless the user explicitly exports it.

## Testing strategy
The numerical core is Android-free and therefore ordinary JVM-testable. Tests cover:
- haversine known distances and zero distance;
- empty run behavior;
- 30 m accuracy rejection;
- impossible-speed GPS teleport rejection;
- out-of-order timestamps;
- tiny jitter suppression;
- exact synthetic constant-speed tracks;
- the user's ~8.87 km/h / 23-minute example;
- fixed 10/20-minute interpolation;
- 1 km split interpolation;
- best rolling 1 km;
- second-half comparison;
- raw-point preservation despite filtering.

`tools/CoreSelfTest.java` provides a dependency-free sanity test for the exact same production core classes in addition to the JUnit suite.

## Later improvements that do not require changing the backup schema
- better GPS smoothing/filtering after comparing against real routes;
- 500 m splits;
- uncertainty estimate based on reported GPS accuracy;
- optional manual note such as breathing cadence or perceived exertion;
- optional CSV export of summaries;
- pause/resume, if ever wanted.
