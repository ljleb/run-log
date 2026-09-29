package dev.local.runlog.ui;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class ProgressChartView extends View {
    public static final class Point {
        public final long epochMs;
        public final double value;
        public Point(long epochMs, double value) { this.epochMs = epochMs; this.value = value; }
    }

    private final Paint axis = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Point> points = List.of();
    private String unit = "";

    public ProgressChartView(Context context) { super(context); init(); }
    public ProgressChartView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        axis.setColor(0xFFBBBBBB); axis.setStrokeWidth(dp(1));
        line.setColor(0xFF222222); line.setStyle(Paint.Style.STROKE); line.setStrokeWidth(dp(2.5f));
        dot.setColor(0xFF222222); dot.setStyle(Paint.Style.FILL);
        text.setColor(0xFF555555); text.setTextSize(dp(12));
        // The parent gives the chart all remaining space. Avoid a fixed minimum height:
        // it can force the graph underneath controls on short landscape displays.
        setMinimumHeight(0);
    }

    public void setData(List<Point> data, String unit) {
        this.points = new ArrayList<>(data);
        this.points.sort((a, b) -> Long.compare(a.epochMs, b.epochMs));
        this.unit = unit == null ? "" : unit;
        invalidate();
    }

    @Override protected void onDraw(Canvas c) {
        super.onDraw(c);
        float width = getWidth();
        float height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Margins shrink slightly on short views while still reserving room for labels.
        float left = dp(54);
        float top = Math.min(dp(20), height * 0.08f);
        float right = width - dp(18);
        float bottom = height - Math.min(dp(42), height * 0.18f);
        if (right <= left + dp(20) || bottom <= top + dp(20)) return;
        RectF plot = new RectF(left, top, right, bottom);
        c.drawLine(plot.left, plot.bottom, plot.right, plot.bottom, axis);
        c.drawLine(plot.left, plot.top, plot.left, plot.bottom, axis);
        if (points.isEmpty()) {
            c.drawText("Not enough runs for this metric yet.", plot.left, plot.centerY(), text);
            return;
        }

        double minY = points.stream().mapToDouble(p -> p.value).min().orElse(0);
        double maxY = points.stream().mapToDouble(p -> p.value).max().orElse(1);
        if (Math.abs(maxY - minY) < 1e-9) { minY -= 0.5; maxY += 0.5; }
        double padding = (maxY - minY) * 0.12;
        minY -= padding; maxY += padding;
        long minX = points.get(0).epochMs, maxX = points.get(points.size() - 1).epochMs;
        if (maxX == minX) maxX = minX + 1;

        c.drawText(formatValue(maxY), dp(4), plot.top + text.getTextSize(), text);
        c.drawText(formatValue(minY), dp(4), plot.bottom, text);
        SimpleDateFormat df = new SimpleDateFormat("MMM d", Locale.getDefault());
        c.drawText(df.format(new Date(minX)), plot.left, getHeight() - dp(12), text);
        String end = df.format(new Date(maxX));
        c.drawText(end, plot.right - text.measureText(end), getHeight() - dp(12), text);

        Path path = new Path();
        for (int i = 0; i < points.size(); i++) {
            Point p = points.get(i);
            float x = (float) (plot.left + (p.epochMs - minX) / (double) (maxX - minX) * plot.width());
            float y = (float) (plot.bottom - (p.value - minY) / (maxY - minY) * plot.height());
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            c.drawCircle(x, y, dp(4), dot);
        }
        c.drawPath(path, line);
    }

    private String formatValue(double v) {
        if (Math.abs(v) >= 100) return String.format(Locale.US, "%.0f%s", v, unit);
        return String.format(Locale.US, "%.1f%s", v, unit);
    }

    private float dp(float x) { return x * getResources().getDisplayMetrics().density; }
}
