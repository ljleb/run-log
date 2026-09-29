package dev.local.runlog;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;

import dev.local.runlog.core.RunClock;
import dev.local.runlog.core.RunMetrics;
import dev.local.runlog.core.TrackPoint;
import dev.local.runlog.data.RunRepository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public final class RunTrackingService extends Service implements LocationListener {
    public static final String ACTION_START = "dev.local.runlog.START";
    public static final String ACTION_STOP = "dev.local.runlog.STOP";
    private static final int NOTIFICATION_ID = 1001;
    private static final String CHANNEL_ID = "active_run";

    private static volatile boolean running;
    private static volatile long runStartElapsedRealtimeMs;
    private static final AtomicReference<Snapshot> SNAPSHOT = new AtomicReference<>(Snapshot.empty());

    private LocationManager locationManager;
    private RunRepository repository;
    private final List<TrackPoint> rawPoints = new ArrayList<>();
    private long startElapsedRealtimeMs;
    private long lastNotificationElapsedMs;

    @Override public void onCreate() {
        super.onCreate();
        locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        repository = new RunRepository(this);
        createNotificationChannel();
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRun();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            startRun();
            return START_NOT_STICKY;
        }
        return START_NOT_STICKY;
    }

    private void startRun() {
        if (running) return;
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            stopSelf();
            return;
        }
        if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            stopSelf();
            return;
        }

        try {
            repository.beginActive(System.currentTimeMillis());
        } catch (IOException e) {
            stopSelf();
            return;
        }

        rawPoints.clear();
        startElapsedRealtimeMs = SystemClock.elapsedRealtime();
        runStartElapsedRealtimeMs = startElapsedRealtimeMs;
        lastNotificationElapsedMs = 0L;
        running = true;
        SNAPSHOT.set(new Snapshot(0L, 0.0, 0.0, 0, true));
        startForeground(NOTIFICATION_ID, buildNotification(SNAPSHOT.get()));

        try {
            Location cached = locationManager.getLastKnownLocation(LocationManager.GPS_PROVIDER);
            if (cached != null && cached.hasAccuracy() && cached.getAccuracy() <= RunMetrics.MAX_ACCEPTED_ACCURACY_M) {
                long ageMs = Math.abs(SystemClock.elapsedRealtimeNanos() - cached.getElapsedRealtimeNanos()) / 1_000_000L;
                if (ageMs <= 10_000L) addLocation(cached, 0L);
            }
            locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    2_000L,
                    2.0f,
                    this,
                    Looper.getMainLooper());
        } catch (SecurityException e) {
            stopRun();
        }
    }

    @Override public void onLocationChanged(Location location) {
        if (!running) return;
        long elapsed = Math.max(0L, SystemClock.elapsedRealtime() - startElapsedRealtimeMs);
        addLocation(location, elapsed);
    }

    private void addLocation(Location location, long elapsedMs) {
        float accuracy = location.hasAccuracy() ? location.getAccuracy() : Float.POSITIVE_INFINITY;
        TrackPoint p = new TrackPoint(elapsedMs, location.getLatitude(), location.getLongitude(), accuracy);
        rawPoints.add(p);
        try {
            repository.appendActivePoint(p);
        } catch (IOException ignored) {
            // Keep the foreground run alive; finalization may still preserve earlier points.
        }
        RunMetrics.Result m = RunMetrics.analyze(rawPoints, elapsedMs);
        Snapshot snap = new Snapshot(elapsedMs, m.distanceM, m.averageSpeedKmh, rawPoints.size(), true);
        SNAPSHOT.set(snap);
        if (elapsedMs - lastNotificationElapsedMs >= 10_000L) {
            getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, buildNotification(snap));
            lastNotificationElapsedMs = elapsedMs;
        }
    }

    private void stopRun() {
        if (!running) {
            stopSelf();
            return;
        }
        long durationMs = Math.max(0L, SystemClock.elapsedRealtime() - startElapsedRealtimeMs);
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        try { repository.finalizeActive(durationMs, false); } catch (IOException ignored) {}
        running = false;
        Snapshot prior = SNAPSHOT.get();
        SNAPSHOT.set(new Snapshot(durationMs, prior.distanceM,
                RunClock.averageSpeedKmh(prior.distanceM, durationMs), prior.rawPoints, false));
        runStartElapsedRealtimeMs = 0L;
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    @Override public void onDestroy() {
        try { locationManager.removeUpdates(this); } catch (Exception ignored) {}
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) { return null; }

    private void createNotificationChannel() {
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "Active run",
                NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("Shown only while a run is being recorded");
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
    }

    private Notification buildNotification(Snapshot s) {
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent openPi = PendingIntent.getActivity(this, 1, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Intent stop = new Intent(this, RunTrackingService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle("Run recording")
                .setContentText(String.format(java.util.Locale.US, "%.2f km  •  %s",
                        s.distanceM / 1000.0, MainActivity.formatDuration(s.elapsedMs)))
                .setContentIntent(openPi)
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause, "Stop", stopPi).build())
                .build();
    }

    public static boolean isRunning() { return running; }

    public static Snapshot snapshot() {
        Snapshot stored = SNAPSHOT.get();
        if (!running || runStartElapsedRealtimeMs <= 0L) return stored;

        long elapsedMs = RunClock.elapsedMs(runStartElapsedRealtimeMs,
                SystemClock.elapsedRealtime(), stored.elapsedMs);
        return new Snapshot(elapsedMs, stored.distanceM,
                RunClock.averageSpeedKmh(stored.distanceM, elapsedMs),
                stored.rawPoints, true);
    }

    public static final class Snapshot {
        public final long elapsedMs;
        public final double distanceM;
        public final double averageSpeedKmh;
        public final int rawPoints;
        public final boolean running;

        Snapshot(long elapsedMs, double distanceM, double averageSpeedKmh, int rawPoints, boolean running) {
            this.elapsedMs = elapsedMs;
            this.distanceM = distanceM;
            this.averageSpeedKmh = averageSpeedKmh;
            this.rawPoints = rawPoints;
            this.running = running;
        }
        static Snapshot empty() { return new Snapshot(0L, 0.0, 0.0, 0, false); }
    }
}
