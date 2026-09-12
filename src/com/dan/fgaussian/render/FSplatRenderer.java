package com.dan.fgaussian.render;

import com.dan.fgaussian.core.FQuat;
import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.shade.FSHEvaluator;

import java.util.Arrays;
import java.util.stream.IntStream;

/**
 * CPU-Rasterisierer fuer 3D-Gauss-Splats, nach dem EWA-Verfahren.
 *
 * <p>Ablauf je Bild:</p>
 * <ol>
 *   <li>Jeder Splat wird in den Kameraraum gedreht, seine 3x3-Kovarianz ueber
 *       die Jacobimatrix der perspektivischen Abbildung auf eine 2x2-Kovarianz
 *       im Bildraum projiziert und daraus die Kegelschnittform (conic) und ein
 *       Pixelradius gewonnen.</li>
 *   <li>Die sichtbaren Splats werden per Radix nach Tiefe sortiert, nah zuerst.</li>
 *   <li>Sie werden in 16x16-Kacheln einsortiert.</li>
 *   <li>Die Kacheln werden parallel gerastert: je Kachel ein kleiner
 *       Akkumulationspuffer, ueber den die Splats in Tiefenreihenfolge
 *       <em>streuen</em>. Jeder Splat beruehrt nur seine eigene Flaeche, statt
 *       dass jedes Pixel die ganze Kachelliste durchlaeuft.</li>
 * </ol>
 *
 * <p>Gemischt wird von vorn nach hinten mit mitgefuehrter Resttransparenz;
 * ein Pixel wird uebersprungen, sobald es praktisch deckend ist.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSplatRenderer {

    /** Kantenlaenge einer Rasterkachel in Pixeln. */
    public static final int TILE = 16;

    /** Abbruchschwelle der Resttransparenz. */
    private static final float MIN_TRANSMITTANCE = 0.0035f;

    /** Jenseits davon traegt die Gaussglocke weniger als ein halbes Farbstufe bei. */
    private static final float MIN_POWER = -7.6f;

    /** Obergrenze fuer den Pixelradius, damit ein naher Splat nicht alle Kacheln fuellt. */
    private static final int MAX_RADIUS = 320;

    /** Hintergrundfarbe, 0..1. */
    public float bgR = 0.04f, bgG = 0.06f, bgB = 0.08f;

    /** Kacheln parallel rastern. Fuer Messungen abschaltbar. */
    public boolean parallel = true;

    /**
     * Richtungsabhaengige Farbe auswerten, wenn die Wolke einen SH-Block traegt.
     * Abschaltbar, weil die Auswertung je Splat und Bild anfaellt und bei
     * Grad 3 sechzehn Koeffizienten je Kanal kostet.
     */
    public boolean evaluateSh = true;

    // --- projizierte Splats, dicht gepackt (nur sichtbare) ---
    private float[] u, v, depth, conicA, conicB, conicC, alpha, colR, colG, colB;
    private int[] radius;
    private int[] order;
    private int visible;

    // --- Kachelverwaltung ---
    private int[] tileStart;
    private int[] tileCursor;
    private int[] tileEntries;
    private int tilesX, tilesY, tileCount;

    private final FSplatSorter sorter = new FSplatSorter();

    /** Erhoeht sich, sobald ein laufendes Bild veraltet ist. */
    private volatile long cancellationEpoch;

    /**
     * Ein Puffer je Worker-Thread. rasterTile() wird parallel aufgerufen;
     * deshalb kann der Puffer nicht einfach ein einziges Feld des Renderers
     * sein. ThreadLocal verhindert zugleich vier neue Arrays je Kachel und
     * Bild.
     */
    private final ThreadLocal<TileBuffer> tileBuffers =
            ThreadLocal.withInitial(TileBuffer::new);

    /** Anzahl der im letzten Bild gezeichneten Splats. */
    public int lastVisible;

    /** Anzahl der im letzten Bild erzeugten Kacheleintraege. */
    public int lastTileEntries;

    /**
     * Zeichnet die Wolke in einen Pixelpuffer im Format
     * {@code BufferedImage.TYPE_INT_RGB}.
     *
     * @param dst Zielpuffer der Laenge w*h
     */
    public void render(FSplatCloud cloud, FCamera camera, int[] dst, int w, int h) {
        render(cloud, null, cloud.count, camera, dst, w, h);
    }

    /**
     * Zeichnet nur die Splats, die in {@code indices} stehen - so gibt eine
     * {@link FSplatHierarchy} ihre Auswahl weiter, ohne sie in eine eigene
     * Wolke kopieren zu muessen.
     *
     * @param indices Indexliste, oder {@code null} fuer die ganze Wolke
     * @param count   gueltige Eintraege in {@code indices}
     */
    public void render(FSplatCloud cloud, int[] indices, int count,
                       FCamera camera, int[] dst, int w, int h) {
        renderInternal(cloud, indices, count, camera, dst, w, h);
    }

    /** Bricht ein laufendes Bild ab, sobald die aktuelle Kamerabewegung weitergeht. */
    public void cancel() {
        cancellationEpoch++;
    }

    /** Wie render(), liefert aber false, wenn das Bild waehrenddessen veraltet wurde. */
    public boolean renderCancellable(FSplatCloud cloud, int[] indices, int count,
                                     FCamera camera, int[] dst, int w, int h) {
        return renderInternal(cloud, indices, count, camera, dst, w, h);
    }

    private boolean renderInternal(FSplatCloud cloud, int[] indices, int count,
                                   FCamera camera, int[] dst, int w, int h) {
        final long epoch = cancellationEpoch;
        if (w <= 0 || h <= 0) {
            return true;
        }
        ensureSplatCapacity(count);
        setupTiles(w, h);

        camera.update();
        if (isCancelled(epoch) || !project(cloud, indices, count, camera, w, h, epoch)) {
            return false;
        }
        lastVisible = visible;

        if (visible == 0) {
            if (isCancelled(epoch)) {
                return false;
            }
            Arrays.fill(dst, packColor(bgR, bgG, bgB));
            lastTileEntries = 0;
            return true;
        }

        for (int i = 0; i < visible; i++) {
            order[i] = i;
        }
        sorter.sortByDepth(depth, order, visible);

        if (isCancelled(epoch) || !binIntoTiles(w, h, epoch)) {
            return false;
        }
        lastTileEntries = tileStart[tileCount];

        return rasterize(dst, w, h, epoch);
    }

    // ------------------------------------------------------------------
    // Schritt 1: Projektion
    // ------------------------------------------------------------------

    /**
     * Laeuft bewusst einstraengig: das Kompaktieren der sichtbaren Splats
     * braucht eine fortlaufende Schreibposition. Bei sechsstelligen Wolken
     * waere hier eine Sichtbarkeitsmaske mit Praefixsumme der naechste Schritt.
     */
    private boolean project(FSplatCloud cloud, int[] indices, int total, FCamera cam,
                            int w, int h, long epoch) {
        final boolean useSh = evaluateSh && cloud.shDegree > 0 && cloud.sh != null;
        final float[] shColor = new float[3];
        final float[] W = cam.view;
        final float ex = cam.eye[0], ey = cam.eye[1], ez = cam.eye[2];
        final float f = cam.focalLength(h);
        final float cx = w * 0.5f, cy = h * 0.5f;
        final float near = cam.nearZ;
        final float[] R = new float[9];

        int n = 0;
        for (int k = 0; k < total; k++) {
            if ((k & 255) == 0 && isCancelled(epoch)) {
                visible = 0;
                return false;
            }
            final int i = indices == null ? k : indices[k];
            float dx = cloud.px[i] - ex;
            float dy = cloud.py[i] - ey;
            float dz = cloud.pz[i] - ez;

            float vz = W[6] * dx + W[7] * dy + W[8] * dz;
            if (vz < near) {
                continue;
            }
            float vx = W[0] * dx + W[1] * dy + W[2] * dz;
            float vy = W[3] * dx + W[4] * dy + W[5] * dz;

            float invz = 1f / vz;
            float su = cx + f * vx * invz;
            float sv = cy + f * vy * invz;

            // 3D-Kovarianz: Sigma = (R*S)(R*S)^T
            FQuat.toMatrix3(cloud.qx[i], cloud.qy[i], cloud.qz[i], cloud.qw[i], R);
            float a0 = cloud.sx[i], a1 = cloud.sy[i], a2 = cloud.sz[i];
            float m00 = R[0] * a0, m01 = R[1] * a1, m02 = R[2] * a2;
            float m10 = R[3] * a0, m11 = R[4] * a1, m12 = R[5] * a2;
            float m20 = R[6] * a0, m21 = R[7] * a1, m22 = R[8] * a2;

            float s00 = m00 * m00 + m01 * m01 + m02 * m02;
            float s01 = m00 * m10 + m01 * m11 + m02 * m12;
            float s02 = m00 * m20 + m01 * m21 + m02 * m22;
            float s11 = m10 * m10 + m11 * m11 + m12 * m12;
            float s12 = m10 * m20 + m11 * m21 + m12 * m22;
            float s22 = m20 * m20 + m21 * m21 + m22 * m22;

            // T = J * W, zwei Zeilen zu je drei Spalten.
            float fz = f * invz;
            float jz0 = -f * vx * invz * invz;
            float jz1 = -f * vy * invz * invz;

            float t00 = fz * W[0] + jz0 * W[6];
            float t01 = fz * W[1] + jz0 * W[7];
            float t02 = fz * W[2] + jz0 * W[8];
            float t10 = fz * W[3] + jz1 * W[6];
            float t11 = fz * W[4] + jz1 * W[7];
            float t12 = fz * W[5] + jz1 * W[8];

            // Sigma2D = T * Sigma * T^T
            float p0 = s00 * t00 + s01 * t01 + s02 * t02;
            float p1 = s01 * t00 + s11 * t01 + s12 * t02;
            float p2 = s02 * t00 + s12 * t01 + s22 * t02;
            float q0 = s00 * t10 + s01 * t11 + s02 * t12;
            float q1 = s01 * t10 + s11 * t11 + s12 * t12;
            float q2 = s02 * t10 + s12 * t11 + s22 * t12;

            float cA = t00 * p0 + t01 * p1 + t02 * p2;
            float cB = t10 * p0 + t11 * p1 + t12 * p2;
            float cC = t10 * q0 + t11 * q1 + t12 * q2;

            // Tiefpass: mindestens ein Pixel Ausdehnung, sonst flimmert es.
            // Der Zuschlag vergroessert den Splat aber auch - bliebe die
            // Deckkraft gleich, deckte ein ferner Splat mehr Flaeche ab als ihm
            // zusteht, und entfernte Bauten wirkten wie ein zugelaufener Block.
            // Der Ausgleich ist das Verhaeltnis der Determinanten vor und nach
            // dem Zuschlag.
            float detRaw = cA * cC - cB * cB;
            cA += 0.3f;
            cC += 0.3f;

            float det = cA * cC - cB * cB;
            if (det <= 1e-9f) {
                continue;
            }
            float shrink = detRaw <= 0f ? 1f : (float) Math.sqrt(Math.min(1.0, detRaw / det));

            // Groesster Eigenwert der 2x2-Kovarianz, geschlossene Loesung.
            float mid = 0.5f * (cA + cC);
            float diff = 0.5f * (cA - cC);
            float root = (float) Math.sqrt(diff * diff + cB * cB);
            float lambda = mid + root;
            int r = (int) Math.ceil(3.0f * Math.sqrt(lambda));
            if (r < 1) {
                continue;
            }
            if (r > MAX_RADIUS) {
                r = MAX_RADIUS;
            }
            if (su + r < 0 || su - r >= w || sv + r < 0 || sv - r >= h) {
                continue;
            }

            if (useSh) {
                // Blickrichtung ist genau der Vektor vom Auge zum Splat, den
                // wir oben ohnehin schon gebildet haben.
                FSHEvaluator.evaluate(cloud, i, dx, dy, dz, shColor);
                colR[n] = shColor[0];
                colG[n] = shColor[1];
                colB[n] = shColor[2];
            } else {
                colR[n] = cloud.cr[i];
                colG[n] = cloud.cg[i];
                colB[n] = cloud.cb[i];
            }

            float invDet = 1f / det;
            u[n] = su;
            v[n] = sv;
            depth[n] = vz;
            conicA[n] = cC * invDet;
            conicB[n] = -cB * invDet;
            conicC[n] = cA * invDet;
            alpha[n] = cloud.opacity[i] * shrink;
            radius[n] = r;
            n++;
        }
        visible = n;
        return true;
    }

    // ------------------------------------------------------------------
    // Schritt 3: Kachelzuordnung
    // ------------------------------------------------------------------

    private boolean binIntoTiles(int w, int h, long epoch) {
        Arrays.fill(tileCursor, 0, tileCount, 0);

        // Erster Durchgang: zaehlen.
        for (int k = 0; k < visible; k++) {
            if ((k & 255) == 0 && isCancelled(epoch)) {
                return false;
            }
            int s = order[k];
            int r = radius[s];
            int x0 = clamp((int) ((u[s] - r) / TILE), 0, tilesX - 1);
            int x1 = clamp((int) ((u[s] + r) / TILE), 0, tilesX - 1);
            int y0 = clamp((int) ((v[s] - r) / TILE), 0, tilesY - 1);
            int y1 = clamp((int) ((v[s] + r) / TILE), 0, tilesY - 1);
            for (int ty = y0; ty <= y1; ty++) {
                int row = ty * tilesX;
                for (int tx = x0; tx <= x1; tx++) {
                    tileCursor[row + tx]++;
                }
            }
        }

        int sum = 0;
        for (int t = 0; t < tileCount; t++) {
            tileStart[t] = sum;
            sum += tileCursor[t];
            tileCursor[t] = tileStart[t];
        }
        tileStart[tileCount] = sum;

        if (tileEntries.length < sum) {
            tileEntries = new int[Math.max(sum, tileEntries.length * 2)];
        }

        // Zweiter Durchgang: fuellen. Da in sortierter Reihenfolge gelaufen
        // wird, ist jede Kachelliste automatisch nach Tiefe geordnet.
        for (int k = 0; k < visible; k++) {
            if ((k & 255) == 0 && isCancelled(epoch)) {
                return false;
            }
            int s = order[k];
            int r = radius[s];
            int x0 = clamp((int) ((u[s] - r) / TILE), 0, tilesX - 1);
            int x1 = clamp((int) ((u[s] + r) / TILE), 0, tilesX - 1);
            int y0 = clamp((int) ((v[s] - r) / TILE), 0, tilesY - 1);
            int y1 = clamp((int) ((v[s] + r) / TILE), 0, tilesY - 1);
            for (int ty = y0; ty <= y1; ty++) {
                int row = ty * tilesX;
                for (int tx = x0; tx <= x1; tx++) {
                    tileEntries[tileCursor[row + tx]++] = s;
                }
            }
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Schritt 4: Rasterung
    // ------------------------------------------------------------------

    private boolean rasterize(int[] dst, int w, int h, long epoch) {
        IntStream tiles = IntStream.range(0, tileCount);
        if (parallel) {
            tiles = tiles.parallel();
        }
        tiles.forEach(t -> rasterTile(t, dst, w, h, epoch));
        return !isCancelled(epoch);
    }

    private void rasterTile(int tileIndex, int[] dst, int w, int h, long epoch) {
        if (isCancelled(epoch)) {
            return;
        }
        final int tx = tileIndex % tilesX;
        final int ty = tileIndex / tilesX;
        final int x0 = tx * TILE;
        final int y0 = ty * TILE;
        final int x1 = Math.min(x0 + TILE, w);
        final int y1 = Math.min(y0 + TILE, h);
        final int tw = x1 - x0;
        final int th = y1 - y0;
        if (tw <= 0 || th <= 0) {
            return;
        }

        final int start = tileStart[tileIndex];
        final int end = tileStart[tileIndex + 1];

        TileBuffer buffer = tileBuffers.get();
        final float[] accR = buffer.r;
        final float[] accG = buffer.g;
        final float[] accB = buffer.b;
        final float[] accT = buffer.t;
        int used = tw * th;
        Arrays.fill(accR, 0, used, 0f);
        Arrays.fill(accG, 0, used, 0f);
        Arrays.fill(accB, 0, used, 0f);
        Arrays.fill(accT, 0, tw * th, 1f);

        int openPixels = tw * th;

        for (int k = start; k < end && openPixels > 0; k++) {
            final int s = tileEntries[k];
            final float su = u[s], sv = v[s];
            final int r = radius[s];
            final float cA = conicA[s], cB = conicB[s], cC = conicC[s];
            final float op = alpha[s];
            final float sr = colR[s], sg = colG[s], sb = colB[s];

            int px0 = Math.max(x0, (int) Math.floor(su - r));
            int px1 = Math.min(x1 - 1, (int) Math.ceil(su + r));
            int py0 = Math.max(y0, (int) Math.floor(sv - r));
            int py1 = Math.min(y1 - 1, (int) Math.ceil(sv + r));

            for (int y = py0; y <= py1; y++) {
                final float dy = y + 0.5f - sv;
                final int rowBase = (y - y0) * tw - x0;
                for (int x = px0; x <= px1; x++) {
                    final int p = rowBase + x;
                    final float T = accT[p];
                    if (T < MIN_TRANSMITTANCE) {
                        continue;
                    }
                    final float dx = x + 0.5f - su;
                    final float power = -0.5f * (cA * dx * dx + 2f * cB * dx * dy + cC * dy * dy);
                    if (power < MIN_POWER) {
                        continue;
                    }
                    float a = op * (float) Math.exp(power);
                    if (a < 0.0039f) {
                        continue;
                    }
                    if (a > 0.995f) {
                        a = 0.995f;
                    }
                    final float weight = T * a;
                    accR[p] += sr * weight;
                    accG[p] += sg * weight;
                    accB[p] += sb * weight;
                    final float nt = T - weight;
                    accT[p] = nt;
                    if (nt < MIN_TRANSMITTANCE) {
                        openPixels--;
                    }
                }
            }
        }

        for (int y = 0; y < th; y++) {
            final int src = y * tw;
            final int dstRow = (y0 + y) * w + x0;
            for (int x = 0; x < tw; x++) {
                final int p = src + x;
                final float T = accT[p];
                dst[dstRow + x] = packColor(
                        accR[p] + bgR * T,
                        accG[p] + bgG * T,
                        accB[p] + bgB * T);
            }
        }
    }

    // ------------------------------------------------------------------
    // Hilfsmittel
    // ------------------------------------------------------------------

    private void setupTiles(int w, int h) {
        int nx = (w + TILE - 1) / TILE;
        int ny = (h + TILE - 1) / TILE;
        if (nx != tilesX || ny != tilesY || tileStart == null) {
            tilesX = nx;
            tilesY = ny;
            tileCount = nx * ny;
            tileStart = new int[tileCount + 1];
            tileCursor = new int[tileCount];
            if (tileEntries == null) {
                tileEntries = new int[tileCount * 4];
            }
        }
    }

    private void ensureSplatCapacity(int n) {
        if (u != null && u.length >= n) {
            return;
        }
        u = new float[n];
        v = new float[n];
        depth = new float[n];
        conicA = new float[n];
        conicB = new float[n];
        conicC = new float[n];
        alpha = new float[n];
        colR = new float[n];
        colG = new float[n];
        colB = new float[n];
        radius = new int[n];
        order = new int[n];
    }

    private static int clamp(int value, int lo, int hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }

    private static int packColor(float r, float g, float b) {
        int ir = (int) (r * 255f + 0.5f);
        int ig = (int) (g * 255f + 0.5f);
        int ib = (int) (b * 255f + 0.5f);
        if (ir < 0) ir = 0; else if (ir > 255) ir = 255;
        if (ig < 0) ig = 0; else if (ig > 255) ig = 255;
        if (ib < 0) ib = 0; else if (ib > 255) ib = 255;
        return (ir << 16) | (ig << 8) | ib;
    }

    private boolean isCancelled(long epoch) {
        return cancellationEpoch != epoch;
    }

    private static final class TileBuffer {
        final float[] r = new float[TILE * TILE];
        final float[] g = new float[TILE * TILE];
        final float[] b = new float[TILE * TILE];
        final float[] t = new float[TILE * TILE];
    }
}
