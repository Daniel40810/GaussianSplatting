package com.dan.fgaussian.render;

import com.dan.fgaussian.core.FCovariance;
import com.dan.fgaussian.core.FSplatCloud;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Detailstufen fuer eine Splat-Wolke: mehrere Ebenen, in denen jeweils acht
 * Splats der feineren Ebene zu einem zusammengefasst sind.
 *
 * <p>Das Problem, das damit verschwindet: Der Splat-Abstand an Waenden und
 * Daechern ist in Metern festgelegt. Aus der Ferne entstehen dadurch mehr
 * Splats als Pixel - der Rasterisierer zeichnet dann hundert Splats in eine
 * Flaeche von zehn Pixeln, und jeder einzelne kostet Projektion, Sortierung
 * und Kacheleintraege.</p>
 *
 * <p>Zusammengefasst wird nicht durch Wegwerfen, sondern durch
 * {@link FCovariance#merge} - der Ersatzsplat nimmt die Streuung der
 * Mittelpunkte in seine eigene Kovarianz auf und deckt damit dieselbe Flaeche
 * ab wie die Gruppe, die er vertritt.</p>
 *
 * <p>Die Auswahl je Bild laeuft von grob nach fein: Ein Splat wird gezeichnet,
 * wenn er klein genug am Bildschirm ist <em>und</em> noch keiner seiner
 * Vorfahren gezeichnet wurde. So entsteht ein Schnitt durch den Baum, der nah
 * an der Kamera fein und in der Ferne grob ist, ohne Loecher und ohne
 * Doppelungen.</p>
 *
 * <p><b>Zu Spherical Harmonics:</b> Ersatzsplats tragen keinen SH-Block. Wolken
 * aus einer Rekonstruktion sollten deshalb vor dem Aufbau ihre Grundfarbe
 * eingebacken bekommen (siehe {@code FSHEvaluator.bakeBaseColor}); die
 * Richtungsabhaengigkeit geht in den groben Stufen ohnehin verloren.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSplatHierarchy {

    /** Ebene 0 ist die Ausgangswolke, hoehere Ebenen sind gröber. */
    private final FSplatCloud[] levels;

    /** parents[k][i] = Index des Ersatzsplats auf Ebene k+1. */
    private final int[][] parents;

    /** Weltradius je Splat und Ebene, drei Standardabweichungen. */
    private final float[][] radii;

    private final boolean[][] selected;
    private final boolean[][] covered;

    private FSplatCloud selection;
    private int lastSelected;

    private FSplatHierarchy(FSplatCloud[] levels, int[][] parents, float[][] radii) {
        this.levels = levels;
        this.parents = parents;
        this.radii = radii;
        this.selected = new boolean[levels.length][];
        this.covered = new boolean[levels.length][];
        for (int k = 0; k < levels.length; k++) {
            selected[k] = new boolean[levels[k].count];
            covered[k] = new boolean[levels[k].count];
        }
        this.selection = new FSplatCloud(levels[0].count);
    }

    /**
     * Baut die Stufen auf.
     *
     * @param base      Ausgangswolke, wird als Ebene 0 uebernommen
     * @param cellSize  Kantenlaenge der Zusammenfassung auf Ebene 1, Meter;
     *                  sinnvoll etwa der doppelte Splat-Abstand
     * @param extraLevels Anzahl zusaetzlicher Stufen; jede verdoppelt die
     *                  Zellkante, fasst also das Achtfache an Volumen zusammen
     */
    public static FSplatHierarchy build(FSplatCloud base, double cellSize, int extraLevels) {
        List<FSplatCloud> clouds = new ArrayList<>();
        List<int[]> parentList = new ArrayList<>();
        clouds.add(base);

        double size = cellSize;
        for (int k = 0; k < extraLevels; k++) {
            FSplatCloud fine = clouds.get(clouds.size() - 1);
            if (fine.count < 64) {
                break;
            }
            int[] parent = new int[fine.count];
            FSplatCloud coarse = collapse(fine, size, parent);
            if (coarse.count >= fine.count) {
                // Nichts mehr zusammengefasst - weitere Stufen bringen nichts.
                break;
            }
            clouds.add(coarse);
            parentList.add(parent);
            size *= 2.0;
        }

        FSplatCloud[] levels = clouds.toArray(new FSplatCloud[0]);
        int[][] parents = parentList.toArray(new int[0][]);

        float[][] radii = new float[levels.length][];
        for (int k = 0; k < levels.length; k++) {
            FSplatCloud c = levels[k];
            float[] r = new float[c.count];
            for (int i = 0; i < c.count; i++) {
                r[i] = 3f * Math.max(c.sx[i], Math.max(c.sy[i], c.sz[i]));
            }
            radii[k] = r;
        }
        return new FSplatHierarchy(levels, parents, radii);
    }

    /** Fasst eine Wolke gitterweise zusammen und fuellt die Elternzuordnung. */
    private static FSplatCloud collapse(FSplatCloud fine, double cellSize, int[] parent) {
        Map<Long, List<Integer>> buckets = new HashMap<>();
        final double inv = 1.0 / cellSize;

        for (int i = 0; i < fine.count; i++) {
            long ix = (long) Math.floor(fine.px[i] * inv);
            long iy = (long) Math.floor(fine.py[i] * inv);
            long iz = (long) Math.floor(fine.pz[i] * inv);
            long key = (ix * 73856093L) ^ (iy * 19349663L) ^ (iz * 83492791L);
            buckets.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }

        FSplatCloud coarse = new FSplatCloud(buckets.size());
        float[] merged = new float[14];
        int[] indices = new int[64];

        for (List<Integer> bucket : buckets.values()) {
            int count = bucket.size();
            if (indices.length < count) {
                indices = new int[count];
            }
            for (int k = 0; k < count; k++) {
                indices[k] = bucket.get(k);
            }
            FCovariance.merge(fine, indices, count, merged);
            int target = coarse.add(merged[0], merged[1], merged[2],
                    merged[3], merged[4], merged[5],
                    new float[] { merged[6], merged[7], merged[8], merged[9] },
                    merged[10], merged[11], merged[12], merged[13]);
            for (int k = 0; k < count; k++) {
                parent[indices[k]] = target;
            }
        }
        coarse.computeBounds();
        return coarse;
    }

    // ------------------------------------------------------------------

    public int levelCount() {
        return levels.length;
    }

    public int size(int level) {
        return levels[level].count;
    }

    /** Splats aller Stufen zusammen, also der Speicherbedarf der Hierarchie. */
    public int totalSplats() {
        int sum = 0;
        for (FSplatCloud c : levels) {
            sum += c.count;
        }
        return sum;
    }

    /** Anzahl der zuletzt ausgewaehlten Splats. */
    public int lastSelected() {
        return lastSelected;
    }

    /**
     * Waehlt fuer die aktuelle Kamera einen Schnitt durch die Stufen und
     * liefert ihn als Wolke. Der Puffer wird wiederverwendet - die
     * zurueckgegebene Wolke gilt nur bis zum naechsten Aufruf.
     *
     * @param pixelThreshold ab welchem Bildradius in Pixeln feiner aufgeloest
     *                       wird; 2 bis 4 sind brauchbare Werte
     */
    public FSplatCloud select(FCamera camera, int viewportHeight, float pixelThreshold) {
        camera.update();
        final float[] w = camera.view;
        final float ex = camera.eye[0], ey = camera.eye[1], ez = camera.eye[2];
        final float f = camera.focalLength(viewportHeight);
        final float near = camera.nearZ;
        final int top = levels.length - 1;

        for (int k = top; k >= 0; k--) {
            FSplatCloud c = levels[k];
            boolean[] sel = selected[k];
            boolean[] cov = covered[k];
            float[] r = radii[k];

            for (int i = 0; i < c.count; i++) {
                if (k < top) {
                    int p = parents[k][i];
                    cov[i] = selected[k + 1][p] || covered[k + 1][p];
                    if (cov[i]) {
                        sel[i] = false;
                        continue;
                    }
                } else {
                    cov[i] = false;
                }

                if (k == 0) {
                    // Feinste Stufe: was bis hier nicht vertreten ist, wird gezeichnet.
                    sel[i] = true;
                    continue;
                }

                float dx = c.px[i] - ex, dy = c.py[i] - ey, dz = c.pz[i] - ez;
                float depth = w[6] * dx + w[7] * dy + w[8] * dz;
                if (depth < near) {
                    // Im Ruecken oder sehr nah: immer feiner aufloesen.
                    sel[i] = false;
                    continue;
                }
                sel[i] = (f * r[i] / depth) <= pixelThreshold;
            }
        }

        selection.count = 0;
        for (int k = 0; k < levels.length; k++) {
            FSplatCloud c = levels[k];
            boolean[] sel = selected[k];
            for (int i = 0; i < c.count; i++) {
                if (sel[i]) {
                    copy(c, i, selection);
                }
            }
        }
        selection.computeBounds();
        lastSelected = selection.count;
        return selection;
    }

    private static void copy(FSplatCloud from, int i, FSplatCloud to) {
        int j = to.count++;
        to.px[j] = from.px[i];  to.py[j] = from.py[i];  to.pz[j] = from.pz[i];
        to.sx[j] = from.sx[i];  to.sy[j] = from.sy[i];  to.sz[j] = from.sz[i];
        to.qx[j] = from.qx[i];  to.qy[j] = from.qy[i];  to.qz[j] = from.qz[i];  to.qw[j] = from.qw[i];
        to.cr[j] = from.cr[i];  to.cg[j] = from.cg[i];  to.cb[j] = from.cb[i];
        to.opacity[j] = from.opacity[i];
    }
}
