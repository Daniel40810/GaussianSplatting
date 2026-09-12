package com.dan.fgaussian.source;

import com.dan.fgaussian.core.FPolygonFeature;
import com.dan.fgaussian.shade.FLandCover;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Flaechenartenmaske im Gitter des Hoehenmodells: je Rasterzelle eine
 * {@link FLandCover}.
 *
 * <p>Damit bekommt das Gelaende seine Farbe nicht mehr allein aus der Hoehe.
 * Bei einer Kachel, die zu 72 Prozent auf exakt 0,00 m liegt, ist das der
 * entscheidende Unterschied: Hafenbecken, offene See und nasser Strand haben
 * dieselbe Hoehe, aber nicht dieselbe Farbe.</p>
 *
 * <p>Gefuellt wird mit einer Scanline ueber die Zellmittelpunkte, gerade/ungerade
 * Regel ueber alle Ringe gemeinsam - dadurch fallen Loecher ohne Sonderbehandlung
 * heraus.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FCoverGrid {

    private static final FLandCover[] VALUES = FLandCover.values();

    public final int nx, ny;
    public final double cell;
    public final double originX, originY;

    private final byte[] cover;

    public FCoverGrid(int nx, int ny, double cell, double originX, double originY) {
        this.nx = nx;
        this.ny = ny;
        this.cell = cell;
        this.originX = originX;
        this.originY = originY;
        this.cover = new byte[nx * ny];
    }

    /** Maske im Gitter eines Hoehenmodells. */
    public static FCoverGrid forSource(FXyzGridSource source) {
        return new FCoverGrid(source.gridWidth(), source.gridHeight(),
                source.cellSize(), source.originEast(), source.originNorth());
    }

    public FLandCover coverAt(int ix, int iy) {
        if (ix < 0 || ix >= nx || iy < 0 || iy >= ny) {
            return FLandCover.UNKNOWN;
        }
        return VALUES[cover[iy * nx + ix] & 0xFF];
    }

    /** Setzt die Art einer einzelnen Zelle. */
    public void set(int ix, int iy, FLandCover kind) {
        if (ix < 0 || ix >= nx || iy < 0 || iy >= ny) {
            return;
        }
        cover[iy * nx + ix] = (byte) kind.ordinal();
    }

    /** Anzahl Zellen, denen eine Art zugeordnet ist. */
    public int classifiedCells() {
        int n = 0;
        for (byte b : cover) {
            if (b != 0) {
                n++;
            }
        }
        return n;
    }

    /**
     * Traegt mehrere Flaechen ein, grossflaechige Arten zuerst.
     * So bleiben Wege und Gebaeude sichtbar, die innerhalb groesserer
     * Flaechen liegen.
     */
    public void rasterizeAll(List<FPolygonFeature> features) {
        List<FPolygonFeature> ordered = new ArrayList<>(features);
        ordered.sort(Comparator.comparingInt(f -> f.cover.ordinal()));
        for (FPolygonFeature f : ordered) {
            rasterize(f);
        }
    }

    /** Traegt eine einzelne Flaeche ein. */
    public void rasterize(FPolygonFeature feature) {
        if (feature == null || feature.isEmpty() || feature.cover == FLandCover.UNKNOWN) {
            return;
        }
        final byte value = (byte) feature.cover.ordinal();

        double[] box = new double[4];
        feature.bounds(box);
        int iy0 = (int) Math.floor((box[1] - originY) / cell);
        int iy1 = (int) Math.ceil((box[3] - originY) / cell);
        if (iy1 < 0 || iy0 >= ny) {
            return;
        }
        iy0 = Math.max(0, iy0);
        iy1 = Math.min(ny - 1, iy1);

        double[] crossings = new double[64];

        for (int iy = iy0; iy <= iy1; iy++) {
            final double y = originY + iy * cell;
            int n = 0;

            for (double[] ring : feature.rings()) {
                final int points = ring.length / 2;
                for (int i = 0; i < points; i++) {
                    int j = (i + 1) % points;
                    double y0 = ring[i * 2 + 1];
                    double y1 = ring[j * 2 + 1];
                    if ((y0 <= y && y1 > y) || (y1 <= y && y0 > y)) {
                        double x0 = ring[i * 2];
                        double x1 = ring[j * 2];
                        double t = (y - y0) / (y1 - y0);
                        if (n == crossings.length) {
                            double[] bigger = new double[n * 2];
                            System.arraycopy(crossings, 0, bigger, 0, n);
                            crossings = bigger;
                        }
                        crossings[n++] = x0 + t * (x1 - x0);
                    }
                }
            }
            if (n < 2) {
                continue;
            }
            java.util.Arrays.sort(crossings, 0, n);

            for (int k = 0; k + 1 < n; k += 2) {
                int ixStart = (int) Math.ceil((crossings[k] - originX) / cell);
                int ixEnd = (int) Math.floor((crossings[k + 1] - originX) / cell);
                if (ixEnd < 0 || ixStart >= nx) {
                    continue;
                }
                ixStart = Math.max(0, ixStart);
                ixEnd = Math.min(nx - 1, ixEnd);
                int base = iy * nx;
                for (int ix = ixStart; ix <= ixEnd; ix++) {
                    cover[base + ix] = value;
                }
            }
        }
    }
}
