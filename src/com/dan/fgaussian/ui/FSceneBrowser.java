package com.dan.fgaussian.ui;

import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.render.FCamera;
import com.dan.fgaussian.db.FGaussianDAO;
import com.dan.fgaussian.db.FGaussianDAO.Anchor;
import com.dan.fgaussian.db.FGaussianDAO.SceneInfo;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.ListCellRenderer;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GridLayout;
import java.sql.SQLException;
import java.util.List;

/**
 * Szenenbrowser: links die gespeicherten Szenen, rechts die Ansicht.
 *
 * <p>Geladen wird in einem {@link SwingWorker} - bei sechsstelligen Splatzahlen
 * dauert das Lesen der BLOBs lange genug, dass die Oberflaeche sonst haengt.
 * Waehrenddessen bleibt die alte Szene sichtbar, nur die Statuszeile wechselt.</p>
 *
 * @author com.dan.fgaussian
 */
public class FSceneBrowser extends JPanel {

    private static final long serialVersionUID = 1L;

    private final transient FGaussianDAO dao;
    private final DefaultListModel<SceneInfo> model = new DefaultListModel<>();
    private final JList<SceneInfo> list = new JList<>(model);
    private final FGaussianView view = new FGaussianView();
    private final DefaultListModel<Anchor> anchorModel = new DefaultListModel<>();
    private final JList<Anchor> anchorList = new JList<>(anchorModel);
    private transient SceneInfo current;
    private final JLabel status = new JLabel(" ");

