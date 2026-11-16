package app.emberglass;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.ShortBuffer;
import java.util.Locale;

/**
 * Turns 256x384 frames (top: ISP image, bottom: uint16 kelvin*64 per pixel) into temperatures,
 * statistics, a rendered bitmap and a JPEG for the web stream.
 */
final class Thermal {
    static final int W = 256, H = 192;
    /** Render scale, adapted to the phone: 4x when it keeps up, down to 2x on slow phones. */
    static volatile int SCALE = 4;
    private float renderMs = 0;
    static final String[] PALETTES = {"iron", "rainbow", "white", "black", "lava", "arctic", "medical", "redhot"};
    static final int MAX_REGIONS = 6;

    // settings (written by UI / web, read by the frame thread)
    volatile int palette = 0;
    volatile int denoise = 1;          // 0 off .. 3 strong temporal smoothing
    /**
     * Image rotation in quarter turns clockwise, remembered separately for each phone orientation
     * (index = Surface.ROTATION_*): the camera is fixed to the phone, so each way of holding it needs its own.
     */
    final int[] rotFor = {0, 3, 2, 1};
    volatile int displayRot = 0;
    volatile boolean mirror = false;   // for a camera facing the user

    int quarter() { return rotFor[displayRot & 3] & 3; }
    int outW() { return quarter() % 2 == 0 ? W : H; }
    int outH() { return quarter() % 2 == 0 ? H : W; }

    /** Sensor coordinates (continuous, 0..W x 0..H) to displayed-image coordinates. */
    float[] toDisplay(float x, float y) {
        if (mirror) x = W - x;
        switch (quarter()) {
            case 1: return new float[]{H - y, x};
            case 2: return new float[]{W - x, H - y};
            case 3: return new float[]{y, W - x};
            default: return new float[]{x, y};
        }
    }

    /** Displayed-image coordinates to sensor coordinates, continuous and unclamped (for texture corners). */
    float[] toSensorF(float dx, float dy) {
        float x, y;
        switch (quarter()) {
            case 1: x = dy; y = H - dx; break;
            case 2: x = W - dx; y = H - dy; break;
            case 3: x = W - dy; y = dx; break;
            default: x = dx; y = dy;
        }
        if (mirror) x = W - x;
        return new float[]{x, y};
    }

    /** Displayed-image pixel to sensor pixel (inverse of toDisplay), clamped to the sensor. */
    int[] toSensor(float dx, float dy) {
        float x, y;
        switch (quarter()) {
            case 1: x = dy; y = H - dx; break;
            case 2: x = W - dx; y = H - dy; break;
            case 3: x = W - dy; y = dx; break;
            default: x = dx; y = dy;
        }
        if (mirror) x = W - x;
        return new int[]{Math.max(0, Math.min(W - 1, (int) x)), Math.max(0, Math.min(H - 1, (int) y))};
    }
    volatile int spotX = W / 2, spotY = H / 2;
    private volatile int flatFieldLeft = 0;
    volatile boolean markMax = true, markMin = true, markCenter = false, markSpot = true, scaleBar = true;
    volatile boolean fahrenheit = false;
    volatile boolean alarmOn = false;   // isotherm: pixels at or above alarmC are painted in the alarm color
    volatile float alarmC = 60;
    volatile boolean rangeLock = false; // fixed color scale instead of automatic
    /** Contrast: plateau histogram equalization (detail everywhere) or linear min..max (proportional). */
    volatile boolean equalize = true;
    private static final int BINS = 512;
    private final int[] hist = new int[BINS];
    private final float[] eqMap = new float[BINS];   // palette index per bin, smoothed over time
    private boolean eqReady;
    volatile float rangeLo = 15, rangeHi = 40;

    // building diagnostics: indoor air, its humidity, outdoor air
    volatile boolean buildingOn = false;
    volatile float airC = 20, rh = 50, outC = 0;
    static final int CONDENSATION_COLOR = 0xff1f8bff, MOLD_COLOR = 0xffff4fd0;
    volatile float dewC, moldC, fRsiMin = Float.NaN, fRsiReq = Float.NaN;
    /** Share of the image (0..1) at or below the dew point and the mold limit. */
    volatile float dewShare, moldShare;
    volatile boolean envelopeValid;

