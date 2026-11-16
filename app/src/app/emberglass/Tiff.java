package app.emberglass;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Radiometric photo as a baseline TIFF: one 32-bit float sample per pixel = temperature in °C,
 * with the measurement metadata as JSON in ImageDescription. Opens in ImageJ/Fiji, MATLAB
 * (imread), Python (tifffile, Pillow), QGIS and GDAL without any vendor software.
 * Pure Java, checked by test/ProCheck.java.
 */
final class Tiff {
    private Tiff() { }

    static byte[] float32(int w, int h, float[] celsius, String description, String software, String dateTime) {
        byte[] desc = z(description), soft = z(software), date = z(dateTime.length() == 19 ? dateTime : "1970:01:01 00:00:00");
        int tags = 14;
        int ifd = 8, ifdSize = 2 + tags * 12 + 4;
        int descOff = ifd + ifdSize, softOff = descOff + desc.length, dateOff = softOff + soft.length;
        int dataOff = (dateOff + date.length + 3) & ~3;
        ByteBuffer b = ByteBuffer.allocate(dataOff + w * h * 4).order(ByteOrder.LITTLE_ENDIAN);
        b.put((byte) 'I').put((byte) 'I').putShort((short) 42).putInt(ifd);
        b.putShort((short) tags);
        // tags in ascending order: type 2 ASCII, 3 SHORT, 4 LONG, 5 RATIONAL
        tag(b, 256, 4, 1, w);                       // ImageWidth
        tag(b, 257, 4, 1, h);                       // ImageLength
        tag(b, 258, 3, 1, 32);                      // BitsPerSample
        tag(b, 259, 3, 1, 1);                       // Compression: none
        tag(b, 262, 3, 1, 1);                       // Photometric: black is zero
        tag(b, 270, 2, desc.length, descOff);       // ImageDescription (JSON)
        tag(b, 273, 4, 1, dataOff);                 // StripOffsets
        tag(b, 277, 3, 1, 1);                       // SamplesPerPixel
        tag(b, 278, 4, 1, h);                       // RowsPerStrip: one strip
        tag(b, 279, 4, 1, w * h * 4);               // StripByteCounts
        tag(b, 284, 3, 1, 1);                       // PlanarConfiguration
        tag(b, 305, 2, soft.length, softOff);       // Software
        tag(b, 306, 2, date.length, dateOff);       // DateTime "YYYY:MM:DD HH:MM:SS"
        tag(b, 339, 3, 1, 3);                       // SampleFormat: IEEE float
        b.putInt(0);                                // no next IFD
        b.position(descOff); b.put(desc); b.put(soft); b.put(date);
        b.position(dataOff);
        for (int i = 0; i < w * h; i++) b.putFloat(celsius[i]);
        return b.array();
    }

    private static void tag(ByteBuffer b, int id, int type, int count, int value) {
        b.putShort((short) id).putShort((short) type).putInt(count);
        if (type == 3 && count == 1) b.putShort((short) value).putShort((short) 0);   // SHORT is left-justified
        else b.putInt(value);
    }

    private static byte[] z(String s) {   // ASCII with a terminating NUL; non-ASCII becomes '?'
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] u = s.getBytes(StandardCharsets.US_ASCII);
        o.write(u, 0, u.length);
        o.write(0);
        return o.toByteArray();
    }
}
