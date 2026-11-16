package app.emberglass;

import android.hardware.usb.UsbDeviceConnection;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * InfiRay Tiny1-C vendor command channel (Realtek bridge vendor control transfers).
 * Protocol from LeoDJ/P2Pro-Viewer and the official SDK headers; see HARDWARE.md.
 * Only read-only and documented-safe setters are exposed: never flash/reset commands.
 */
final class Tiny {
    // TPD (temperature measurement) parameter ids
    static final int TPD_DISTANCE = 0, TPD_REFLECTED_K = 1, TPD_AMBIENT_K = 2, TPD_EMISSIVITY = 3, TPD_TAU = 4, TPD_GAIN = 5;

    private final UsbDeviceConnection c;

    Tiny(UsbDeviceConnection c) { this.c = c; }

    private void waitReady(long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        byte[] b = new byte[1];
        while (true) {
            if (c.controlTransfer(0xC1, 0x44, 0x78, 0x200, b, 1, 1000) < 1) throw new IOException("status read failed");
            if ((b[0] & 3) == 0) {
                if ((b[0] & 0xFC) != 0) throw new IOException("camera error 0x" + Integer.toHexString(b[0] & 0xff));
                return;
            }
            if (System.currentTimeMillis() > end) throw new IOException("camera busy (timeout)");
            try { Thread.sleep(5); } catch (InterruptedException e) { throw new IOException(e); }
        }
    }

    private void write(int addr, byte[] data) throws IOException {
        if (c.controlTransfer(0x41, 0x45, 0x78, addr, data, data.length, 1000) != data.length)
            throw new IOException("write 0x" + Integer.toHexString(addr) + " failed");
    }

    private byte[] read(int addr, int len) throws IOException {
        byte[] b = new byte[len];
        if (c.controlTransfer(0xC1, 0x44, 0x78, addr, b, len, 1000) != len)
            throw new IOException("read 0x" + Integer.toHexString(addr) + " failed");
        return b;
    }

    private static byte[] header(int cmd, int param, int len) {
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        b.putShort((short) cmd).putInt(param).order(ByteOrder.BIG_ENDIAN).putShort((short) len);
        return b.array();
    }

    synchronized void stdWrite(int cmd, int param) throws IOException {
        write(0x1d00, header(cmd, param, 0));
        waitReady(2000);
    }

    synchronized byte[] stdRead(int cmd, int param, int len) throws IOException {
        write(0x1d00, header(cmd, param, len));
        waitReady(2000);
        return read(0x1d08, len);
    }

    private void longCmd(int cmd, int p1, int p2, int p3, int p4) throws IOException {
        ByteBuffer a = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN);
        a.putShort((short) cmd).order(ByteOrder.BIG_ENDIAN).putShort((short) p1).putInt(p2);
        ByteBuffer b = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN);
        b.putInt(p3).putInt(p4);
        write(0x9d00, a.array());
        write(0x1d08, b.array());
    }

    synchronized void longWrite(int cmd, int p1, int p2, long timeoutMs) throws IOException {
        longCmd(cmd, p1, p2, 0, 0);
        waitReady(timeoutMs);
    }

    synchronized int longRead16(int cmd, int p1) throws IOException {
        longCmd(cmd, p1, 0, 0, 2);
        waitReady(2000);
        byte[] r = read(0x1d10, 2);
        return (r[0] & 0xff) << 8 | (r[1] & 0xff);
    }

    /** Waits until the command channel answers (about 3 s after the stream starts on a cold camera). */
    void waitAlive(long timeoutMs) throws IOException {
        long end = System.currentTimeMillis() + timeoutMs;
        while (true) {
            try { waitReady(500); return; } catch (IOException e) {
                if (System.currentTimeMillis() > end) throw e;
            }
        }
    }

    // --- commands -------------------------------------------------------------------

    /** Flat-field correction with the internal shutter. */
    synchronized void ffc() throws IOException {   // the shutter cycle takes a few seconds on a 0bda:5840
        write(0x1d00, header(0xc10d, 1, 0));
        waitReady(8000);
    }

    int getTpd(int id) throws IOException { return longRead16(0x8514, id); }

    /** Gain switch recalibrates: allow up to 30 s. */
    void setTpd(int id, int value) throws IOException { longWrite(0xC514, id, value, id == TPD_GAIN ? 30000 : 3000); }

    void setAutoShutter(int id, int value) throws IOException { longWrite(0xC214, id, value, 3000); }

    /** Raw sensor (FPA) temperature counter; scale not documented. */
    int sensorVtemp() throws IOException { return be16(stdRead(0x8B0D, 0, 2)); }

    int shutterVtemp() throws IOException { return be16(stdRead(0x840C, 0, 2)); }

    /** Device info strings: 5 = firmware version (50 bytes), 7 = serial (16 bytes). */
    String info(int id, int len) throws IOException {
        byte[] b = stdRead(0x8405, id, len);
        int n = 0;
        while (n < b.length && b[n] != 0) n++;
        return new String(b, 0, n, java.nio.charset.StandardCharsets.US_ASCII).trim();
    }

    private static int be16(byte[] b) { return (b[0] & 0xff) << 8 | (b[1] & 0xff); }
}
