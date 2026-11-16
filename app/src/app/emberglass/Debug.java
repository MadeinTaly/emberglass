package app.emberglass;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayDeque;
import java.util.Date;
import java.util.Locale;

/**
 * Development channel over the app's own web server: log, internal state, real screenshots and
 * UI actions for end-to-end tests. Only in builds made with DEV=1 (assets/debug.on); release
 * builds do not contain the flag and every /debug path answers 404.
 */
final class Debug {
    static volatile boolean on;
    private static final ArrayDeque<String> lines = new ArrayDeque<>();
    private static File crashFile;
    static volatile Activity activity;   // the visible activity, for screenshots and UI actions
    interface Ui { String run(String action) throws Exception; }
    static volatile Ui ui;

    static void init(Context c) {
        try { c.getAssets().open("debug.on").close(); on = true; } catch (java.io.IOException e) { on = false; }
        if (!on) return;
        crashFile = new File(c.getFilesDir(), "last_crash.txt");
        if (crashFile.isFile()) log("previous crash:\n" + read(crashFile));
        Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            StringWriter w = new StringWriter();
            e.printStackTrace(new PrintWriter(w));
            try (FileWriter f = new FileWriter(crashFile)) { f.write(t.getName() + ": " + w); } catch (java.io.IOException ignored) { }
            if (prev != null) prev.uncaughtException(t, e);
        });
        log("debug channel on");
    }

    static void log(String msg) {
        if (!on) return;
        String line = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US).format(new Date()) + "  " + msg;
        synchronized (lines) {
            lines.add(line);
            while (lines.size() > 2000) lines.poll();
        }
        android.util.Log.i("emberglass", msg);
    }

    static String log() {
        StringBuilder b = new StringBuilder();
        synchronized (lines) { for (String l : lines) b.append(l).append('\n'); }
        return b.toString();
    }

    static String state(Engine e) {
        Runtime r = Runtime.getRuntime();
        Thermal th = e.th;
        return "{\"status\":" + e.statusJson()
                + String.format(Locale.US, ",\"debug\":{\"heapUsedMB\":%.1f,\"heapMaxMB\":%.1f,\"threads\":%d,\"published\":%d,"
                        + "\"renderScale\":%d,\"camOpen\":%b,\"usb\":\"%s\",\"recording\":%b,\"monitoring\":%b,\"activity\":%b}}",
                (r.totalMemory() - r.freeMemory()) / 1048576f, r.maxMemory() / 1048576f, Thread.activeCount(), th.published,
                Thermal.SCALE, e.cam != null, e.cam != null ? e.cam.info.replace("\"", "'") : "", e.recorder.running(),
                e.monitor.active(), activity != null);
    }

    /**
     * PNG of the visible window with the GPU surfaces composited in: PixelCopy of the window leaves a
     * transparent hole where a SurfaceView is, so each SurfaceView is copied too and drawn underneath.
     */
    static byte[] screen() throws Exception {
        Activity a = activity;
        if (a == null) throw new IllegalStateException("no visible activity");
        android.view.View root = a.getWindow().getDecorView();
        Bitmap win = copy(root.getWidth(), root.getHeight(), (b, l) -> PixelCopy.request(a.getWindow(), b, l, new Handler(Looper.getMainLooper())));
        Bitmap out = Bitmap.createBitmap(win.getWidth(), win.getHeight(), Bitmap.Config.ARGB_8888);
        android.graphics.Canvas c = new android.graphics.Canvas(out);
        java.util.List<android.view.SurfaceView> surfaces = new java.util.ArrayList<>();
        find(root, surfaces);
        int[] rootPos = new int[2];
        root.getLocationInWindow(rootPos);
        for (android.view.SurfaceView sv : surfaces) {
            if (sv.getWidth() == 0 || sv.getHeight() == 0) continue;
            Bitmap sb = copy(sv.getWidth(), sv.getHeight(), (b, l) -> PixelCopy.request(sv, b, l, new Handler(Looper.getMainLooper())));
            int[] pos = new int[2];
            sv.getLocationInWindow(pos);
            c.drawBitmap(sb, pos[0] - rootPos[0], pos[1] - rootPos[1], null);
        }
        c.drawBitmap(win, 0, 0, null);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        out.compress(Bitmap.CompressFormat.PNG, 100, o);
        return o.toByteArray();
    }

    private interface Copier { void request(Bitmap b, PixelCopy.OnPixelCopyFinishedListener l); }

    private static Bitmap copy(int w, int h, Copier copier) throws Exception {
        Bitmap b = Bitmap.createBitmap(Math.max(1, w), Math.max(1, h), Bitmap.Config.ARGB_8888);
        final int[] result = {-1};
        final Object lock = new Object();
        copier.request(b, r -> { synchronized (lock) { result[0] = r; lock.notifyAll(); } });
        synchronized (lock) { if (result[0] == -1) lock.wait(3000); }
        if (result[0] != PixelCopy.SUCCESS) throw new IllegalStateException("PixelCopy failed: " + result[0]);
        return b;
    }

    private static void find(android.view.View v, java.util.List<android.view.SurfaceView> out) {
        if (v instanceof android.view.SurfaceView) out.add((android.view.SurfaceView) v);
        if (v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g = (android.view.ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) find(g.getChildAt(i), out);
        }
    }

    private static String read(File f) {
        try { return new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8); }
        catch (Exception e) { return ""; }
    }
}