    // electrical inspection: rise of each area over area 1 (similar component) or over ambient air
    volatile boolean elecOn = false, elecVsAmbient = false;

    // rate-of-rise alarm on the scene max, C per minute (0 = off)
    volatile float rateAlarm = 0;
    volatile float rate;
    volatile boolean rateHigh;
    private final float[] rateT = new float[61], rateV = new float[61];
    private int rateN, rateHead;
    private long rateLastNs;
    static final int ALARM_COLOR = 0xffd5e51b;

    /** Measurement regions in image pixels {x, y, w, h}; a 1x1 region is a point. */
    final java.util.List<int[]> regions = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Per region {min, max, avg}, same order as regions. */
    volatile float[][] regionStats = new float[0][];

    void addRegion(int x, int y, int w, int h) {
        x = Math.max(0, Math.min(W - 1, x));
        y = Math.max(0, Math.min(H - 1, y));
        w = Math.max(1, Math.min(W - x, w));
        h = Math.max(1, Math.min(H - y, h));
        if (regions.size() >= MAX_REGIONS) regions.remove(0);
        regions.add(new int[]{x, y, w, h});
    }

    // Rendering (bitmap + JPEG) is the costly part: only while someone looks (screen, stream, photo).
    private volatile long renderUntil = Long.MAX_VALUE;

    void want() { renderUntil = System.nanoTime() + 3_000_000_000L; }

    /** JPEG encoding is only for the web stream and snapshots: asked for separately. */
    private volatile long jpegUntil;
    void wantJpeg() { want(); jpegUntil = System.nanoTime() + 3_000_000_000L; }

    // --- frame hand-off: the USB thread only copies; one processing thread takes the newest frame ---
    private final ByteBuffer pending = ByteBuffer.allocateDirect(W * H * 2 * 2).order(ByteOrder.LITTLE_ENDIAN);
    private final ByteBuffer work = ByteBuffer.allocateDirect(W * H * 2 * 2).order(ByteOrder.LITTLE_ENDIAN);
    private boolean hasPending;
    private Thread worker;
    volatile float camFps;   // frames arriving from the camera
    private long camLastNs;

    /** Called by the USB reader (or the replay) for every frame; never blocks on processing. */
    void submit(ByteBuffer frame) {
        long now = System.nanoTime();
        if (camLastNs != 0) camFps = camFps * 0.9f + 0.1f * 1e9f / (now - camLastNs);
        camLastNs = now;
        frame.rewind();
        if (frame.remaining() > pending.capacity()) return;   // not a frame of the negotiated size
        synchronized (pending) {
            pending.clear();
            pending.put(frame);
            hasPending = true;   // an older unprocessed frame is simply replaced
            pending.notify();
            if (worker == null) {
                worker = new Thread(this::work, "thermal");
                worker.setDaemon(true);
                worker.start();
            }
        }
    }

    private void work() {
        while (true) {
            synchronized (pending) {
                while (!hasPending) {
                    try { pending.wait(); } catch (InterruptedException e) { return; }
                }
                work.clear();
                pending.flip();
                work.put(pending);
                hasPending = false;
            }
            work.flip();
            try { onFrame(work); } catch (Throwable e) { android.util.Log.e("emberglass", "frame", e); }   // never let processing die
        }
    }

    /** Renders a new frame and waits for it; null if no frame arrives in time. */
    Bitmap freshBitmap(long timeoutMs) {
        want();
        Bitmap old = bitmap;
        long end = System.currentTimeMillis() + timeoutMs;
        synchronized (frameLock) {
            while (bitmap == old && System.currentTimeMillis() < end) {
                try { frameLock.wait(100); } catch (InterruptedException e) { break; }
            }
        }
        return bitmap != old ? bitmap : old;
    }

    // outputs
    final short[] raw = new short[W * H];   // last raw frame in sensor orientation, for /raw
    volatile boolean warming = true;
    volatile float min, max, center, spot;
    volatile int minI, maxI;
    volatile float fps;
    volatile boolean alarm;   // max >= alarmC while the alarm is on
    volatile Bitmap bitmap;
    volatile byte[] jpeg;
    final Object frameLock = new Object();
    long frames;

