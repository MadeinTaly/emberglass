package app.emberglass;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Locale;

/** The phone UI. All state lives in {@link Engine}, which outlives this activity while monitoring. */
public class MainActivity extends Activity {
    // Arcade theme (same tokens as the web page, converted from OKLCH)
    static final int PAPER = 0xff0f0415, PAPER_2 = 0xff190d20, SCREEN = 0xff000001, INK = 0xfff2ecf5,
            MUTED = 0xffa79dac, RULE = 0xff41304b, EMBER = 0xfff85200, ACID = 0xffd5e51b;
    /** Trend series colors: validated categorical palette (dataviz reference, dark), fixed order. */
    static final int[] SERIES = {0xffd95926, 0xff3987e5, 0xff199e70, 0xffc98500, 0xffd55181, 0xff0ca30c, 0xff9085e9, 0xffe66767};

    private Engine e;
    private Thermal th;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Typeface display, figures;
    private ThermalView live;          // GPU view (OpenGL ES 3)
    private ImageView image;           // fallback for phones without ES 3: CPU composite
    private TextView stats, maxView, minView, spotView, fpsView, panelText;
    private Button recBtn, monitorBtn, areasBtn, paletteBtn, highBtn, lowBtn, contrastBtn, buildingBtn, elecBtn;
    private TrendView trend;
    private LinearLayout panel;
    private final Button[] tabs = new Button[4];
    private int tab = -1;               // open tab, -1 = panel closed (largest image)
    private boolean landscape, areaMode, visible, alarmed;
    private float downX, downY;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        e = Engine.get(this);
        th = e.th;
        if (e.backgroundStream) e.keepAlive(true);   // saved setting: start the service while in the foreground
        applyWindowFlags();
        buildUi();
        ui.post(refresh);
        askBattery(false);
        // UVC devices need the CAMERA permission (target SDK >= 28) before USB access.
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.CAMERA}, 1);
        else e.findCamera();
    }

    @Override public void onRequestPermissionsResult(int code, String[] p, int[] r) {
        if (code != 1) return;
        if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) e.findCamera();
        else e.setStatus("camera permission denied: UVC cameras need it");
    }

    @Override protected void onNewIntent(Intent i) {
        super.onNewIntent(i);
        e.findCamera();
    }

    @Override protected void onResume() {
        super.onResume();
        visible = true;
        applyWindowFlags();
        Debug.activity = this;
        Debug.ui = this::debugUi;
        ((android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE)).registerDisplayListener(rotation, ui);
        rotation.onDisplayChanged(android.view.Display.DEFAULT_DISPLAY);
    }

    /**
     * A 180 degree turn (USB port up) or left/right landscape swap keeps the same screen size, so no
     * configuration change arrives: follow the display rotation directly, each one has its own image rotation.
     */
    private final android.hardware.display.DisplayManager.DisplayListener rotation = new android.hardware.display.DisplayManager.DisplayListener() {
        @Override public void onDisplayAdded(int id) { }
        @Override public void onDisplayRemoved(int id) { }
        @Override public void onDisplayChanged(int id) {
            int r = getWindowManager().getDefaultDisplay().getRotation();
            if (r != th.displayRot) {
                th.displayRot = r;
                Debug.log("display rotation " + r + " -> image quarter " + th.quarter());
                if (live != null) live.invalidate();
            }
        }
    };

    @Override protected void onPause() {
        visible = false;
        ((android.hardware.display.DisplayManager) getSystemService(DISPLAY_SERVICE)).unregisterDisplayListener(rotation);
        if (Debug.activity == this) { Debug.activity = null; Debug.ui = null; }
        super.onPause();
    }

    /** UI actions for end-to-end tests over the debug channel: tab:N, settings, back, areas, recreate. */
    private String debugUi(String action) throws Exception {
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        String[] out = {"ok"};
        ui.post(() -> {
            try {
                if (action.startsWith("tab:")) openTab(Integer.parseInt(action.substring(4)));
                else if (action.equals("settings")) showSettings();
                else if (action.equals("areas")) areaMode = !areaMode;
                else if (action.equals("back")) onBackPressed();
                else if (action.equals("recreate")) buildUi();
                else out[0] = "unknown action " + action;
            } catch (Exception ex) { out[0] = "error: " + ex; }
            done.countDown();
        });
        done.await(3, java.util.concurrent.TimeUnit.SECONDS);
        Debug.log("ui " + action + ": " + out[0]);
        return out[0];
    }

    @Override protected void onDestroy() {
        ui.removeCallbacksAndMessages(null);
        // leaving the app stops everything, unless a monitoring session is running
        if (isFinishing() && !e.monitor.active() && !e.backgroundStream) e.shutdown();
        super.onDestroy();
    }

    @Override public void onConfigurationChanged(android.content.res.Configuration c) {
        super.onConfigurationChanged(c);
        buildUi();   // camera, server and settings live in the engine; only the views are rebuilt
    }

    private void applyWindowFlags() {
        if (e.keepScreenOn) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private void safeRun(String name, String v) {
        try { e.run(name, v); } catch (Exception ex) { e.setStatus(ex.getMessage()); }
    }

    // --- layout ------------------------------------------------------------------------

    private boolean gles3() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        return am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }

    private void buildUi() {
        if (display == null) {
            display = Typeface.createFromAsset(getAssets(), "fonts/bungee.ttf");
            figures = Typeface.createFromAsset(getAssets(), "fonts/silkscreen.ttf");
        }
        landscape = getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE;
        th.displayRot = getWindowManager().getDefaultDisplay().getRotation();   // each orientation keeps its own image rotation
        getWindow().setStatusBarColor(PAPER);
        getWindow().setNavigationBarColor(PAPER);
        if (Build.VERSION.SDK_INT >= 28) {   // landscape viewfinder uses the notch area too
            WindowManager.LayoutParams lp = getWindow().getAttributes();
            lp.layoutInDisplayCutoutMode = landscape
                    ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                    : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT;
            getWindow().setAttributes(lp);
        }
        getWindow().getDecorView().setSystemUiVisibility(landscape
                ? View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                : 0);

        // the image: GPU when available
        View viewer;
        if (gles3()) {
            live = new ThermalView(this, th, SCREEN);
            live.tap = this::onImageTap;
            live.footer(false);
            live.clearAreas(() -> safeRun("region", "clear"), landscape);   // the screen shows max/min in the readings; the footer is for exported photos and video
            image = null;
            viewer = live;
        } else {
            live = null;
            image = new ImageView(this);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setBackgroundColor(SCREEN);
            image.setOnTouchListener(this::onImageTouch);
            viewer = image;
        }

        // readings, always visible
        LinearLayout score = new LinearLayout(this);
        score.setPadding(dp(4), dp(6), 0, dp(2));
        maxView = cell(score, "Max", EMBER);
        minView = cell(score, "Min", ACID);
        spotView = cell(score, "Spot", INK);
        fpsView = cell(score, "fps", INK);
        stats = text(13, ACID);

        // tabs
        LinearLayout tabBar = new LinearLayout(this);
        String[] names = {"Measure", "Pro", "Monitor", "Info"};
        for (int i = 0; i < 4; i++) {
            final int n = i;
            Button t = new Button(this);
            t.setText(names[i]);
            t.setAllCaps(false);
            t.setTextSize(14);
            t.setTypeface(Typeface.DEFAULT_BOLD);
            t.setStateListAnimator(null);
            t.setMinHeight(dp(48));
            t.setOnClickListener(v -> openTab(tab == n ? -1 : n));   // tap the open tab to close the panel
            tabs[i] = t;
            tabBar.addView(t, new LinearLayout.LayoutParams(0, -2, 1));
        }
        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(12), dp(8), dp(12), dp(8));
        ScrollView panelScroll = new ScrollView(this);
        panelScroll.addView(panel);

        if (landscape) { viewerView = null; buildCameraLayout(viewer, score, tabBar, panelScroll); return; }

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.VERTICAL);
        controls.setBackgroundColor(PAPER);
        controls.setPadding(dp(8), 0, dp(8), 0);
        controls.addView(score);
        controls.addView(stats);
        LinearLayout capture = new LinearLayout(this);
        Button photo = button("Photo", false, v -> onButton("photo"));
        recBtn = button("● Rec", true, v -> onButton("record"));
        Button rotate = button("Rotate 90°", false, v -> safeRun("rotate", null));
        Button settings = button("Settings", false, v -> showSettings());
        for (Button b : new Button[]{photo, recBtn, rotate, settings}) capture.addView(b, weight());
        controls.addView(capture);
        controls.addView(tabBar);

        LinearLayout root = new LinearLayout(this);
        root.setBackgroundColor(PAPER);
        root.setOrientation(LinearLayout.VERTICAL);
        // the viewer is exactly as tall as the image (no black bands); the room left goes to big readings
        viewerView = viewer;
        root.addView(viewer, new LinearLayout.LayoutParams(-1, viewerHeight()));
        bigReadings(score);
        controls.removeView(score);
        root.addView(score, new LinearLayout.LayoutParams(-1, 0, 1));
        root.addView(controls);
        panelScroll.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        root.addView(panelScroll);
        setContentView(root);
        sheet = null;
        panelHeight = (int) (getResources().getDisplayMetrics().heightPixels * 0.34f);
        this.panelScroll = panelScroll;
        openTab(tab);
    }

    private View sheet;   // landscape: the translucent side sheet with the tabs
    private View viewerView;
    private int viewerAspect;   // outW * 1000 + outH the viewer was sized for

    /** Portrait: image height at full width, capped so the controls always fit. */
    private int viewerHeight() {
        int w = getResources().getDisplayMetrics().widthPixels, h = getResources().getDisplayMetrics().heightPixels;
        viewerAspect = th.outW() * 1000 + th.outH();
        return Math.min(w * th.outH() / th.outW(), (int) (h * 0.58f));
    }

    /** Portrait readings as a 2x2 grid of large figures filling the space under the image. */
    private void bigReadings(LinearLayout score) {
        java.util.List<View> cells = new java.util.ArrayList<>();
        for (int i = 0; i < score.getChildCount(); i++) cells.add(score.getChildAt(i));
        score.removeAllViews();
        score.setOrientation(LinearLayout.VERTICAL);
        score.setPadding(dp(16), dp(4), dp(16), dp(4));
        for (int r = 0; r < 2; r++) {
            LinearLayout row = new LinearLayout(this);
            for (int c = 0; c < 2; c++) {
                LinearLayout cell = (LinearLayout) cells.get(r * 2 + c);
                cell.setOrientation(LinearLayout.VERTICAL);
                cell.setGravity(android.view.Gravity.CENTER_VERTICAL);
                ((TextView) cell.getChildAt(0)).setTextSize(13);
                ((TextView) cell.getChildAt(1)).setTextSize(30);
                row.addView(cell, new LinearLayout.LayoutParams(0, -1, 1));
            }
            score.addView(row, new LinearLayout.LayoutParams(-1, 0, 1));
        }
    }

    /**
     * Landscape is a camera: the image fills the screen, the controls float over it. A column of round
     * buttons on the right (settings, rotate, shutter, record, panel), the readings top left, and the
     * tabs in a translucent sheet that slides over the image only when asked for.
     */
    private void buildCameraLayout(View viewer, LinearLayout score, LinearLayout tabBar, ScrollView panelScroll) {
        android.widget.FrameLayout root = new android.widget.FrameLayout(this);
        root.setBackgroundColor(0xff000000);
        root.addView(viewer, new android.widget.FrameLayout.LayoutParams(-1, -1));

        int col = dp(124);
        // readings, big, stacked down the left band the 4:3 image leaves free: nothing covers the picture
        score.setOrientation(LinearLayout.VERTICAL);
        float dens = getResources().getDisplayMetrics().density;
        int screenW = getResources().getDisplayMetrics().widthPixels, screenH = getResources().getDisplayMetrics().heightPixels;
        int band = Math.max(dp(110), (screenW - col - screenH * 4 / 3) / 2 - dp(8));   // free width left of a 4:3 image
        for (int i = 0; i < score.getChildCount(); i++) {
            LinearLayout c = (LinearLayout) score.getChildAt(i);
            c.setOrientation(LinearLayout.VERTICAL);
            c.setGravity(android.view.Gravity.CENTER_VERTICAL);
            c.setLayoutParams(new LinearLayout.LayoutParams(-1, 0, 1));   // four equal rows over the full height
            TextView label = (TextView) c.getChildAt(0), value = (TextView) c.getChildAt(1);
            label.setTextSize(13);
            value.setTextSize(Math.min(34, band / dens / 4.2f));   // as large as the band allows for "123.4"
        }
        score.setPadding(dp(14), dp(16), dp(8), dp(16));
        android.widget.FrameLayout.LayoutParams sp = new android.widget.FrameLayout.LayoutParams(band, -1, android.view.Gravity.START);
        root.addView(score, sp);
        stats.setBackground(glass(dp(10)));
        stats.setPadding(dp(12), dp(4), dp(12), dp(4));
        android.widget.FrameLayout.LayoutParams tp = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.START);
        tp.setMargins(dp(12), 0, 0, dp(12));
        root.addView(stats, tp);

        // the button column
        LinearLayout column = new LinearLayout(this);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
        column.setBackgroundColor(PAPER);
        column.setPadding(0, dp(12), 0, dp(12));
        // Arcade press surfaces with text labels (no symbol icons); Photo is the one filled control
        Button settings = button("Settings", false, v -> showSettings());
        Button rotate = button("Rotate", false, v -> safeRun("rotate", null));
        Button shutter = button("Photo", true, v -> onButton("photo"));
        recBtn = button("● Rec", false, v -> onButton("record"));
        View[] order = {settings, rotate, shutter, recBtn};
        for (int i = 0; i < order.length; i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(order[i] == shutter ? 64 : 52));
            lp.setMargins(dp(10), 0, dp(6), 0);
            column.addView(order[i], lp);
            if (i < order.length - 1) column.addView(new View(this), new LinearLayout.LayoutParams(1, 0, 1));   // even spacing
        }
        root.addView(column, new android.widget.FrameLayout.LayoutParams(col, -1, android.view.Gravity.END));

        // the side sheet: the open tab's panel, faded over the image
        LinearLayout sh = new LinearLayout(this);
        sh.setOrientation(LinearLayout.VERTICAL);
        sh.setBackgroundColor(0xb30f0415);   // faded: the image stays visible behind the panel
        sh.addView(panelScroll, new LinearLayout.LayoutParams(-1, 0, 1));
        android.widget.FrameLayout.LayoutParams shp = new android.widget.FrameLayout.LayoutParams(
                (int) (getResources().getDisplayMetrics().widthPixels * 0.42f), -1, android.view.Gravity.END);
        shp.rightMargin = col;
        shp.bottomMargin = dp(64);   // above the mode strip
        root.addView(sh, shp);
        // the tabs as a camera mode strip at the bottom of the image: tap to open, tap again to close
        tabBar.setBackground(glass(dp(24)));
        tabBar.setPadding(dp(6), 0, dp(6), 0);
        for (Button t : tabs) {
            t.setLayoutParams(new LinearLayout.LayoutParams(-2, dp(44)));
            t.setPadding(dp(16), 0, dp(16), 0);
            t.setMinWidth(0);
        }
        android.widget.FrameLayout.LayoutParams tbp = new android.widget.FrameLayout.LayoutParams(-2, -2, android.view.Gravity.BOTTOM | android.view.Gravity.CENTER_HORIZONTAL);
        tbp.bottomMargin = dp(10);
        tbp.rightMargin = col;
        root.addView(tabBar, tbp);
        sheet = sh;
        setContentView(root);
        this.panelScroll = panelScroll;
        openTab(tab);
    }

    /** Dark translucent rounded backing for text over the image. */
    private Drawable glass(int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(0xa6000000);
        g.setCornerRadius(radius);
        return g;
    }

    /** Round camera button; the shutter is a white ring around an ember disc. */
    private Button round(String label, int sizeDp, boolean shutter, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(shutter ? 0 : 18);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(INK);
        b.setStateListAnimator(null);
        b.setPadding(0, 0, 0, 0);
        b.setMinWidth(0);
        b.setMinHeight(0);
        GradientDrawable face = new GradientDrawable();
        face.setShape(GradientDrawable.OVAL);
        face.setColor(shutter ? EMBER : 0x80000000);
        face.setStroke(dp(shutter ? 5 : 2), shutter ? 0xffffffff : INK);
        GradientDrawable down = new GradientDrawable();
        down.setShape(GradientDrawable.OVAL);
        down.setColor(shutter ? 0xffffffff : 0xcc000000);
        down.setStroke(dp(shutter ? 5 : 2), ACID);
        StateListDrawable st = new StateListDrawable();
        st.addState(new int[]{android.R.attr.state_pressed}, down);
        st.addState(new int[]{}, face);
        b.setBackground(st);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp)));
        b.setOnClickListener(l);
        b.setContentDescription(shutter ? "Photo" : label);
        return b;
    }

    private ScrollView panelScroll;
    private int panelHeight;

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
        lp.rightMargin = dp(8);
        lp.topMargin = dp(6);
        lp.bottomMargin = dp(4);
        return lp;
    }

    private Button button(String label, boolean filled, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(filled ? PAPER : INK);
        b.setBackground(press(filled));
        b.setStateListAnimator(null);
        b.setMinHeight(dp(48));
        b.setPadding(dp(4), 0, dp(8), 0);
        b.setOnClickListener(l);
        return b;
    }

    /** Opens a tab in the bottom panel, or closes the panel (-1) to give the image the room. */
    private void openTab(int n) {
        tab = n;
        for (int i = 0; i < 4; i++) {
            boolean on = i == n;
            if (sheet != null) {   // mode strip over the image: the open tab in ember, the others plain
                tabs[i].setTextColor(on ? EMBER : INK);
                tabs[i].setBackgroundColor(0);
            } else {
                tabs[i].setTextColor(on ? PAPER : INK);
                tabs[i].setBackgroundColor(on ? EMBER : PAPER_2);
            }
        }
        panel.removeAllViews();
        paletteBtn = highBtn = lowBtn = contrastBtn = areasBtn = monitorBtn = buildingBtn = elecBtn = null;
        trend = null;
        panelText = null;
        if (sheet != null) {   // landscape: a faded sheet that fades in over the image and out again
            View sh = sheet;
            sh.animate().cancel();
            if (n < 0) sh.animate().alpha(0).setDuration(160).withEndAction(() -> sh.setVisibility(View.GONE)).start();
            else {
                if (sh.getVisibility() != View.VISIBLE) { sh.setAlpha(0); sh.setVisibility(View.VISIBLE); }
                sh.animate().alpha(1).setDuration(160).start();
            }
        }
        else panelScroll.getLayoutParams().height = n < 0 ? 0 : panelHeight;
        panelScroll.requestLayout();
        if (n < 0) return;
        switch (n) {
            case 0: {   // measure
                LinearLayout r1 = row(), r2 = row(), r3 = row();
                paletteBtn = button("Palette", false, v -> safeRun("palette", null));
                contrastBtn = button("Contrast", false, v -> trySet("equalize", th.equalize ? "0" : "1"));
                Button ffc = button("Shutter FFC", false, v -> safeRun("ffc", null));
                r1.addView(paletteBtn, weight()); r1.addView(contrastBtn, weight()); r1.addView(ffc, weight());
                highBtn = button("−15…150 °C", false, v -> safeRun("gain", "1"));
                lowBtn = button("50…550 °C", false, v -> safeRun("gain", "0"));
                r2.addView(highBtn, weight()); r2.addView(lowBtn, weight());
                areasBtn = button("Areas", false, v -> areaMode = !areaMode);
                Button clear = button("Clear areas", false, v -> safeRun("region", "clear"));
                Button mirror = button("Mirror", false, v -> safeRun("mirror", null));
                r3.addView(areasBtn, weight()); r3.addView(clear, weight()); r3.addView(mirror, weight());
                note("Range: high sensitivity up to 150 °C, or up to 550 °C for hot objects. Switching recalibrates for a few seconds.");
                panelText = text(13, INK);
                panel.addView(panelText);
                break;
            }
            case 1: {   // pro
                LinearLayout r = row();
                buildingBtn = button("Building", false, v -> trySet("buildingOn", th.buildingOn ? "0" : "1"));
                elecBtn = button("Electrical", false, v -> trySet("elecOn", th.elecOn ? "0" : "1"));
                r.addView(buildingBtn, weight()); r.addView(elecBtn, weight());
                note("Air, humidity, reference and alarms are in Settings.");
                panelText = text(13, INK);
                panel.addView(panelText);
                break;
            }
            case 2: {   // monitor
                LinearLayout r = row();
                monitorBtn = button("Start monitoring", false, v -> onButton("monitor"));
                r.addView(monitorBtn, weight());
                panelText = text(13, INK);
                panel.addView(panelText);
                trend = new TrendView(this);
                panel.addView(trend, new LinearLayout.LayoutParams(-1, dp(170)));
                break;
            }
            default: {  // info
                panelText = text(13, INK);
                panel.addView(panelText);
            }
        }
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(this);
        panel.addView(r);
        return r;
    }

    private void note(String t) {
        TextView v = text(12, MUTED);
        v.setText(t);
        panel.addView(v);
    }

    private void selected(Button b, boolean on) {
        if (b == null) return;
        b.setTextColor(on ? PAPER : INK);
        b.setBackground(press(on));
    }

    private void onButton(String id) {
        switch (id) {
            case "monitor":
                if (e.monitor.active()) { e.stopMonitoring(); break; }
                askNotifications();
                askBattery(true);
                e.startMonitoring();
                break;
            case "record":
                boolean stopping = e.recorder.running();
                sound(stopping ? android.media.MediaActionSound.STOP_VIDEO_RECORDING : android.media.MediaActionSound.START_VIDEO_RECORDING);
                new Thread(() -> {
                    try {
                        if (stopping) {
                            long secs = (System.currentTimeMillis() - e.recorder.startMs) / 1000;
                            e.recorder.stop();
                            toast(e.status.startsWith("video saved")
                                    ? String.format(Locale.US, "Video saved, %d:%02d, %s", secs / 60, secs % 60, e.recorder.codecName) : e.status);
                        } else {
                            e.recorder.start(e.recordRaw);
                            toast("Recording (" + e.recorder.codecName + ")");
                        }
                    } catch (Exception ex) { e.setStatus("video failed: " + ex.getMessage()); toast("Video failed: " + ex.getMessage()); }
                }).start();
                break;
            case "photo":
                sound(android.media.MediaActionSound.SHUTTER_CLICK);
                if (live != null) live.flash();
                new Thread(() -> {
                    try { e.setStatus("saved " + e.savePhoto()); toast("Photo saved: JPEG + radiometric TIFF"); }
                    catch (Exception ex) { e.setStatus("photo failed: " + ex.getMessage()); toast("Photo failed: " + ex.getMessage()); }
                }).start();
                break;
            default: safeRun(id, null);
        }
    }

    private android.media.MediaActionSound shutter;

    /** The system camera sounds (shutter, start and stop recording). */
    private void sound(int which) {
        if (shutter == null) { shutter = new android.media.MediaActionSound(); shutter.load(android.media.MediaActionSound.SHUTTER_CLICK); }
        shutter.play(which);
    }

    private void toast(String text) {
        ui.post(() -> android.widget.Toast.makeText(this, text, android.widget.Toast.LENGTH_SHORT).show());
    }

    /**
     * Streaming with the screen off and monitoring need the phone's battery saver to leave the app running.
     * Asked once at first launch, and again when one of those is switched on without the exemption.
     */
    void askBattery(boolean force) {
        android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
        if (pm.isIgnoringBatteryOptimizations(getPackageName())) return;
        android.content.SharedPreferences p = getSharedPreferences("settings", MODE_PRIVATE);
        if (!force && p.getBoolean("batteryAsked", false)) return;
        p.edit().putBoolean("batteryAsked", true).apply();
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Keep running in the background")
                .setMessage("To stream with the screen off and to monitor for hours, the battery saver must leave Emberglass running. "
                        + "Allow it in the next screen. On vivo, Xiaomi, Huawei and similar phones also open the app settings "
                        + "and allow high background power use.")
                .setPositiveButton("Allow", (d, w) -> {
                    try {
                        startActivity(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                android.net.Uri.parse("package:" + getPackageName())));
                    } catch (RuntimeException ex) { openAppSettings(); }
                })
                .setNeutralButton("App settings", (d, w) -> openAppSettings())
                .setNegativeButton("Not now", null)
                .show();
    }

    private void openAppSettings() {
        startActivity(new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName())));
    }

    private void askNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
    }

    /** Taps and drags on the displayed image, in displayed-image pixels. */
    private void onImageTap(int x0, int y0, int x1, int y1, boolean drag) {
        if (!areaMode) safeRun("spot", x1 + "," + y1);
        else if (!drag) safeRun("region", x1 + "," + y1 + ",1,1");
        else safeRun("region", Math.min(x0, x1) + "," + Math.min(y0, y1) + "," + (Math.abs(x0 - x1) + 1) + "," + (Math.abs(y0 - y1) + 1));
    }

    /** Fallback (no ES 3): map through the fitted bitmap of the ImageView. */
    private boolean onImageTouch(View v, MotionEvent ev) {
        if (ev.getAction() == MotionEvent.ACTION_DOWN) { downX = ev.getX(); downY = ev.getY(); return true; }
        if (ev.getAction() != MotionEvent.ACTION_UP) return true;
        Drawable d = ((ImageView) v).getDrawable();
        if (d == null) return true;
        float vw = v.getWidth(), vh = v.getHeight(), sc = Math.min(vw / d.getIntrinsicWidth(), vh / d.getIntrinsicHeight());
        float x0 = (vw - d.getIntrinsicWidth() * sc) / 2, y0 = (vh - d.getIntrinsicHeight() * sc) / 2, k = sc * Thermal.SCALE;
        int ow = th.outW(), oh = th.outH();
        int ax = clamp((downX - x0) / k, ow), ay = clamp((downY - y0) / k, oh), bx = clamp((ev.getX() - x0) / k, ow), by = clamp((ev.getY() - y0) / k, oh);
        onImageTap(ax, ay, bx, by, Math.hypot(ev.getX() - downX, ev.getY() - downY) > dp(12));
        return true;
    }

    private static int clamp(float v, int n) { return Math.max(0, Math.min(n - 1, (int) v)); }

    private TextView cell(LinearLayout parent, String label, int color) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.HORIZONTAL);
        c.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label + " ");
        l.setTextColor(MUTED);
        l.setTextSize(11);
        l.setTypeface(Typeface.DEFAULT_BOLD);
        TextView v = new TextView(this);
        v.setText("--");
        v.setTextColor(color);
        v.setTypeface(figures);
        v.setSingleLine(true);
        v.setTextSize(14);
        c.addView(l);
        c.addView(v);
        parent.addView(c, new LinearLayout.LayoutParams(0, -2, 1));
        return v;
    }

    /** Press surface: square, 2dp ink border, hard acid offset shadow; pressed slams onto the shadow. */
    private Drawable press(boolean filled) {
        StateListDrawable s = new StateListDrawable();
        s.addState(new int[]{android.R.attr.state_pressed}, pressLayers(filled, dp(3)));
        s.addState(new int[]{}, pressLayers(filled, 0));
        return s;
    }

    private Drawable pressLayers(boolean filled, int shift) {
        int off = dp(4);
        GradientDrawable shadow = new GradientDrawable();
        shadow.setColor(ACID);
        shadow.setCornerRadius(dp(4));
        GradientDrawable face = new GradientDrawable();
        face.setColor(filled ? EMBER : PAPER_2);
        face.setStroke(dp(2), INK);
        face.setCornerRadius(dp(4));
        LayerDrawable l = new LayerDrawable(new Drawable[]{shadow, face});
        l.setLayerInset(0, off, off, 0, 0);
        l.setLayerInset(1, shift, shift, off - shift, off - shift);
        return l;
    }

    private TextView text(int sp, int color) {
        TextView t = new TextView(this);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setPadding(dp(4), dp(4), 0, dp(4));
        return t;
    }

    // --- refresh -------------------------------------------------------------------------

    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            if (image != null) {   // fallback path renders the composite on the CPU
                if (visible) th.want();
                if (th.bitmap != null) image.setImageBitmap(th.bitmap);
            }
            if (!landscape && viewerView != null && th.outW() * 1000 + th.outH() != viewerAspect) {
                viewerView.getLayoutParams().height = viewerHeight();
                viewerView.requestLayout();
            }
            if (live != null) { live.showClearAreas(!th.regions.isEmpty()); live.areaMode = areaMode; }
            boolean on = th.frames > 0 && !th.warming;
            stats.setText(!on ? (e.cam != null || e.replaying ? "Warming up..." : e.status)
                    : th.alarm ? "ALARM: above " + th.fmt(th.alarmC)
                    : areaMode ? "Areas: drag for a box, tap for a point" : "");
            stats.setVisibility(stats.getText().length() == 0 ? View.GONE : View.VISIBLE);
            maxView.setText(on ? th.num(th.max) : "--");
            minView.setText(on ? th.num(th.min) : "--");
            spotView.setText(on ? th.num(th.spot) : "--");
            fpsView.setText(on ? String.format(Locale.US, "%.0f", th.camFps) : "--");
            boolean rec = e.recorder.running();
            recBtn.setText(rec ? String.format(Locale.US, (System.currentTimeMillis() / 500) % 2 == 0 ? "● %d:%02d" : "○ %d:%02d", (System.currentTimeMillis() - e.recorder.startMs) / 60000,
                    (System.currentTimeMillis() - e.recorder.startMs) / 1000 % 60) : "● Rec");
            if (paletteBtn != null) paletteBtn.setText(Thermal.PALETTES[th.palette]);
            if (contrastBtn != null) contrastBtn.setText(th.equalize ? "Detail" : "Linear");
            selected(highBtn, e.gain == 1);
            selected(lowBtn, e.gain == 0);
            selected(areasBtn, areaMode);
            selected(buildingBtn, th.buildingOn);
            selected(elecBtn, th.elecOn);
            if (monitorBtn != null) monitorBtn.setText(e.monitor.active() ? "Stop monitoring" : "Start monitoring");
            if (panelText != null) panelText.setText(panelInfo(on));
            if (th.alarm && !alarmed) vibrate();
            alarmed = th.alarm;
            if (trend != null) trend.invalidate();
            ui.postDelayed(this, 100);
        }
    };

    private String panelInfo(boolean on) {
        StringBuilder s = new StringBuilder();
        float[][] rs = th.regionStats;
        if (tab == 0 || tab == 1) {
            for (int i = 0; i < rs.length; i++) {
                s.append(String.format(Locale.US, "%d  min %s  max %s  avg %s", i + 1, th.num(rs[i][0]), th.num(rs[i][1]), th.num(rs[i][2])));
                if (th.buildingOn && !Float.isNaN(rs[i][3])) s.append(String.format(Locale.US, "  fRsi %.2f%s", rs[i][3], rs[i][3] < th.fRsiReq ? " RISK" : ""));
                if (th.elecOn && rs[i][5] >= 0) s.append("  ΔT ").append(th.delta(rs[i][4])).append(": ").append(Pro.SEVERITY[(int) rs[i][5]]);
                if (th.elecOn && !th.elecVsAmbient && i == 0) s.append("  (reference)");
                s.append('\n');
            }
        }
        if (tab == 1 && on) {
            if (th.buildingOn) {
                s.append("Dew point ").append(th.fmt(th.dewC)).append(", mold limit ").append(th.fmt(th.moldC)).append('\n');
                s.append(String.format(Locale.US, "fRsi coldest %.2f, needed %.2f: %s%n", th.fRsiMin, th.fRsiReq, th.fRsiMin < th.fRsiReq ? "MOLD RISK" : "ok"));
                if (!th.envelopeValid) s.append("Inside-outside difference under 10 C: fRsi not reliable\n");
            }
            if (th.rateAlarm > 0)
                s.append(String.format(Locale.US, "Max rising %.1f %s/min%n", th.fahrenheit ? th.rate * 9 / 5 : th.rate, th.fahrenheit ? "F" : "C"));
        }
        if (tab == 2) {
            s.append(e.monitor.active() ? "Recording " + e.monitor.rows() + " samples, every " + e.monitor.intervalSec + " s"
                    : "Logs every area to a CSV, also with the screen off.");
        }
        if (tab == 3) {
            s.append(e.status).append("\nhttp://").append(Engine.ip()).append(':').append(Engine.PORT);
            if (!e.serial.isEmpty()) s.append("\nSN ").append(e.serial).append(", ").append(e.firmware);
            s.append(String.format(Locale.US, "\nCamera %.0f fps, processed %.0f fps, render %s", th.camFps, th.fps,
                    live != null ? "GPU" : "CPU " + Thermal.SCALE + "x"));
            if (e.vtemp >= 0) s.append("\nSensor counter ").append(e.vtemp);
        }
        return s.toString().trim();
    }

    @SuppressWarnings("deprecation")
    private void vibrate() {
        android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
        if (v == null || !v.hasVibrator()) return;
        v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 200, 120, 200}, -1));
    }

    // --- settings dialog -----------------------------------------------------------------

    private void showSettings() {
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(20), dp(8), dp(20), dp(8));
        list.setBackgroundColor(PAPER_2);

        section(list, "Units");
        RadioGroup units = new RadioGroup(this);
        units.setOrientation(LinearLayout.HORIZONTAL);
        RadioButton c = radio("Celsius °C"), f = radio("Fahrenheit °F");
        units.addView(c);
        units.addView(f);
        (th.fahrenheit ? f : c).setChecked(true);
        list.addView(units);

        section(list, "On the image");
        toggle(list, "Mark the hottest point", "markMax", th.markMax);
        toggle(list, "Mark the coldest point", "markMin", th.markMin);
        toggle(list, "Mark the center", "markCenter", th.markCenter);
        toggle(list, "Mark the tapped spot", "markSpot", th.markSpot);
        toggle(list, "Color scale bar", "scaleBar", th.scaleBar);
        toggle(list, "Detail-enhanced contrast (histogram equalization; off = linear)", "equalize", th.equalize);
        toggle(list, "Mirror the image (camera facing you)", "mirror", th.mirror);
        Button clear = new Button(this);
        clear.setText("Clear measurement areas");
        clear.setAllCaps(false);
        clear.setOnClickListener(v -> safeRun("region", "clear"));
        list.addView(clear);

        section(list, "Temperature alarm");
        toggle(list, "Highlight everything above the threshold, vibrate on alarm", "alarmOn", th.alarmOn);
        EditText alarmC = temperature(list, "Threshold", "alarmC", th.alarmC);

        section(list, "Color scale");
        toggle(list, "Lock the scale (otherwise automatic)", "rangeLock", th.rangeLock);
        EditText lo = temperature(list, "Scale from", "rangeLo", th.rangeLo);
        EditText hi = temperature(list, "Scale to", "rangeHi", th.rangeHi);

        section(list, "Measurement corrections (sent to the camera)");
        EditText ems = plain(list, "Emissivity, 0.01 to 1", "emissivity", e.corrEmissivity);
        EditText refl = temperature(list, "Reflected temperature", "reflectedC", e.corrReflectedC);
        EditText amb = temperature(list, "Ambient temperature", "ambientC", e.corrAmbientC);
        plain(list, "Distance to the object, m", "distanceM", e.corrDistanceM);
        TextView ml = text(14, MUTED);
        ml.setText("Material (sets the emissivity)");
        list.addView(ml);
        android.widget.Spinner materials = new android.widget.Spinner(this);
        String[] mats = new String[Pro.MATERIALS.length + 1];
        mats[0] = "Choose a material...";
        System.arraycopy(Pro.MATERIALS, 0, mats, 1, Pro.MATERIALS.length);
        android.widget.ArrayAdapter<String> ad = new android.widget.ArrayAdapter<>(this, android.R.layout.simple_spinner_item, mats);
        ad.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        materials.setAdapter(ad);
        materials.setMinimumHeight(dp(48));
        list.addView(materials);

        section(list, "Building diagnostics");
        toggle(list, "Show condensation and mold risk, compute fRsi", "buildingOn", th.buildingOn);
        EditText air = temperature(list, "Indoor air temperature", "airC", th.airC);
        plain(list, "Indoor relative humidity, %", "rh", th.rh);
        EditText out = temperature(list, "Outdoor air temperature", "outC", th.outC);
        TextView bh = text(13, MUTED);
        bh.setText("Blue: below the dew point (condensation). Pink: surface humidity over 80% (mold risk, EN ISO 13788). "
                + "fRsi needs at least 10 C between inside and outside, steady for hours.");
        list.addView(bh);

        section(list, "Electrical inspection");
        toggle(list, "Rise of each area and severity", "elecOn", th.elecOn);
        RadioGroup ref = new RadioGroup(this);
        RadioButton r1 = radio("Compare with area 1 (similar part)"), r2 = radio("Compare with ambient air");
        ref.addView(r1);
        ref.addView(r2);
        (th.elecVsAmbient ? r2 : r1).setChecked(true);
        ref.setOnCheckedChangeListener((g, id) -> trySet("elecRef", r2.isChecked() ? "ambient" : "area1"));
        list.addView(ref);
        plain(list, "Rate alarm: max rising faster than, per minute (0 = off)", "rateAlarm", th.rateAlarm);
        TextView eh = text(13, MUTED);
        eh.setText("Severity after NETA MTS Table 100.18. Ambient air is the indoor air temperature above. "
                + "Compare components under similar load.");
        list.addView(eh);

        section(list, "Monitoring");
        plain(list, "Log every, seconds", "logInterval", e.monitor.intervalSec);
        plain(list, "Time-lapse photo every, minutes (0 = off)", "snapshotMin", e.monitor.snapshotMin);
        TextView where = text(13, MUTED);
        where.setText("Logs and time-lapse go to Android/data/app.emberglass/files/monitor. The CSV is also at /log.csv on the web page.");
        list.addView(where);

        section(list, "Web");
        toggle(list, "Allow control from the browser", "remoteControl", e.remoteControl);
        EditText pin = field(list, "PIN for the browser (empty = none)", e.webPin, InputType.TYPE_CLASS_NUMBER);

        section(list, "Phone");
        toggle(list, "Keep the screen on", "keepScreenOn", e.keepScreenOn);
        toggle(list, "Keep streaming with the screen off or the phone locked", "backgroundStream", e.backgroundStream);
        Button battery = new Button(this);
        battery.setText("Battery saver: let the app run in the background");
        battery.setAllCaps(false);
        battery.setOnClickListener(v -> askBattery(true));
        list.addView(battery);

        materials.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            public void onItemSelected(android.widget.AdapterView<?> a, View v, int pos, long id) {
                if (pos > 0) ems.setText(String.valueOf(Pro.materialEmissivity(Pro.MATERIALS[pos - 1])));
            }
            public void onNothingSelected(android.widget.AdapterView<?> a) { }
        });

        // switching the unit converts the temperature fields in place
        EditText[] temps = {alarmC, lo, hi, refl, amb, air, out};
        units.setOnCheckedChangeListener((g, id) -> {
            boolean toF = f.isChecked();
            if (toF == th.fahrenheit) return;
            trySet("fahrenheit", toF ? "1" : "0");
            for (EditText t : temps) {
                try {
                    float v = Float.parseFloat(t.getText().toString());
                    t.setTag(Boolean.TRUE);   // skip the watcher: the stored Celsius value does not change
                    t.setText(String.format(Locale.US, "%.1f", toF ? v * 9 / 5 + 32 : (v - 32) * 5 / 9));
                    t.setTag(null);
                } catch (NumberFormatException ignored) { }
            }
        });

        ScrollView sv = new ScrollView(this);
        sv.addView(list);
        new android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_NoActionBar)
                .setView(sv)
                .setPositiveButton("Done", (d, w) -> {
                    trySet("webPin", pin.getText().toString());
                    applyWindowFlags();
                })
                .show();
    }

    private RadioButton radio(String label) {
        RadioButton r = new RadioButton(this);
        r.setText(label);
        r.setTextColor(INK);
        r.setMinHeight(dp(48));
        r.setButtonTintList(new android.content.res.ColorStateList(new int[][]{{android.R.attr.state_checked}, {}}, new int[]{EMBER, MUTED}));
        r.setPadding(0, 0, dp(16), 0);
        return r;
    }

    private void section(LinearLayout parent, String title) {
        TextView t = text(16, ACID);
        t.setText(title);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(16), 0, dp(4));
        parent.addView(t);
    }

    private void toggle(LinearLayout parent, String label, String key, boolean value) {
        android.widget.Switch sw = new android.widget.Switch(this);
        sw.setText(label);
        sw.setTextColor(INK);
        sw.setTextSize(15);
        sw.setChecked(value);
        int[][] st = {{android.R.attr.state_checked}, {}};
        sw.setThumbTintList(new android.content.res.ColorStateList(st, new int[]{EMBER, MUTED}));
        sw.setTrackTintList(new android.content.res.ColorStateList(st, new int[]{0x99f85200, RULE}));
        sw.setMinHeight(dp(48));
        sw.setOnCheckedChangeListener((b, c) -> trySet(key, c ? "1" : "0"));
        parent.addView(sw);
    }

    /** A temperature field shown in the current unit; the engine always stores Celsius. */
    private EditText temperature(LinearLayout parent, String label, String key, float celsius) {
        boolean fUnit = th.fahrenheit;
        EditText t = field(parent, label + (fUnit ? " (°F)" : " (°C)"),
                String.format(Locale.US, "%.1f", fUnit ? celsius * 9 / 5 + 32 : celsius),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL | InputType.TYPE_NUMBER_FLAG_SIGNED);
        t.addTextChangedListener(new Watcher(t, s -> {
            float v = Float.parseFloat(s);
            float cel = th.fahrenheit ? (v - 32) * 5 / 9 : v;
            e.applySetting(key, String.format(Locale.US, "%.2f", cel), true);
        }));
        return t;
    }

    private EditText plain(LinearLayout parent, String label, String key, float value) {
        EditText t = field(parent, label, value == Math.rint(value) ? String.valueOf((int) value) : String.valueOf(value),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        t.addTextChangedListener(new Watcher(t, s -> e.applySetting(key, s, true)));
        return t;
    }

    private EditText field(LinearLayout parent, String label, String value, int type) {
        TextView l = text(14, MUTED);
        l.setText(label);
        parent.addView(l);
        EditText t = new EditText(this);
        t.setText(value);
        t.setInputType(type);
        t.setTextColor(INK);
        t.setMinHeight(dp(48));
        parent.addView(t);
        return t;
    }

    interface Apply { void apply(String s); }

    /** Applies a field as the user types; shows the engine's range message on bad input. */
    private static final class Watcher implements android.text.TextWatcher {
        final EditText t; final Apply a;
        Watcher(EditText t, Apply a) { this.t = t; this.a = a; }
        public void beforeTextChanged(CharSequence c, int s, int b, int n) { }
        public void onTextChanged(CharSequence c, int s, int b, int n) { }
        public void afterTextChanged(android.text.Editable ed) {
            if (t.getTag() != null) return;
            try { a.apply(ed.toString()); t.setError(null); }
            catch (RuntimeException ex) { t.setError(ex instanceof NumberFormatException ? "a number" : ex.getMessage()); }
        }
    }

    private void trySet(String key, String value) {
        try { e.applySetting(key, value, true); } catch (RuntimeException ex) { e.setStatus(ex.getMessage()); }
    }

    // --- trend ---------------------------------------------------------------------------

    /** Line chart of the monitoring history: scene max and min, then the max of each area. */
    final class TrendView extends View {
        private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

        TrendView(android.content.Context c) { super(c); }

        @Override protected void onDraw(Canvas cv) {
            int w = getWidth(), h = getHeight();
            float pad = dp(8), left = dp(44), bottom = h - dp(34), top = dp(8);
            p.setTypeface(Typeface.DEFAULT_BOLD);
            p.setTextSize(dp(11));
            float[][] s = e.monitor.snapshot();
            if (s.length < 2) {
                p.setColor(MUTED);
                cv.drawText(e.monitor.active() ? "Collecting the first samples..." : "Press Monitor to record a trend.", left, h / 2f, p);
                return;
            }
            int series = 2 + th.regionStats.length;
            float lo = Float.MAX_VALUE, hi = -Float.MAX_VALUE;
            for (float[] r : s)
                for (int k = 1; k <= series; k++) {
                    int col = k <= 2 ? k : 4 + (k - 2);
                    if (col >= r.length || Float.isNaN(r[col])) continue;
                    lo = Math.min(lo, r[col]); hi = Math.max(hi, r[col]);
                }
            if (hi - lo < 1) { hi += 0.5f; lo -= 0.5f; }
            float t0 = s[0][0], t1 = s[s.length - 1][0];
            // recessive grid: three hairlines with labels in muted ink
            p.setStrokeWidth(1);
            for (int i = 0; i <= 2; i++) {
                float v = lo + (hi - lo) * i / 2f, y = bottom - (v - lo) / (hi - lo) * (bottom - top);
                p.setColor(RULE);
                cv.drawLine(left, y, w - pad, y, p);
                p.setColor(MUTED);
                cv.drawText(th.num(v), 0, y + dp(4), p);
            }
            p.setColor(MUTED);
            cv.drawText(duration(t1 - t0) + " shown", left, h - dp(20), p);
            // lines
            p.setStrokeWidth(dp(2));
            p.setStyle(Paint.Style.STROKE);
            for (int k = 1; k <= series; k++) {
                int col = k <= 2 ? k : 4 + (k - 2);
                p.setColor(SERIES[k - 1]);
                android.graphics.Path path = new android.graphics.Path();
                boolean pen = false;
                for (float[] r : s) {
                    if (col >= r.length || Float.isNaN(r[col])) { pen = false; continue; }
                    float x = left + (r[0] - t0) / Math.max(1e-3f, t1 - t0) * (w - pad - left);
                    float y = bottom - (r[col] - lo) / (hi - lo) * (bottom - top);
                    if (pen) path.lineTo(x, y); else path.moveTo(x, y);
                    pen = true;
                }
                cv.drawPath(path, p);
            }
            p.setStyle(Paint.Style.FILL);
            // legend: identity never by color alone
            float lx = left;
            for (int k = 1; k <= series; k++) {
                String name = k == 1 ? "Max" : k == 2 ? "Min" : "Area " + (k - 2);
                p.setColor(SERIES[k - 1]);
                cv.drawRect(lx, h - dp(10), lx + dp(10), h - dp(2), p);
                p.setColor(INK);
                cv.drawText(name, lx + dp(13), h - dp(2), p);
                lx += dp(16) + p.measureText(name) + dp(8);
            }
        }

        private String duration(float sec) {
            if (sec < 120) return String.format(Locale.US, "%.0f s", sec);
            if (sec < 7200) return String.format(Locale.US, "%.0f min", sec / 60);
            return String.format(Locale.US, "%.1f h", sec / 3600);
        }
    }
}
