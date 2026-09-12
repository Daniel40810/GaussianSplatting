package com.dan.fgaussian.source;

import com.dan.fgaussian.core.FPolygonFeature;
import com.dan.fgaussian.core.FQuat;
import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.shade.FLandCover;

import java.util.Arrays;
import java.util.List;

/**
 * Macht aus Flaechenobjekten aufgehende Koerper: Waende entlang der Ringe,
 * ein Dach auf der Deckflaeche, beides als Splats.
 *
 * <p>Ein Gebaeudeumring wird dabei nicht trianguliert, sondern direkt mit
 * Splats belegt - der Rasterisierer zeichnet ohnehin nur Gaussellipsen, also
 * spart man sich den Umweg ueber Dreiecke. Der Abstand der Splats ist so
 * gewaehlt, dass sie sich ueberlappen und die Wand geschlossen wirkt.</p>
 *
 * <p><b>Zur Hoehenskalierung:</b> Das Gelaende wird stark ueberhoeht, sonst ist
 * auf einer Kachel mit fuenf Hoehenmetern nichts zu sehen. Aufgehende Bauten mit
 * demselben Faktor zu strecken ist konsistent, laesst aber aus einem
 * Hafenschuppen einen Turm werden. Deshalb ist {@link #setHeightScale(float)}
 * getrennt einstellbar; voreingestellt ist die Wurzel der Gelaendeueberhoehung,
 * was in der Praxis am glaubwuerdigsten aussieht.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FExtrusionBuilder {

    private float wallSpacing = 1.3f;
    private float roofSpacing = 1.6f;
    private float defaultHeight = 7.5f;
    private float heightScale = Float.NaN;

    /** Sonnenrichtung, gleich der in {@link FXyzGridSource}. */
    private static final float LX = 0.38f, LY = 0.78f, LZ = 0.50f;

    /** Abstand der Splats an den Waenden, Szenenmeter. */
    public void setWallSpacing(float value) {
        this.wallSpacing = Math.max(0.2f, value);
    }

    /** Abstand der Splats auf den Daechern, Szenenmeter. */
    public void setRoofSpacing(float value) {
        this.roofSpacing = Math.max(0.2f, value);
    }

    /** Hoehe fuer Objekte ohne eigene Hoehenangabe, Meter. */
    public void setDefaultHeight(float value) {
        this.defaultHeight = Math.max(0.5f, value);
    }

    /**
     * Streckung der Bauhoehen. {@code NaN} bedeutet: Wurzel der
     * Gelaendeueberhoehung verwenden.
     */
    public void setHeightScale(float value) {
        this.heightScale = value;
    }

    /**
     * Baut die Koerper.
     *
     * @param features     Flaechen; beruecksichtigt werden nur solche mit
     *                     {@link FLandCover#isRaised()}
     * @param terrain      liefert Gelaendehoehe und Szenengeoreferenz
     * @param exaggeration dieselbe Ueberhoehung, mit der das Gelaende gebaut wurde
     * @return Wolke nur mit den Bauten; leer, wenn nichts zu extrudieren war
     */
    public FSplatCloud build(List<FPolygonFeature> features, FXyzGridSource terrain, float exaggeration) {
        float scale = Float.isNaN(heightScale)
                ? (float) Math.sqrt(Math.max(1f, exaggeration))
                : heightScale;

        int needed = emit(features, terrain, exaggeration, scale, null);
        FSplatCloud cloud = new FSplatCloud(needed);
        emit(features, terrain, exaggeration, scale, cloud);
        cloud.computeBounds();
        return cloud;
    }

    /**
     * Zaehlt die noetigen Splats, wenn {@code sink} null ist, und schreibt sie
     * sonst. Dieselbe Schleife fuer beide Durchgaenge, damit Zaehlung und
     * Erzeugung nicht auseinanderlaufen koennen.
     */
    private int emit(List<FPolygonFeature> features, FXyzGridSource terrain,
                     float exaggeration, float scale, FSplatCloud sink) {
        final float[] q = new float[4];
        final float[] rgb = new float[3];
        final double[] centre = new double[2];
        int produced = 0;

        for (FPolygonFeature f : features) {
            if (f == null || f.isEmpty() || !f.cover.isRaised()) {
                continue;
            }
            float raw = f.height > 0f ? f.height : defaultHeight;
            f.centroid(centre);
            float base = terrain.heightAt(centre[0], centre[1]) * exaggeration;
            float span = raw * scale;
            if (span < 0.3f) {
                continue;
            }
            f.cover.color(rgb);

            produced += walls(f, terrain, base, span, rgb, q, sink);
            produced += roof(f, terrain, base + span, rgb, q, sink);
        }
        return produced;
    }

    // ------------------------------------------------------------------

    private int walls(FPolygonFeature f, FXyzGridSource terrain, float base, float span,
                      float[] rgb, float[] q, FSplatCloud sink) {
        final int rows = Math.max(1, Math.round(span / wallSpacing));
        final float rowStep = span / rows;
        final float extent = Math.max(wallSpacing, rowStep) * 0.62f;
        final float thin = Math.max(0.05f, wallSpacing * 0.10f);
        int produced = 0;

        for (double[] ring : f.rings()) {
            final int points = ring.length / 2;
            for (int i = 0; i < points; i++) {
                int j = (i + 1) % points;
                double ax = ring[i * 2], ay = ring[i * 2 + 1];
                double bx = ring[j * 2], by = ring[j * 2 + 1];
                double dx = bx - ax, dy = by - ay;
                double length = Math.sqrt(dx * dx + dy * dy);
                if (length < 1e-6) {
                    continue;
                }
                int cols = Math.max(1, (int) Math.round(length / wallSpacing));

                // Kantenrichtung in Szenenkoordinaten: z zeigt nach Sueden,
                // die Nordkomponente wechselt daher das Vorzeichen.
                float dsx = (float) (dx / length);
                float dsz = -(float) (dy / length);

                // Waagerechte Normale zur Kante.
                float wallNx = dsz;
                float wallNz = -dsx;
                if (sink != null) {
                    FQuat.fromUnitZTo(wallNx, 0f, wallNz, q);
                }

                // Betrag, nicht Vorzeichen: die Ringorientierung der Quelle ist
                // nicht garantiert, und ein Splat hat ohnehin keine Rueckseite.
                float lambert = Math.abs(wallNx * LX + wallNz * LZ);
                float shade = 0.34f + 0.52f * lambert;

                for (int c = 0; c < cols; c++) {
                    double t = (c + 0.5) / cols;
                    double east = ax + dx * t;
                    double north = ay + dy * t;
                    float sx = terrain.sceneX(east);
                    float sz = terrain.sceneZ(north);

                    for (int r = 0; r < rows; r++) {
                        float y = base + (r + 0.5f) * rowStep;
                        if (sink != null) {
                            sink.add(sx, y, sz, extent, extent, thin, q,
                                    rgb[0] * shade, rgb[1] * shade, rgb[2] * shade, 1f);
                        }
                        produced++;
                    }
                }
            }
        }
        return produced;
    }

    private int roof(FPolygonFeature f, FXyzGridSource terrain, float top,
                     float[] rgb, float[] q, FSplatCloud sink) {
        if (sink != null) {
            FQuat.fromUnitZTo(0f, 1f, 0f, q);
        }
        final float extent = roofSpacing * 0.62f;
        final float thin = Math.max(0.05f, roofSpacing * 0.08f);
        final float shade = 0.42f + 0.58f * LY;

        double[] box = new double[4];
        f.bounds(box);

        int produced = 0;
        double[] crossings = new double[64];

        for (double north = box[1]; north <= box[3]; north += roofSpacing) {
            int n = 0;
            for (double[] ring : f.rings()) {
                final int points = ring.length / 2;
                for (int i = 0; i < points; i++) {
                    int j = (i + 1) % points;
                    double y0 = ring[i * 2 + 1];
                    double y1 = ring[j * 2 + 1];
                    if ((y0 <= north && y1 > north) || (y1 <= north && y0 > north)) {
                        double x0 = ring[i * 2];
                        double x1 = ring[j * 2];
                        double t = (north - y0) / (y1 - y0);
                        if (n == crossings.length) {
                            crossings = Arrays.copyOf(crossings, n * 2);
                        }
                        crossings[n++] = x0 + t * (x1 - x0);
                    }
                }
            }
            if (n < 2) {
                continue;
            }
            Arrays.sort(crossings, 0, n);

            for (int k = 0; k + 1 < n; k += 2) {
                for (double east = crossings[k]; east <= crossings[k + 1]; east += roofSpacing) {
                    if (sink != null) {
                        sink.add(terrain.sceneX(east), top, terrain.sceneZ(north),
                                extent, extent, thin, q,
                                rgb[0] * shade, rgb[1] * shade, rgb[2] * shade, 1f);
                    }
                    produced++;
                }
            }
        }
        return produced;
    }
}
