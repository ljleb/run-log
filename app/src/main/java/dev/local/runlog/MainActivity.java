package dev.local.runlog;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.res.Configuration;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import dev.local.runlog.data.RunRecord;
import dev.local.runlog.data.RunRepository;
import dev.local.runlog.ui.ProgressChartView;
import dev.local.runlog.ui.RunListAdapter;

import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class MainActivity extends Activity {
    private static final int REQ_LOCATION = 10;
    private static final int REQ_EXPORT = 20;
    private static final int REQ_IMPORT = 21;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private RunRepository repository;
    private LinearLayout root;
    private TextView runningTime;
    private TextView runningDistance;
    private TextView runningSpeed;
    private boolean showingRunScreen;
    private long startRequestedElapsedMs;
    private int currentPage;
    private int progressMetricIndex;

    private static final String STATE_PAGE = "page";
    private static final String STATE_PROGRESS_METRIC = "progressMetric";

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (RunTrackingService.isRunning()) {
                // Once the service confirms the run started, the pending-start grace period
                // is no longer needed. Keep refreshing even between GPS fixes so elapsed
                // time visibly advances.
                startRequestedElapsedMs = 0L;
                if (!showingRunScreen) showRunning();
                updateRunningStats();
            } else if (showingRunScreen) {
                // startForegroundService() is asynchronous. Give it a short window to
                // initialize before deciding that a run has ended/failed to start.
                boolean startStillPending = startRequestedElapsedMs != 0L
                        && android.os.SystemClock.elapsedRealtime() - startRequestedElapsedMs <= 2500L;
                if (!startStillPending) {
                    showingRunScreen = false;
                    startRequestedElapsedMs = 0L;
                    showRuns();
                }
            }

            // The old implementation only scheduled another tick while the service was
            // already running. That meant the ticker normally died on app launch, so a
            // later Start/Stop could neither update the timer nor navigate back.
            handler.postDelayed(this, 250L);
        }
    };

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        repository = new RunRepository(this);
        if (savedInstanceState != null) {
            currentPage = savedInstanceState.getInt(STATE_PAGE, 0);
            progressMetricIndex = savedInstanceState.getInt(STATE_PROGRESS_METRIC, 0);
        }
        if (!RunTrackingService.isRunning() && repository.hasActiveRun()) {
            try {
                repository.recoverInterruptedRun();
                Toast.makeText(this, "Recovered an interrupted run up to its last GPS point.", Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        }
        if (RunTrackingService.isRunning()) {
            showRunning();
        } else if (currentPage == 1) {
            showProgress();
        } else if (currentPage == 2) {
            showData();
        } else {
            showRuns();
        }
    }

    @Override protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(STATE_PAGE, currentPage);
        outState.putInt(STATE_PROGRESS_METRIC, progressMetricIndex);
    }

    @Override protected void onResume() {
        super.onResume();
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    @Override protected void onPause() {
        handler.removeCallbacks(ticker);
        super.onPause();
    }

    private void showRuns() {
        currentPage = 0;
        showingRunScreen = false;
        List<RunRecord> runs = repository.listRuns();
        LinearLayout content = vertical(0);

        TextView intro = text("Runs", 28, true, 0xFF111111);
        intro.setPadding(dp(18), dp(18), dp(18), dp(10));
        content.addView(intro);

        if (runs.isEmpty()) {
            TextView empty = text("No runs yet. Press Start Run when you're ready.", 16, false, 0xFF666666);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(28), dp(80), dp(28), dp(30));
            content.addView(empty, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        } else {
            ListView list = new ListView(this);
            list.setDividerHeight(dp(1));
            RunListAdapter adapter = new RunListAdapter(this);
            adapter.setRuns(runs);
            list.setAdapter(adapter);
            list.setOnItemClickListener((parent, view, position, id) -> showRunDetail(adapter.getItem(position)));
            content.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        }

        Button start = primaryButton("START RUN");
        start.setTextSize(18);
        start.setMinHeight(dp(62));
        start.setOnClickListener(v -> requestStartRun());
        LinearLayout.LayoutParams startLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        startLp.setMargins(dp(18), dp(12), dp(18), dp(14));
        content.addView(start, startLp);
        showWithBottomNav(content, 0);
    }

    private void requestStartRun() {
        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            startRunService();
            return;
        }
        requestPermissions(new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION}, REQ_LOCATION);
    }

    @Override public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_LOCATION) {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                startRunService();
            } else if (checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                new AlertDialog.Builder(this)
                        .setTitle("Precise location required")
                        .setMessage("Approximate location is far too coarse for measuring a running route. RunLog stores GPS only on this phone and has no Internet permission.")
                        .setPositiveButton("OK", null)
                        .show();
            }
        }
    }

    private void startRunService() {
        android.location.LocationManager lm = (android.location.LocationManager) getSystemService(LOCATION_SERVICE);
        if (!lm.isProviderEnabled(android.location.LocationManager.GPS_PROVIDER)) {
            new AlertDialog.Builder(this)
                    .setTitle("Turn on location")
                    .setMessage("GPS must be enabled to record a run.")
                    .setPositiveButton("Open settings", (d, w) -> startActivity(new Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)))
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        Intent i = new Intent(this, RunTrackingService.class).setAction(RunTrackingService.ACTION_START);
        startRequestedElapsedMs = android.os.SystemClock.elapsedRealtime();
        startForegroundService(i);
        showRunning();
        // Ensure the pending start is actively observed even if the activity's first
        // onResume tick happened before the user pressed Start.
        handler.removeCallbacks(ticker);
        handler.post(ticker);
    }

    private void showRunning() {
        showingRunScreen = true;
        LinearLayout screen = vertical(Gravity.CENTER_HORIZONTAL);
        screen.setGravity(Gravity.CENTER);
        screen.setPadding(dp(24), dp(24), dp(24), dp(24));

        runningTime = text("00:00", 48, true, 0xFF111111);
        runningTime.setGravity(Gravity.CENTER);
        runningDistance = text("0.00 km", 26, false, 0xFF333333);
        runningDistance.setGravity(Gravity.CENTER);
        runningDistance.setPadding(0, dp(4), 0, 0);
        runningSpeed = text("0.0 km/h", 16, false, 0xFF777777);
        runningSpeed.setGravity(Gravity.CENTER);
        runningSpeed.setPadding(0, dp(4), 0, dp(32));

        Button stop = primaryButton("STOP");
        stop.setTextSize(24);
        LinearLayout.LayoutParams stopLp = new LinearLayout.LayoutParams(dp(190), dp(190));
        stop.setOnClickListener(v -> {
            stop.setEnabled(false);
            Intent i = new Intent(this, RunTrackingService.class).setAction(RunTrackingService.ACTION_STOP);
            startService(i);
        });

        screen.addView(runningTime);
        screen.addView(runningDistance);
        screen.addView(runningSpeed);
        screen.addView(stop, stopLp);
        setRoot(screen);
        if (RunTrackingService.isRunning()) {
            updateRunningStats();
        }
    }

    private void updateRunningStats() {
        if (runningTime == null) return;
        RunTrackingService.Snapshot s = RunTrackingService.snapshot();
        runningTime.setText(formatDuration(s.elapsedMs));
        runningDistance.setText(String.format(Locale.US, "%.2f km", s.distanceM / 1000.0));
        runningSpeed.setText(String.format(Locale.US, "%.1f km/h", s.averageSpeedKmh));
    }

    private void showRunDetail(RunRecord run) {
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical(0);
        body.setPadding(dp(20), dp(18), dp(20), dp(30));
        body.addView(text(String.format(Locale.US, "%.2f km", run.metrics.distanceM / 1000.0), 34, true, 0xFF111111));
        body.addView(text(new SimpleDateFormat("EEEE, MMMM d • h:mm a", Locale.getDefault()).format(new Date(run.startedAtEpochMs)), 15, false, 0xFF666666));
        body.addView(stat("Time", formatDuration(run.durationMs)));
        body.addView(stat("Average pace", Double.isFinite(run.metrics.paceSecPerKm) ? formatPace(run.metrics.paceSecPerKm) + " /km" : "—"));
        body.addView(stat("Average speed", String.format(Locale.US, "%.2f km/h", run.metrics.averageSpeedKmh)));
        body.addView(stat("10-minute distance", run.metrics.distanceAt10MinM == null ? "—" : String.format(Locale.US, "%.2f km", run.metrics.distanceAt10MinM / 1000.0)));
        body.addView(stat("20-minute distance", run.metrics.distanceAt20MinM == null ? "—" : String.format(Locale.US, "%.2f km", run.metrics.distanceAt20MinM / 1000.0)));
        body.addView(stat("Best 1 km", run.metrics.best1KmSec == null ? "—" : formatPace(run.metrics.best1KmSec)));
        body.addView(stat("Second-half speed change", run.metrics.secondHalfSpeedChangePct == null ? "—" : String.format(Locale.US, "%+.1f%%", run.metrics.secondHalfSpeedChangePct)));
        body.addView(stat("GPS points", run.metrics.acceptedPointCount + " accepted / " + run.metrics.rawPointCount + " raw"));

        if (!run.metrics.oneKmSplitSeconds.isEmpty()) {
            TextView splitHeader = text("1 km splits", 20, true, 0xFF222222);
            splitHeader.setPadding(0, dp(24), 0, dp(8));
            body.addView(splitHeader);
            for (int i = 0; i < run.metrics.oneKmSplitSeconds.size(); i++) {
                body.addView(text((i + 1) + " km   " + formatPace(run.metrics.oneKmSplitSeconds.get(i)), 16, false, 0xFF333333));
            }
        }

        Button delete = secondaryButton("Delete this run");
        delete.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete run?")
                .setMessage("This permanently deletes the run and its GPS points from this phone.")
                .setPositiveButton("Delete", (d, w) -> { repository.deleteRun(run.id); showRuns(); })
                .setNegativeButton("Cancel", null).show());
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        dlp.setMargins(0, dp(30), 0, 0);
        body.addView(delete, dlp);
        scroll.addView(body);
        showWithBottomNav(scroll, 0);
    }

    private void showProgress() {
        currentPage = 1;
        List<RunRecord> runs = repository.listRuns();
        boolean landscape = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;

        LinearLayout content = vertical(0);
        int outerPadding = landscape ? dp(12) : dp(18);
        content.setPadding(outerPadding, landscape ? dp(8) : dp(18), outerPadding, landscape ? dp(6) : dp(18));

        String[] labels = {"Average speed", "Total distance", "Duration", "10-minute distance", "20-minute distance", "Best 1 km time (lower is better)"};
        Spinner spinner = new Spinner(this);
        spinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, labels));

        if (landscape) {
            // Keep controls in a compact header so the graph receives almost all of the
            // limited vertical space in landscape.
            LinearLayout header = new LinearLayout(this);
            header.setOrientation(LinearLayout.HORIZONTAL);
            header.setGravity(Gravity.CENTER_VERTICAL);

            TextView title = text("Progress", 24, true, 0xFF111111);
            header.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            slp.setMargins(dp(18), 0, 0, 0);
            header.addView(spinner, slp);
            content.addView(header, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            content.addView(text("Progress", 28, true, 0xFF111111));
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.setMargins(0, dp(12), 0, dp(8));
            content.addView(spinner, slp);
        }

        ProgressChartView chart = new ProgressChartView(this);
        LinearLayout.LayoutParams chartLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        if (landscape) chartLp.setMargins(0, dp(4), 0, 0);
        content.addView(chart, chartLp);

        if (!landscape) {
            TextView note = text("Each point is one run. Fixed-time distance is especially useful when total run duration changes.", 13, false, 0xFF666666);
            note.setPadding(0, dp(8), 0, dp(8));
            content.addView(note);
        }

        AdapterView.OnItemSelectedListener l = new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                progressMetricIndex = position;
                List<ProgressChartView.Point> pts = new ArrayList<>();
                String unit = "";
                List<RunRecord> chronological = new ArrayList<>(runs);
                chronological.sort((a, b) -> Long.compare(a.startedAtEpochMs, b.startedAtEpochMs));
                for (RunRecord r : chronological) {
                    Double value = null;
                    switch (position) {
                        case 0 -> { value = r.metrics.averageSpeedKmh; unit = " km/h"; }
                        case 1 -> { value = r.metrics.distanceM / 1000.0; unit = " km"; }
                        case 2 -> { value = r.durationMs / 60_000.0; unit = " min"; }
                        case 3 -> { if (r.metrics.distanceAt10MinM != null) value = r.metrics.distanceAt10MinM / 1000.0; unit = " km"; }
                        case 4 -> { if (r.metrics.distanceAt20MinM != null) value = r.metrics.distanceAt20MinM / 1000.0; unit = " km"; }
                        case 5 -> { if (r.metrics.best1KmSec != null) value = r.metrics.best1KmSec / 60.0; unit = " min"; }
                    }
                    if (value != null && Double.isFinite(value)) pts.add(new ProgressChartView.Point(r.startedAtEpochMs, value));
                }
                chart.setData(pts, unit);
            }
            @Override public void onNothingSelected(AdapterView<?> parent) {}
        };
        spinner.setOnItemSelectedListener(l);
        int selectedMetric = Math.max(0, Math.min(progressMetricIndex, labels.length - 1));
        spinner.setSelection(selectedMetric, false);
        // Populate immediately as setSelection() is allowed to skip a callback when the
        // requested item is already selected (notably metric 0 on first open).
        l.onItemSelected(spinner, null, selectedMetric, selectedMetric);
        showWithBottomNav(content, 1);
    }

    private void showData() {
        currentPage = 2;
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = vertical(0);
        body.setPadding(dp(20), dp(18), dp(20), dp(30));
        body.addView(text("Data", 28, true, 0xFF111111));
        TextView privacy = text("Local only. RunLog has no Internet permission, no account, and no analytics. Backups contain your precise GPS route history, so treat them as sensitive files.", 15, false, 0xFF444444);
        privacy.setPadding(0, dp(12), 0, dp(18));
        body.addView(privacy);
        body.addView(stat("Saved runs", Integer.toString(repository.listRuns().size())));

        Button export = primaryButton("Export backup");
        export.setOnClickListener(v -> beginExport());
        body.addView(export, buttonLp());
        Button importButton = secondaryButton("Import / merge backup");
        importButton.setOnClickListener(v -> beginImport());
        body.addView(importButton, buttonLp());
        Button deleteAll = secondaryButton("Delete all local data");
        deleteAll.setOnClickListener(v -> new AlertDialog.Builder(this)
                .setTitle("Delete all runs?")
                .setMessage("This cannot be undone unless you have exported a backup.")
                .setPositiveButton("Delete all", (d, w) -> { repository.deleteAll(); showData(); })
                .setNegativeButton("Cancel", null).show());
        body.addView(deleteAll, buttonLp());
        scroll.addView(body);
        showWithBottomNav(scroll, 2);
    }

    private void beginExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        i.putExtra(Intent.EXTRA_TITLE, "runlog-backup-" + new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date()) + ".json");
        startActivityForResult(i, REQ_EXPORT);
    }

    private void beginImport() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/json");
        startActivityForResult(i, REQ_IMPORT);
    }

    @Override protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            if (requestCode == REQ_EXPORT) {
                try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                    if (out == null) throw new IllegalStateException("Cannot open destination");
                    repository.exportBackup(out);
                }
                Toast.makeText(this, "Backup exported.", Toast.LENGTH_LONG).show();
            } else if (requestCode == REQ_IMPORT) {
                try (InputStream in = getContentResolver().openInputStream(uri)) {
                    if (in == null) throw new IllegalStateException("Cannot open backup");
                    RunRepository.ImportResult r = repository.importBackup(in);
                    Toast.makeText(this, "Imported " + r.imported + " run(s); skipped " + r.skippedDuplicates + " duplicate(s).", Toast.LENGTH_LONG).show();
                }
                showData();
            }
        } catch (Exception e) {
            new AlertDialog.Builder(this).setTitle("Data operation failed").setMessage(e.getMessage()).setPositiveButton("OK", null).show();
        }
    }

    private View stat(String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(0, dp(18), 0, dp(4));
        TextView l = text(label, 15, false, 0xFF666666);
        TextView v = text(value, 17, true, 0xFF222222);
        v.setGravity(Gravity.END);
        row.addView(l, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row.addView(v, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private void showWithBottomNav(View content, int selected) {
        LinearLayout page = vertical(0);
        page.addView(content, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        LinearLayout nav = new LinearLayout(this);
        nav.setOrientation(LinearLayout.HORIZONTAL);
        nav.setPadding(dp(8), dp(4), dp(8), dp(6));
        String[] names = {"Runs", "Progress", "Data"};
        for (int i = 0; i < names.length; i++) {
            Button b = new Button(this);
            b.setText(names[i]);
            b.setAllCaps(false);
            b.setTypeface(Typeface.DEFAULT, i == selected ? Typeface.BOLD : Typeface.NORMAL);
            int index = i;
            b.setOnClickListener(v -> { if (index == 0) showRuns(); else if (index == 1) showProgress(); else showData(); });
            nav.addView(b, new LinearLayout.LayoutParams(0, dp(50), 1f));
        }
        page.addView(nav);
        setRoot(page);
    }

    private void setRoot(LinearLayout newRoot) {
        root = newRoot;
        root.setBackgroundColor(0xFFFAFAFA);
        final int baseLeft = root.getPaddingLeft();
        final int baseTop = root.getPaddingTop();
        final int baseRight = root.getPaddingRight();
        final int baseBottom = root.getPaddingBottom();
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(baseLeft + bars.left, baseTop + bars.top, baseRight + bars.right, baseBottom + bars.bottom);
            return insets;
        });
        setContentView(root);
        root.requestApplyInsets();
    }

    private LinearLayout vertical(int gravity) {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        if (gravity != 0) l.setGravity(gravity);
        return l;
    }

    private TextView text(String s, float sp, boolean bold, int color) {
        TextView t = new TextView(this);
        t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private Button primaryButton(String s) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setTypeface(Typeface.DEFAULT, Typeface.BOLD); return b;
    }
    private Button secondaryButton(String s) { Button b = new Button(this); b.setText(s); b.setAllCaps(false); return b; }
    private LinearLayout.LayoutParams buttonLp() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56));
        p.setMargins(0, dp(10), 0, 0); return p;
    }
    private int dp(int x) { return (int) (x * getResources().getDisplayMetrics().density + 0.5f); }

    public static String formatDuration(long ms) {
        long sec = Math.max(0, ms / 1000L);
        long h = sec / 3600L; long m = (sec % 3600L) / 60L; long s = sec % 60L;
        return h > 0 ? String.format(Locale.US, "%d:%02d:%02d", h, m, s) : String.format(Locale.US, "%02d:%02d", m, s);
    }

    public static String formatPace(double seconds) {
        if (!Double.isFinite(seconds) || seconds < 0) return "—";
        long rounded = Math.round(seconds);
        return String.format(Locale.US, "%d:%02d", rounded / 60L, rounded % 60L);
    }
}
