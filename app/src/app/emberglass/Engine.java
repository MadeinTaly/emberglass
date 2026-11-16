package app.emberglass;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.Build;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Everything that is not UI: the camera, the frame pipeline, the web server, settings, commands and
 * long-term monitoring. One instance per process, so it survives the activity (screen off, app in
 * the background) while the monitoring service keeps the process alive.
 */
final class Engine implements Web.Commands {
    static final int PORT = 8080;
    private static final String ACTION_PERMISSION = "app.emberglass.USB_PERMISSION";
    private static Engine instance;

    static synchronized Engine get(Context c) {
        if (instance == null) {
            instance = new Engine(c.getApplicationContext());
            instance.start();
        }
        return instance;
    }

    final Context ctx;
    final Thermal th = new Thermal();
    final Monitor monitor;
    final Recorder recorder = new Recorder(this);
    /** Save the radiometric frames next to each video (~2.4 MB/s). */
    volatile boolean recordRaw = false;
    private final UsbManager usb;
    private final ScheduledExecutorService cmdThread = Executors.newSingleThreadScheduledExecutor();
    private Web web;
    volatile Uvc cam;
    volatile Tiny tiny;
    volatile String status = "no camera";
    volatile int gain = -1, emissivity = -1, vtemp = -1, shutterVtemp = -1;
    volatile String serial = "", firmware = "";
    private volatile boolean vtempBroken;
    volatile boolean replaying;
    private volatile boolean stopped;

    private Engine(Context ctx) {
        this.ctx = ctx;
        this.usb = (UsbManager) ctx.getSystemService(Context.USB_SERVICE);
        this.monitor = new Monitor(this);
    }

