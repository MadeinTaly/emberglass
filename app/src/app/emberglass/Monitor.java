package app.emberglass;

import android.graphics.Bitmap;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Long-term monitoring: every intervalSec one sample (max, min, center, spot, each region) goes to a
 * CSV log and to an in-memory history for the trend graph; events (alarm, camera lost/back, FFC) are
 * logged too. Optional time-lapse: a JPEG and the radiometric frame every snapshotMin minutes.
 */
final class Monitor {
    static final int MAX_SAMPLES = 20000;   // ~55 h at 10 s
    volatile int intervalSec = 10, snapshotMin = 0;

    private final Engine e;
    private final ArrayDeque<float[]> samples = new ArrayDeque<>();
    private final ArrayDeque<Object[]> events = new ArrayDeque<>();   // {seconds, text}
    private ScheduledExecutorService timer;
    private ScheduledFuture<?> task;
    private volatile boolean active;
    private long startMs, lastFrames = -1, lastSnapshotMs;
    private boolean lastAlarm, gap;
    private File dir, csv;
    private Writer out;
    private int rows;

    Monitor(Engine e) { this.e = e; }

    boolean active() { return active; }

    int rows() { return rows; }

    long startMs() { return startMs; }

    synchronized void start() {
        if (active) return;
        String name = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        dir = new File(e.dir("monitor"), name);
        dir.mkdirs();
        csv = new File(dir, "log_" + name + ".csv");
        try {
            out = new FileWriter(csv);
            StringBuilder h = new StringBuilder("time,elapsed_s,max_C,min_C,center_C,spot_C,max_x,max_y");
            for (int i = 1; i <= Thermal.MAX_REGIONS; i++) h.append(",r").append(i).append("_min_C,r").append(i).append("_max_C,r").append(i).append("_avg_C");
            h.append(",sensor_vtemp,alarm,event\n");
            out.write(h.toString());
            out.flush();
        } catch (IOException ex) {
            e.setStatus("log not created: " + ex.getMessage());
            return;
        }
        synchronized (samples) { samples.clear(); events.clear(); }
        startMs = System.currentTimeMillis();
        lastSnapshotMs = 0;
        rows = 0;
        lastFrames = -1;
        active = true;
        timer = Executors.newSingleThreadScheduledExecutor();
        task = timer.scheduleWithFixedDelay(this::tick, 0, Math.max(1, intervalSec), TimeUnit.SECONDS);
        event("monitoring started, every " + intervalSec + " s");
    }

    synchronized void stop() {
        if (!active) return;
        event("monitoring stopped");
        active = false;
        if (task != null) task.cancel(false);
        if (timer != null) timer.shutdown();
        try { out.close(); } catch (IOException ignored) { }
    }

    File file() { return csv; }

    /** Records an event in the log and the history (no-op when not monitoring). */
    synchronized void event(String text) {
        if (!active) return;
        float t = (System.currentTimeMillis() - startMs) / 1000f;
        synchronized (samples) {
            events.add(new Object[]{t, text});
            while (events.size() > 500) events.poll();
        }
        write(row(null, text));
    }

