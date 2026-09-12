package com.dan.fgaussian.demo;

import com.dan.fgaussian.core.FPolygonFeature;
import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.shade.FHeightRamp;
import com.dan.fgaussian.shade.FLandCover;
import com.dan.fgaussian.source.FCoverGrid;
import com.dan.fgaussian.source.FExtrusionBuilder;
import com.dan.fgaussian.source.FSdoPolygonSource;
import com.dan.fgaussian.source.FTerrainCover;
import com.dan.fgaussian.source.FXyzGridSource;
import com.dan.fgaussian.ui.FGaussianView;
import com.dan.fgaussian.ui.FOrbitController;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Zeigt das Gelaende gemeinsam mit den Flaechen aus Oracle Spatial: das
 * Hoehenmodell traegt die Form, die Geodaten die Flaechenart, und was eine
 * Bauhoehe hat, wird als Koerper aufgestellt.
 *
 * <p>Aufruf:</p>
 * <pre>
 * java com.dan.fgaussian.demo.FGaussianOracleDemo kachel.xyz [user] [passwort] [host:port/service]
 * </pre>
 *
 * <p>Die Geometriespalte jeder Ebene wird aus den Spatial-Metadaten ermittelt,
 * muss also nicht bekannt sein. Ebenen, die es im Schema nicht gibt, werden
 * uebersprungen und auf der Konsole vermerkt - die Ansicht kommt auch mit
 * einem Teil der Ebenen zustande.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FGaussianOracleDemo {

    /**
     * Eine Ebene: Tabelle, Flaechenart und die Hoehe, mit der extrudiert wird,
     * wenn die Tabelle keine eigene Hoehenspalte hat.
     */
    public record Layer(String table, String heightColumn, FLandCover cover, float defaultHeight) {
        public Layer(String table, FLandCover cover) {
            this(table, null, cover, 0f);
        }
    }

    /** Voreinstellung fuer das OGP-Schema von Timmendorfer Strand. */
    private static final List<Layer> OGP_LAYERS = List.of(
            new Layer("OGP.SEEN", FLandCover.SEA),
            new Layer("OGP.WALD", FLandCover.FOREST),
            new Layer("OGP.PARKS", FLandCover.GRASS),
            new Layer("OGP.STRAND", FLandCover.BEACH),
            new Layer("OGP.HAFEN", FLandCover.HARBOUR_BASIN),
            new Layer("OGP.PARKPLATZ", FLandCover.QUAY),
            // Kommt erst mit sql/ogp_gebaeude.sql dazu; fehlt die Tabelle,
            // meldet die Demo das und zeigt die uebrigen Ebenen.
            new Layer("OGP.GEBAEUDE", "HEIGHT_M", FLandCover.BUILDING, 7.5f)
    );

    /**
     * Ueberhoehung des Gelaendes. Fuer eine Kachel mit Bebauung deutlich
     * niedriger als fuer reines Gelaende: bei ueberhoehtem Untergrund und
     * gestreckten Haeusern wird aus einem Ostseebad sonst eine Skyline.
     */
    private static final float EXAGGERATION = 6f;

    /** Streckung der Bauhoehen; 1,6 laesst die Haeuser als Haeuser lesen. */
    private static final float BUILDING_HEIGHT_SCALE = 1.6f;

    private FGaussianOracleDemo() {
    }

    public static void main(String[] args) {
        // Ohne Argument die Kachel selbst suchen: in der IDE wird die
        // Hauptklasse ohne Parameter gestartet, und eine Demo, die dann nur
        // eine Aufrufzeile druckt und sich beendet, sieht aus wie ein Fehler.
        File grid = args.length > 0 ? new File(args[0]) : FGaussianDemo.findGridFile();
        if (grid == null || !grid.isFile()) {
            System.out.println("Keine .xyz-Kachel gefunden. Aufruf: "
                    + "FGaussianOracleDemo [kachel.xyz] [user] [passwort] [host:port/service]");
            return;
        }
        System.out.println("Kachel: " + grid.getAbsolutePath());
        String user = args.length > 1 ? args[1] : "OGP";
        String password = args.length > 2 ? args[2] : "ogp";
        String target = args.length > 3 ? args[3] : "localhost:1521/PDBORCL";
        String url = "jdbc:oracle:thin:@//" + target;

        try {
            FXyzGridSource terrain = FXyzGridSource.read(grid);
            System.out.printf("Raster %d x %d, Zellweite %.2f m%n",
                    terrain.gridWidth(), terrain.gridHeight(), terrain.cellSize());

            double[] window = {
                terrain.originEast(),
                terrain.originNorth(),
                terrain.originEast() + (terrain.gridWidth() - 1) * terrain.cellSize(),
                terrain.originNorth() + (terrain.gridHeight() - 1) * terrain.cellSize()
            };

            List<FPolygonFeature> features = loadFeatures(url, user, password, window);

            FCoverGrid cover = FCoverGrid.forSource(terrain);
            cover.rasterizeAll(features);

            // Wasser, Hafenbecken und Strand stehen fuer diese Kachel in keiner
            // Tabelle - sie werden aus der Form des Gelaendes abgeleitet.
            // Aus Geodaten gelesene Flaechen bleiben dabei unangetastet.
            FTerrainCover derived = new FTerrainCover();
            derived.apply(terrain, cover);
            System.out.printf("  abgeleitet: See %,d, Hafen %,d, Strand %,d Zellen%n",
                    derived.seaCells(), derived.harbourCells(), derived.beachCells());
            System.out.printf("%,d Flaechen, %,d von %,d Zellen klassifiziert%n",
                    features.size(), cover.classifiedCells(),
                    terrain.gridWidth() * terrain.gridHeight());

            FSplatCloud ground = terrain.buildCloud(1, EXAGGERATION, FHeightRamp.balticCoast(), cover);
            FExtrusionBuilder extruder = new FExtrusionBuilder();
            extruder.setHeightScale(BUILDING_HEIGHT_SCALE);
            FSplatCloud raised = extruder.build(features, terrain, EXAGGERATION);
            FSplatCloud scene = FSplatCloud.concat(ground, raised);
            System.out.printf("Gelaende %,d + Bauten %,d = %,d Splats%n",
                    ground.count, raised.count, scene.count);

            SwingUtilities.invokeLater(() -> show(scene, grid.getName()));

        } catch (Exception ex) {
            ex.printStackTrace();
            JOptionPane.showMessageDialog(null, ex.getMessage(), "FGaussian", JOptionPane.ERROR_MESSAGE);
        }
    }

    private static List<FPolygonFeature> loadFeatures(String url, String user, String password,
                                                      double[] window) {
        List<FPolygonFeature> features = new ArrayList<>();
        try (Connection cn = DriverManager.getConnection(url, user, password)) {
            FSdoPolygonSource source = new FSdoPolygonSource(cn);
            for (Layer layer : OGP_LAYERS) {
                String geomColumn = source.findGeometryColumn(layer.table());
                if (geomColumn == null) {
                    System.out.println("  uebersprungen (keine Spatial-Metadaten): " + layer.table());
                    continue;
                }
                try {
                    List<FPolygonFeature> part = source.load(
                            layer.table(), geomColumn, null, layer.heightColumn(),
                            layer.cover(), layer.defaultHeight(), window);
                    features.addAll(part);
                    System.out.printf("  %-16s %s  %,d Flaechen%n",
                            layer.table(), geomColumn, part.size());
                } catch (SQLException ex) {
                    System.out.println("  Fehler bei " + layer.table() + ": " + ex.getMessage());
                }
            }
            if (source.getSkippedElements() > 0) {
                System.out.println("  " + source.getSkippedElements()
                        + " Elemente uebersprungen (keine einfachen Polygonringe)");
            }
        } catch (SQLException ex) {
            System.out.println("Keine Datenbankverbindung (" + ex.getMessage()
                    + ") - es wird nur das Gelaende gezeigt.");
        }
        return features;
    }

    private static void show(FSplatCloud scene, String title) {
        FGaussianView view = new FGaussianView();
        view.setPreferredSize(new Dimension(1100, 660));
        view.setCloud(scene);
        view.buildHierarchyAsync(1.0, 6);
        view.getCamera().pitch = 0.34f;
        view.getCamera().yaw = 0.2f;
        FOrbitController.install(view);

        JFrame frame = new JFrame("FGaussian - " + title + " mit Oracle-Flaechen");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());
        frame.add(view, BorderLayout.CENTER);
        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }
}
