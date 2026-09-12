package com.dan.fgaussian.demo;

import com.dan.fgaussian.core.FPolygonFeature;
import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.shade.FLandCover;
import com.dan.fgaussian.source.FCoverGrid;
import com.dan.fgaussian.source.FExtrusionBuilder;
import com.dan.fgaussian.source.FSdoPolygonSource;
import com.dan.fgaussian.source.FXyzGridSource;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Diagnose fuer den Weg von Oracle Spatial bis zu den Splats - ohne Fenster,
 * ohne Rendern, mit Zwischenstand nach jedem Schritt.
 *
 * <p>Wenn in {@link FGaussianOracleDemo} nichts zu sehen ist, liegt es an genau
 * einer von vier Stellen: Verbindung, Metadaten, Abfrage oder Extrusion. Diese
 * Klasse geht sie der Reihe nach durch und sagt, wie weit es trägt.</p>
 *
 * <pre>
 * java -cp build\classes;lib\ojdbc11.jar;lib\sdoapi.jar ^
 *      com.dan.fgaussian.demo.FGeoCheck kachel.xyz OGP.GEBAEUDE HEIGHT_M
 * </pre>
 *
 * @author com.dan.fgaussian
 */
public final class FGeoCheck {

    private FGeoCheck() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("Aufruf: FGeoCheck <kachel.xyz> <TABELLE> [HOEHENSPALTE] [user] [pwd] [host:port/service]");
            return;
        }
        String table = args[1];
        String heightColumn = args.length > 2 && !"-".equals(args[2]) ? args[2] : null;
        String user = args.length > 3 ? args[3] : "OGP";
        String password = args.length > 4 ? args[4] : "ogp";
        String target = args.length > 5 ? args[5] : "localhost:1521/PDBORCL";
        String url = "jdbc:oracle:thin:@//" + target;

        FXyzGridSource terrain = FXyzGridSource.read(new File(args[0]));
        double[] window = {
            terrain.originEast(),
            terrain.originNorth(),
            terrain.originEast() + (terrain.gridWidth() - 1) * terrain.cellSize(),
            terrain.originNorth() + (terrain.gridHeight() - 1) * terrain.cellSize()
        };
        System.out.printf("1) Kachel  %.1f / %.1f  bis  %.1f / %.1f  (SRID 25832)%n",
                window[0], window[1], window[2], window[3]);

        System.out.println("2) Verbinde als " + user + " nach " + target);
        try (Connection cn = DriverManager.getConnection(url, user, password)) {
            System.out.println("   verbunden: " + cn.getMetaData().getDatabaseProductVersion());

            System.out.println("3) Zeilen in " + table);
            try (Statement st = cn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
                if (rs.next()) {
                    System.out.println("   " + rs.getInt(1) + " Zeilen");
                }
            } catch (SQLException ex) {
                System.out.println("   NICHT LESBAR: " + ex.getMessage());
                System.out.println("   -> fehlt das SELECT-Recht, oder heisst die Tabelle anders?");
                return;
            }

            FSdoPolygonSource source = new FSdoPolygonSource(cn);

            System.out.println("4) Geometriespalte aus den Spatial-Metadaten");
            String geom = source.findGeometryColumn(table);
            System.out.println("   " + (geom == null ? "NICHT GEFUNDEN" : geom));
            if (geom == null) {
                System.out.println("   -> ALL_SDO_GEOM_METADATA zeigt fremde Eintraege nur bei Leserecht");
                return;
            }

            System.out.println("5) Abfrage mit Kachelfenster");
            List<FPolygonFeature> features = source.load(table, geom, null, heightColumn,
                    FLandCover.BUILDING, 7.5f, window);
            System.out.printf("   %d Flaechen, %d Elemente uebersprungen%n",
                    features.size(), source.getSkippedElements());
            if (features.isEmpty()) {
                System.out.println("   -> Fenster und Daten liegen nicht uebereinander (SRID?)");
                return;
            }

            double[] box = new double[4];
            double[] centre = new double[2];
            features.get(0).bounds(box);
            features.get(0).centroid(centre);
            System.out.printf("   erste Flaeche: %d Ringe, Schwerpunkt %.1f / %.1f, Hoehe %.1f m%n",
                    features.get(0).ringCount(), centre[0], centre[1], features.get(0).height);
            System.out.printf("   Huelle: %.1f / %.1f bis %.1f / %.1f%n", box[0], box[1], box[2], box[3]);

            int inside = 0;
            for (FPolygonFeature f : features) {
                f.centroid(centre);
                if (centre[0] >= window[0] && centre[0] <= window[2]
                 && centre[1] >= window[1] && centre[1] <= window[3]) {
                    inside++;
                }
            }
            System.out.println("   davon mit Schwerpunkt in der Kachel: " + inside);

            System.out.println("6) Rasterung der Grundflaechen");
            FCoverGrid cover = FCoverGrid.forSource(terrain);
            cover.rasterizeAll(features);
            System.out.printf("   %,d Rasterzellen belegt%n", cover.classifiedCells());

            System.out.println("7) Extrusion");
            FExtrusionBuilder extruder = new FExtrusionBuilder();
            extruder.setHeightScale(1.6f);
            FSplatCloud built = extruder.build(features, terrain, 6f);
            System.out.printf("   %,d Splats, Hoehen von %.1f bis %.1f (Szeneneinheiten)%n",
                    built.count, built.minY, built.maxY);
            if (built.count == 0) {
                System.out.println("   -> keine Flaeche ist extrudierbar: Hoehe 0 oder falsche Flaechenart");
            }

        } catch (SQLException ex) {
            System.out.println("   FEHLER: " + ex.getMessage());
        }
    }
}
