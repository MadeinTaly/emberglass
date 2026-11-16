package app.emberglass;

import android.content.Context;
import android.graphics.Canvas;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * The live thermal view, drawn by the GPU: each frame only the temperature field (256x192 floats)
 * goes to a texture; the fragment shader does bicubic upscaling, the contrast curve, the palette,
 * the isotherms, rotation and mirror. Markers and labels are drawn by a transparent view on top,
 * at the display's own resolution.
 */
final class ThermalView extends FrameLayout {
    interface Tap { void onTap(int x0, int y0, int x1, int y1, boolean drag); }

    private final Thermal th;
    private final GLSurfaceView gl;
    private final View marks;
    private final Overlay overlay;
    private final Runnable onFrame;
    private volatile float k = 1, ox, oy;   // letterbox: image px -> view px
    private volatile int viewW = 1, viewH = 1;   // set on the UI thread; GPU, overlay and touch all fit from it

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        viewW = Math.max(1, w);
        viewH = Math.max(1, h);
    }
    private float downX, downY, curX, curY;
    private boolean dragging;
    /** Set by the activity: in area mode a drag draws a box, shown live under the finger. */
    volatile boolean areaMode;
    private final android.graphics.Paint band = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
    private final View flash;
    Tap tap;

    void footer(boolean on) { overlay.footer = on; }

    private android.widget.Button clearChip;

    /** A "Clear areas" chip on the image, shown while there are areas: one tap away, not in a menu. */
    void clearAreas(Runnable action, boolean landscape) {
        android.widget.Button b = new android.widget.Button(getContext());
        b.setText("Clear areas");
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setTextColor(0xfff2ecf5);
        b.setStateListAnimator(null);
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setColor(0xcc000000);
        g.setStroke(Math.round(2 * getResources().getDisplayMetrics().density), 0xfff2ecf5);
        g.setCornerRadius(4 * getResources().getDisplayMetrics().density);
        b.setBackground(g);
        int pad = Math.round(14 * getResources().getDisplayMetrics().density);
        b.setPadding(pad, 0, pad, 0);
        b.setMinHeight(Math.round(44 * getResources().getDisplayMetrics().density));
        b.setVisibility(GONE);
        b.setOnClickListener(v -> action.run());
        // portrait: bottom left of the image; landscape: centered just above the mode strip, clear of the side readings
        LayoutParams lp = new LayoutParams(-2, -2, android.view.Gravity.BOTTOM | (landscape ? android.view.Gravity.CENTER_HORIZONTAL : android.view.Gravity.START));
        lp.setMargins(pad, 0, 0, landscape ? Math.round(68 * getResources().getDisplayMetrics().density) : pad);
        addView(b, lp);
        clearChip = b;
    }

    void showClearAreas(boolean on) {
        if (clearChip != null && (clearChip.getVisibility() == VISIBLE) != on) clearChip.setVisibility(on ? VISIBLE : GONE);
    }

    /** Camera-style white flash over the image. */
    void flash() {
        flash.animate().cancel();
        flash.setAlpha(0.85f);
        flash.animate().alpha(0).setDuration(260).start();
    }

    ThermalView(Context c, Thermal th, int background) {
        super(c);
        this.th = th;
        overlay = new Overlay(th);
        gl = new GLSurfaceView(c);
        gl.setEGLContextClientVersion(3);
        gl.setPreserveEGLContextOnPause(true);
        gl.setRenderer(new Renderer(background));
        gl.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        marks = new View(c) {
            private final android.graphics.Paint np = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas cv) {
                String n = th.notice();
                if (n != null) {   // disconnected, connecting or warming: say so in the middle of the viewer
                    np.setColor(0xfff2ecf5);
                    np.setTextAlign(android.graphics.Paint.Align.CENTER);
                    np.setTextSize(20 * getResources().getDisplayMetrics().scaledDensity);
                    np.setFakeBoldText(true);
                    cv.drawText(n, getWidth() / 2f, getHeight() / 2f, np);
                    return;
                }
                fit();
                overlay.draw(cv, k, ox, oy);
                if (dragging && areaMode) {   // the box being drawn follows the finger
                    float d = getResources().getDisplayMetrics().density;
                    band.setStyle(android.graphics.Paint.Style.STROKE);
                    band.setStrokeWidth(2 * d);
                    band.setColor(0xffd5e51b);
                    band.setPathEffect(new android.graphics.DashPathEffect(new float[]{8 * d, 6 * d}, 0));
                    cv.drawRect(Math.min(downX, curX), Math.min(downY, curY), Math.max(downX, curX), Math.max(downY, curY), band);
                }
            }
        };
        addView(gl, new LayoutParams(-1, -1));
        addView(marks, new LayoutParams(-1, -1));
        flash = new View(c);
        flash.setBackgroundColor(0xffffffff);
        flash.setAlpha(0);
        addView(flash, new LayoutParams(-1, -1));
        onFrame = () -> { gl.requestRender(); marks.postInvalidateOnAnimation(); };
        setOnTouchListener(this::touch);
    }

    @Override protected void onAttachedToWindow() { super.onAttachedToWindow(); th.listeners.add(onFrame); gl.onResume(); }

    @Override protected void onDetachedFromWindow() { th.listeners.remove(onFrame); gl.onPause(); super.onDetachedFromWindow(); }

    /** Letterbox of the displayed image inside the view, same for the GPU and the overlay. */
    private void fit() {
        int w = viewW, h = viewH;
        float ow = th.outW(), oh = th.outH();
        k = Math.min(w / ow, h / oh);
        ox = (w - ow * k) / 2;
        oy = (h - oh * k) / 2;
    }

    private boolean touch(View v, MotionEvent e) {
        if (e.getAction() == MotionEvent.ACTION_DOWN) { downX = curX = e.getX(); downY = curY = e.getY(); dragging = true; return true; }
        if (e.getAction() == MotionEvent.ACTION_MOVE) { curX = e.getX(); curY = e.getY(); marks.invalidate(); return true; }
        if (e.getAction() == MotionEvent.ACTION_CANCEL) { dragging = false; marks.invalidate(); return true; }
        if (e.getAction() != MotionEvent.ACTION_UP || tap == null) return true;
        dragging = false;
        marks.invalidate();
        fit();
        boolean drag = Math.hypot(e.getX() - downX, e.getY() - downY) > 12 * getResources().getDisplayMetrics().density;
        tap.onTap(px(downX, ox, th.outW()), px(downY, oy, th.outH()), px(e.getX(), ox, th.outW()), px(e.getY(), oy, th.outH()), drag);
        return true;
    }

    private int px(float v, float o, int n) { return Math.max(0, Math.min(n - 1, (int) ((v - o) / k))); }

    // --- GPU ---------------------------------------------------------------------------

    private static final String VS = "#version 300 es\n"
            + "in vec2 aPos; in vec2 aUv; out vec2 uv;\n"
            + "void main() { gl_Position = vec4(aPos, 0.0, 1.0); uv = aUv; }\n";

    private static final String FS = "#version 300 es\n"
            + "precision highp float;\n"
            + "uniform highp sampler2D uVals; uniform highp sampler2D uCurve; uniform sampler2D uLut;\n"
            + "uniform float uLo, uHi, uAlarm, uDew, uMold; uniform int uEq, uIso, uBld;\n"
            + "uniform vec3 uAlarmC, uDewC, uMoldC;\n"
            + "in vec2 uv; out vec4 o;\n"
            + "float val(ivec2 p) { return texelFetch(uVals, clamp(p, ivec2(0), ivec2(255, 191)), 0).r; }\n"
            + "vec4 cr(float t) { float t2 = t * t, t3 = t2 * t;\n"   // Catmull-Rom weights
            + "  return vec4(-0.5*t3 + t2 - 0.5*t, 1.5*t3 - 2.5*t2 + 1.0, -1.5*t3 + 2.0*t2 + 0.5*t, 0.5*t3 - 0.5*t2); }\n"
            + "float bicubic(vec2 st) {\n"
            + "  vec2 p = st * vec2(256.0, 192.0) - 0.5; vec2 f = fract(p); ivec2 b = ivec2(floor(p));\n"
            + "  vec4 wx = cr(f.x), wy = cr(f.y); float r = 0.0;\n"
            + "  for (int j = 0; j < 4; j++) { float row = 0.0;\n"
            + "    for (int i = 0; i < 4; i++) row += wx[i] * val(b + ivec2(i - 1, j - 1));\n"
            + "    r += wy[j] * row; }\n"
            + "  return r; }\n"
            + "void main() {\n"
            + "  float v = bicubic(uv);\n"
            + "  if (uIso == 1 && v >= uAlarm) { o = vec4(uAlarmC, 1.0); return; }\n"
            + "  if (uBld == 1 && v <= uDew) { o = vec4(uDewC, 1.0); return; }\n"
            + "  if (uBld == 1 && v <= uMold) { o = vec4(uMoldC, 1.0); return; }\n"
            + "  float f = clamp((v - uLo) / max(uHi - uLo, 0.5), 0.0, 1.0), idx = f;\n"
            + "  if (uEq == 1) { float x = f * " + Thermal.CURVE_BINS + ".0 - 0.5; float x0 = clamp(floor(x), 0.0, "
            + (Thermal.CURVE_BINS - 1) + ".0);\n"
            + "    float a = texelFetch(uCurve, ivec2(int(x0), 0), 0).r, b = texelFetch(uCurve, ivec2(int(min(x0 + 1.0, "
            + (Thermal.CURVE_BINS - 1) + ".0)), 0), 0).r;\n"
            + "    idx = mix(a, b, clamp(x - x0, 0.0, 1.0)) / 255.0; }\n"
            + "  o = texture(uLut, vec2((idx * 255.0 + 0.5) / 256.0, 0.5));\n"
            + "}\n";

    private final class Renderer implements GLSurfaceView.Renderer {
        private final int bg;
        private int prog, texVals, texCurve, texLut, lutPalette = -1, w, h;
        private final FloatBuffer vals = ByteBuffer.allocateDirect(Thermal.W * Thermal.H * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final FloatBuffer curve = ByteBuffer.allocateDirect(Thermal.CURVE_BINS * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final ByteBuffer lut = ByteBuffer.allocateDirect(256 * 4).order(ByteOrder.nativeOrder());
        private final FloatBuffer quad = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        Renderer(int bg) { this.bg = bg; }

        @Override public void onSurfaceCreated(GL10 unused, EGLConfig cfg) {
            prog = link(VS, FS);
            int[] t = new int[3];
            GLES30.glGenTextures(3, t, 0);
            texVals = t[0]; texCurve = t[1]; texLut = t[2];
            texture(texVals, GLES30.GL_NEAREST);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, Thermal.W, Thermal.H, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null);
            texture(texCurve, GLES30.GL_NEAREST);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R32F, Thermal.CURVE_BINS, 1, 0, GLES30.GL_RED, GLES30.GL_FLOAT, null);
            texture(texLut, GLES30.GL_LINEAR);
            GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, 256, 1, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null);
            lutPalette = -1;
        }

        @Override public void onSurfaceChanged(GL10 unused, int width, int height) { w = width; h = height; }

        @Override public void onDrawFrame(GL10 unused) {
            GLES30.glViewport(0, 0, w, h);
            GLES30.glClearColor(((bg >> 16) & 255) / 255f, ((bg >> 8) & 255) / 255f, (bg & 255) / 255f, 1);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            if (th.notice() != null) return;   // nothing stale on screen
            float lo, hi;
            boolean eq;
            synchronized (th.frameLock) {
                vals.clear(); vals.put(th.view).flip();
                curve.clear(); curve.put(th.viewCurve).flip();
                lo = th.viewLo; hi = th.viewHi; eq = th.viewEq;
            }
            fit();
            int vw = Math.round(th.outW() * k), vh = Math.round(th.outH() * k);
            GLES30.glViewport(Math.round(ox), Math.round(h - oy - vh), vw, vh);
            GLES30.glUseProgram(prog);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texVals);
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, Thermal.W, Thermal.H, GLES30.GL_RED, GLES30.GL_FLOAT, vals);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texCurve);
            GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, Thermal.CURVE_BINS, 1, GLES30.GL_RED, GLES30.GL_FLOAT, curve);
            GLES30.glActiveTexture(GLES30.GL_TEXTURE2);
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, texLut);
            if (lutPalette != th.palette) {
                lutPalette = th.palette;
                lut.clear();
                for (int c : th.lut()) lut.put((byte) (c >> 16)).put((byte) (c >> 8)).put((byte) c).put((byte) 255);
                lut.flip();
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, 256, 1, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, lut);
            }
            u1i("uVals", 0); u1i("uCurve", 1); u1i("uLut", 2);
            u1f("uLo", lo); u1f("uHi", hi); u1i("uEq", eq ? 1 : 0);
            u1i("uIso", th.alarmOn ? 1 : 0); u1f("uAlarm", th.alarmC);
            u1i("uBld", th.buildingOn ? 1 : 0); u1f("uDew", th.dewC); u1f("uMold", th.moldC);
            u3("uAlarmC", Thermal.ALARM_COLOR); u3("uDewC", Thermal.CONDENSATION_COLOR); u3("uMoldC", Thermal.MOLD_COLOR);
            // quad corners: screen (x, y) -> displayed image (0..outW, 0..outH) -> sensor uv, so rotation/mirror is free
            float ow = th.outW(), oh = th.outH();
            float[][] corners = {{-1, -1, 0, oh}, {1, -1, ow, oh}, {-1, 1, 0, 0}, {1, 1, ow, 0}};
            quad.clear();
            for (float[] c : corners) {
                float[] s = th.toSensorF(c[2], c[3]);
                quad.put(c[0]).put(c[1]).put(s[0] / Thermal.W).put(s[1] / Thermal.H);
            }
            quad.flip();
            int aPos = GLES30.glGetAttribLocation(prog, "aPos"), aUv = GLES30.glGetAttribLocation(prog, "aUv");
            quad.position(0);
            GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 16, quad);
            GLES30.glEnableVertexAttribArray(aPos);
            quad.position(2);
            GLES30.glVertexAttribPointer(aUv, 2, GLES30.GL_FLOAT, false, 16, quad);
            GLES30.glEnableVertexAttribArray(aUv);
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);
        }

        private void u1i(String n, int v) { GLES30.glUniform1i(GLES30.glGetUniformLocation(prog, n), v); }
        private void u1f(String n, float v) { GLES30.glUniform1f(GLES30.glGetUniformLocation(prog, n), v); }
        private void u3(String n, int c) {
            GLES30.glUniform3f(GLES30.glGetUniformLocation(prog, n), ((c >> 16) & 255) / 255f, ((c >> 8) & 255) / 255f, (c & 255) / 255f);
        }

        private void texture(int t, int filter) {
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, filter);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        }

        private int link(String vs, String fs) {
            int p = GLES30.glCreateProgram();
            GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs));
            GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs));
            GLES30.glLinkProgram(p);
            int[] ok = new int[1];
            GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("GL link: " + GLES30.glGetProgramInfoLog(p));
            return p;
        }

        private int shader(int type, String src) {
            int s = GLES30.glCreateShader(type);
            GLES30.glShaderSource(s, src);
            GLES30.glCompileShader(s);
            int[] ok = new int[1];
            GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) throw new IllegalStateException("GL shader: " + GLES30.glGetShaderInfoLog(s));
            return s;
        }
    }
}