    private final BroadcastReceiver usbReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context c, Intent i) {
            UsbDevice d = i.getParcelableExtra(UsbManager.EXTRA_DEVICE);
            String a = i.getAction();
            if (ACTION_PERMISSION.equals(a)) {
                if (i.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false) && d != null) openCamera(d);
                else setStatus("USB permission denied");
            } else if (UsbManager.ACTION_USB_DEVICE_DETACHED.equals(a)) {
                if (cam != null) monitor.event("camera unplugged");
                closeCamera();
                setStatus("camera unplugged");
                th.resetStream("disconnected");
            } else if (UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(a)) {
                th.resetStream("connecting");
                findCamera();   // auto-resume, also while monitoring in the background
            }
        }
    };

    private void start() {
        Debug.init(ctx);
        loadSettings();
        IntentFilter f = new IntentFilter(ACTION_PERMISSION);
        f.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        f.addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(usbReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        else ctx.registerReceiver(usbReceiver, f);
        try {
            web = new Web(th, this, readAsset("index.html"));
            web.start(PORT);
        } catch (IOException e) {
            setStatus("web server failed: " + e.getMessage());
        }
        // sensor temperature, every 2 s
        cmdThread.scheduleWithFixedDelay(() -> {
            Tiny t = tiny;
            if (t == null || vtempBroken) return;
            try { vtemp = t.sensorVtemp(); shutterVtemp = t.shutterVtemp(); } catch (IOException e) { vtempBroken = true; }
        }, 2, 2, TimeUnit.SECONDS);
    }

    /** Stops everything; the next get() starts fresh. */
    void shutdown() {
        synchronized (Engine.class) { if (instance == this) instance = null; }
        stopped = true;
        replaying = false;
        monitor.stop();
        try { ctx.unregisterReceiver(usbReceiver); } catch (IllegalArgumentException ignored) { }
        closeCamera();
        if (web != null) web.stop();
        cmdThread.shutdownNow();
    }

    void setStatus(String s) {
        if (!s.equals(status)) Debug.log("status: " + s);
        status = s;
    }

    // --- camera ---------------------------------------------------------------------

    void findCamera() {
        if (cam != null || stopped) return;
        UsbDevice pick = null;
        for (UsbDevice d : usb.getDeviceList().values()) {
            if (!Uvc.isVideo(d)) continue;
            if (pick == null || d.getVendorId() == 0x0bda) pick = d;
        }
        if (pick == null) {
            if (!startReplay()) { setStatus("plug in the thermal camera"); th.phase = "disconnected"; }
            return;
        }
        if (usb.hasPermission(pick)) { openCamera(pick); return; }
        setStatus("waiting for USB permission");
        Intent i = new Intent(ACTION_PERMISSION).setPackage(ctx.getPackageName());
        int flags = Build.VERSION.SDK_INT >= 31 ? PendingIntent.FLAG_MUTABLE : 0;
        usb.requestPermission(pick, PendingIntent.getBroadcast(ctx, 0, i, flags));
    }

    private synchronized void openCamera(UsbDevice d) {
        if (cam != null || stopped) return;
        replaying = false;
        String id = String.format(Locale.US, "%04x:%04x", d.getVendorId(), d.getProductId());
        try {
            Uvc u = Uvc.open(usb, d, Thermal.W, Thermal.H * 2);
            cam = u;
            tiny = new Tiny(u.conn);
            vtempBroken = false;
            setStatus("camera " + id + " warming up (" + u.info + ")");
            th.resetStream("warming");
            monitor.event("camera connected " + id);
            Thread reader = new Thread(() -> readLoop(u), "uvc-read");
            reader.setPriority(Thread.MAX_PRIORITY);
            reader.start();
            cmdThread.execute(this::initCommands);
        } catch (IOException e) {
            setStatus("camera " + id + ": " + e.getMessage());
        }
    }

    private void readLoop(Uvc u) {
        ByteBuffer buf = ByteBuffer.allocateDirect(u.frameSize);
        while (true) {
            int n = Uvc.read(buf);
            if (n < 0) break;
            buf.rewind();
            th.submit(buf);
        }
        u.release();
        if (cam == u) { cam = null; tiny = null; }
    }

    /**
     * Development/demo source: loops recorded 256x384 YUYV frames from
     * Android/data/app.emberglass/files/replay.raw at 25 fps when no camera is attached.
     */
    private boolean startReplay() {
        File f = new File(ctx.getExternalFilesDir(null), "replay.raw");
        int size = Thermal.W * Thermal.H * 2 * 2;
        if (replaying || !f.isFile() || f.length() < size) return replaying;
        replaying = true;
        setStatus("replay: " + f.getName() + " (no camera)");
        new Thread(() -> {
            ByteBuffer buf = ByteBuffer.allocateDirect(size);
            byte[] b = new byte[size];
            while (replaying && cam == null) {
                try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
                    while (replaying && cam == null && in.read(b) == size) {
                        buf.clear();
                        buf.put(b).rewind();
                        th.submit(buf);
                        Thread.sleep(40);
                    }
                } catch (IOException | InterruptedException e) { break; }
            }
            replaying = false;
        }, "replay").start();
        return true;
    }

    private void closeCamera() {
        Uvc u = cam;
        cam = null;
        tiny = null;
        if (u != null) u.close();
    }

    /** Runs once per stream: shortens the start-up shutter delays, reads device info, re-applies corrections. */
    private void initCommands() {
        Tiny t = tiny;
        if (t == null) return;
        try {
            t.waitAlive(8000);
            try { t.setAutoShutter(9, 1); t.setAutoShutter(10, 1); } catch (IOException ignored) { }
            try { serial = t.info(7, 16); firmware = t.info(5, 50); } catch (IOException ignored) { }
            applyCorrections(t);
            try { gain = t.getTpd(Tiny.TPD_GAIN); emissivity = t.getTpd(Tiny.TPD_EMISSIVITY); } catch (IOException ignored) { }
            Uvc u = cam;
            setStatus("camera ready" + (u != null ? " (" + u.info + ")" : ""));
        } catch (IOException e) {
            setStatus("command channel not answering: " + e.getMessage() + " (video still works)");
            vtempBroken = true;
        }
    }

    // measurement corrections, sent to the camera (TPD parameters, SDK units)
    volatile float corrEmissivity = 0.95f, corrReflectedC = 25f, corrAmbientC = 25f, corrDistanceM = 1f;
    private volatile boolean corrSet;   // only push corrections the user has set

    private void applyCorrections(Tiny t) {
        if (!corrSet) return;
        try {
            t.setTpd(Tiny.TPD_EMISSIVITY, Math.max(1, Math.min(128, Math.round(corrEmissivity * 128))));
            t.setTpd(Tiny.TPD_REFLECTED_K, Math.round(corrReflectedC + 273.15f));
            t.setTpd(Tiny.TPD_AMBIENT_K, Math.round(corrAmbientC + 273.15f));
            t.setTpd(Tiny.TPD_DISTANCE, Math.max(0, Math.min(25600, Math.round(corrDistanceM * 128))));   // 128 = 1 m
        } catch (IOException e) {
            setStatus("corrections not applied: " + e.getMessage());
        }
    }

    // --- commands shared by the phone UI and the web page ------------------------------

    @Override public String run(String name, String v) throws Exception {
        if (name == null) throw new IllegalArgumentException("missing name");
        Debug.log("cmd " + name + (v != null ? " " + v : ""));
        switch (name) {
            case "palette": {
                int p = java.util.Arrays.asList(Thermal.PALETTES).indexOf(v);
                th.palette = p >= 0 ? p : (th.palette + 1) % Thermal.PALETTES.length;
                save("palette", Thermal.PALETTES[th.palette]);
                break;
            }
            case "denoise":
                th.denoise = Math.max(0, Math.min(3, Integer.parseInt(v)));
                save("denoise", String.valueOf(th.denoise));
                break;
            case "rotate": {   // 90 degrees more, for the way the phone is held right now
                int r = th.displayRot & 3;
                th.rotFor[r] = (th.rotFor[r] + 1) & 3;
                save("rot" + r, String.valueOf(th.rotFor[r]));
                break;
            }
            case "mirror":
                th.mirror = !th.mirror;
                save("mirror", th.mirror ? "1" : "0");
                break;
            case "set": {   // v = key:value
                int c = v == null ? -1 : v.indexOf(':');
                if (c < 0) throw new IllegalArgumentException("set needs key:value");
                applySetting(v.substring(0, c), v.substring(c + 1), true);
                break;
            }
            case "spot": {   // coordinates on the displayed (rotated) image
                String[] xy = v.split(",");
                int[] p = th.toSensor(Integer.parseInt(xy[0]) + 0.5f, Integer.parseInt(xy[1]) + 0.5f);
                th.spotX = p[0];
                th.spotY = p[1];
                break;
            }
            case "region": {   // "x,y,w,h" adds; "clear"; "del:<n>" (1-based)
                if ("clear".equals(v)) th.regions.clear();
                else if (v != null && v.startsWith("del:")) {
                    int n = Integer.parseInt(v.substring(4)) - 1;
                    if (n >= 0 && n < th.regions.size()) th.regions.remove(n);
                } else {   // x,y,w,h on the displayed image; stored in sensor coordinates
                    String[] r = v.split(",");
                    int x = Integer.parseInt(r[0]), y = Integer.parseInt(r[1]), w = Integer.parseInt(r[2]), h = Integer.parseInt(r[3]);
                    int[] a = th.toSensor(x + 0.5f, y + 0.5f), b = th.toSensor(x + w - 0.5f, y + h - 0.5f);
                    th.addRegion(Math.min(a[0], b[0]), Math.min(a[1], b[1]), Math.abs(a[0] - b[0]) + 1, Math.abs(a[1] - b[1]) + 1);
                }
                saveRegions();
                break;
            }
            case "flatfield":
                if ("clear".equals(v)) th.clearFlatField(); else th.startFlatField();
                break;
            case "ffc": onCam(t -> t.ffc()); monitor.event("manual FFC"); break;
            case "gain": {
                int g = v == null ? (gain == 1 ? 0 : 1) : Integer.parseInt(v);
                onCam(t -> { t.setTpd(Tiny.TPD_GAIN, g); gain = t.getTpd(Tiny.TPD_GAIN); });
                monitor.event(g == 1 ? "gain high" : "gain low");
                break;
            }
            case "emissivity": applySetting("emissivity", v, true); break;
            case "monitor":
                if ("start".equals(v)) startMonitoring();
                else if ("stop".equals(v)) stopMonitoring();
                else throw new IllegalArgumentException("monitor: start or stop");
                break;
            case "photo": return "{\"ok\":true,\"saved\":\"" + savePhoto() + "\"}";
            case "record":
                if ("stop".equals(v) || (v == null && recorder.running())) recorder.stop();
                else recorder.start(recordRaw);
                break;
            default: throw new IllegalArgumentException("unknown command " + name);
        }
        return "{\"ok\":true}";
    }

    private static int clamp(int v, int n) { return Math.max(0, Math.min(n - 1, v)); }

    interface CamAction { void run(Tiny t) throws IOException; }

    /** Camera commands run on one background thread; errors show up in the status line. */
    void onCam(CamAction a) {
        Tiny t = tiny;
        if (t == null) throw new IllegalStateException("no camera");
        cmdThread.execute(() -> {
            try { a.run(t); } catch (IOException e) { setStatus("command failed: " + e.getMessage()); }
        });
    }

    /** Keeps the stream and the web server alive with the screen off or the phone locked. */
    volatile boolean backgroundStream = false;

    void startMonitoring() {
        monitor.start();
        keepAlive(true);
    }

    void stopMonitoring() {
        monitor.stop();
        keepAlive(backgroundStream);
    }

    /** The foreground service holds CPU and Wi-Fi while monitoring or background streaming needs them. */
    void keepAlive(boolean on) {
        Intent i = new Intent(ctx, MonitorService.class);
        try {
            if (on) { if (Build.VERSION.SDK_INT >= 26) ctx.startForegroundService(i); else ctx.startService(i); }
            else ctx.stopService(i);
        } catch (RuntimeException e) {   // not allowed from the background on recent Android: retried when the app opens
            setStatus("background service: " + e.getMessage());
        }
    }

    @Override public String statusJson() {
        return th.json() + String.format(Locale.US,
                ",\"status\":\"%s\",\"gain\":\"%s\",\"emissivity\":%s,\"sensorVtemp\":%d,\"shutterVtemp\":%d,"
                        + "\"serial\":\"%s\",\"firmware\":\"%s\",\"corrections\":{\"emissivity\":%.2f,\"reflectedC\":%.1f,"
                        + "\"ambientC\":%.1f,\"distanceM\":%.2f},\"monitor\":%s}",
                status.replace("\"", "'"), gain == 1 ? "high" : gain == 0 ? "low" : "?",
                emissivity > 0 ? String.format(Locale.US, "%.2f", emissivity / 128f) : "null",
                vtemp, shutterVtemp, serial.replace("\"", ""), firmware.replace("\"", ""),
                corrEmissivity, corrReflectedC, corrAmbientC, corrDistanceM, monitor.json() + recorderJson());
    }

    private String recorderJson() {
        return String.format(Locale.US, ",\"recording\":{\"on\":%b,\"codec\":\"%s\",\"seconds\":%d,\"frames\":%d,\"raw\":%b}",
                recorder.running(), recorder.codecName, recorder.running() ? (System.currentTimeMillis() - recorder.startMs) / 1000 : 0,
                recorder.frames, recordRaw);
    }

    /** Camera and measurement settings, for photo and video metadata. */
    String photoMetaJson() {
        return String.format(Locale.US, "{\"serial\":\"%s\",\"firmware\":\"%s\",\"usb\":\"%s\",\"range\":\"%s\","
                        + "\"cameraEmissivity\":%s,\"emissivity\":%.2f,\"reflectedC\":%.1f,\"ambientC\":%.1f,\"distanceM\":%.2f}",
                serial.replace("\"", ""), firmware.replace("\"", ""), cam != null ? cam.info.replace("\"", "") : "",
                gain == 1 ? "high gain -15..150 C" : gain == 0 ? "low gain 50..550 C" : "unknown",
                emissivity > 0 ? String.format(Locale.US, "%.3f", emissivity / 128f) : "null",
                corrEmissivity, corrReflectedC, corrAmbientC, corrDistanceM);
    }

    @Override public String history(double since) { return monitor.historyJson(since); }

    @Override public File logFile() { return monitor.file(); }

    private final Map<String, byte[]> assetCache = new ConcurrentHashMap<>();

    @Override public byte[] asset(String name) {
        byte[] b = assetCache.get(name);
        if (b != null) return b;
        try { b = readAsset(name); } catch (IOException e) { return null; }
        assetCache.put(name, b);
        return b;
    }

    byte[] readAsset(String name) throws IOException {
        try (InputStream in = ctx.getAssets().open(name)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            for (int n; (n = in.read(b)) > 0; ) out.write(b, 0, n);
            return out.toByteArray();
        }
    }

    static String ip() {
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces()))
                for (InetAddress a : Collections.list(n.getInetAddresses()))
                    if (!a.isLoopbackAddress() && a instanceof Inet4Address) return a.getHostAddress();
        } catch (Exception ignored) { }
        return "<phone-ip>";
    }

    // --- settings (SharedPreferences, shared by the phone and the web page) --------------

    private SharedPreferences prefs;
    volatile boolean keepScreenOn = true, remoteControl = true;
    volatile String webPin = "";
    static final String[] SETTINGS = {"palette", "denoise", "rot0", "rot1", "rot2", "rot3", "mirror", "equalize", "markMax", "markMin", "markCenter", "markSpot",
            "scaleBar", "fahrenheit", "alarmOn", "alarmC", "rangeLock", "rangeLo", "rangeHi", "keepScreenOn",
            "remoteControl", "webPin", "emissivity", "reflectedC", "ambientC", "distanceM", "logInterval", "snapshotMin",
            "buildingOn", "airC", "rh", "outC", "elecOn", "elecRef", "rateAlarm", "backgroundStream", "recordRaw"};

    private void loadSettings() {
        prefs = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE);
        for (String k : SETTINGS) {
            String v = prefs.getString(k, null);
            if (v == null) continue;
            try { applySetting(k, v, false); } catch (RuntimeException ignored) { }   // stale value: keep the default
        }
        String r = prefs.getString("regions", "");
        for (String one : r.split(";")) {
            String[] p = one.split(",");
            if (p.length == 4) try {
                th.addRegion(Integer.parseInt(p[0]), Integer.parseInt(p[1]), Integer.parseInt(p[2]), Integer.parseInt(p[3]));
            } catch (RuntimeException ignored) { }
        }
    }

    void save(String k, String v) { if (prefs != null) prefs.edit().putString(k, v).apply(); }

    private void saveRegions() {
        StringBuilder b = new StringBuilder();
        for (int[] r : th.regions) b.append(r[0]).append(',').append(r[1]).append(',').append(r[2]).append(',').append(r[3]).append(';');
        save("regions", b.toString());
    }

    private static boolean on(String v) { return "1".equals(v) || "true".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v); }

    /** Temperatures are always stored and sent in Celsius; the UIs convert for display. */
    void applySetting(String k, String v, boolean persist) {
        switch (k) {
            case "palette": {
                int p = java.util.Arrays.asList(Thermal.PALETTES).indexOf(v);
                if (p < 0) throw new IllegalArgumentException("unknown palette " + v);
                th.palette = p;
                break;
            }
            case "denoise": th.denoise = Math.max(0, Math.min(3, Integer.parseInt(v))); break;
            case "rot0": case "rot1": case "rot2": case "rot3":
                th.rotFor[k.charAt(3) - '0'] = Integer.parseInt(v) & 3;
                break;
            case "mirror": th.mirror = on(v); break;
            case "equalize": th.equalize = on(v); break;
            case "markMax": th.markMax = on(v); break;
            case "markMin": th.markMin = on(v); break;
            case "markCenter": th.markCenter = on(v); break;
            case "markSpot": th.markSpot = on(v); break;
            case "scaleBar": th.scaleBar = on(v); break;
            case "fahrenheit": th.fahrenheit = on(v); break;
            case "alarmOn": th.alarmOn = on(v); break;
            case "rangeLock": th.rangeLock = on(v); break;
            case "alarmC": th.alarmC = temp(v); break;
            case "rangeLo": th.rangeLo = temp(v); break;
            case "rangeHi": th.rangeHi = temp(v); break;
            case "keepScreenOn": keepScreenOn = on(v); break;
            case "recordRaw": recordRaw = on(v); break;
            case "backgroundStream":
                backgroundStream = on(v);
                if (persist) keepAlive(backgroundStream || monitor.active());
                break;
            case "remoteControl": remoteControl = on(v); break;
            case "webPin":
                if (!v.matches("\\d{0,8}")) throw new IllegalArgumentException("PIN: up to 8 digits");
                webPin = v;
                break;
            case "emissivity": corrEmissivity = range(v, 0.01f, 1f); corrSet = true; pushCorrections(); break;
            case "reflectedC": corrReflectedC = range(v, -40f, 600f); corrSet = true; pushCorrections(); break;
            case "ambientC": corrAmbientC = range(v, -40f, 600f); corrSet = true; pushCorrections(); break;
            case "distanceM": corrDistanceM = range(v, 0f, 200f); corrSet = true; pushCorrections(); break;
            case "buildingOn": th.buildingOn = on(v); break;
            case "airC": th.airC = temp(v); break;
            case "rh": th.rh = range(v, 1f, 100f); break;
            case "outC": th.outC = temp(v); break;
            case "elecOn": th.elecOn = on(v); break;
            case "elecRef":
                if (!"area1".equals(v) && !"ambient".equals(v)) throw new IllegalArgumentException("elecRef: area1 or ambient");
                th.elecVsAmbient = "ambient".equals(v);
                break;
            case "rateAlarm": th.rateAlarm = range(v, 0f, 100f); break;
            case "material": {   // shortcut: sets the emissivity from the materials table
                String m = null;
                for (String x : Pro.MATERIALS) if (x.equalsIgnoreCase(v) || x.toLowerCase(Locale.US).startsWith(v.toLowerCase(Locale.US))) m = x;
                if (m == null) throw new IllegalArgumentException("unknown material " + v);
                applySetting("emissivity", String.valueOf(Pro.materialEmissivity(m)), persist);
                return;
            }
            case "logInterval": monitor.intervalSec = (int) range(v, 1f, 3600f); break;
            case "snapshotMin": monitor.snapshotMin = (int) range(v, 0f, 1440f); break;
            default: throw new IllegalArgumentException("unknown setting " + k);
        }
        if (persist) save(k, v);
    }

    private void pushCorrections() {
        Tiny t = tiny;
        if (t != null) cmdThread.execute(() -> {
            applyCorrections(t);
            try { emissivity = t.getTpd(Tiny.TPD_EMISSIVITY); } catch (IOException ignored) { }
        });
    }

    private static float temp(String v) { return range(v, -40f, 600f); }

    private static float range(String v, float lo, float hi) {
        float f = Float.parseFloat(v);
        if (!(f >= lo && f <= hi)) throw new IllegalArgumentException("value must be " + lo + " to " + hi);
        return f;
    }

    @Override public String pin() { return webPin; }

    @Override public boolean remoteControl() { return remoteControl; }

    // --- photos ------------------------------------------------------------------------

    /** Saves the current view to the gallery and the radiometric frame next to the app's files. */
    String savePhoto() throws IOException {
        Bitmap b = th.freshBitmap(1500);
        if (b == null) throw new IllegalStateException("no image yet");
        java.util.Date now = new java.util.Date();
        String name = "emberglass_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now);
        String tiff = saveTiff(name, now);
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name + ".jpg");
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Emberglass");
            android.net.Uri uri = ctx.getContentResolver().insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) throw new IOException("gallery not available");
            try (java.io.OutputStream o = ctx.getContentResolver().openOutputStream(uri)) {
                b.compress(Bitmap.CompressFormat.JPEG, 92, o);
            }
            return "Pictures/Emberglass/" + name + ".jpg + " + tiff;
        }
        File f = new File(dir(android.os.Environment.DIRECTORY_PICTURES), name + ".jpg");
        writeJpeg(b, f);
        return f.getAbsolutePath() + " + " + tiff;
    }

    /**
     * Radiometric TIFF: float32 degrees Celsius for every pixel, oriented as displayed, straight from the
     * camera's temperature data (no denoise or palette); metadata as JSON in the ImageDescription tag.
     */
    private String saveTiff(String name, java.util.Date when) throws IOException {
        short[] raw;
        synchronized (th.frameLock) { raw = th.raw.clone(); }
        int w = th.outW(), h = th.outH();
        float[] c = new float[w * h];
        for (int y = 0; y < h; y++)
            for (int x = 0; x < w; x++) {
                int[] p = th.toSensor(x + 0.5f, y + 0.5f);
                c[y * w + x] = (raw[p[1] * Thermal.W + p[0]] & 0xffff) / 64f - 273.15f;
            }
        String desc = "{\"format\":\"float32 degrees Celsius per pixel, oriented as displayed\",\"app\":\"Emberglass\","
                + "\"time\":\"" + new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US).format(when) + "\","
                + "\"camera\":" + photoMetaJson() + ",\"scene\":" + statusJson() + "}";
        byte[] data = Tiff.float32(w, h, c, desc, "Emberglass",
                new java.text.SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.US).format(when));
        if (Build.VERSION.SDK_INT >= 29) {
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name + ".tif");
            cv.put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/tiff");
            cv.put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "Documents/Emberglass");
            android.net.Uri uri = ctx.getContentResolver().insert(android.provider.MediaStore.Files.getContentUri("external"), cv);
            if (uri != null) {
                try (java.io.OutputStream o = ctx.getContentResolver().openOutputStream(uri)) { o.write(data); }
                return "Documents/Emberglass/" + name + ".tif";
            }
        }
        File f = new File(dir("radiometric"), name + ".tif");
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(data); }
        return f.getAbsolutePath();
    }

    File dir(String name) {
        File d = new File(ctx.getExternalFilesDir(null), name);
        if (!d.isDirectory() && !d.mkdirs()) d = new File(ctx.getFilesDir(), name);
        d.mkdirs();
        return d;
    }

    void writeRaw(File f) throws IOException {
        short[] raw;
        synchronized (th.frameLock) { raw = th.raw.clone(); }
        ByteBuffer bb = ByteBuffer.allocate(raw.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        bb.asShortBuffer().put(raw);
        try (FileOutputStream o = new FileOutputStream(f)) { o.write(bb.array()); }
    }

    static void writeJpeg(Bitmap b, File f) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) { b.compress(Bitmap.CompressFormat.JPEG, 90, o); }
    }
}