    private synchronized void tick() {
        if (!active) return;
        Thermal th = e.th;
        boolean fresh = th.frames != lastFrames && !th.warming;
        lastFrames = th.frames;
        if (!fresh) {
            if (!gap) event("no data from the camera");
            gap = true;
            return;
        }
        if (gap) event("data resumed");
        gap = false;
        if (th.alarm != lastAlarm) event(!th.alarm ? "alarm cleared"
                : th.rateHigh ? String.format(Locale.US, "alarm: rising %.1f C/min", th.rate) : "alarm: above " + th.alarmC + " C");
        lastAlarm = th.alarm;

        float[][] rs = th.regionStats;
        float[] s = new float[5 + Thermal.MAX_REGIONS];
        s[0] = (System.currentTimeMillis() - startMs) / 1000f;
        s[1] = th.max; s[2] = th.min; s[3] = th.center; s[4] = th.spot;
        for (int i = 0; i < Thermal.MAX_REGIONS; i++) s[5 + i] = i < rs.length ? rs[i][1] : Float.NaN;
        synchronized (samples) {
            samples.add(s);
            while (samples.size() > MAX_SAMPLES) samples.poll();
        }
        write(row(rs, ""));
        rows++;

        long now = System.currentTimeMillis();
        if (snapshotMin > 0 && now - lastSnapshotMs >= snapshotMin * 60_000L) {
            lastSnapshotMs = now;
            try {
                String n = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date(now));
                Bitmap b = th.freshBitmap(1500);
                if (b != null) Engine.writeJpeg(b, new File(dir, n + ".jpg"));
                e.writeRaw(new File(dir, n + ".raw"));
            } catch (IOException ex) {
                e.setStatus("snapshot failed: " + ex.getMessage());
            }
        }
    }

    private String row(float[][] rs, String event) {
        Thermal th = e.th;
        long now = System.currentTimeMillis();
        StringBuilder b = new StringBuilder();
        b.append(new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(new Date(now)))
                .append(',').append(String.format(Locale.US, "%.1f", (now - startMs) / 1000f));
        if (rs == null) {   // event-only row
            for (int i = 0; i < 6 + 3 * Thermal.MAX_REGIONS + 5; i++) b.append(',');
        } else {
            b.append(String.format(Locale.US, ",%.2f,%.2f,%.2f,%.2f,%d,%d", th.max, th.min, th.center, th.spot, th.maxI % Thermal.W, th.maxI / Thermal.W));
            for (int i = 0; i < Thermal.MAX_REGIONS; i++) {
                if (i < rs.length) b.append(String.format(Locale.US, ",%.2f,%.2f,%.2f", rs[i][0], rs[i][1], rs[i][2]));
                else b.append(",,,");
            }
            b.append(',').append(e.vtemp >= 0 ? String.valueOf(e.vtemp) : "").append(',').append(th.alarm ? 1 : 0)
                    .append(String.format(Locale.US, ",%.2f", th.rate))
                    .append(th.buildingOn && !Float.isNaN(th.fRsiMin) ? String.format(Locale.US, ",%.3f,%.2f", th.fRsiMin, th.dewC) : ",,");
        }
        b.append(',').append(event.replace(",", ";")).append('\n');
        return b.toString();
    }

    private void write(String line) {
        try { out.write(line); out.flush(); } catch (IOException | NullPointerException ignored) { }
    }

    String json() {
        return String.format(Locale.US, "{\"active\":%b,\"intervalSec\":%d,\"snapshotMin\":%d,\"rows\":%d,\"startMs\":%d,\"file\":\"%s\"}",
                active, intervalSec, snapshotMin, rows, startMs, csv == null ? "" : csv.getName());
    }

    /** Samples newer than `since` seconds: {"startMs":..,"samples":[[t,max,min,center,spot,r1..r6],..],"events":[[t,"text"],..]} */
    String historyJson(double since) {
        StringBuilder b = new StringBuilder("{\"startMs\":").append(startMs).append(",\"active\":").append(active).append(",\"samples\":[");
        boolean first = true;
        synchronized (samples) {
            for (float[] s : samples) {
                if (s[0] <= since) continue;
                if (!first) b.append(',');
                first = false;
                b.append('[');
                for (int i = 0; i < s.length; i++) {
                    if (i > 0) b.append(',');
                    b.append(Float.isNaN(s[i]) ? "null" : String.format(Locale.US, i == 0 ? "%.1f" : "%.2f", s[i]));
                }
                b.append(']');
            }
            b.append("],\"events\":[");
            first = true;
            for (Object[] ev : events) {
                if ((float) ev[0] <= since) continue;
                if (!first) b.append(',');
                first = false;
                b.append(String.format(Locale.US, "[%.1f,\"%s\"]", (float) ev[0], ((String) ev[1]).replace("\"", "'")));
            }
        }
        return b.append("]}").toString();
    }

    /** Copy of the samples for the phone's trend view. */
    float[][] snapshot() {
        synchronized (samples) { return samples.toArray(new float[0][]); }
    }
}