    private final float[] t = new float[W * H];
    private final float[] ff = new float[W * H];
    private final double[] ffSum = new double[W * H];
    private final int[] px = new int[W * H];
    private final int[][] luts = {lut(IRON), lut(RAINBOW), lut(new int[]{0x000000, 0xffffff}), lut(new int[]{0xffffff, 0x000000}),
            lut(LAVA), lut(ARCTIC), lut(MEDICAL), redHot()};
    private float lo = Float.NaN, hi;
    private long lastNs;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    // flat-field requests are applied by the processing thread, which owns ffSum and ff
    private volatile int ffRequest;   // 1 = start, 2 = clear

    void startFlatField() { ffRequest = 1; }

    void clearFlatField() { ffRequest = 2; }

    void onFrame(ByteBuffer frame) {
        ShortBuffer s = frame.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer();
        s.position(W * H);   // bottom half
        synchronized (frameLock) {
            s.get(raw);
        }

        // Warm-up: the module sends a constant frame for the first seconds.
        short first = raw[0];
        boolean flat = true;
        for (int i = 1; i < raw.length && flat; i++) flat = raw[i] == first;
        // After the constant phase the module streams a few seconds of uncorrected data: neighbouring
        // pixels differ by ~11 C there, against ~0.15 C once calibrated (measured on a 0bda:5840).
        if (!flat) {
            long d = 0;
            int n = 0;
            for (int i = 0; i + 1 < raw.length; i += 7, n++) d += Math.abs((raw[i] & 0xffff) - (raw[i + 1] & 0xffff));
            flat = d / (64f * n) > 2f;
        }
        warming = flat;
        if (flat) return;

        float a = new float[]{0f, 0.3f, 0.5f, 0.7f}[Math.max(0, Math.min(3, denoise))];
        boolean reset = Float.isNaN(lo);
        for (int i = 0; i < t.length; i++) {
            float c = (raw[i] & 0xffff) / 64f - 273.15f;
            // temporal smoothing, but follow real changes (> 1 C) immediately
            t[i] = reset || a == 0 || Math.abs(c - t[i]) > 1f ? c : a * t[i] + (1 - a) * c;
        }
        int req = ffRequest;
        if (req != 0) {
            ffRequest = 0;
            if (req == 1) { java.util.Arrays.fill(ffSum, 0); flatFieldLeft = 32; }
            else { flatFieldLeft = 0; java.util.Arrays.fill(ff, 0); }
        }
        if (flatFieldLeft > 0) {
            for (int i = 0; i < t.length; i++) ffSum[i] += t[i];
            if (--flatFieldLeft == 0) {
                double mean = 0;
                for (double v : ffSum) mean += v;
                mean /= ffSum.length;
                for (int i = 0; i < ff.length; i++) ff[i] = (float) (ffSum[i] / 32 - mean);
            }
        }

        float mn = Float.MAX_VALUE, mx = -Float.MAX_VALUE;
        int mnI = 0, mxI = 0;
        for (int i = 0; i < t.length; i++) {
            float v = t[i] - ff[i];
            if (v < mn) { mn = v; mnI = i; }
            if (v > mx) { mx = v; mxI = i; }
        }
        min = mn; max = mx; minI = mnI; maxI = mxI;
        updateRate(mx);
        alarm = (alarmOn && mx >= alarmC) || rateHigh;
        float[][] rs = new float[regions.size()][];
        int ri = 0;
        for (int[] r : regions) {
            if (ri >= rs.length) break;
            float rmn = Float.MAX_VALUE, rmx = -Float.MAX_VALUE;
            double sum = 0;
            for (int y = r[1]; y < r[1] + r[3]; y++)
                for (int x = r[0]; x < r[0] + r[2]; x++) {
                    float v = t[y * W + x] - ff[y * W + x];
                    if (v < rmn) rmn = v;
                    if (v > rmx) rmx = v;
                    sum += v;
                }
            rs[ri++] = new float[]{rmn, rmx, (float) (sum / (r[2] * r[3])), Float.NaN, Float.NaN, -1};
        }
        analyse(rs, mn, mx);
        regionStats = rs;
        center = t[(H / 2) * W + W / 2] - ff[(H / 2) * W + W / 2];
        int si = spotY * W + spotX;
        spot = t[si] - ff[si];

        // smoothed color range avoids flicker
        if (rangeLock) { lo = rangeLo; hi = Math.max(rangeHi, rangeLo + 0.5f); }
        else if (reset) { lo = mn; hi = mx; } else { lo += (mn - lo) * 0.2f; hi += (mx - hi) * 0.2f; }
        float span = Math.max(hi - lo, 0.5f);
        eqActive = equalize && !rangeLock;
        if (eqActive) equalizeMap(span);
        publish();
        long now = System.nanoTime();
        if (now < renderUntil) render();   // composite only for the web MJPEG, photos and video
        if (renderUntil == Long.MAX_VALUE) renderUntil = now + 3_000_000_000L;

        if (lastNs != 0) fps = fps * 0.9f + 0.1f * 1e9f / (now - lastNs);
        lastNs = now;
        frames++;
    }

