package com.dan.fgaussian.source;

import com.dan.fgaussian.shade.FLandCover;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Leitet Wasser-, Hafen- und Strandflaechen aus dem Hoehenmodell selbst ab,
 * ohne zusaetzliche Geodaten.
 *
 * <p><b>Wasser ist, was eben ist</b> - nicht, was auf null liegt. Diese
 * Unterscheidung ist der Kern des Verfahrens und an der Niendorfer Kachel
 * gemessen: Die offene See steht dort auf exakt 0,00 m, das Hafenbecken aber
 * auf 0,68 m, mit 758 zusammenhaengenden Zellen ueber 310 mal 110 Meter. Eine
 * feste Hoehenschwelle haette das Becken als flaches Land gewertet - und weil
 * es ufernah liegt, waere daraus Strand geworden.</p>
 *
 * <p>Ablauf:</p>
 * <ol>
 *   <li>Ebenheit: Spannweite der Hoehen im Dreimaldrei-Fenster je Zelle.</li>
 *   <li>Zusammenhangskomponenten der ebenen Zellen.</li>
 *   <li>Eine Komponente gilt als Wasser, wenn sie gross genug ist und ihre
 *       Hoehen insgesamt eng beieinander liegen. Ein ebenes Feld faellt durch
 *       die zweite Bedingung, ein Parkplatz durch die erste.</li>
 *   <li>Wasserflaechen mit Kachelrandkontakt sind offene See, alle uebrigen
 *       geschuetztes Wasser - Hafenbecken, Priel, Muendung. Die
 *       Hoehendifferenz trennt die beiden hier ohnehin schon.</li>
 *   <li>Strand: Land dicht am Wasser, dessen Hoehe hoechstens
 *       {@link #setBeachRise(float)} ueber dem Spiegel der offenen See liegt.</li>
 * </ol>
 *
 * @author com.dan.fgaussian
 */
public final class FTerrainCover {

    /** Schrittkosten der Chamfer-Distanz, gerade und diagonal. */
    private static final int STEP_ORTHO = 5;
    private static final int STEP_DIAG = 7;

    private float flatTolerance = 0.10f;
    private float levelTolerance = 0.30f;
    private float growTolerance = 0.08f;
    private double minWaterArea = 2500.0;
    private float beachRise = 1.2f;
    private int beachDistance = 4;

    private int seaCells, harbourCells, beachCells;
    private final List<Float> waterLevels = new ArrayList<>();

    /** Zulaessige Hoehenspannweite im Dreimaldrei-Fenster, Meter. */
    public void setFlatTolerance(float value) {
        this.flatTolerance = value;
    }

    /** Zulaessige Gesamtspannweite innerhalb einer Wasserflaeche, Meter. */
    public void setLevelTolerance(float value) {
        this.levelTolerance = value;
    }

    /** Zulaessiger Abstand vom Spiegel beim Wachsen ueber den Kern hinaus, Meter. */
    public void setGrowTolerance(float value) {
        this.growTolerance = value;
    }

    /** Mindestgroesse einer Wasserflaeche, Quadratmeter. */
    public void setMinWaterArea(double squareMetres) {
        this.minWaterArea = squareMetres;
    }

    /** Wie hoch der Strand ueber den Wasserspiegel reicht, Meter. */
    public void setBeachRise(float value) {
        this.beachRise = value;
    }

    /** Wie weit der Strand vom Wasser reicht, in Rasterzellen. */
    public void setBeachDistance(int cells) {
        this.beachDistance = Math.max(1, cells);
    }

    public int seaCells()     { return seaCells; }
    public int harbourCells() { return harbourCells; }
    public int beachCells()   { return beachCells; }

    /** Die gefundenen Wasserspiegel in Metern, groesste Flaeche zuerst. */
    public List<Float> waterLevels() {
        return waterLevels;
    }

    /**
     * Traegt die abgeleiteten Flaechen in die Maske ein. Bereits gesetzte
     * Zellen bleiben unangetastet - aus Geodaten gelesene Flaechen haben also
     * Vorrang vor der Ableitung.
     */
    public void apply(FXyzGridSource terrain, FCoverGrid cover) {
        final int nx = terrain.gridWidth();
        final int ny = terrain.gridHeight();
        final int n = nx * ny;
        final double cellArea = terrain.cellSize() * terrain.cellSize();
        final int minCells = (int) Math.ceil(minWaterArea / cellArea);

        float[] h = new float[n];
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                h[iy * nx + ix] = terrain.heightAtCell(ix, iy);
            }
        }

        boolean[] flat = new boolean[n];
        for (int iy = 0; iy < ny; iy++) {
            for (int ix = 0; ix < nx; ix++) {
                flat[iy * nx + ix] = isFlat(h, nx, ny, ix, iy);
            }
        }

        List<int[]> components = components(flat, nx, ny);
        components.sort(Comparator.comparingInt((int[] c) -> c.length).reversed());

        boolean[] water = new boolean[n];
        seaCells = 0;
        harbourCells = 0;
        waterLevels.clear();
        float seaLevel = Float.NaN;

        for (int[] cells : components) {
            if (cells.length < minCells) {
                continue;
            }
            float min = Float.MAX_VALUE, max = -Float.MAX_VALUE, sum = 0f;
            boolean touchesBorder = false;
            for (int i : cells) {
                float value = h[i];
                if (value < min) min = value;
                if (value > max) max = value;
                sum += value;
                int y = i / nx;
                int x = i - y * nx;
                if (x == 0 || y == 0 || x == nx - 1 || y == ny - 1) {
                    touchesBorder = true;
                }
            }
            if (max - min > levelTolerance) {
                // Eben, aber geneigt: ein Feld oder eine Rampe, kein Wasser.
                continue;
            }

            float level = sum / cells.length;
            waterLevels.add(level);
            FLandCover kind = touchesBorder ? FLandCover.SEA : FLandCover.HARBOUR_BASIN;
            if (touchesBorder && Float.isNaN(seaLevel)) {
                seaLevel = level;
            }

            // Der ebene Kern ist nur der Innenbereich: zum Ufer hin wird das
            // Raster unruhig und faellt durch die Ebenheitspruefung. Deshalb von
            // hier aus ueber die Hoehe weiterwachsen - fuer das Niendorfer
            // Becken sind das 363 Kernzellen, aus denen die vollen rund 750
            // werden.
            int grown = grow(cells, water, h, nx, ny, level);
            for (int i = 0; i < n; i++) {
                if (water[i] && cover.coverAt(i - (i / nx) * nx, i / nx) == FLandCover.UNKNOWN) {
                    set(cover, nx, i, kind);
                }
            }
            if (kind == FLandCover.SEA) {
                seaCells += grown;
            } else {
                harbourCells += grown;
            }
        }
        if (Float.isNaN(seaLevel)) {
            seaLevel = terrain.minHeight();
        }

        // Strand: Land dicht am Wasser, knapp ueber dem Spiegel.
        int[] toWater = distanceTransform(water, nx, ny);
        final int beachLimit = beachDistance * STEP_ORTHO;
        final float beachMax = seaLevel + beachRise;
        beachCells = 0;
        for (int i = 0; i < n; i++) {
            if (water[i] || toWater[i] > beachLimit || Float.isNaN(h[i])) {
                continue;
            }
            if (h[i] <= beachMax) {
                set(cover, nx, i, FLandCover.BEACH);
                beachCells++;
            }
        }
    }


    /**
     * Erweitert eine Wasserflaeche vom ebenen Kern aus ueber alle anliegenden
     * Zellen, deren Hoehe nah genug am Spiegel liegt.
     *
     * @return Anzahl der neu belegten Zellen
     */
    private int grow(int[] core, boolean[] water, float[] h, int nx, int ny, float level) {
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int count = 0;
        for (int i : core) {
            if (!water[i]) {
                water[i] = true;
                count++;
                queue.add(i);
            }
        }
        while (!queue.isEmpty()) {
            int i = queue.poll();
            int y = i / nx;
            int x = i - y * nx;
            if (x > 0)      count += tryGrow(i - 1, water, h, level, queue);
            if (x < nx - 1) count += tryGrow(i + 1, water, h, level, queue);
            if (y > 0)      count += tryGrow(i - nx, water, h, level, queue);
            if (y < ny - 1) count += tryGrow(i + nx, water, h, level, queue);
        }
        return count;
    }

    private int tryGrow(int i, boolean[] water, float[] h, float level, ArrayDeque<Integer> queue) {
        if (water[i] || Float.isNaN(h[i]) || Math.abs(h[i] - level) > growTolerance) {
            return 0;
        }
        water[i] = true;
        queue.add(i);
        return 1;
    }

    // ------------------------------------------------------------------

    /**
     * Spannweite der Zelle und ihrer vorhandenen Nachbarn unter der Toleranz?
     *
     * <p>Am Kachelrand werden die fehlenden Nachbarn uebergangen, statt die
     * Zelle abzulehnen. Sonst waere die aeusserste Zellreihe nie eben - und
     * dann beruehrt die Wasserflaeche den Rand nicht mehr, womit die offene
     * See als geschuetztes Wasser durchginge.</p>
     */
    private boolean isFlat(float[] h, int nx, int ny, int ix, int iy) {
        float centre = h[iy * nx + ix];
        if (Float.isNaN(centre)) {
            return false;
        }
        float min = centre, max = centre;
        for (int dy = -1; dy <= 1; dy++) {
            int y = iy + dy;
            if (y < 0 || y >= ny) {
                continue;
            }
            for (int dx = -1; dx <= 1; dx++) {
                int x = ix + dx;
                if (x < 0 || x >= nx) {
                    continue;
                }
                float value = h[y * nx + x];
                if (Float.isNaN(value)) {
                    return false;
                }
                if (value < min) min = value;
                if (value > max) max = value;
            }
        }
        return max - min <= flatTolerance;
    }

    /** Zusammenhangskomponenten der gesetzten Zellen, Vierernachbarschaft. */
    private static List<int[]> components(boolean[] mask, int nx, int ny) {
        List<int[]> result = new ArrayList<>();
        boolean[] seen = new boolean[mask.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        int[] buffer = new int[mask.length];

        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || seen[start]) {
                continue;
            }
            int count = 0;
            seen[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int i = queue.poll();
                buffer[count++] = i;
                int y = i / nx;
                int x = i - y * nx;
                if (x > 0)      visit(mask, seen, queue, i - 1);
                if (x < nx - 1) visit(mask, seen, queue, i + 1);
                if (y > 0)      visit(mask, seen, queue, i - nx);
                if (y < ny - 1) visit(mask, seen, queue, i + nx);
            }
            int[] cells = new int[count];
            System.arraycopy(buffer, 0, cells, 0, count);
            result.add(cells);
        }
        return result;
    }

    private static void visit(boolean[] mask, boolean[] seen, ArrayDeque<Integer> queue, int i) {
        if (mask[i] && !seen[i]) {
            seen[i] = true;
            queue.add(i);
        }
    }

    private static void set(FCoverGrid cover, int nx, int index, FLandCover kind) {
        int iy = index / nx;
        int ix = index - iy * nx;
        if (cover.coverAt(ix, iy) == FLandCover.UNKNOWN) {
            cover.set(ix, iy, kind);
        }
    }

    /**
     * Chamfer-Distanz zur naechsten gesetzten Zelle, zwei Durchlaeufe,
     * Kosten 5 gerade und 7 diagonal.
     */
    private static int[] distanceTransform(boolean[] mask, int nx, int ny) {
        final int n = nx * ny;
        final int far = Integer.MAX_VALUE / 4;
        int[] d = new int[n];
        for (int i = 0; i < n; i++) {
            d[i] = mask[i] ? 0 : far;
        }
        for (int y = 0; y < ny; y++) {
            for (int x = 0; x < nx; x++) {
                int i = y * nx + x;
                if (d[i] == 0) {
                    continue;
                }
                int best = d[i];
                if (x > 0)               best = Math.min(best, d[i - 1] + STEP_ORTHO);
                if (y > 0)               best = Math.min(best, d[i - nx] + STEP_ORTHO);
                if (x > 0 && y > 0)      best = Math.min(best, d[i - nx - 1] + STEP_DIAG);
                if (x < nx - 1 && y > 0) best = Math.min(best, d[i - nx + 1] + STEP_DIAG);
                d[i] = best;
            }
        }
        for (int y = ny - 1; y >= 0; y--) {
            for (int x = nx - 1; x >= 0; x--) {
                int i = y * nx + x;
                if (d[i] == 0) {
                    continue;
                }
                int best = d[i];
                if (x < nx - 1)               best = Math.min(best, d[i + 1] + STEP_ORTHO);
                if (y < ny - 1)               best = Math.min(best, d[i + nx] + STEP_ORTHO);
                if (x < nx - 1 && y < ny - 1) best = Math.min(best, d[i + nx + 1] + STEP_DIAG);
                if (x > 0 && y < ny - 1)      best = Math.min(best, d[i + nx - 1] + STEP_DIAG);
                d[i] = best;
            }
        }
        return d;
    }
}
