package dev.local.runlog.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import dev.local.runlog.MainActivity;
import dev.local.runlog.data.RunRecord;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class RunListAdapter extends BaseAdapter {
    private final Context context;
    private List<RunRecord> runs = new ArrayList<>();
    private final SimpleDateFormat dateFormat = new SimpleDateFormat("EEE, MMM d • h:mm a", Locale.getDefault());

    public RunListAdapter(Context context) { this.context = context; }
    public void setRuns(List<RunRecord> runs) { this.runs = new ArrayList<>(runs); notifyDataSetChanged(); }
    @Override public int getCount() { return runs.size(); }
    @Override public RunRecord getItem(int position) { return runs.get(position); }
    @Override public long getItemId(int position) { return position; }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        LinearLayout row = convertView instanceof LinearLayout ? (LinearLayout) convertView : createRow();
        RunRecord run = getItem(position);
        TextView title = (TextView) row.getChildAt(0);
        TextView meta = (TextView) row.getChildAt(1);
        title.setText(String.format(Locale.US, "%.2f km    %s", run.metrics.distanceM / 1000.0,
                MainActivity.formatDuration(run.durationMs)));
        String pace = Double.isFinite(run.metrics.paceSecPerKm)
                ? MainActivity.formatPace(run.metrics.paceSecPerKm) + "/km"
                : "—";
        meta.setText(dateFormat.format(new Date(run.startedAtEpochMs)) + "   •   " + pace
                + (run.recovered ? "   •   recovered" : ""));
        return row;
    }

    private LinearLayout createRow() {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(dp(18), dp(14), dp(18), dp(14));
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(context);
        title.setTextSize(19); title.setTypeface(Typeface.DEFAULT, Typeface.BOLD); title.setTextColor(0xFF111111);
        TextView meta = new TextView(context);
        meta.setTextSize(13); meta.setTextColor(0xFF666666); meta.setPadding(0, dp(4), 0, 0);
        row.addView(title, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.addView(meta, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private int dp(int x) { return (int) (x * context.getResources().getDisplayMetrics().density + 0.5f); }
}
