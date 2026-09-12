package com.dan.fgaussian.core;

import com.dan.fgaussian.shade.FLandCover;

import java.util.ArrayList;
import java.util.List;

/**
 * Ein Flaechenobjekt aus einer Geodatenquelle, unabhaengig davon, ob es aus
 * Oracle Spatial, einer Datei oder einem Test stammt.
 *
 * <p>Die Ringe liegen in <em>metrischen Absolutkoordinaten</em> (UTM 32N,
 * Rechtswert/Hochwert), je Ring ein {@code double[]} der Form
 * {x0, y0, x1, y1, ...}. Der erste Ring ist die Aussenkontur, weitere Ringe
 * sind Loecher. Die Umrechnung in Szenenkoordinaten macht erst der Verbraucher,
 * der die Georeferenz der Kachel kennt.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FPolygonFeature {

    private final List<double[]> rings = new ArrayList<>(2);

    /** Flaechenart, bestimmt Farbe und ob extrudiert wird. */
    public FLandCover cover = FLandCover.UNKNOWN;

    /** Beschriftung aus der Quelle, etwa der Name des Hafenbeckens. */
    public String label;

    /** Bauhoehe in Metern ueber Gelaende; 0 bedeutet reine Flaeche. */
    public float height;

    public FPolygonFeature() {
    }

    public FPolygonFeature(FLandCover cover, String label, float height) {
        this.cover = cover;
        this.label = label;
        this.height = height;
    }

    /** Haengt einen Ring an: erster Ring aussen, weitere sind Loecher. */
    public void addRing(double[] xy) {
        if (xy == null || xy.length < 6) {
            return;
        }
        rings.add(xy);
    }

    public int ringCount() {
        return rings.size();
    }

    public double[] ring(int index) {
        return rings.get(index);
    }

    public List<double[]> rings() {
        return rings;
    }

    public boolean isEmpty() {
        return rings.isEmpty();
    }

    /**
     * Flaechenschwerpunkt der Aussenkontur nach der Trapezformel.
     * Faellt bei entarteten Ringen auf den Mittelwert der Stuetzpunkte zurueck.
     *
     * <p><b>Gerechnet wird relativ zum ersten Stuetzpunkt</b>, nicht in
     * Absolutkoordinaten. Die Trapezformel multipliziert Koordinaten
     * miteinander; bei UTM-Hochwerten um 5 984 000 und einem Gebaeude von
     * wenigen Metern Groesse entstehen dabei Zwischenwerte in der
     * Groessenordnung 10^19, die sich zu einem Ergebnis um 10^7 wegheben
     * sollen. Von den 16 signifikanten Stellen eines double bleiben dann zu
     * wenige uebrig - der Schwerpunkt landete in der Praxis Dutzende Meter
     * neben der eigenen Flaeche. Nach der Verschiebung liegen alle Werte im
     * Meterbereich, und der Fehler verschwindet.</p>
     *
     * @param out Ziel der Laenge 2
     */
    public void centroid(double[] out) {
        if (rings.isEmpty()) {
            out[0] = 0;
            out[1] = 0;
            return;
        }
        double[] r = rings.get(0);
        int n = r.length / 2;
        final double ox = r[0];
        final double oy = r[1];

        double area = 0, cx = 0, cy = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double x0 = r[i * 2] - ox, y0 = r[i * 2 + 1] - oy;
            double x1 = r[j * 2] - ox, y1 = r[j * 2 + 1] - oy;
            double cross = x0 * y1 - x1 * y0;
            area += cross;
            cx += (x0 + x1) * cross;
            cy += (y0 + y1) * cross;
        }
        area *= 0.5;
        if (Math.abs(area) < 1e-9) {
            double sx = 0, sy = 0;
            for (int i = 0; i < n; i++) {
                sx += r[i * 2];
                sy += r[i * 2 + 1];
            }
            out[0] = sx / n;
            out[1] = sy / n;
            return;
        }
        out[0] = ox + cx / (6.0 * area);
        out[1] = oy + cy / (6.0 * area);
    }

    /** Umfang der Aussenkontur in Metern. */
    public double perimeter() {
        if (rings.isEmpty()) {
            return 0;
        }
        double[] r = rings.get(0);
        int n = r.length / 2;
        double sum = 0;
        for (int i = 0; i < n; i++) {
            int j = (i + 1) % n;
            double dx = r[j * 2] - r[i * 2];
            double dy = r[j * 2 + 1] - r[i * 2 + 1];
            sum += Math.sqrt(dx * dx + dy * dy);
        }
        return sum;
    }

    /** Schreibt die Huelle aller Ringe als {minX, minY, maxX, maxY}. */
    public void bounds(double[] out) {
        out[0] = Double.MAX_VALUE;
        out[1] = Double.MAX_VALUE;
        out[2] = -Double.MAX_VALUE;
        out[3] = -Double.MAX_VALUE;
        for (double[] r : rings) {
            for (int i = 0; i < r.length; i += 2) {
                if (r[i] < out[0]) out[0] = r[i];
                if (r[i] > out[2]) out[2] = r[i];
                if (r[i + 1] < out[1]) out[1] = r[i + 1];
                if (r[i + 1] > out[3]) out[3] = r[i + 1];
            }
        }
    }
}
