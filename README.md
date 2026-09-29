# RunLog

A deliberately small, local-only Android running logger.

## What is implemented
- Start/stop run flow with a stripped-down active-run screen.
- GPS sampling via the Android GPS provider about every 2 seconds / 2 metres.
- Android foreground location service so screen-off recording can continue.
- Run history and detail view.
- Distance, speed, pace, 10/20-minute distance, best 1 km, 1 km splits, and second-half speed change.
- Progress graph with selectable metric.
- JSON backup export and import/merge through Android's document picker.
- Crash/interruption recovery through an append-only active-run file.
- No Internet permission, account, telemetry, ads, or cloud dependency.
- Pure-Java numerical core plus JVM unit tests.

See `DESIGN.md` for the full design rationale.

## Build
Current project settings:
- compileSdk 36 (Android 16)
- targetSdk 36
- minSdk 31
- Android Gradle Plugin 9.4.0
- Java 17

Open the directory in a current Android Studio installation, let it install Android SDK Platform 36 if necessary, and build/install the `app` module. AGP 9.4.0 uses Gradle 9.6.0; if Android Studio asks to create/use a Gradle wrapper, select Gradle 9.6.0.

The source bundle intentionally does not contain a generated Gradle wrapper binary.

## Tests
In Android Studio / Gradle:

    gradle :app:test

The important numerical code (`Geo`, `RunMetrics`, `TrackPoint`) has no Android imports.

A dependency-free sanity runner is also included. From the repository root on any JDK 17+ machine:

    mkdir -p out
    javac -d out \
      app/src/main/java/dev/local/runlog/core/TrackPoint.java \
      app/src/main/java/dev/local/runlog/core/Geo.java \
      app/src/main/java/dev/local/runlog/core/RunMetrics.java \
      tools/CoreSelfTest.java
    java -cp out CoreSelfTest

## Permission behavior
Android 12+ allows users to grant approximate rather than precise location. RunLog requests coarse + fine together, but refuses to start a run without the precise grant because approximate location is unsuitable for kilometre-scale route measurement.

RunLog deliberately does not request `ACCESS_BACKGROUND_LOCATION`. The location foreground service is started while the Activity is visible, then continues during the active run.

Android requires every foreground service to supply a notification. RunLog creates one with a Stop action, but deliberately does not request notification permission. On Android 13+ the service can still run without that permission; the foreground-service notice remains visible through the system task manager, while the notification-drawer entry may be hidden.

## Accuracy note
Phone GPS is not laboratory instrumentation. Buildings, trees, weather, satellite geometry, and device hardware affect results. The app keeps raw locations in every run so filtering can be improved later without throwing away the underlying observations.
