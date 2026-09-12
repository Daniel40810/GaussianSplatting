package com.dan.fgaussian.demo;

import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.db.FGaussianDAO;
import com.dan.fgaussian.db.FGaussianDAO.SceneInfo;
import com.dan.fgaussian.db.FGaussianDAO.SceneMeta;
import com.dan.fgaussian.shade.FHeightRamp;
import com.dan.fgaussian.source.FPlySplatSource;
import com.dan.fgaussian.source.FXyzGridSource;
import com.dan.fgaussian.ui.FGaussianView;
import com.dan.fgaussian.ui.FOrbitController;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;

/**
 * Kommandozeilenwerkzeug fuer die Szenenablage: Kachel einlesen und speichern,
 * gespeicherte Szenen auflisten, eine davon anzeigen oder loeschen.
 *
 * <pre>
 * java com.dan.fgaussian.demo.FSceneTool import kachel.xyz [name] [stride] [ueberhoehung]
 * java com.dan.fgaussian.demo.FSceneTool list
 * java com.dan.fgaussian.demo.FSceneTool show &lt;name oder ID&gt;
 * java com.dan.fgaussian.demo.FSceneTool delete &lt;name oder ID&gt;
 * </pre>
 *
 * <p>Verbindung ueber System-Properties, voreingestellt auf das Schema DEMO:
 * {@code -Dfgs.user=DEMO -Dfgs.password=de -Dfgs.url=jdbc:oracle:thin:@//localhost:1521/PDBORCL}</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSceneTool {

    private FSceneTool() {
    }

    public static void main(String[] args) {
        if (args.length == 0) {
            usage();
            return;
        }
        String command = args[0].toLowerCase();
        try (Connection cn = connect()) {
            FGaussianDAO dao = new FGaussianDAO(cn);
            switch (command) {
                case "import" -> doImport(dao, cn, args);
                case "ply"    -> doImportPly(dao, cn, args);
                case "list"   -> doList(dao);
                case "show"   -> doShow(dao, args);
                case "delete" -> doDelete(dao, cn, args);
                default -> usage();
            }
        } catch (Exception ex) {
            System.out.println("Fehlgeschlagen: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private static Connection connect() throws SQLException {
        String url = System.getProperty("fgs.url", "jdbc:oracle:thin:@//localhost:1521/PDBORCL");
        String user = System.getProperty("fgs.user", "DEMO");
        String password = System.getProperty("fgs.password", "de");
        Connection cn = DriverManager.getConnection(url, user, password);
        cn.setAutoCommit(false);
        return cn;
    }

    // ------------------------------------------------------------------

    private static void doImport(FGaussianDAO dao, Connection cn, String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }
        File file = new File(args[1]);
        String name = args.length > 2 ? args[2] : file.getName().replaceFirst("\\.[^.]+$", "");
        int stride = args.length > 3 ? Integer.parseInt(args[3]) : 1;
        float exaggeration = args.length > 4 ? Float.parseFloat(args[4]) : 12f;

        FXyzGridSource source = FXyzGridSource.read(file);
        FSplatCloud cloud = source.buildCloud(stride, exaggeration, FHeightRamp.balticCoast());
        System.out.printf("%,d Splats aus %d x %d Rasterpunkten%n",
                cloud.count, source.gridWidth(), source.gridHeight());

        long t0 = System.nanoTime();
        long id = dao.save(name, cloud,
                new SceneMeta("XYZ", exaggeration,
                        source.centreEast(), source.centreNorth(), 25832,
                        file.getName()));
        cn.commit();
        System.out.printf("Gespeichert als \"%s\" (ID %d) in %.1f ms%n",
                name, id, (System.nanoTime() - t0) / 1e6);
    }

    /** Liest eine 3DGS-Rekonstruktion und legt sie als Szene ab. */
    private static void doImportPly(FGaussianDAO dao, Connection cn, String[] args) throws Exception {
        if (args.length < 2) {
            usage();
            return;
        }
        File file = new File(args[1]);
        String name = args.length > 2 ? args[2] : file.getName().replaceFirst("\\.[^.]+$", "");

        FPlySplatSource source = new FPlySplatSource();
        long t0 = System.nanoTime();
        FSplatCloud cloud = source.read(file);
        System.out.printf("%,d Splats, SH-Grad %d, gelesen in %.1f ms%n",
                cloud.count, source.getShDegree(), (System.nanoTime() - t0) / 1e6);

        // Eine Rekonstruktion hat keine Georeferenz - Ursprung und Ueberhoehung
        // bleiben neutral, bis sie jemand einmisst.
        long id = dao.save(name, cloud, new SceneMeta("PLY", 1f, 0, 0, 25832, file.getName()));
        cn.commit();
        System.out.printf("Gespeichert als \"%s\" (ID %d)%n", name, id);
    }

    private static void doList(FGaussianDAO dao) throws SQLException {
        List<SceneInfo> scenes = dao.list();
        if (scenes.isEmpty()) {
            System.out.println("Keine Szenen gespeichert.");
            return;
        }
        System.out.printf("%4s  %-28s %-5s %12s %6s  %s%n",
                "ID", "Name", "Quelle", "Splats", "Ueber.", "Angelegt");
        for (SceneInfo s : scenes) {
            System.out.printf("%4d  %-28s %-5s %,12d %6.1f  %s%n",
                    s.id(), s.name(), s.sourceKind(), s.splatCount(),
                    s.exaggeration(), s.created());
        }
    }

    private static void doShow(FGaussianDAO dao, String[] args) throws SQLException {
        if (args.length < 2) {
            usage();
            return;
        }
        long id = resolve(dao, args[1]);
        if (id < 0) {
            System.out.println("Keine Szene gefunden: " + args[1]);
            return;
        }
        long t0 = System.nanoTime();
        FSplatCloud cloud = dao.load(id);
        if (cloud == null) {
            System.out.println("Szene " + id + " ist leer.");
            return;
        }
        System.out.printf("%,d Splats geladen in %.1f ms%n",
                cloud.count, (System.nanoTime() - t0) / 1e6);

        SwingUtilities.invokeLater(() -> {
            FGaussianView view = new FGaussianView();
            view.setPreferredSize(new Dimension(1100, 660));
            view.setCloud(cloud);
            view.buildHierarchyAsync(1.0, 6);
            view.getCamera().pitch = 0.38f;
            view.getCamera().yaw = 0.75f;
            FOrbitController.install(view);

            JFrame frame = new JFrame("FGaussian - Szene " + id);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout());
            frame.add(view, BorderLayout.CENTER);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
        });
    }

    private static void doDelete(FGaussianDAO dao, Connection cn, String[] args) throws SQLException {
        if (args.length < 2) {
            usage();
            return;
        }
        long id = resolve(dao, args[1]);
        if (id < 0) {
            System.out.println("Keine Szene gefunden: " + args[1]);
            return;
        }
        int rows = dao.delete(id);
        cn.commit();
        System.out.println(rows > 0 ? "Szene " + id + " geloescht." : "Nichts geloescht.");
    }

    /** Nimmt eine ID oder einen Namen und liefert die ID, sonst -1. */
    private static long resolve(FGaussianDAO dao, String token) throws SQLException {
        try {
            return Long.parseLong(token);
        } catch (NumberFormatException notAnId) {
            SceneInfo info = dao.findByName(token);
            return info == null ? -1L : info.id();
        }
    }

    private static void usage() {
        System.out.println("""
            FGaussian Szenenwerkzeug

              import <kachel.xyz> [name] [stride] [ueberhoehung]
              ply    <datei.ply> [name]
              list
              show   <name oder ID>
              delete <name oder ID>

            Verbindung ueber System-Properties:
              -Dfgs.user=DEMO -Dfgs.password=de
              -Dfgs.url=jdbc:oracle:thin:@//localhost:1521/PDBORCL
            """);
    }
}
