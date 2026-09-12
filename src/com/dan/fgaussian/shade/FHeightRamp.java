package com.dan.fgaussian.shade;

/**
 * Hoehenabhaengige Einfaerbung mit linearer Interpolation zwischen Stuetzstellen.
 *
 * <p>Die mitgelieferte Rampe {@link #balticCoast()} ist auf das Hoehenband der
 * Luebecker Bucht zugeschnitten: unterhalb von etwa 0,05 m liegt Wasser, bis
 * etwa 1 m Strand und Hafensohle, darueber Gruenland und Duenenkamm. Ohne
 * diesen engen Zuschnitt verschwindet die gesamte Landflaeche in einem
 * einzigen Farbton, denn die Kachel umfasst nur gut fuenf Hoehenmeter.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FHeightRamp {

    private final float[] stops;
    private final float[][] colors;

    public FHeightRamp(float[] stops, float[][] colors) {
        if (stops.length != colors.length || stops.length < 2) {
            throw new IllegalArgumentException("Stuetzstellen und Farben passen nicht zusammen");
        }
        this.stops = stops.clone();
        this.colors = new float[colors.length][];
        for (int i = 0; i < colors.length; i++) {
            this.colors[i] = colors[i].clone();
        }
    }

    /**
     * Naturnahe Kuestenrampe, Hoehen in Metern ueber NHN.
     * Werte sind Anzeigefarben (0..1), kein Gamma.
     */
    public static FHeightRamp balticCoast() {
        return new FHeightRamp(
            new float[] { -0.60f,  0.00f,  0.04f,  0.35f,  1.00f,  2.20f,  3.60f,  4.80f },
            new float[][] {
                { 0.043f, 0.145f, 0.196f },   // tiefes Hafenbecken
                { 0.098f, 0.286f, 0.333f },   // Wasser an der Kante
                { 0.706f, 0.655f, 0.525f },   // nasser Sand
                { 0.816f, 0.769f, 0.635f },   // Strand
                { 0.573f, 0.588f, 0.435f },   // Duenengras
                { 0.412f, 0.490f, 0.353f },   // Gruenland
                { 0.482f, 0.510f, 0.400f },   // Deichkrone
                { 0.635f, 0.635f, 0.588f }    // Bebauung, Kaikante
            });
    }

    /**
     * Schreibt die Farbe fuer eine Hoehe nach {@code out} (Laenge 3).
     */
    public void colorAt(float height, float[] out) {
        if (height <= stops[0]) {
            System.arraycopy(colors[0], 0, out, 0, 3);
            return;
        }
        int last = stops.length - 1;
        if (height >= stops[last]) {
            System.arraycopy(colors[last], 0, out, 0, 3);
            return;
        }
        int i = 1;
        while (i < last && height > stops[i]) {
            i++;
        }
        float span = stops[i] - stops[i - 1];
        float t = span <= 0f ? 0f : (height - stops[i - 1]) / span;
        float[] a = colors[i - 1];
        float[] b = colors[i];
        out[0] = a[0] + (b[0] - a[0]) * t;
        out[1] = a[1] + (b[1] - a[1]) * t;
        out[2] = a[2] + (b[2] - a[2]) * t;
    }
}
