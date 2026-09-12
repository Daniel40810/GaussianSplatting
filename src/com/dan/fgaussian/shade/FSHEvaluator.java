package com.dan.fgaussian.shade;

import com.dan.fgaussian.core.FSplatCloud;

/**
 * Wertet Spherical-Harmonics-Koeffizienten zu einer Farbe aus.
 *
 * <p>Damit bekommt ein Splat eine <em>blickrichtungsabhaengige</em> Farbe: eine
 * nasse Kaimauer ist aus der Gegenlichtrichtung heller als von der Seite, eine
 * Fensterfront spiegelt nur in einem schmalen Winkelbereich. Genau das
 * unterscheidet eine aus Fotos rekonstruierte Szene von einem eingefaerbten
 * Hoehenmodell - und genau dafuer traegt {@link FSplatCloud} von Anfang an
 * einen SH-Block.</p>
 *
 * <p>Die Basisfunktionen und Vorfaktoren entsprechen der ueblichen reellen
 * SH-Formulierung bis Grad 3, wie sie auch die gaengigen 3DGS-Implementierungen
 * verwenden. Koeffizient 0 ist der richtungsunabhaengige Anteil; die Farbe wird
 * am Ende um 0,5 verschoben und auf 0..1 begrenzt.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSHEvaluator {

    private static final float C0 = 0.28209479177387814f;

    private static final float C1 = 0.4886025119029199f;

    private static final float C2_0 = 1.0925484305920792f;
    private static final float C2_1 = -1.0925484305920792f;
    private static final float C2_2 = 0.31539156525252005f;
    private static final float C2_3 = -1.0925484305920792f;
    private static final float C2_4 = 0.5462742152960396f;

    private static final float C3_0 = -0.5900435899266435f;
    private static final float C3_1 = 2.890611442640554f;
    private static final float C3_2 = -0.4570457994644658f;
    private static final float C3_3 = 0.3731763325901154f;
    private static final float C3_4 = -0.4570457994644658f;
    private static final float C3_5 = 1.445305721320277f;
    private static final float C3_6 = -0.5900435899266435f;

    private FSHEvaluator() {
    }

    /**
     * Farbe eines Splats fuer eine Blickrichtung.
     *
     * @param cloud Wolke mit belegtem SH-Block
     * @param index Splatindex
     * @param dx,dy,dz Richtung vom Auge zum Splat, muss nicht normiert sein
     * @param out   Ziel der Laenge 3, Werte 0..1
     */
    public static void evaluate(FSplatCloud cloud, int index,
                                float dx, float dy, float dz, float[] out) {
        if (cloud.shDegree == 0 || cloud.sh == null) {
            out[0] = cloud.cr[index];
            out[1] = cloud.cg[index];
            out[2] = cloud.cb[index];
            return;
        }
        float len = (float) Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1e-12f) {
            dx = 0f; dy = 0f; dz = 1f;
        } else {
            dx /= len; dy /= len; dz /= len;
        }

        final int coeffs = cloud.shCoeffCount();
        final int base = index * coeffs * 3;
        final float[] sh = cloud.sh;

        float r = C0 * sh[base];
        float g = C0 * sh[base + 1];
        float b = C0 * sh[base + 2];

        if (cloud.shDegree >= 1) {
            float b1 = -C1 * dy;
            float b2 =  C1 * dz;
            float b3 = -C1 * dx;
            r += b1 * sh[base + 3] + b2 * sh[base + 6] + b3 * sh[base + 9];
            g += b1 * sh[base + 4] + b2 * sh[base + 7] + b3 * sh[base + 10];
            b += b1 * sh[base + 5] + b2 * sh[base + 8] + b3 * sh[base + 11];
        }

        if (cloud.shDegree >= 2) {
            float xx = dx * dx, yy = dy * dy, zz = dz * dz;
            float xy = dx * dy, yz = dy * dz, xz = dx * dz;
            float b4 = C2_0 * xy;
            float b5 = C2_1 * yz;
            float b6 = C2_2 * (2f * zz - xx - yy);
            float b7 = C2_3 * xz;
            float b8 = C2_4 * (xx - yy);
            for (int c = 0; c < 3; c++) {
                float value = b4 * sh[base + 12 + c]
                            + b5 * sh[base + 15 + c]
                            + b6 * sh[base + 18 + c]
                            + b7 * sh[base + 21 + c]
                            + b8 * sh[base + 24 + c];
                if (c == 0) r += value; else if (c == 1) g += value; else b += value;
            }
        }

        if (cloud.shDegree >= 3) {
            float xx = dx * dx, yy = dy * dy, zz = dz * dz;
            float b9  = C3_0 * dy * (3f * xx - yy);
            float b10 = C3_1 * dx * dy * dz;
            float b11 = C3_2 * dy * (4f * zz - xx - yy);
            float b12 = C3_3 * dz * (2f * zz - 3f * xx - 3f * yy);
            float b13 = C3_4 * dx * (4f * zz - xx - yy);
            float b14 = C3_5 * dz * (xx - yy);
            float b15 = C3_6 * dx * (xx - 3f * yy);
            for (int c = 0; c < 3; c++) {
                float value = b9  * sh[base + 27 + c]
                            + b10 * sh[base + 30 + c]
                            + b11 * sh[base + 33 + c]
                            + b12 * sh[base + 36 + c]
                            + b13 * sh[base + 39 + c]
                            + b14 * sh[base + 42 + c]
                            + b15 * sh[base + 45 + c];
                if (c == 0) r += value; else if (c == 1) g += value; else b += value;
            }
        }

        out[0] = clamp01(r + 0.5f);
        out[1] = clamp01(g + 0.5f);
        out[2] = clamp01(b + 0.5f);
    }

    /**
     * Schreibt die richtungsunabhaengige Farbe (nur Koeffizient 0) nach
     * {@code cr/cg/cb}. Dient als Grundfarbe, solange der Renderer die
     * Richtungsanteile nicht auswertet.
     */
    public static void bakeBaseColor(FSplatCloud cloud) {
        if (cloud.shDegree == 0 || cloud.sh == null) {
            return;
        }
        int coeffs = cloud.shCoeffCount();
        for (int i = 0; i < cloud.count; i++) {
            int base = i * coeffs * 3;
            cloud.cr[i] = clamp01(C0 * cloud.sh[base] + 0.5f);
            cloud.cg[i] = clamp01(C0 * cloud.sh[base + 1] + 0.5f);
            cloud.cb[i] = clamp01(C0 * cloud.sh[base + 2] + 0.5f);
        }
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