    private boolean eqActive;

    // --- published frame: what the GPU view, the web socket and the composite read -------------
    /** Temperatures (C) after denoise and flat-field, sensor orientation. Guarded by frameLock. */
    final float[] view = new float[W * H];
    /** Contrast curve, palette index (0..255) per bin of [viewLo, viewHi]. Guarded by frameLock. */
    final float[] viewCurve = new float[BINS];
    volatile float viewLo, viewHi = 1;
    volatile boolean viewEq;
    static final int CURVE_BINS = BINS;
    /** Called on the processing thread after each published frame (GPU view, web socket). */
    final java.util.List<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    private void publish() {
        synchronized (frameLock) {
            for (int i = 0; i < view.length; i++) view[i] = t[i] - ff[i];
            System.arraycopy(eqMap, 0, viewCurve, 0, BINS);
            viewLo = lo;
            viewHi = Math.max(hi, lo + 0.5f);
            viewEq = eqActive;
            published++;
            if (!"live".equals(phase) && !"disconnected".equals(phase)) phase = "live";
            frameLock.notifyAll();   // web socket clients wait on this
        }
        for (Runnable r : listeners) r.run();
    }

    volatile long published;

    /** Source state, for the screens: disconnected, connecting, warming or live. */
    volatile String phase = "connecting";

    /** Shown over the image while there is no live picture. */
    String notice() {
        switch (phase) {
            case "disconnected": return "Camera disconnected";
            case "connecting": return "Connecting to the camera...";
            default: return warming || published == 0 ? "Warming up..." : null;
        }
    }

    /** Forget the last picture: a new or lost camera must not show a stale frame. */
    void resetStream(String newPhase) {
        synchronized (frameLock) {
            phase = newPhase;
            warming = true;
            published = 0;
            lo = Float.NaN;
            eqReady = false;
            frameLock.notifyAll();
        }
        for (Runnable r : listeners) r.run();
    }

    int[] lut() { return luts[palette]; }

    /** Palette index 0..255 for a temperature, through the published contrast curve. */
    float index(float v) {
        float lo = viewLo, span = Math.max(viewHi - lo, 0.5f), f = (v - lo) / span;
        if (!viewEq) return f * 255;
        int b = (int) (f * BINS);
        return viewCurve[b < 0 ? 0 : b >= BINS ? BINS - 1 : b];
    }

    /** Palette index 0..255 for a temperature, through the current contrast curve. */
    private float index(float v, float span) {
        float f = (v - lo) / span;
        if (!eqActive) return f * 255;
        int b = (int) (f * BINS);
        return eqMap[b < 0 ? 0 : b >= BINS ? BINS - 1 : b];
    }

