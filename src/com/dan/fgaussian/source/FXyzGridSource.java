package com.dan.fgaussian.source;

import com.dan.fgaussian.core.FQuat;
import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.shade.FHeightRamp;
import com.dan.fgaussian.shade.FLandCover;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.TreeSet;

/**
 * Liest eine ASCII-Rasterdatei im XYZ-Format (Rechtswert, Hochwert, Hoehe je
 * Zeile) und baut daraus eine Splat-Wolke.
 *
 * <p>Das Verfahren ist Flaechen-Splatting: jede Rasterzelle wird zu einem
 * flachen, an der Gelaendeoberflaeche ausgerichteten Gauss. Zwei Achsen liegen
 * in der Hangebene und sind etwas groesser als die halbe Zellweite, damit sich
 * benachbarte Splats ueberlappen und keine Loecher entstehen; die dritte Achse
 * ist duenn, wodurch aus der Punktwolke eine geschlossene Oberflaeche wird.</p>
 *
 * <p>Szenenkoordinaten: x = Ost, y = Hoehe, z = Sued. Der Kachelmittelpunkt
 * liegt im Ursprung, damit die Orbit-Kamera keine grossen Absolutwerte
 * verrechnen muss - bei UTM-Hochwerten um 5 984 000 kostet das sonst
 * sichtbar Genauigkeit in float.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FXyzGridSource {

    /** Werte unterhalb dieser Schwelle gelten als Fehlstelle. */
    private static final float NO_DATA_LIMIT = -100f;

    private final int nx, ny;
    private final double cell;
    private final double originX, originY;
    private final float[] heights;

    private float minHeight = Float.MAX_VALUE;
    private float maxHeight = -Float.MAX_VALUE;

    private FXyzGridSource(int nx, int ny, double cell, double originX, double originY, float[] heights) {
        this.nx = nx;
        this.ny = ny;
        this.cell = cell;
        this.originX = originX;
        this.originY = originY;
        this.heights = heights;
        for (float value : heights) {
            if (!Float.isNaN(value)) {
                if (value < minHeight) minHeight = value;
                if (value > maxHeight) maxHeight = value;
            }
        }
        if (minHeight > maxHeight) {
            minHeight = 0f;
            maxHeight = 0f;
        }
    }

    public int gridWidth()      { return nx; }
    public int gridHeight()     { return ny; }
    public double cellSize()    { return cell; }
    public double originEast()  { return originX; }
    public double originNorth() { return originY; }
    public float minHeight()    { return minHeight; }
    public float maxHeight()    { return maxHeight; }

    /**
     * Liest die Datei und rekonstruiert das Raster.
     *
     * <p>Die Zellweite wird nicht angenommen, sondern aus den vorkommenden
     * Koordinaten abgeleitet - dieselbe Klasse liest damit DGM1, DGM5 und
     * DGM25 ohne Parameter.</p>
     */
    public static FXyzGridSource read(File file) throws IOException {
        int lineCount = 0;
        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.US_ASCII)) {
            while (r.readLine() != null) {
                lineCount++;
            }
        }

        double[] xs = new double[lineCount];
        double[] ys = new double[lineCount];
        float[] zs = new float[lineCount];
        int n = 0;

        try (BufferedReader r = Files.newBufferedReader(file.toPath(), StandardCharsets.US_ASCII)) {
            String line;
            while ((line = r.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.charAt(0) == '#') {
                    continue;
                }
                String[] parts = line.split("[\\s;]+");
                if (parts.length < 3) {
                    continue;
                }
                try {
                    xs[n] = Double.parseDouble(parts[0]);
                    ys[n] = Double.parseDouble(parts[1]);
                    zs[n] = Float.parseFloat(parts[2]);
                    n++;
                } catch (NumberFormatException ignored) {
                    // Kopfzeile oder Schrott - ueberspringen.
                }
            }
        }
        if (n == 0) {
            throw new IOException("Keine auswertbaren Zeilen in " + file.getName());
        }

        TreeSet<Double> uniqueX = new TreeSet<>();
        TreeSet<Double> uniqueY = new TreeSet<>();
        for (int i = 0; i < n; i++) {
            uniqueX.add(round3(xs[i]));
            uniqueY.add(round3(ys[i]));
        }
        if (uniqueX.size() < 2 || uniqueY.size() < 2) {
            throw new IOException("Datei beschreibt kein Raster: " + file.getName());
        }

        double cell = smallestStep(uniqueX);
        double cellY = smallestStep(uniqueY);
        if (cellY > 0 && Math.abs(cellY - cell) > 1e-6) {
            // Nicht quadratisch: die kleinere Weite entscheidet ueber die Aufloesung.
            cell = Math.min(cell, cellY);
        }

        double ox = uniqueX.first();
        double oy = uniqueY.first();
        int gw = (int) Math.round((uniqueX.last() - ox) / cell) + 1;
        int gh = (int) Math.round((uniqueY.last() - oy) / cell) + 1;

        float[] grid = new float[gw * gh];
        java.util.Arrays.fill(grid, Float.NaN);
        for (int i = 0; i < n; i++) {
            int ix = (int) Math.round((xs[i] - ox) / cell);
            int iy = (int) Math.round((ys[i] - oy) / cell);
            if (ix < 0 || ix >= gw || iy < 0 || iy >= gh) {
                continue;
            }
            float z = zs[i];
            grid[iy * gw + ix] = z <= NO_DATA_LIMIT ? Float.NaN : z;
        }
        return new FXyzGridSource(gw, gh, cell, ox, oy, grid);
    }

    /**
     * Baut die Splat-Wolke.
     *
     * @param stride       1 = jede Zelle, 2 = jede zweite, usw. (Detailstufe)
     * @param exaggeration Hoehenueberhoehung; bei fuenf Metern auf einen
     *                     Kilometer ist unter etwa 8 nichts zu erkennen
     * @param ramp         Hoehenrampe fuer die Grundfarbe
     */
    public FSplatCloud buildCloud(int stride, float exaggeration, FHeightRamp ramp) {
        return buildCloud(stride, exaggeration, ramp, null);
    }

    /**
     * Baut die Splat-Wolke und faerbt sie, wo vorhanden, nach Flaechenart
     * statt nach Hoehe.
     *
     * @param cover Flaechenmaske im selben Raster, oder {@code null}
     */
    public FSplatCloud buildCloud(int stride, float exaggeration, FHeightRamp ramp, FCoverGrid cover) {
        if (stride < 1) {
            stride = 1;
        }
        int cols = (nx + stride - 1) / stride;
        int rows = (ny + stride - 1) / stride;

        FSplatCloud cloud = new FSplatCloud(cols * rows);

        final double centerX = originX + (nx - 1) * cell * 0.5;
        final double centerY = originY + (ny - 1) * cell * 0.5;
        final float spacing = (float) (cell * stride);
        final float inPlane = spacing * 0.62f;
        final float thin = Math.max(0.04f, spacing * 0.06f);

        // Sonne aus Suedwesten, mittelhoch.
        final float lx = 0.38f, ly = 0.78f, lz = 0.50f;

        final float[] q = new float[4];
        final float[] rgb = new float[3];

        for (int iy = 0; iy < ny; iy += stride) {
            for (int ix = 0; ix < nx; ix += stride) {
                float hCentre = heights[iy * nx + ix];
                if (Float.isNaN(hCentre)) {
                    continue;
                }

                // Zentrale Differenzen, am Rand einseitig.
                float hxm = sample(ix - stride, iy, hCentre);
                float hxp = sample(ix + stride, iy, hCentre);
                float hym = sample(ix, iy - stride, hCentre);
                float hyp = sample(ix, iy + stride, hCentre);

                float dhdx = (hxp - hxm) / (2f * spacing) * exaggeration;
                float dhdNorth = (hyp - hym) / (2f * spacing) * exaggeration;

                // z zeigt nach Sueden, daher wechselt die Nordableitung das Vorzeichen.
                float nxv = -dhdx;
                float nyv = 1f;
                float nzv = dhdNorth;

                float invLen = 1f / (float) Math.sqrt(nxv * nxv + nyv * nyv + nzv * nzv);
                nxv *= invLen; nyv *= invLen; nzv *= invLen;

                FQuat.fromUnitZTo(nxv, nyv, nzv, q);

                FLandCover kind = cover == null ? FLandCover.UNKNOWN : cover.coverAt(ix, iy);
                if (kind == FLandCover.UNKNOWN) {
                    ramp.colorAt(hCentre, rgb);
                } else {
                    kind.color(rgb);
                }
                float lambert = nxv * lx + nyv * ly + nzv * lz;
                if (lambert < 0f) {
                    lambert = 0f;
                }
                float shade = 0.42f + 0.58f * lambert;

                // Winzige Streuung, damit grosse gleichhohe Flaechen - hier die
                // Wasserflaeche auf exakt 0,00 m - nicht wie lackiert wirken.
                float jitter = 1f + 0.025f * hash(ix, iy);

                float px = (float) (originX + ix * cell - centerX);
                float pz = -(float) (originY + iy * cell - centerY);
                float py = hCentre * exaggeration;

                cloud.add(px, py, pz,
                        inPlane, inPlane, thin,
                        q,
                        clamp01(rgb[0] * shade * jitter),
                        clamp01(rgb[1] * shade * jitter),
                        clamp01(rgb[2] * shade * jitter),
                        1.0f);
            }
        }
        cloud.computeBounds();
        return cloud;
    }

    /** Rechtswert der Kachelmitte, also des Szenenursprungs. */
    public double centreEast() {
        return originX + (nx - 1) * cell * 0.5;
    }

    /** Hochwert der Kachelmitte. */
    public double centreNorth() {
        return originY + (ny - 1) * cell * 0.5;
    }

    /** Rechnet einen Rechtswert in die Szenenachse x um. */
    public float sceneX(double east) {
        return (float) (east - centreEast());
    }

    /** Rechnet einen Hochwert in die Szenenachse z um; z zeigt nach Sueden. */
    public float sceneZ(double north) {
        return -(float) (north - centreNorth());
    }

    /**
     * Rohe Hoehe einer Rasterzelle, {@code NaN} bei Fehlstelle oder ausserhalb.
     * Anders als {@link #heightAt(double, double)} wird nicht interpoliert -
     * fuer Analysen auf dem Raster ist der Originalwert gefragt.
     */
    public float heightAtCell(int ix, int iy) {
        if (ix < 0 || ix >= nx || iy < 0 || iy >= ny) {
            return Float.NaN;
        }
        return heights[iy * nx + ix];
    }

    /**
     * Gelaendehoehe an einer beliebigen Stelle, bilinear zwischen den vier
     * umgebenden Rasterpunkten. Liefert 0, wenn die Stelle ausserhalb der
     * Kachel liegt oder ringsum Fehlstellen hat.
     */
    public float heightAt(double east, double north) {
        double fx = (east - originX) / cell;
        double fy = (north - originY) / cell;
        int ix = (int) Math.floor(fx);
        int iy = (int) Math.floor(fy);
        float tx = (float) (fx - ix);
        float ty = (float) (fy - iy);

        float h00 = valueOrNaN(ix, iy);
        float h10 = valueOrNaN(ix + 1, iy);
        float h01 = valueOrNaN(ix, iy + 1);
        float h11 = valueOrNaN(ix + 1, iy + 1);

        // Fehlstellen durch den Mittelwert der vorhandenen Ecken ersetzen.
        float sum = 0f;
        int have = 0;
        if (!Float.isNaN(h00)) { sum += h00; have++; }
        if (!Float.isNaN(h10)) { sum += h10; have++; }
        if (!Float.isNaN(h01)) { sum += h01; have++; }
        if (!Float.isNaN(h11)) { sum += h11; have++; }
        if (have == 0) {
            return 0f;
        }
        float fill = sum / have;
        if (Float.isNaN(h00)) h00 = fill;
        if (Float.isNaN(h10)) h10 = fill;
        if (Float.isNaN(h01)) h01 = fill;
        if (Float.isNaN(h11)) h11 = fill;

        float a = h00 + (h10 - h00) * tx;
        float b = h01 + (h11 - h01) * tx;
        return a + (b - a) * ty;
    }

    private float valueOrNaN(int ix, int iy) {
        if (ix < 0 || ix >= nx || iy < 0 || iy >= ny) {
            return Float.NaN;
        }
        return heights[iy * nx + ix];
    }

    private float sample(int ix, int iy, float fallback) {
        if (ix < 0 || ix >= nx || iy < 0 || iy >= ny) {
            return fallback;
        }
        float value = heights[iy * nx + ix];
        return Float.isNaN(value) ? fallback : value;
    }

    private static float hash(int a, int b) {
        int h = a * 73856093 ^ b * 19349663;
        h ^= h >>> 13;
        h *= 0x5bd1e995;
        h ^= h >>> 15;
        return ((h & 0xFFFF) / 32768f) - 1f;
    }

    private static float clamp01(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }

    private static double round3(double value) {
        return Math.round(value * 1000.0) / 1000.0;
    }

    private static double smallestStep(TreeSet<Double> values) {
        double previous = Double.NaN;
        double best = Double.MAX_VALUE;
        for (double value : values) {
            if (!Double.isNaN(previous)) {
                double step = value - previous;
                if (step > 1e-6 && step < best) {
                    best = step;
                }
            }
            previous = value;
        }
        return best == Double.MAX_VALUE ? 1.0 : best;
    }
}
