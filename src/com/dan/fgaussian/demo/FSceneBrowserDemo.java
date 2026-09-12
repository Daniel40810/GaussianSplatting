package com.dan.fgaussian.demo;

import com.dan.fgaussian.db.FGaussianDAO;
import com.dan.fgaussian.ui.FSceneBrowser;

import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Fenster um den {@link FSceneBrowser}: alle gespeicherten Szenen in einer
 * Liste, Auswahl laedt sie in die Ansicht.
 *
 * <pre>
 * java -cp build/classes:lib/ojdbc11.jar com.dan.fgaussian.demo.FSceneBrowserDemo
 * </pre>
 *
 * <p>Verbindung ueber dieselben System-Properties wie {@link FSceneTool}:
 * {@code -Dfgs.user}, {@code -Dfgs.password}, {@code -Dfgs.url}.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSceneBrowserDemo {

    private FSceneBrowserDemo() {
    }

    public static void main(String[] args) {
        String url = System.getProperty("fgs.url", "jdbc:oracle:thin:@//localhost:1521/PDBORCL");
        String user = System.getProperty("fgs.user", "DEMO");
        String password = System.getProperty("fgs.password", "de");

        final Connection connection;
        try {
            connection = DriverManager.getConnection(url, user, password);
        } catch (SQLException ex) {
            JOptionPane.showMessageDialog(null,
                    "Keine Verbindung zu " + url + "\n" + ex.getMessage(),
                    "FGaussian", JOptionPane.ERROR_MESSAGE);
            return;
        }

        // Verbindung beim Schliessen des Fensters freigeben.
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Beim Herunterfahren nicht mehr interessant.
            }
        }));

        SwingUtilities.invokeLater(() -> {
            FSceneBrowser browser = new FSceneBrowser(new FGaussianDAO(connection));
            JFrame frame = new JFrame("FGaussian - Szenen");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setContentPane(browser);
            frame.pack();
            frame.setLocationRelativeTo(null);
            frame.setVisible(true);
            browser.refresh();
        });
    }
}
