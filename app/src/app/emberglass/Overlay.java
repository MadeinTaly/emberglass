package app.emberglass;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import java.util.Locale;

/**
 * Draws markers, areas, labels and the scale bar over the thermal image, at any scale.
 * One instance per drawing thread (it owns its Paints): the on-screen view and the composite
 * renderer (web MJPEG, photos, video) each have their own.
 */
final class Overlay {
    private final Thermal th;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float k, ox, oy;   // image pixels -> canvas: x * k + ox
    private int outW, outH;
    /** The max/min line at the bottom; off where the screen shows the readings elsewhere. */
    volatile boolean footer = true;

    Overlay(Thermal th) { this.th = th; }

    private float sz(float px) { return px * k / 3f; }   // sizes tuned for 3 canvas px per image px

    /** k = canvas pixels per displayed-image pixel; (ox, oy) = where the image starts on the canvas. */
    void draw(Canvas cv, float k, float ox, float oy) {
        this.k = Math.max(1.5f, k);
        this.ox = ox;
        this.oy = oy;
        outW = th.outW();
        outH = th.outH();
        float[][] rs = th.regionStats;
        if (th.scaleBar) scaleBar(cv);
        if (th.markMax) mark(cv, th.maxI % Thermal.W, th.maxI / Thermal.W, 0xffff5a4a, th.fmt(th.max));
        if (th.markMin) mark(cv, th.minI % Thermal.W, th.minI / Thermal.W, 0xff38d5ff, th.fmt(th.min));
        if (th.markCenter) mark(cv, Thermal.W / 2, Thermal.H / 2, Color.WHITE, th.fmt(th.center));
        if (th.markSpot) mark(cv, th.spotX, th.spotY, Color.WHITE, th.fmt(th.spot));
        float right = ox + outW * k - (th.scaleBar ? sz(110) : sz(8));
        int n = 0;
        for (int[] r : th.regions) {
            if (n >= rs.length) break;
            float[] st = rs[n++];
            paint.setColor(Color.WHITE);
            paint.setStrokeWidth(sz(2));
            paint.setStyle(Paint.Style.STROKE);
            if (r[2] == 1 && r[3] == 1) {
                float[] c = th.toDisplay(r[0] + 0.5f, r[1] + 0.5f);
                float cx = ox + c[0] * k, cy = oy + c[1] * k;
                cv.drawCircle(cx, cy, sz(9), paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(sz(22));
                pill(cv, n + "  " + th.fmt(st[1]) + th.analysisLabel(st), cx + sz(14), cy - sz(14), Color.WHITE, right);
            } else {
                float[] a = th.toDisplay(r[0], r[1]), z = th.toDisplay(r[0] + r[2], r[1] + r[3]);
                float l = ox + Math.min(a[0], z[0]) * k, t = oy + Math.min(a[1], z[1]) * k;
                float rr = ox + Math.max(a[0], z[0]) * k, b = oy + Math.max(a[1], z[1]) * k;
                cv.drawRect(l, t, rr, b, paint);
                paint.setStyle(Paint.Style.FILL);
                paint.setTextSize(sz(22));
                pill(cv, n + "  max " + th.fmt(st[1]) + "  avg " + th.fmt(st[2]) + th.analysisLabel(st),
                        l + sz(2), t - oy > sz(34) ? t - sz(8) : b + sz(28), Color.WHITE, right);
            }
        }
        String foot = (th.alarm ? (th.rateHigh ? "RISING " + String.format(Locale.US, "%.1f/min  ", th.rate) : "ALARM  ") : "")
                + "max " + th.fmt(th.max) + "  min " + th.fmt(th.min);
        paint.setTextSize(sz(26));
        if (footer) pill(cv, foot, ox + sz(10), oy + outH * k - sz(14), th.alarm ? Thermal.ALARM_COLOR : Color.WHITE, right);
        if (th.buildingOn) {
            paint.setTextSize(sz(22));
            pill(cv, String.format(Locale.US, "condensation <= %s: %.1f%%", th.fmt(th.dewC), th.dewShare * 100), ox + sz(10), oy + sz(30), Thermal.CONDENSATION_COLOR, right);
            pill(cv, String.format(Locale.US, "mold risk <= %s: %.1f%%", th.fmt(th.moldC), th.moldShare * 100), ox + sz(10), oy + sz(62), Thermal.MOLD_COLOR, right);
        }
    }

    /** Text on a dark rounded backing, kept inside the image; readable over any palette color. */
    private void pill(Canvas cv, String text, float x, float baseline, int color, float right) {
        float w = paint.measureText(text), pad = sz(6), asc = -paint.ascent(), desc = paint.descent();
        x = Math.max(ox + pad, Math.min(x, right - w - pad));
        baseline = Math.max(oy + asc + pad, Math.min(baseline, oy + outH * k - desc - pad));
        bg.setColor(0xb3000000);
        cv.drawRoundRect(x - pad, baseline - asc - pad / 2, x + w + pad, baseline + desc + pad / 2, sz(6), sz(6), bg);
        paint.setColor(color);
        cv.drawText(text, x, baseline, paint);
    }

    /** Vertical color legend on the right edge, through the same contrast curve as the image. */
    private void scaleBar(Canvas cv) {
        int[] lut = th.lut();
        float x1 = ox + outW * k - sz(14), x0 = x1 - sz(16), y0 = oy + sz(24), y1 = oy + outH * k - sz(54);
        float lo = th.viewLo, hi = th.viewHi, span = Math.max(hi - lo, 0.5f);
        paint.setStyle(Paint.Style.FILL);
        float step = Math.max(1, (y1 - y0) / 128f);
        for (float y = y0; y < y1; y += step) {
            float v = hi - (y - y0) / (y1 - y0) * span;
            int i = (int) th.index(v);
            paint.setColor(th.alarmOn && v >= th.alarmC ? Thermal.ALARM_COLOR : lut[i < 0 ? 0 : i > 255 ? 255 : i]);
            cv.drawRect(x0, y, x1, Math.min(y1, y + step + 1), paint);
        }
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, sz(1)));
        paint.setColor(Color.WHITE);
        cv.drawRect(x0, y0, x1, y1, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(sz(22));
        String a = th.fmt(hi), b = th.fmt(lo);
        pill(cv, a, x0 - sz(10) - paint.measureText(a), y0 + sz(16), Color.WHITE, x0 - sz(4));
        pill(cv, b, x0 - sz(10) - paint.measureText(b), y1, Color.WHITE, x0 - sz(4));
    }

    private void mark(Canvas cv, int x, int y, int color, String label) {
        float[] c = th.toDisplay(x + 0.5f, y + 0.5f);
        float cx = ox + c[0] * k, cy = oy + c[1] * k, r = sz(14);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(0xcc000000);   // dark outline first: the cross reads on white-hot and black-hot alike
        paint.setStrokeWidth(sz(6));
        cv.drawLine(cx - r, cy, cx + r, cy, paint);
        cv.drawLine(cx, cy - r, cx, cy + r, paint);
        paint.setColor(color);
        paint.setStrokeWidth(sz(3));
        cv.drawLine(cx - r, cy, cx + r, cy, paint);
        cv.drawLine(cx, cy - r, cx, cy + r, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextSize(sz(24));
        float w = paint.measureText(label);
        float right = ox + outW * k - (th.scaleBar ? sz(110) : sz(8));
        pill(cv, label, cx + sz(16) + w > right ? cx - sz(16) - w : cx + sz(16), cy - oy < sz(40) ? cy + sz(36) : cy - sz(12), color, right);
    }
}
