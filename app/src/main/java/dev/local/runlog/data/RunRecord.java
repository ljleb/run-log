package dev.local.runlog.data;

import dev.local.runlog.core.RunMetrics;
import dev.local.runlog.core.TrackPoint;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public final class RunRecord {
    public final String id;
    public final long startedAtEpochMs;
    public final long durationMs;
    public final boolean recovered;
    public final List<TrackPoint> points;
    public final RunMetrics.Result metrics;

    public RunRecord(String id, long startedAtEpochMs, long durationMs, boolean recovered,
                     List<TrackPoint> points) {
        this.id = id;
        this.startedAtEpochMs = startedAtEpochMs;
        this.durationMs = Math.max(0L, durationMs);
        this.recovered = recovered;
        this.points = List.copyOf(points);
        this.metrics = RunMetrics.analyze(points, this.durationMs);
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("id", id);
        o.put("startedAtEpochMs", startedAtEpochMs);
        o.put("durationMs", durationMs);
        o.put("recovered", recovered);
        JSONArray arr = new JSONArray();
        for (TrackPoint p : points) {
            JSONArray row = new JSONArray();
            row.put(p.elapsedMs);
            row.put(p.latitudeDeg);
            row.put(p.longitudeDeg);
            row.put((double) p.accuracyM);
            arr.put(row);
        }
        o.put("points", arr);
        return o;
    }

    public static RunRecord fromJson(JSONObject o) throws JSONException {
        String id = o.getString("id");
        long started = o.getLong("startedAtEpochMs");
        long duration = o.getLong("durationMs");
        boolean recovered = o.optBoolean("recovered", false);
        JSONArray arr = o.getJSONArray("points");
        List<TrackPoint> points = new ArrayList<>(arr.length());
        for (int i = 0; i < arr.length(); i++) {
            JSONArray row = arr.getJSONArray(i);
            points.add(new TrackPoint(row.getLong(0), row.getDouble(1), row.getDouble(2),
                    (float) row.getDouble(3)));
        }
        return new RunRecord(id, started, duration, recovered, points);
    }
}
