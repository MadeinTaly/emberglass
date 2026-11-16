package app.emberglass;

/**
 * Professional analysis, pure math (no Android): building physics and electrical severity.
 * Checked by test/ProCheck.java.
 */
final class Pro {
    private Pro() { }

    /** Saturation vapour pressure over water, hPa (Magnus, WMO coefficients). */
    static double es(double c) { return 6.112 * Math.exp(17.62 * c / (243.12 + c)); }

    /** Temperature at which vapour pressure pv (hPa) saturates. */
    static double saturationTemp(double pv) {
        double l = Math.log(pv / 6.112);
        return 243.12 * l / (17.62 - l);
    }

    /** Dew point of air at airC and relative humidity rh (%). */
    static double dewPoint(double airC, double rh) { return saturationTemp(rh / 100 * es(airC)); }

    /**
     * Mold limit: the surface temperature at which the relative humidity at the surface reaches 80%,
     * the criterion of EN ISO 13788 for surface temperature design.
     */
    static double moldLimit(double airC, double rh) { return saturationTemp(rh / 100 * es(airC) / 0.8); }

    /** Temperature factor of an inner surface: fRsi = (Tsi - Te) / (Ti - Te). */
    static double fRsi(double surfaceC, double insideC, double outsideC) { return (surfaceC - outsideC) / (insideC - outsideC); }

    /** Thermography of the envelope needs a steady difference across it (EN 13187 suggests >= 10 K). */
    static boolean envelopeValid(double insideC, double outsideC) { return insideC - outsideC >= 10; }

    static final String[] SEVERITY = {"ok", "possible deficiency", "probable deficiency", "monitor, plan repair", "major, repair now"};

    /**
     * Electrical severity from a temperature rise, after NETA MTS Table 100.18.
     * vsAmbient: rise over ambient air; otherwise rise over a similar component under similar load.
     * Returns 0 (below 1 C) to 4.
     */
    static int severity(double dT, boolean vsAmbient) {
        if (dT < 1) return 0;
        if (vsAmbient) return dT <= 10 ? 1 : dT <= 20 ? 2 : dT <= 40 ? 3 : 4;
        return dT <= 3 ? 1 : dT <= 15 ? 2 : 4;   // similar components have no "monitor" class
    }

    /** Typical emissivities (tables vary; check with tape or a contact probe for critical work). */
    static final String[] MATERIALS = {
            "Matt paint 0.95", "Brick 0.93", "Concrete 0.94", "Plaster 0.91", "Wood 0.90", "Glass 0.92",
            "PVC 0.92", "Rubber 0.93", "Insulating tape 0.95", "Skin 0.98", "Water 0.96",
            "Oxidised steel 0.80", "Oxidised copper 0.65", "Stainless steel 0.16", "Oxidised aluminium 0.30"};

    static float materialEmissivity(String m) { return Float.parseFloat(m.substring(m.lastIndexOf(' ') + 1)); }
}
