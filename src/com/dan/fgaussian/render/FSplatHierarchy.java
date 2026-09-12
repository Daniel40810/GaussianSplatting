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

    /**
     * Alle Ebenen in einer einzigen Wolke, Ebene 0 zuerst. So ist die Auswahl
     * eine reine Indexliste in diese Wolke - ohne sie muesste je Bild die
     * getroffene Auswahl in einen Puffer kopiert werden, und bei sechsstelligen
     * Splatzahlen kostet dieses Kopieren mehr, als das Weglassen von Splats
     * einbringt.
     */
    private final FSplatCloud all;

    /** Startindex jeder Ebene in {@link #all}. */
    private final int[] levelStart;

    /** Splatzahl je Ebene. */
    private final int[] levelCount;

    /** parents[k][i] = Index des Ersatzsplats auf Ebene k+1. */
    private final int[][] parents;

    /**
     * Fehlerradius je Splat und Ebene: wie weit die vertretene Gruppe um den
     * Ersatzmittelpunkt streut. Auf Ebene 0 ist er null.
     */
    private final float[][] errors;

    private final boolean[][] selected;
    private final boolean[][] covered;

    private final int[] selection;
    private int lastSelected;

    private FSplatHierarchy(FSplatCloud all, int[] levelStart, int[] levelCount,
                            int[][] parents, float[][] errors) {
        this.all = all;
        this.levelStart = levelStart;
        this.levelCount = levelCount;
        this.parents = parents;
        this.errors = errors;
        this.selected = new boolean[levelCount.length][];
        this.covered = new boolean[levelCount.length][];
        for (int k = 0; k < levelCount.length; k++) {
            selected[k] = new boolean[levelCount[k]];
            covered[k] = new boolean[levelCount[k]];
        }
        this.selection = new int[all.count];
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
        List<float[]> errorList = new ArrayList<>();
        clouds.add(base);
        errorList.add(new float[base.count]);

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

            // Fehlerradius des Ersatzsplats: der weiteste Kindmittelpunkt,
            // zuzueglich dessen eigenem Fehler. Damit umschliesst er den
            // gesamten Teilbaum, den er vertritt.
            float[] fineError = errorList.get(errorList.size() - 1);
            float[] coarseError = new float[coarse.count];
            for (int i = 0; i < fine.count; i++) {
                int p = parent[i];
                float dx = fine.px[i] - coarse.px[p];
                float dy = fine.py[i] - coarse.py[p];
                float dz = fine.pz[i] - coarse.pz[p];
                float d = (float) Math.sqrt(dx * dx + dy * dy + dz * dz) + fineError[i];
                if (d > coarseError[p]) {
                    coarseError[p] = d;
                }
            }

            clouds.add(coarse);
            parentList.add(parent);
            errorList.add(coarseError);
            size *= 2.0;
        }

        FSplatCloud[] levels = clouds.toArray(new FSplatCloud[0]);
        int[] start = new int[levels.length];
        int[] counts = new int[levels.length];
        int offset = 0;
        for (int k = 0; k < levels.length; k++) {
            start[k] = offset;
            counts[k] = levels[k].count;
            offset += levels[k].count;
        }
        FSplatCloud all = FSplatCloud.concat(levels);

        return new FSplatHierarchy(all, start, counts,
                parentList.toArray(new int[0][]),
                errorList.toArray(new float[0][]));
    }

    /** Fasst eine Wolke gitterweise zusammen und fuellt die Elternzuordnung. */
    private static FSplatCloud collapse(FSplatCloud fine, double cellSize, int[] parent) {
        if (!(cellSize > 0.0) || !Double.isFinite(cellSize)) {
            throw new IllegalArgumentException("cellSize muss endlich und > 0 sein");
        }
        Map<Cell, List<Integer>> buckets = new HashMap<>();
        final double inv = 1.0 / cellSize;

        for (int i = 0; i < fine.count; i++) {
            long ix = (long) Math.floor(fine.px[i] * inv);
            long iy = (long) Math.floor(fine.py[i] * inv);
            long iz = (long) Math.floor(fine.pz[i] * inv);
            Cell key = new Cell(ix, iy, iz);
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

    /** Koordinate statt Hashwert als Schlüssel: Hash-Kollisionen bleiben getrennt. */
    private record Cell(long x, long y, long z) {
    }

    // ------------------------------------------------------------------

    public int levelCount() {
        return levelCount.length;
    }

    public int size(int level) {
        return levelCount[level];
    }

    /** Splats aller Stufen zusammen, also der Speicherbedarf der Hierarchie. */
    public int totalSplats() {
        return all.count;
    }

    /** Die Wolke, in die die Auswahl zeigt: alle Ebenen hintereinander. */
    public FSplatCloud cloud() {
        return all;
    }

    /** Die zuletzt getroffene Auswahl als Indexliste in {@link #cloud()}. */
    public int[] selection() {
        return selection;
    }

    /** Anzahl der zuletzt ausgewaehlten Splats. */
    public int lastSelected() {
        return lastSelected;
    }

    /**
     * Waehlt fuer die aktuelle Kamera einen Schnitt durch die Stufen.
     * Das Ergebnis steht in {@link #selection()} und gilt bis zum naechsten
     * Aufruf; gezeichnet wird es mit
     * {@code renderer.render(hierarchy.cloud(), hierarchy.selection(), n, ...)}.
     *
     * @return Anzahl der ausgewaehlten Splats
     *
     * @param pixelThreshold zulaessiger Lagefehler in Pixeln; darunter wird
     *                       der Ersatzsplat genommen, darueber feiner
     *                       aufgeloest. 1 bis 3 sind brauchbare Werte
     */
    public int select(FCamera camera, int viewportHeight, float pixelThreshold) {
        camera.update();
        final float[] w = camera.view;
        final float ex = camera.eye[0], ey = camera.eye[1], ez = camera.eye[2];
        final float f = camera.focalLength(viewportHeight);
        final float near = camera.nearZ;
        final int top = levelCount.length - 1;

        for (int k = top; k >= 0; k--) {
            final int base = levelStart[k];
            final int n = levelCount[k];
            boolean[] sel = selected[k];
            boolean[] cov = covered[k];
            float[] err = errors[k];

            for (int i = 0; i < n; i++) {
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

                int g = base + i;
                float dx = all.px[g] - ex, dy = all.py[g] - ey, dz = all.pz[g] - ez;
                float depth = w[6] * dx + w[7] * dy + w[8] * dz;
                if (depth < near) {
                    // Im Ruecken oder sehr nah: immer feiner aufloesen.
                    sel[i] = false;
                    continue;
                }
                // Massgeblich ist der Fehler, nicht die Groesse des Splats:
                // ein Ersatzsplat ist stets groesser als seine Kinder, eine
                // Pruefung auf seinen eigenen Bildradius waere also strenger
                // als bei den Kindern und wuerde nie zuerst zutreffen.
                sel[i] = (f * err[i] / depth) <= pixelThreshold;
            }
        }

        int n = 0;
        for (int k = 0; k < levelCount.length; k++) {
            final int base = levelStart[k];
            boolean[] sel = selected[k];
            for (int i = 0; i < levelCount[k]; i++) {
                if (sel[i]) {
                    selection[n++] = base + i;
                }
            }
        }
        lastSelected = n;
        return n;
    }

}