    public FSceneBrowser(FGaussianDAO dao) {
        this.dao = dao;
        setLayout(new BorderLayout());

        list.setCellRenderer(new SceneRenderer());
        list.setFixedCellHeight(40);
        list.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                loadSelected();
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setBorder(BorderFactory.createEmptyBorder());

        // Anker der gewaehlten Szene: ein Klick fliegt hin.
        anchorList.setCellRenderer(new AnchorRenderer());
        anchorList.addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                flyToAnchor(anchorList.getSelectedValue());
            }
        });
        JScrollPane anchorScroll = new JScrollPane(anchorList);
        anchorScroll.setBorder(BorderFactory.createTitledBorder("Anker"));

        JSplitPane left = new JSplitPane(JSplitPane.VERTICAL_SPLIT, scroll, anchorScroll);
        left.setDividerLocation(280);
        left.setResizeWeight(0.7);
        left.setPreferredSize(new Dimension(240, 600));
        left.setBorder(BorderFactory.createEmptyBorder());

        view.setPreferredSize(new Dimension(900, 600));
        FOrbitController.install(view);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, view);
        split.setDividerLocation(240);
        split.setResizeWeight(0.0);
        split.setBorder(BorderFactory.createEmptyBorder());
        add(split, BorderLayout.CENTER);

        status.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        status.setForeground(new Color(0x5A646C));
        status.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        add(status, BorderLayout.SOUTH);
    }

    public FGaussianView getView() {
        return view;
    }

    /** Liest die Szenenliste neu und waehlt die erste aus. */
    public void refresh() {
        model.clear();
        try {
            List<SceneInfo> scenes = dao.list();
            for (SceneInfo scene : scenes) {
                model.addElement(scene);
            }
            status.setText(scenes.isEmpty()
                    ? "Keine Szenen gespeichert."
                    : scenes.size() + " Szenen");
            if (!scenes.isEmpty()) {
                list.setSelectedIndex(0);
            }
        } catch (SQLException ex) {
            status.setText("Liste nicht lesbar: " + ex.getMessage());
        }
    }

    private void loadSelected() {
        SceneInfo scene = list.getSelectedValue();
        if (scene == null) {
            return;
        }
        status.setText("Lade \"" + scene.name() + "\" ...");
        list.setEnabled(false);

        new SwingWorker<FSplatCloud, Void>() {
            private long millis;

            @Override
            protected FSplatCloud doInBackground() throws Exception {
                long t0 = System.nanoTime();
                FSplatCloud cloud = dao.load(scene.id());
                millis = (System.nanoTime() - t0) / 1_000_000;
                return cloud;
            }

            @Override
            protected void done() {
                list.setEnabled(true);
                try {
                    FSplatCloud cloud = get();
                    if (cloud == null) {
                        status.setText("Szene " + scene.id() + " ist leer.");
                        return;
                    }
                    view.setCloud(cloud);
                    view.buildHierarchyAsync(1.0, 6);
                    view.getCamera().pitch = 0.38f;
                    view.getCamera().yaw = 0.75f;
                    view.repaint();
                    current = scene;
                    loadAnchors(scene);
                    status.setText(String.format(
                            "%s  |  %,d Splats  |  geladen in %d ms  |  Ueberhoehung %.0f  |  Ursprung %.0f / %.0f",
                            scene.name(), cloud.count, millis, scene.exaggeration(),
                            scene.centreEast(), scene.centreNorth()));
                } catch (Exception ex) {
                    status.setText("Fehler beim Laden: " + ex.getMessage());
                }
            }
        }.execute();
    }

    private void loadAnchors(SceneInfo scene) {
        anchorModel.clear();
        try {
            for (Anchor anchor : dao.loadAnchors(scene.id())) {
                anchorModel.addElement(anchor);
            }
        } catch (SQLException ex) {
            status.setText("Anker nicht lesbar: " + ex.getMessage());
        }
    }

    /**
     * Setzt den Kamerazielpunkt auf einen Anker. Die Anker stehen in
     * UTM-Absolutkoordinaten, die Szene dagegen um ihren Ursprung zentriert -
     * umgerechnet wird ueber CENTRE_EAST und CENTRE_NORTH der Szene, und die
     * Nordrichtung kehrt sich dabei um, weil die Szenenachse z nach Sueden zeigt.
     */
    private void flyToAnchor(Anchor anchor) {
        if (anchor == null || current == null) {
            return;
        }
        FCamera camera = view.getCamera();
        camera.targetX = (float) (anchor.east() - current.centreEast());
        camera.targetZ = -(float) (anchor.north() - current.centreNorth());
        camera.targetY = 10f;
        camera.distance = Math.min(camera.distance, 320f);
        camera.pitch = 0.30f;
        view.repaint();
        status.setText(String.format("%s  |  %.0f / %.0f",
                anchor.label() == null ? "Anker " + anchor.id() : anchor.label(),
                anchor.east(), anchor.north()));
    }

    /** Beschriftung, sonst die Art, sonst die Nummer. */
    private static final class AnchorRenderer extends javax.swing.DefaultListCellRenderer {

        private static final long serialVersionUID = 1L;

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focused) {
            Object text = value;
            if (value instanceof Anchor a) {
                String label = a.label() != null ? a.label()
                        : (a.kind() != null ? a.kind() : "Anker " + a.id());
                text = label;
            }
            return super.getListCellRendererComponent(list, text, index, selected, focused);
        }
    }

    /** Zeigt Name gross, Kennzahlen klein darunter. */
    private static final class SceneRenderer extends JPanel implements ListCellRenderer<SceneInfo> {

        private static final long serialVersionUID = 1L;

        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        SceneRenderer() {
            super(new GridLayout(2, 1));
            setBorder(BorderFactory.createEmptyBorder(4, 10, 4, 8));
            name.setFont(name.getFont().deriveFont(Font.PLAIN, 13f));
            detail.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 10));
            add(name);
            add(detail);
        }

        @Override
        public Component getListCellRendererComponent(JList<? extends SceneInfo> list, SceneInfo value,
                                                      int index, boolean selected, boolean focused) {
            name.setText(value.name());
            detail.setText(String.format("%s  %,d Splats", value.sourceKind(), value.splatCount()));
            setBackground(selected ? list.getSelectionBackground() : list.getBackground());
            Color fg = selected ? list.getSelectionForeground() : list.getForeground();
            name.setForeground(fg);
            detail.setForeground(selected ? fg : new Color(0x7A848C));
            setOpaque(true);
            return this;
        }
    }
}
