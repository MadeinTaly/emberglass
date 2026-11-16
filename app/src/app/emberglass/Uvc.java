package app.emberglass;

import android.hardware.usb.UsbConstants;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbDeviceConnection;
import android.hardware.usb.UsbEndpoint;
import android.hardware.usb.UsbInterface;
import android.hardware.usb.UsbManager;
import java.io.IOException;
import java.nio.ByteBuffer;

/** Opens the UVC streaming interface, negotiates the frame size and feeds frames from the native reader. */
final class Uvc {
    static { System.loadLibrary("uvcraw"); }
    static native int start(int fd, int ep, int iso, int pktSize, int frameSize);
    static native int read(ByteBuffer buf);
    static native void stop();
    /** URBs in flight * 100 + packets per URB, as accepted by the kernel. */
    static native int layout();

    final UsbDeviceConnection conn;
    final int frameSize;
    /** Transfer setup, for the status line: alt setting, packet size, URBs. */
    String info = "";
    private final UsbInterface vs0;

    private Uvc(UsbDeviceConnection conn, UsbInterface vs0, int frameSize) {
        this.conn = conn; this.vs0 = vs0; this.frameSize = frameSize;
    }

    static boolean isVideo(UsbDevice d) {
        for (int i = 0; i < d.getInterfaceCount(); i++)
            if (d.getInterface(i).getInterfaceClass() == UsbConstants.USB_CLASS_VIDEO) return true;
        return false;
    }

    /** Opens the device and starts streaming the w x h uncompressed (YUYV) format. */
    static Uvc open(UsbManager um, UsbDevice dev, int w, int h) throws IOException {
        UsbDeviceConnection c = um.openDevice(dev);
        if (c == null) throw new IOException("openDevice failed (no permission?)");
        try {
            return open(c, dev, w, h);
        } catch (IOException | RuntimeException e) {
            c.close();
            throw e;
        }
    }

    private static Uvc open(UsbDeviceConnection c, UsbDevice dev, int w, int h) throws IOException {
        // Find format/frame index for w x h in the class-specific VideoStreaming descriptors.
        byte[] d = c.getRawDescriptors();
        int vsIf = -1, fmt = -1, frm = -1, interval = 400000, curIf = -1, curFmt = -1;
        boolean inVs = false;
        for (int i = 0; i + 2 < d.length; ) {
            int len = d[i] & 0xff, type = d[i + 1] & 0xff;
            if (len < 2) break;
            if (type == 4 && i + 6 < d.length) {           // interface descriptor
                curIf = d[i + 2] & 0xff;
                inVs = (d[i + 5] & 0xff) == 14 && (d[i + 6] & 0xff) == 2;
            } else if (type == 0x24 && inVs && i + 8 < d.length) {
                int sub = d[i + 2] & 0xff;
                if (sub == 0x04 || sub == 0x10) curFmt = d[i + 3] & 0xff;            // uncompressed / frame-based format
                if ((sub == 0x05 || sub == 0x11) && fmt < 0) {                     // frame descriptor
                    int fw = u16(d, i + 5), fh = u16(d, i + 7);
                    if (fw == w && fh == h) {
                        vsIf = curIf; fmt = curFmt; frm = d[i + 3] & 0xff;
                        if (sub == 0x05 && i + 25 <= d.length) interval = u32(d, i + 21);
                    }
                }
            }
            i += len;
        }
        if (fmt < 0) throw new IOException("camera has no " + w + "x" + h + " mode");

        UsbInterface alt0 = null;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface f = dev.getInterface(i);
            if (f.getId() == vsIf && f.getAlternateSetting() == 0) alt0 = f;
        }
        if (alt0 == null || !c.claimInterface(alt0, true)) throw new IOException("cannot claim streaming interface");
        // The vendor command channel lives on the control interface; claim it so no kernel driver holds it.
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface f = dev.getInterface(i);
            if (f.getInterfaceClass() == 14 && f.getInterfaceSubclass() == 1) c.claimInterface(f, true);
        }

        // Probe / commit (UVC 1.0: 26 bytes, 1.1: 34, 1.5: 48 -- the device tells us via GET_CUR).
        byte[] p = new byte[48];
        int n = c.controlTransfer(0xA1, 0x81, 0x0100, vsIf, p, p.length, 1000);
        if (n < 26) n = 26;
        java.util.Arrays.fill(p, (byte) 0);
        p[0] = 1;                       // bmHint: keep frame interval
        p[2] = (byte) fmt; p[3] = (byte) frm;
        put32(p, 4, interval);
        if (c.controlTransfer(0x21, 0x01, 0x0100, vsIf, p, n, 1000) < 0) throw new IOException("probe SET failed");
        if (c.controlTransfer(0xA1, 0x81, 0x0100, vsIf, p, n, 1000) < 0) throw new IOException("probe GET failed");
        int maxPayload = u32(p, 22);
        if (c.controlTransfer(0x21, 0x01, 0x0200, vsIf, p, n, 1000) < 0) throw new IOException("commit failed");

        // Pick the endpoint: bulk on alt 0, or the smallest isochronous alt setting that fits one payload.
        UsbInterface best = null; UsbEndpoint ep = null; int pkt = 0;
        for (int i = 0; i < dev.getInterfaceCount(); i++) {
            UsbInterface f = dev.getInterface(i);
            if (f.getId() != vsIf || f.getEndpointCount() == 0) continue;
            UsbEndpoint e = f.getEndpoint(0);
            if (e.getDirection() != UsbConstants.USB_DIR_IN) continue;
            int mps = e.getMaxPacketSize();
            int size = e.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC ? (mps & 0x7ff) * (1 + ((mps >> 11) & 3)) : mps;
            boolean better = ep == null
                    || (pkt < maxPayload && size > pkt)
                    || (size >= maxPayload && size < pkt);
            if (better) { best = f; ep = e; pkt = size; }
        }
        if (ep == null) throw new IOException("no video endpoint");
        boolean iso = ep.getType() == UsbConstants.USB_ENDPOINT_XFER_ISOC;
        if (iso && !c.setInterface(best)) throw new IOException("setInterface failed");
        if (!iso) pkt = Math.max(maxPayload, 16384);

        int frameSize = w * h * 2;
        String setup = (iso ? "iso alt " + best.getAlternateSetting() : "bulk") + ", " + pkt + " B, payload " + maxPayload;
        int r = start(c.getFileDescriptor(), ep.getAddress(), iso ? 1 : 0, pkt, frameSize);
        if (r < 0) throw new IOException("stream start failed: errno " + (-r) + " (" + setup + ")");
        Uvc u = new Uvc(c, alt0, frameSize);
        int l = layout();
        u.info = setup + ", " + (l / 100) + " x " + (l % 100) + " packets";
        return u;
    }

    /** Stops streaming from any thread; the reader's read() then returns -1. */
    void close() {
        stop();
    }

    /** Call from the reader thread after read() returned < 0. */
    void release() {
        try { conn.setInterface(vs0); } catch (RuntimeException ignored) { }
        conn.close();
    }

    private static int u16(byte[] b, int o) { return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8; }
    private static int u32(byte[] b, int o) { return u16(b, o) | u16(b, o + 2) << 16; }
    private static void put32(byte[] b, int o, int v) { for (int k = 0; k < 4; k++) b[o + k] = (byte) (v >> (8 * k)); }
}
