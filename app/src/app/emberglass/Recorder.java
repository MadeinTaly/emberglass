package app.emberglass;

import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Locale;

/**
 * Video of the rendered view: H.265 when the phone has an encoder for it, otherwise H.264, in MP4
 * (Movies/Emberglass). Optionally the radiometric frames too (uint16 kelvin*64, 256x192 per frame,
 * sensor orientation) in a .raw file next to a .json with the timestamps.
 */
final class Recorder {
    private final Engine e;
    private volatile boolean running;
    private Thread thread;
    volatile String codecName = "", file = "";
    volatile long startMs;
    volatile int frames;

    Recorder(Engine e) { this.e = e; }

    boolean running() { return running; }

    synchronized void start(boolean radiometric) throws IOException {
        if (running || (thread != null && thread.isAlive())) return;   // the previous file is still being finished
        Bitmap first = e.th.freshBitmap(1500);
        if (first == null) throw new IllegalStateException("no image yet");
        int w = first.getWidth() & ~15, h = first.getHeight() & ~15;   // encoders want multiples of 16
        String mime = pick(w, h);
        MediaFormat f = MediaFormat.createVideoFormat(mime, w, h);
        f.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        f.setInteger(MediaFormat.KEY_BIT_RATE, MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) ? 2_500_000 : 4_000_000);
        f.setInteger(MediaFormat.KEY_FRAME_RATE, 25);
        f.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);
        MediaCodec codec = null;
        Surface input = null;
        ParcelFileDescriptor pfd = null;
        MediaMuxer muxer = null;
        Uri uri = null;
        FileOutputStream raw = null;
        String name = "emberglass_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new java.util.Date());
        try {
            codec = MediaCodec.createEncoderByType(mime);
            codec.configure(f, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            input = codec.createInputSurface();
            codec.start();
            if (Build.VERSION.SDK_INT >= 29) {
                ContentValues cv = new ContentValues();
                cv.put(MediaStore.Video.Media.DISPLAY_NAME, name + ".mp4");
                cv.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                cv.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/Emberglass");
                cv.put(MediaStore.Video.Media.IS_PENDING, 1);
                uri = e.ctx.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv);
                if (uri == null) throw new IOException("gallery not available");
                pfd = e.ctx.getContentResolver().openFileDescriptor(uri, "rw");
                if (pfd == null) throw new IOException("cannot open the video file");
                muxer = new MediaMuxer(pfd.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                file = "Movies/Emberglass/" + name + ".mp4";
            } else {
                File out = new File(e.dir("video"), name + ".mp4");
                muxer = new MediaMuxer(out.getAbsolutePath(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
                file = out.getAbsolutePath();
            }
            if (radiometric) raw = new FileOutputStream(new File(e.dir("radiometric"), name + "_256x192_u16le.raw"));
        } catch (IOException | RuntimeException ex) {   // undo everything set up so far
            if (codec != null) { try { codec.stop(); } catch (RuntimeException ignored) { } codec.release(); }
            if (input != null) input.release();
            if (muxer != null) muxer.release();
            try { if (pfd != null) pfd.close(); } catch (IOException ignored) { }
            try { if (raw != null) raw.close(); } catch (IOException ignored) { }
            if (uri != null) e.ctx.getContentResolver().delete(uri, null, null);
            throw ex;
        }
        codecName = MediaFormat.MIMETYPE_VIDEO_HEVC.equals(mime) ? "H.265" : "H.264";
        startMs = System.currentTimeMillis();
        frames = 0;
        running = true;
        final MediaCodec fc = codec;
        final Surface fi = input;
        final MediaMuxer fm = muxer;
        final FileOutputStream fr = raw;
        final ParcelFileDescriptor fpfd = pfd;
        final Uri furi = uri;
        thread = new Thread(() -> loop(fc, fi, fm, w, h, fr, fpfd, furi, name), "recorder");
        thread.start();
    }

    /** Asks the recording to finish; the recorder thread closes the file. Call off the UI thread to wait. */
    void stop() {
        running = false;
        Thread t = thread;
        if (t != null && Thread.currentThread() != t && android.os.Looper.myLooper() != android.os.Looper.getMainLooper())
            try { t.join(6000); } catch (InterruptedException ignored) { }
    }

    /** H.265 if an encoder takes this size, else H.264. */
    private static String pick(int w, int h) {
        MediaCodecList list = new MediaCodecList(MediaCodecList.REGULAR_CODECS);
        MediaFormat hevc = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_HEVC, w, h);
        hevc.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
        return list.findEncoderForFormat(hevc) != null ? MediaFormat.MIMETYPE_VIDEO_HEVC : MediaFormat.MIMETYPE_VIDEO_AVC;
    }

    private void loop(MediaCodec codec, Surface input, MediaMuxer muxer, int w, int h, FileOutputStream raw,
                      ParcelFileDescriptor pfd, Uri uri, String name) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        int[] track = {-1};
        Bitmap last = null;
        Rect dst = new Rect();
        StringBuilder stamps = new StringBuilder();
        ByteBuffer rb = ByteBuffer.allocate(Thermal.W * Thermal.H * 2).order(ByteOrder.LITTLE_ENDIAN);
        boolean ok = false;
        try {
            while (running) {
                Bitmap b = e.th.freshBitmap(500);
                if (b == null || b == last) continue;
                last = b;
                Canvas c = input.lockHardwareCanvas();
                c.drawColor(Color.BLACK);
                // keep the aspect if the phone is turned while recording
                float s = Math.min((float) w / b.getWidth(), (float) h / b.getHeight());
                int dw = Math.round(b.getWidth() * s), dh = Math.round(b.getHeight() * s);
                dst.set((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2);
                c.drawBitmap(b, null, dst, null);
                input.unlockCanvasAndPost(c);
                if (raw != null) {
                    synchronized (e.th.frameLock) { rb.clear(); rb.asShortBuffer().put(e.th.raw); }
                    raw.write(rb.array());
                    stamps.append(frames == 0 ? "" : ",").append(System.currentTimeMillis() - startMs);
                }
                frames++;
                drain(codec, muxer, info, track, false);
            }
            codec.signalEndOfInputStream();
            drain(codec, muxer, info, track, true);
            ok = track[0] >= 0 && frames > 0;
        } catch (Exception ex) {
            e.setStatus("recording failed: " + ex.getMessage());
        } finally {
            try { codec.stop(); } catch (RuntimeException ignored) { }
            codec.release();
            input.release();
            try { if (track[0] >= 0) muxer.stop(); } catch (RuntimeException ex) { ok = false; }
            muxer.release();
            try { if (pfd != null) pfd.close(); } catch (IOException ignored) { }
            if (uri != null) {
                if (ok) {
                    ContentValues cv = new ContentValues();
                    cv.put(MediaStore.Video.Media.IS_PENDING, 0);
                    e.ctx.getContentResolver().update(uri, cv, null, null);
                } else {
                    e.ctx.getContentResolver().delete(uri, null, null);   // never publish a broken file
                }
            }
            if (raw != null) {
                try {
                    raw.close();
                    String meta = String.format(Locale.US, "{\"format\":\"uint16 little-endian, 256x192 per frame, sensor orientation\","
                            + "\"celsius\":\"value / 64 - 273.15\",\"frames\":%d,\"ms\":[%s],\"camera\":%s}",
                            frames, stamps, e.photoMetaJson());
                    try (FileOutputStream m = new FileOutputStream(new File(e.dir("radiometric"), name + "_256x192_u16le.json"))) {
                        m.write(meta.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    }
                } catch (IOException ignored) { }
            }
            running = false;
            if (ok) e.setStatus("video saved: " + file + " (" + codecName + ", " + frames + " frames)");
            else if (!e.status.startsWith("recording failed")) e.setStatus("recording failed: no video written");
        }
    }

    private static void drain(MediaCodec codec, MediaMuxer muxer, MediaCodec.BufferInfo info, int[] track, boolean eos) {
        long deadline = System.currentTimeMillis() + 5000;   // an encoder that never signals EOS must not hang the thread
        while (true) {
            int i = codec.dequeueOutputBuffer(info, eos ? 10_000 : 0);
            if (i == MediaCodec.INFO_TRY_AGAIN_LATER) {
                if (!eos || System.currentTimeMillis() > deadline) return;
                continue;
            }
            if (i == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                track[0] = muxer.addTrack(codec.getOutputFormat());
                muxer.start();
                continue;
            }
            if (i < 0) continue;
            ByteBuffer out = codec.getOutputBuffer(i);
            if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) info.size = 0;
            if (info.size > 0 && track[0] >= 0 && out != null) {
                out.position(info.offset).limit(info.offset + info.size);
                muxer.writeSampleData(track[0], out, info);
            }
            codec.releaseOutputBuffer(i, false);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) return;
        }
    }
}