    /**
     * Plateau histogram equalization, as in professional thermal cameras: each bin's count is capped so
     * large uniform areas cannot take the whole palette, then the cumulative histogram becomes the curve.
     * A little linear mapping is mixed in to keep hotter = brighter readable; the curve is smoothed in time.
     */
    private void equalizeMap(float span) {
        java.util.Arrays.fill(hist, 0);
        for (int i = 0; i < t.length; i++) {
            int b = (int) ((t[i] - ff[i] - lo) / span * BINS);
            hist[b < 0 ? 0 : b >= BINS ? BINS - 1 : b]++;
        }
        int plateau = Math.max(1, t.length / BINS * 6);
        long total = 0;
        for (int c : hist) total += Math.min(c, plateau);
        long cum = 0;
        for (int b = 0; b < BINS; b++) {
            int c = Math.min(hist[b], plateau);
            float eq = (cum + c / 2f) / total * 255f;
            cum += c;
            float target = 0.85f * eq + 0.15f * (b + 0.5f) / BINS * 255f;
            eqMap[b] = eqReady ? eqMap[b] + (target - eqMap[b]) * 0.3f : target;
        }
        eqReady = true;
    }

    /** Building and electrical figures for the scene and each region (indexes 3 = fRsi, 4 = dT, 5 = severity). */
    private void analyse(float[][] rs, float mn, float mx) {
        if (buildingOn) {
            dewC = (float) Pro.dewPoint(airC, rh);
            moldC = (float) Pro.moldLimit(airC, rh);
            envelopeValid = Pro.envelopeValid(airC, outC);
            fRsiReq = (float) Pro.fRsi(moldC, airC, outC);
            fRsiMin = (float) Pro.fRsi(mn, airC, outC);
            for (float[] r : rs) r[3] = (float) Pro.fRsi(r[0], airC, outC);   // coldest point of the area
            int d = 0, m = 0;
            float dc = dewC, mc = moldC;
            for (int i = 0; i < t.length; i++) {
                float v = t[i] - ff[i];
                if (v <= mc) { m++; if (v <= dc) d++; }
            }
            dewShare = d / (float) t.length;
            moldShare = m / (float) t.length;
        }
        if (elecOn) {
            float ref = elecVsAmbient ? airC : rs.length > 0 ? rs[0][1] : Float.NaN;
            for (int i = 0; i < rs.length; i++) {
                if (!elecVsAmbient && i == 0) continue;   // area 1 is the reference
                float dT = rs[i][1] - ref;
                rs[i][4] = dT;
                rs[i][5] = Pro.severity(dT, elecVsAmbient);
            }
        }
    }

    /** Least-squares slope of the max over the last minute, sampled once a second. */
    private void updateRate(float mx) {
        long now = System.nanoTime();
        if (now - rateLastNs < 1_000_000_000L) return;
        rateLastNs = now;
        rateT[rateHead] = now / 1e9f;
        rateV[rateHead] = mx;
        rateHead = (rateHead + 1) % rateT.length;
        if (rateN < rateT.length) rateN++;
        if (rateN < 10) { rate = 0; rateHigh = false; return; }
        double st = 0, sv = 0, stt = 0, stv = 0;
        float t0 = rateT[(rateHead - rateN + rateT.length) % rateT.length];
        for (int k = 0; k < rateN; k++) {
            int i = (rateHead - rateN + k + rateT.length) % rateT.length;
            double x = rateT[i] - t0, y = rateV[i];
            st += x; sv += y; stt += x * x; stv += x * y;
        }
        double slope = (rateN * stv - st * sv) / (rateN * stt - st * st);   // C per second
        rate = (float) (slope * 60);
        rateHigh = rateAlarm > 0 && rate >= rateAlarm;
    }

    private final Overlay overlay = new Overlay(this);

    /** Composite image (palette + overlays) for the web MJPEG, photos and video; scale adapts to the phone. */
    private void render() {
        long t0 = System.nanoTime();
        renderComposite();
        renderMs = renderMs * 0.9f + 0.1f * (System.nanoTime() - t0) / 1e6f;
        if (renderMs > 30 && SCALE > 2) { SCALE--; renderMs = 15; }
        else if (renderMs < 9 && SCALE < 4 && frames % 250 == 0) { SCALE++; renderMs = 20; }
    }

