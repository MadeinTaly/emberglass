package app.emberglass;

/** Runnable check of the professional math: ./test/run.sh (plain JDK, no Android). */
public class ProCheck {
    static void near(String what, double got, double want, double tol) {
        if (Math.abs(got - want) > tol) throw new AssertionError(what + ": got " + got + ", want " + want);
    }

    public static void main(String[] a) {
        // dew point: 20 C / 50 % -> 9.3 C; 20 C / 65 % -> 13.2 C (psychrometric tables)
        near("dew 20/50", Pro.dewPoint(20, 50), 9.3, 0.1);
        near("dew 20/65", Pro.dewPoint(20, 65), 13.2, 0.1);
        // at 100 % RH the dew point is the air temperature
        near("dew at saturation", Pro.dewPoint(15, 100), 15, 1e-9);
        // mold limit (80 % at the surface) is warmer than the dew point: 20 C / 50 % -> 12.6 C
        near("mold 20/50", Pro.moldLimit(20, 50), 12.6, 0.1);
        // EN ISO 13788 example shape: Ti 20, Te 0, surface 14 -> fRsi 0.70
        near("fRsi", Pro.fRsi(14, 20, 0), 0.70, 1e-9);
        // required fRsi for 20 C / 50 % inside, 0 C outside: (12.6 - 0) / 20 = 0.63
        near("fRsi,min", Pro.fRsi(Pro.moldLimit(20, 50), 20, 0), 0.63, 0.01);
        if (Pro.envelopeValid(20, 15)) throw new AssertionError("5 K across the envelope is not enough");
        // NETA bands
        int[][] sim = {{0, 0}, {2, 1}, {10, 2}, {16, 4}};
        for (int[] s : sim) if (Pro.severity(s[0] + 0.5, false) != s[1]) throw new AssertionError("similar " + s[0]);
        int[][] amb = {{5, 1}, {15, 2}, {30, 3}, {45, 4}};
        for (int[] s : amb) if (Pro.severity(s[0], true) != s[1]) throw new AssertionError("ambient " + s[0]);
        near("material", Pro.materialEmissivity("Oxidised copper 0.65"), 0.65, 1e-6);
        // radiometric TIFF: float32 temperatures + JSON description, read back by tools in test/run.sh
        float[] t = new float[6 * 4];
        for (int i = 0; i < t.length; i++) t[i] = 20.5f + i * 0.25f;
        byte[] tif = Tiff.float32(6, 4, t, "{\"unit\":\"C\"}", "Emberglass test", "2026:11:16 17:00:00");
        if (tif[0] != 'I' || tif[2] != 42) throw new AssertionError("tiff header");
        try { java.nio.file.Files.write(java.nio.file.Paths.get(a.length > 0 ? a[0] : "check.tif"), tif); }
        catch (java.io.IOException e) { throw new AssertionError(e); }
        System.out.println("ProCheck ok");
    }
}
