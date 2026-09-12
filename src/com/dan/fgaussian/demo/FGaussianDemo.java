package com.dan.fgaussian.demo;

import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.shade.FHeightRamp;
import com.dan.fgaussian.source.FXyzGridSource;
import com.dan.fgaussian.ui.FGaussianView;
import com.dan.fgaussian.ui.FOrbitController;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Demo-Fenster fuer den Splat-Renderer.
 *
 * <p>Laedt eine XYZ-Rasterdatei - ohne Argument die erste, die sich unterhalb
 * des Arbeitsverzeichnisses findet - baut daraus eine Splat-Wolke und zeigt
 * sie in einem {@link FGaussianView}.</p>
 *
 * <p>Start: {@code java com.dan.fgaussian.demo.FGaussianDemo [datei.xyz]}</p>
 *
 * @author com.dan.fgaussian
 */
public final class FGaussianDemo {

    private final FGaussianView view = new FGaussianView();
    private final FHeightRamp ramp = FHeightRamp.balticCoast();

    private FXyzGridSource source;
    private int stride = 2;
    private float exaggeration = 12f;

    private final JLabel status = new JLabel(" ");

    private FGaussianDemo(FXyzGridSource source, String title) {
        this.source = source;
        buildFrame(title);
        rebuild(true);
    }

    private void buildFrame(String title) {
        JFrame frame = new JFrame("FGaussian - " + title);
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        view.setPreferredSize(new Dimension(1100, 660));
        FOrbitController.install(view);
        frame.add(view, BorderLayout.CENTER);
        frame.add(buildControls(), BorderLayout.SOUTH);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);

        new Timer(300, e -> updateStatus()).start();
    }

    private JPanel buildControls() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 14, 0));

        JComboBox<String> lod = new JComboBox<>(new String[] {
            "Detailstufe 1:1", "Detailstufe 1:2", "Detailstufe 1:4", "Detailstufe 1:8"
        });
        lod.setSelectedIndex(1);
        lod.addActionListener(e -> {
            stride = 1 << lod.getSelectedIndex();
            rebuild(false);
        });
        left.add(lod);

        left.add(new JLabel("Ueberhoehung"));
        JSlider exag = new JSlider(1, 40, (int) exaggeration);
        exag.setPreferredSize(new Dimension(170, exag.getPreferredSize().height));
        exag.addChangeListener(e -> {
            exaggeration = exag.getValue();
            rebuild(false);
        });
        left.add(exag);

        JCheckBox spin = new JCheckBox("Drehen");
        spin.addActionListener(e -> view.setSpinning(spin.isSelected()));
        left.add(spin);

        panel.add(left, BorderLayout.WEST);

        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        status.setForeground(new Color(90, 100, 108));
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        right.add(status);
        right.add(Box.createHorizontalStrut(4));
        panel.add(right, BorderLayout.EAST);

        return panel;
    }

    private void rebuild(boolean frameCamera) {
        FSplatCloud cloud = source.buildCloud(stride, exaggeration, ramp);
        if (frameCamera) {
            view.setCloud(cloud);
            view.getCamera().pitch = 0.38f;
            view.getCamera().yaw = 0.75f;
        } else {
            view.updateCloud(cloud);
        }
        view.buildHierarchyAsync(source.cellSize() * stride * 2.0, 6);
        view.repaint();
    }

    private void updateStatus() {
        FSplatCloud cloud = view.getCloud();
        if (cloud == null) {
            return;
        }
        status.setText(String.format(
                "%,d Splats  |  %,d sichtbar  |  %.1f ms",
                cloud.count,
                view.getRenderer().lastVisible,
                view.getLastFrameMillis()));
    }

    // ------------------------------------------------------------------

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            File file = args.length > 0 ? new File(args[0]) : findGridFile();
            if (file == null) {
                JFileChooser chooser = new JFileChooser(".");
                chooser.setDialogTitle("XYZ-Rasterdatei waehlen");
                chooser.setFileFilter(new FileNameExtensionFilter("XYZ-Raster (*.xyz)", "xyz"));
                if (chooser.showOpenDialog(null) != JFileChooser.APPROVE_OPTION) {
                    return;
                }
                file = chooser.getSelectedFile();
            }
            try {
                FXyzGridSource source = FXyzGridSource.read(file);
                System.out.printf("Raster %d x %d, Zellweite %.2f m, Hoehen %.2f .. %.2f m%n",
                        source.gridWidth(), source.gridHeight(), source.cellSize(),
                        source.minHeight(), source.maxHeight());
                new FGaussianDemo(source, file.getName());
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(null,
                        "Datei konnte nicht gelesen werden:\n" + ex.getMessage(),
                        "FGaussian", JOptionPane.ERROR_MESSAGE);
            }
        });
    }

    /**
     * Sucht die groesste .xyz-Datei unterhalb des Arbeitsverzeichnisses.
     * Paketweit sichtbar, damit auch {@link FGaussianOracleDemo} ohne Argument
     * startet - in der IDE wird eine Hauptklasse ohne Parameter aufgerufen.
     */
    static File findGridFile() {
        Path root = Paths.get(".").toAbsolutePath().normalize();
        try (Stream<Path> paths = Files.walk(root, 4)) {
            Optional<Path> best = paths
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".xyz"))
                    .max(Comparator.comparingLong(p -> {
                        try {
                            return Files.size(p);
                        } catch (IOException e) {
                            return 0L;
                        }
                    }));
            return best.map(Path::toFile).orElse(null);
        } catch (IOException e) {
            return null;
        }
    }
}
