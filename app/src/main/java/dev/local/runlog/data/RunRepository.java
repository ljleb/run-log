package dev.local.runlog.data;

import android.content.Context;

import dev.local.runlog.core.TrackPoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** App-private file storage. No database, account, network, or cloud dependency. */
public final class RunRepository {
    public static final int BACKUP_SCHEMA_VERSION = 1;
    private static final String ACTIVE_FILE = "active_run.csv";
    private final File runsDir;
    private final File activeFile;

    public RunRepository(Context context) {
        File root = new File(context.getFilesDir(), "runlog");
        this.runsDir = new File(root, "runs");
        this.activeFile = new File(root, ACTIVE_FILE);
        //noinspection ResultOfMethodCallIgnored
        runsDir.mkdirs();
    }

    public synchronized void beginActive(long startedAtEpochMs) throws IOException {
        File parent = activeFile.getParentFile();
        if (parent != null) parent.mkdirs();
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(activeFile, false), StandardCharsets.UTF_8))) {
            w.write("#startEpochMs=" + startedAtEpochMs);
            w.newLine();
        }
    }

    public synchronized void appendActivePoint(TrackPoint p) throws IOException {
        try (BufferedWriter w = new BufferedWriter(new OutputStreamWriter(
                new FileOutputStream(activeFile, true), StandardCharsets.UTF_8))) {
            w.write(p.elapsedMs + "," + p.latitudeDeg + "," + p.longitudeDeg + "," + p.accuracyM);
            w.newLine();
        }
    }

    public synchronized boolean hasActiveRun() { return activeFile.isFile(); }

    public synchronized ActiveData readActive() throws IOException {
        if (!activeFile.isFile()) return null;
        long startEpochMs = 0L;
        List<TrackPoint> points = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new FileInputStream(activeFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.startsWith("#startEpochMs=")) {
                    startEpochMs = Long.parseLong(line.substring("#startEpochMs=".length()));
                } else if (!line.isBlank()) {
                    String[] f = line.split(",");
                    if (f.length == 4) {
                        points.add(new TrackPoint(Long.parseLong(f[0]), Double.parseDouble(f[1]),
                                Double.parseDouble(f[2]), Float.parseFloat(f[3])));
                    }
                }
            }
        }
        return new ActiveData(startEpochMs, points);
    }

    public synchronized RunRecord finalizeActive(long durationMs, boolean recovered) throws IOException {
        ActiveData active = readActive();
        if (active == null) return null;
        long safeDuration = durationMs;
        if (safeDuration <= 0 && !active.points.isEmpty()) {
            safeDuration = active.points.get(active.points.size() - 1).elapsedMs;
        }
        RunRecord record = new RunRecord(UUID.randomUUID().toString(), active.startedAtEpochMs,
                safeDuration, recovered, active.points);
        writeRun(record);
        //noinspection ResultOfMethodCallIgnored
        activeFile.delete();
        return record;
    }

    public synchronized RunRecord recoverInterruptedRun() throws IOException {
        ActiveData active = readActive();
        if (active == null) return null;
        long duration = active.points.isEmpty() ? 0L : active.points.get(active.points.size() - 1).elapsedMs;
        return finalizeActive(duration, true);
    }

    public synchronized List<RunRecord> listRuns() {
        List<RunRecord> result = new ArrayList<>();
        File[] files = runsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (files == null) return result;
        for (File f : files) {
            try {
                result.add(RunRecord.fromJson(new JSONObject(readText(f))));
            } catch (Exception ignored) {
                // A damaged run file should not make the whole app unusable.
            }
        }
        result.sort(Comparator.comparingLong((RunRecord r) -> r.startedAtEpochMs).reversed());
        return result;
    }

    public synchronized void deleteRun(String id) {
        File f = new File(runsDir, safeId(id) + ".json");
        //noinspection ResultOfMethodCallIgnored
        f.delete();
    }

    public synchronized void deleteAll() {
        File[] files = runsDir.listFiles();
        if (files != null) for (File f : files) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
        }
        //noinspection ResultOfMethodCallIgnored
        activeFile.delete();
    }

    public synchronized void exportBackup(OutputStream output) throws IOException, JSONException {
        JSONObject root = new JSONObject();
        root.put("schemaVersion", BACKUP_SCHEMA_VERSION);
        root.put("exportedAtEpochMs", System.currentTimeMillis());
        JSONArray runs = new JSONArray();
        for (RunRecord run : listRuns()) runs.put(run.toJson());
        root.put("runs", runs);
        output.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
        output.flush();
    }

    public synchronized ImportResult importBackup(InputStream input) throws IOException, JSONException {
        String json = readText(input);
        JSONObject root = new JSONObject(json);
        int schema = root.getInt("schemaVersion");
        if (schema != BACKUP_SCHEMA_VERSION) {
            throw new JSONException("Unsupported backup schema: " + schema);
        }
        Set<String> existingIds = new HashSet<>();
        for (RunRecord existing : listRuns()) existingIds.add(existing.id);
        JSONArray arr = root.getJSONArray("runs");
        int imported = 0;
        int skipped = 0;
        for (int i = 0; i < arr.length(); i++) {
            RunRecord run = RunRecord.fromJson(arr.getJSONObject(i));
            if (existingIds.contains(run.id)) {
                skipped++;
            } else {
                writeRun(run);
                existingIds.add(run.id);
                imported++;
            }
        }
        return new ImportResult(imported, skipped);
    }

    private void writeRun(RunRecord run) throws IOException {
        try {
            File f = new File(runsDir, safeId(run.id) + ".json");
            File tmp = new File(runsDir, safeId(run.id) + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp, false)) {
                out.write(run.toJson().toString().getBytes(StandardCharsets.UTF_8));
                out.getFD().sync();
            }
            if (f.exists() && !f.delete()) throw new IOException("Could not replace run file");
            if (!tmp.renameTo(f)) throw new IOException("Could not commit run file");
        } catch (JSONException e) {
            throw new IOException("Could not encode run", e);
        }
    }

    private static String safeId(String id) { return id.replaceAll("[^A-Za-z0-9_-]", "_"); }

    private static String readText(File file) throws IOException {
        try (InputStream in = new FileInputStream(file)) { return readText(in); }
    }

    private static String readText(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            char[] buf = new char[8192];
            int n;
            while ((n = r.read(buf)) >= 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    public static final class ActiveData {
        public final long startedAtEpochMs;
        public final List<TrackPoint> points;
        ActiveData(long startedAtEpochMs, List<TrackPoint> points) {
            this.startedAtEpochMs = startedAtEpochMs;
            this.points = points;
        }
    }

    public static final class ImportResult {
        public final int imported;
        public final int skippedDuplicates;
        ImportResult(int imported, int skippedDuplicates) {
            this.imported = imported;
            this.skippedDuplicates = skippedDuplicates;
        }
    }
}
