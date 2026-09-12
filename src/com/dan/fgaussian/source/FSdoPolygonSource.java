package com.dan.fgaussian.source;

import com.dan.fgaussian.core.FPolygonFeature;
import com.dan.fgaussian.shade.FLandCover;

import oracle.spatial.geometry.JGeometry;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Struct;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Liest Polygone aus Oracle-Spatial-Tabellen und liefert sie als
 * {@link FPolygonFeature} in UTM-Metern.
 *
 * <p>Die Umprojektion macht die Datenbank per {@code SDO_CS.TRANSFORM}: Die
 * OGP-Bestaende liegen in SRID 8307 (WGS 84, Grad), das Hoehenmodell in
 * SRID 25832 (ETRS89 / UTM 32N, Meter). In Metern zu rechnen ist fuer den
 * Renderer kein Komfort, sondern Voraussetzung - ein Splat-Radius in Grad
 * waere eine Funktion der geografischen Breite.</p>
 *
 * <p>Vorgefiltert wird ueber {@code SDO_FILTER} auf das Kachelfenster. Fehlt der
 * raeumliche Index, faellt die Abfrage automatisch auf einen vollen
 * Tabellendurchlauf mit anschliessender Pruefung in Java zurueck.</p>
 *
 * <p>Zusammengesetzte Ringe (ETYPE 1005/2005, also Boegen) werden
 * uebersprungen und in {@link #getSkippedElements()} gezaehlt; in OSM-Importen
 * kommen sie praktisch nicht vor.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSdoPolygonSource {

    /** Nur einfache Bezeichner, damit aus einem Tabellennamen kein SQL wird. */
    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_$#]*(\\.[A-Za-z][A-Za-z0-9_$#]*)?");

    private final Connection connection;
    private int targetSrid = 25832;
    private int skippedElements;

    public FSdoPolygonSource(Connection connection) {
        this.connection = connection;
    }

    /** Ziel-SRID der gelieferten Koordinaten, voreingestellt 25832. */
    public void setTargetSrid(int srid) {
        this.targetSrid = srid;
    }

    /** Anzahl der Elemente, die nicht als Polygonring gelesen werden konnten. */
    public int getSkippedElements() {
        return skippedElements;
    }

    /**
     * Laedt alle Polygone einer Tabelle, die das Fenster beruehren.
     *
     * @param table         Tabelle, optional mit Schema, etwa {@code OGP.HAFEN}
     * @param geomColumn    Spalte vom Typ SDO_GEOMETRY
     * @param labelColumn   Spalte fuer die Beschriftung, oder {@code null}
     * @param heightColumn  Spalte mit der Bauhoehe in Metern, oder {@code null}
     * @param cover         Flaechenart, die allen Objekten zugewiesen wird
     * @param defaultHeight Hoehe, wenn keine Spalte angegeben oder der Wert leer ist
     * @param window        Fenster in Ziel-SRID als {minE, minN, maxE, maxN}
     */
    public List<FPolygonFeature> load(String table, String geomColumn,
                                      String labelColumn, String heightColumn,
                                      FLandCover cover, float defaultHeight,
                                      double[] window) throws SQLException {
        check(table, "Tabelle");
        check(geomColumn, "Geometriespalte");
        if (labelColumn != null) {
            check(labelColumn, "Beschriftungsspalte");
        }
        if (heightColumn != null) {
            check(heightColumn, "Hoehenspalte");
        }

        int sourceSrid = resolveSourceSrid(table, geomColumn);

        List<FPolygonFeature> features = new ArrayList<>();
        boolean spatial = true;
        try {
            query(table, geomColumn, labelColumn, heightColumn, cover, defaultHeight,
                    window, sourceSrid, true, features);
        } catch (SQLException ex) {
            // ORA-13226: kein raeumlicher Index. Dann eben ohne Vorfilter.
            if (ex.getMessage() != null && ex.getMessage().contains("ORA-13226")) {
                spatial = false;
                features.clear();
            } else {
                throw ex;
            }
        }
        if (!spatial) {
            query(table, geomColumn, labelColumn, heightColumn, cover, defaultHeight,
                    window, sourceSrid, false, features);
        }
        return features;
    }

    // ------------------------------------------------------------------

    private void query(String table, String geomColumn, String labelColumn, String heightColumn,
                       FLandCover cover, float defaultHeight, double[] window,
                       int sourceSrid, boolean useSpatialFilter,
                       List<FPolygonFeature> out) throws SQLException {

        StringBuilder sql = new StringBuilder(320);
        sql.append("SELECT SDO_CS.TRANSFORM(g.").append(geomColumn).append(", ")
           .append(targetSrid).append(") AS GEOM");
        sql.append(labelColumn == null ? ", NULL AS LBL" : ", g." + labelColumn + " AS LBL");
        sql.append(heightColumn == null ? ", NULL AS HGT" : ", g." + heightColumn + " AS HGT");
        sql.append(" FROM ").append(table).append(" g");

        if (useSpatialFilter) {
            sql.append(" WHERE SDO_FILTER(g.").append(geomColumn)
               .append(", SDO_CS.TRANSFORM(SDO_GEOMETRY(2003, ").append(targetSrid)
               .append(", NULL, SDO_ELEM_INFO_ARRAY(1,1003,3),")
               .append(" SDO_ORDINATE_ARRAY(?,?,?,?)), ").append(sourceSrid)
               .append("), 'querytype=WINDOW') = 'TRUE'");
        }

        try (PreparedStatement ps = connection.prepareStatement(sql.toString())) {
            if (useSpatialFilter) {
                ps.setDouble(1, window[0]);
                ps.setDouble(2, window[1]);
                ps.setDouble(3, window[2]);
                ps.setDouble(4, window[3]);
            }
            ps.setFetchSize(256);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Object raw = rs.getObject("GEOM");
                    if (!(raw instanceof Struct)) {
                        continue;
                    }
                    String label = rs.getString("LBL");
                    float height = defaultHeight;
                    if (heightColumn != null) {
                        double value = rs.getDouble("HGT");
                        if (!rs.wasNull() && value > 0) {
                            height = (float) value;
                        }
                    }
                    for (FPolygonFeature f : toFeatures(JGeometry.loadJS((Struct) raw), cover, label, height)) {
                        if (!useSpatialFilter && !intersects(f, window)) {
                            continue;
                        }
                        out.add(f);
                    }
                }
            }
        }
    }

    /**
     * Zerlegt eine Geometrie in Features. Jeder aeussere Ring (ETYPE 1003)
     * beginnt ein neues Feature, innere Ringe (2003) haengen sich als Loecher
     * an das zuletzt begonnene an. Damit zerfaellt ein MultiPolygon in mehrere
     * Features, was fuer Rasterung und Extrusion genau richtig ist.
     */
    private List<FPolygonFeature> toFeatures(JGeometry geometry, FLandCover cover,
                                             String label, float height) {
        List<FPolygonFeature> result = new ArrayList<>(2);
        if (geometry == null) {
            return result;
        }
        int[] elemInfo = geometry.getElemInfo();
        double[] ordinates = geometry.getOrdinatesArray();
        if (elemInfo == null || ordinates == null) {
            return result;
        }
        final int dim = Math.max(2, geometry.getDimensions());
        FPolygonFeature current = null;

        for (int t = 0; t + 2 < elemInfo.length; t += 3) {
            int start = elemInfo[t] - 1;
            int etype = elemInfo[t + 1];
            int interpretation = elemInfo[t + 2];
            int end = (t + 3 < elemInfo.length) ? elemInfo[t + 3] - 1 : ordinates.length;
            if (start < 0 || end > ordinates.length || end <= start) {
                continue;
            }
            if (etype != 1003 && etype != 2003) {
                skippedElements++;
                continue;
            }

            double[] ring;
            if (interpretation == 3) {
                ring = rectangleRing(ordinates, start, dim);
            } else if (interpretation == 1) {
                ring = linearRing(ordinates, start, end, dim);
            } else {
                skippedElements++;
                continue;
            }
            if (ring == null) {
                continue;
            }

            if (etype == 1003 || current == null) {
                current = new FPolygonFeature(cover, label, height);
                current.addRing(ring);
                result.add(current);
            } else {
                current.addRing(ring);
            }
        }
        return result;
    }

    private static double[] linearRing(double[] ordinates, int start, int end, int dim) {
        int points = (end - start) / dim;
        if (points < 3) {
            return null;
        }
        double[] ring = new double[points * 2];
        for (int i = 0; i < points; i++) {
            ring[i * 2] = ordinates[start + i * dim];
            ring[i * 2 + 1] = ordinates[start + i * dim + 1];
        }
        return ring;
    }

    private static double[] rectangleRing(double[] ordinates, int start, int dim) {
        if (start + dim + 1 >= ordinates.length) {
            return null;
        }
        double x0 = ordinates[start];
        double y0 = ordinates[start + 1];
        double x1 = ordinates[start + dim];
        double y1 = ordinates[start + dim + 1];
        return new double[] { x0, y0, x1, y0, x1, y1, x0, y1 };
    }

    private static boolean intersects(FPolygonFeature feature, double[] window) {
        double[] box = new double[4];
        feature.bounds(box);
        return box[2] >= window[0] && box[0] <= window[2]
            && box[3] >= window[1] && box[1] <= window[3];
    }

    /**
     * Ermittelt die Geometriespalte einer Tabelle aus den Spatial-Metadaten.
     * Erspart das Raten bei fremden Schemata; liefert {@code null}, wenn die
     * Tabelle dort nicht eingetragen ist.
     */
    public String findGeometryColumn(String table) {
        check(table, "Tabelle");
        String owner = null;
        String name = table;
        int dot = table.indexOf('.');
        if (dot > 0) {
            owner = table.substring(0, dot).toUpperCase();
            name = table.substring(dot + 1);
        }
        String sql = owner == null
                ? "SELECT COLUMN_NAME FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME = ?"
                : "SELECT COLUMN_NAME FROM ALL_SDO_GEOM_METADATA WHERE OWNER = ? AND TABLE_NAME = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            if (owner != null) {
                ps.setString(i++, owner);
            }
            ps.setString(i, name.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException ignored) {
            // Kein Zugriff auf die Metadaten - der Aufrufer muss die Spalte nennen.
        }
        return null;
    }

    private int resolveSourceSrid(String table, String geomColumn) {
        String owner = null;
        String name = table;
        int dot = table.indexOf('.');
        if (dot > 0) {
            owner = table.substring(0, dot).toUpperCase();
            name = table.substring(dot + 1);
        }
        String sql = owner == null
                ? "SELECT SRID FROM USER_SDO_GEOM_METADATA WHERE TABLE_NAME = ? AND COLUMN_NAME = ?"
                : "SELECT SRID FROM ALL_SDO_GEOM_METADATA WHERE OWNER = ? AND TABLE_NAME = ? AND COLUMN_NAME = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            int i = 1;
            if (owner != null) {
                ps.setString(i++, owner);
            }
            ps.setString(i++, name.toUpperCase());
            ps.setString(i, geomColumn.toUpperCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    int srid = rs.getInt(1);
                    if (!rs.wasNull() && srid > 0) {
                        return srid;
                    }
                }
            }
        } catch (SQLException ignored) {
            // Kein Metadateneintrag lesbar - dann die uebliche Annahme.
        }
        return 8307;
    }

    private static void check(String identifier, String what) {
        if (identifier == null || !IDENTIFIER.matcher(identifier).matches()) {
            throw new IllegalArgumentException(what + " ist kein einfacher Bezeichner: " + identifier);
        }
    }
}