    private void renderComposite() {
        int[] lut = lut();
        boolean iso = alarmOn, bld = buildingOn;
        float th = alarmC, dew = dewC, mold = moldC;
        for (int i = 0; i < px.length; i++) {
            float v = view[i];
            if (iso && v >= th) { px[i] = ALARM_COLOR; continue; }
            if (bld && v <= dew) { px[i] = CONDENSATION_COLOR; continue; }
            if (bld && v <= mold) { px[i] = MOLD_COLOR; continue; }
            int k = (int) index(v);
            px[i] = lut[k < 0 ? 0 : k > 255 ? 255 : k];
        }
        Bitmap small = Bitmap.createBitmap(px, W, H, Bitmap.Config.ARGB_8888);
        int q = quarter();
        if (q != 0 || mirror) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            if (mirror) m.postScale(-1, 1, W / 2f, H / 2f);
            m.postRotate(90 * q);
            Bitmap turned = Bitmap.createBitmap(small, 0, 0, W, H, m, false);
            small.recycle();
            small = turned;
        }
        int sc = SCALE, ow = outW(), oh = outH();
        Bitmap b = Bitmap.createScaledBitmap(small, ow * sc, oh * sc, true);
        small.recycle();
        overlay.draw(new Canvas(b), sc, 0, 0);
        byte[] j = null;
        if (System.nanoTime() < jpegUntil) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(96 * 1024);
            b.compress(Bitmap.CompressFormat.JPEG, 80, out);
            j = out.toByteArray();
        }
        synchronized (frameLock) {
            bitmap = b;
            if (j != null) jpeg = j;
            frameLock.notifyAll();
        }
    }

    String analysisLabel(float[] st) {
        String a = "";
        if (buildingOn && !Float.isNaN(st[3])) a += String.format(Locale.US, "  fRsi %.2f", st[3]);
        if (elecOn && st[5] >= 0) a += "  ΔT " + delta(st[4]) + " " + Pro.SEVERITY[(int) st[5]];
        return a;
    }

    String fmt(float c) { return String.format(Locale.US, fahrenheit ? "%.1f°F" : "%.1f°C", fahrenheit ? c * 9 / 5 + 32 : c); }

    /** A temperature difference in the current unit (no +32 offset for Fahrenheit). */
    String delta(float d) { return String.format(Locale.US, fahrenheit ? "%.1f°F" : "%.1f°C", fahrenheit ? d * 9 / 5 : d); }

    /** Number in the current unit, without the unit. */
    String num(float c) { return String.format(Locale.US, "%.1f", fahrenheit ? c * 9 / 5 + 32 : c); }

    String json() {
        return String.format(Locale.US,
                "{\"warming\":%b,\"min\":%.2f,\"max\":%.2f,\"center\":%.2f,\"spot\":%.2f,"
                        + "\"minX\":%d,\"minY\":%d,\"maxX\":%d,\"maxY\":%d,\"spotX\":%d,\"spotY\":%d,"
                        + "\"fps\":%.1f,\"camFps\":%.1f,\"renderScale\":%d,\"renderMs\":%.1f,\"viewLo\":%.3f,\"viewHi\":%.3f,\"viewEq\":%b,\"phase\":\"%s\",\"palette\":\"%s\",\"denoise\":%d,\"quarter\":%d,\"mirror\":%b,\"outW\":%d,\"outH\":%d,\"equalize\":%b,\"flatField\":%b,\"frames\":%d,"
                        + "\"alarm\":%b,\"settings\":{\"markMax\":%b,\"markMin\":%b,\"markCenter\":%b,\"markSpot\":%b,"
                        + "\"scaleBar\":%b,\"fahrenheit\":%b,\"alarmOn\":%b,\"alarmC\":%.1f,\"rangeLock\":%b,"
                        + "\"rangeLo\":%.1f,\"rangeHi\":%.1f},\"regions\":%s,"
                        + "\"building\":{\"on\":%b,\"airC\":%.1f,\"rh\":%.0f,\"outC\":%.1f,\"dewC\":%.2f,\"moldC\":%.2f,"
                        + "\"fRsiMin\":%s,\"fRsiReq\":%s,\"valid\":%b,\"dewShare\":%.4f,\"moldShare\":%.4f},\"electrical\":{\"on\":%b,\"vsAmbient\":%b},"
                        + "\"rate\":%.2f,\"rateAlarm\":%.1f,\"rateHigh\":%b",
                warming, min, max, center, spot, minI % W, minI / W, maxI % W, maxI / W, spotX, spotY,
                fps, camFps, SCALE, renderMs, viewLo, viewHi, viewEq, phase, PALETTES[palette], denoise, quarter(), mirror, outW(), outH(), equalize, flatFieldLeft > 0, frames,
                alarm, markMax, markMin, markCenter, markSpot, scaleBar, fahrenheit, alarmOn, alarmC, rangeLock, rangeLo, rangeHi, regionsJson(),
                buildingOn, airC, rh, outC, dewC, moldC, num2(fRsiMin), num2(fRsiReq), envelopeValid, dewShare, moldShare, elecOn, elecVsAmbient,
                rate, rateAlarm, rateHigh);
    }

    private static String num2(float v) { return Float.isNaN(v) ? "null" : String.format(Locale.US, "%.3f", v); }

    private String regionsJson() {
        StringBuilder b = new StringBuilder("[");
        float[][] rs = regionStats;
        int i = 0;
        for (int[] r : regions) {
            if (i > 0) b.append(',');
            float[] st = i < rs.length ? rs[i] : null;
            b.append(String.format(Locale.US, "{\"x\":%d,\"y\":%d,\"w\":%d,\"h\":%d", r[0], r[1], r[2], r[3]));
            if (st != null) {
                b.append(String.format(Locale.US, ",\"min\":%.2f,\"max\":%.2f,\"avg\":%.2f", st[0], st[1], st[2]));
                if (!Float.isNaN(st[3])) b.append(String.format(Locale.US, ",\"fRsi\":%.3f", st[3]));
                if (st[5] >= 0) b.append(String.format(Locale.US, ",\"dT\":%.2f,\"severity\":%d,\"severityText\":\"%s\"", st[4], (int) st[5], Pro.SEVERITY[(int) st[5]]));
            }
            b.append('}');
            i++;
        }
        return b.append(']').toString();
    }

    // palettes: color stops, interpolated to 256 entries
    private static final int[] IRON = {0x000000, 0x1f0a5c, 0x6a0a8a, 0xb5187a, 0xe8452e, 0xf98b0a, 0xfccc2a, 0xffffe0};
    private static final int[] RAINBOW = {0x000080, 0x0000ff, 0x00ffff, 0x00ff00, 0xffff00, 0xff8000, 0xff0000, 0xffffff};
    private static final int[] LAVA = {0x000000, 0x2b0f54, 0x7a1c6b, 0xc8323c, 0xf5751a, 0xfbd23c, 0xffffff};
    private static final int[] ARCTIC = {0x000010, 0x0b2a6f, 0x1f6fbf, 0x5fc8e8, 0xd9f6ff, 0xffd27a, 0xff7a1a};
    private static final int[] MEDICAL = {0x000000, 0x0000aa, 0x00aaaa, 0x00aa00, 0xaaaa00, 0xaa0000, 0xffffff};

    /** Grayscale with the top 15% in red: hot spots stand out on a neutral image. */
    private static int[] redHot() {
        int[] l = new int[256];
        for (int i = 0; i < 256; i++) {
            if (i >= 218) { int r = 180 + (i - 218) * 2; l[i] = 0xff000000 | Math.min(255, r) << 16 | 20 << 8 | 20; }
            else { int g = i * 230 / 218; l[i] = 0xff000000 | g << 16 | g << 8 | g; }
        }
        return l;
    }

    private static int[] lut(int[] stops) {
        int[] l = new int[256];
        for (int i = 0; i < 256; i++) {
            float f = i / 255f * (stops.length - 1);
            int k = Math.min((int) f, stops.length - 2);
            float u = f - k;
            int a = stops[k], b = stops[k + 1];
            int r = (int) (((a >> 16) & 255) * (1 - u) + ((b >> 16) & 255) * u);
            int g = (int) (((a >> 8) & 255) * (1 - u) + ((b >> 8) & 255) * u);
            int bl = (int) ((a & 255) * (1 - u) + (b & 255) * u);
            l[i] = 0xff000000 | r << 16 | g << 8 | bl;
        }
        return l;
    }
}
