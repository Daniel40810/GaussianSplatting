package com.dan.fgaussian.ui;

import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.render.FCamera;
import com.dan.fgaussian.render.FSplatHierarchy;
import com.dan.fgaussian.render.FSplatRenderer;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Swing-Komponente, die eine {@link FSplatCloud} anzeigt.
 *
 * <p>Gerendert wird in das {@code int[]} eines {@code TYPE_INT_RGB}-Bildes und
 * mit einem einzigen {@code drawImage} ausgegeben - dasselbe Muster, das sich
 * schon in RayPhong Studio bewaehrt hat.</p>
 *
 * <p>Waehrend einer Mausbewegung senkt {@link #setInteractiveScale(float)} die
 * Renderaufloesung. Das ist wirkungsvoller als jede Mikrooptimierung im
 * Rasterisierer: halbe Kantenlaenge heisst ein Viertel der Pixelarbeit.</p>
 *
 * @author com.dan.fgaussian
 */
public class FGaussianView extends JComponent {

    private static final long serialVersionUID = 1L;

    private transient FSplatCloud cloud;
    private final transient FCamera camera = new FCamera();
    private final transient FSplatRenderer renderer = new FSplatRenderer();

    private transient BufferedImage image;
    private transient ExecutorService renderExecutor;
    private boolean renderPending;
    private long renderRevision;
    private long displayedRevision = -1L;

    private transient FSplatHierarchy hierarchy;
    private float lodThreshold = 1.0f;
    private float interactiveLodThreshold = 4.0f;

    private float renderScale = 1.0f;
    private float interactiveScale = 0.6f;
    private boolean interacting;

    private transient Timer spinTimer;
    private float spinSpeed = 0.28f;

    private long lastFrameNanos;

    public FGaussianView() {
        setPreferredSize(new Dimension(960, 600));
        setOpaque(true);
        setBackground(new Color(10, 15, 20));
        setFocusable(true);
        renderRevision = 1L;
    }

    // ------------------------------------------------------------------
    // Inhalt
    // ------------------------------------------------------------------

    /** Setzt die Wolke und richtet die Kamera darauf aus. */
    public void setCloud(FSplatCloud cloud) {
        this.cloud = cloud;
        this.hierarchy = null;
        if (cloud != null) {
            cloud.computeBounds();
            float[] c = cloud.center();
            camera.frame(c[0], c[1], c[2], cloud.boundingRadius());
        }
        invalidateRender();
        repaint();
    }

    /** Setzt die Wolke, ohne die Kameraposition anzutasten. */
    public void updateCloud(FSplatCloud cloud) {
        this.cloud = cloud;
        this.hierarchy = null;
        invalidateRender();
        repaint();
    }

    public FSplatCloud getCloud() {
        return cloud;
    }

    public FCamera getCamera() {
        return camera;
    }

    public FSplatRenderer getRenderer() {
        return renderer;
    }

    /**
     * Haengt Detailstufen an die Ansicht. Ist eine Hierarchie gesetzt, wird je
     * Bild ein Schnitt durch sie gewaehlt statt die ganze Wolke zu zeichnen.
     * {@code null} schaltet zurueck auf die volle Wolke.
     *
     * <p>Der Gewinn steckt vor allem in der groben Stufe waehrend des Ziehens:
     * der Rasterisierer dieses Pakets ist pixel- und nicht splatbegrenzt, ein
     * Ersatzsplat deckt also dieselbe Flaeche ab wie die Gruppe, die er
     * vertritt. Gespart werden Projektion, Sortierung und Kacheleintraege - was
     * erst bei deutlich gelockerter Fehlerschwelle durchschlaegt.</p>
     */
    public void setHierarchy(FSplatHierarchy hierarchy) {
        this.hierarchy = hierarchy;
        invalidateRender();
        repaint();
    }

    /**
     * Baut eine LOD-Hierarchie im Renderthread auf und haengt sie anschliessend
     * an diese Ansicht. Die Ansicht bleibt waehrenddessen bedienbar.
     */
    public void buildHierarchyAsync(double cellSize, int extraLevels) {
        final FSplatCloud requestedCloud = cloud;
        if (requestedCloud == null || requestedCloud.count < 4096) {
            return;
        }
        ensureRenderExecutor().execute(() -> {
            FSplatHierarchy built = FSplatHierarchy.build(
                    requestedCloud, cellSize, extraLevels);
            javax.swing.SwingUtilities.invokeLater(() -> {
                if (cloud == requestedCloud) {
                    setHierarchy(built);
                }
            });
        });
    }

    public FSplatHierarchy getHierarchy() {
        return hierarchy;
    }

    /** Zulaessiger Lagefehler im Ruhezustand, Pixel. */
    public float getLodThreshold() {
        return lodThreshold;
    }

    public void setLodThreshold(float value) {
        float old = this.lodThreshold;
        this.lodThreshold = Math.max(0.1f, value);
        firePropertyChange("lodThreshold", old, this.lodThreshold);
        invalidateRender();
        repaint();
    }

    /**
     * Zulaessiger Lagefehler waehrend einer Mausbewegung, Pixel. Gemessen an
     * der Niendorfer Szene mit 724 000 Splats: bei 1 px bleibt alles fein und
     * es wird nichts gespart, bei 3 px knapp das Doppelte, bei 4 px das
     * Dreieinhalbfache, ab 6 px sieht man die Zusammenfassung als Raster.
     */
    public float getInteractiveLodThreshold() {
        return interactiveLodThreshold;
    }

    public void setInteractiveLodThreshold(float value) {
        float old = this.interactiveLodThreshold;
        this.interactiveLodThreshold = Math.max(0.1f, value);
        firePropertyChange("interactiveLodThreshold", old, this.interactiveLodThreshold);
        invalidateRender();
        repaint();
    }

    // ------------------------------------------------------------------
    // Eigenschaften
    // ------------------------------------------------------------------

    /** Renderaufloesung im Ruhezustand, 0,25 bis 1,0. */
    public float getRenderScale() {
        return renderScale;
    }

    public void setRenderScale(float value) {
        float old = this.renderScale;
        this.renderScale = clamp(value, 0.25f, 1.0f);
        firePropertyChange("renderScale", old, this.renderScale);
        invalidateRender();
        repaint();
    }

    /** Renderaufloesung waehrend einer Mausbewegung. */
    public float getInteractiveScale() {
        return interactiveScale;
    }

    public void setInteractiveScale(float value) {
        float old = this.interactiveScale;
        this.interactiveScale = clamp(value, 0.2f, 1.0f);
        firePropertyChange("interactiveScale", old, this.interactiveScale);
        invalidateRender();
    }

    /** Schaltet die langsame Eigendrehung. */
    public boolean isSpinning() {
        return spinTimer != null && spinTimer.isRunning();
    }

    public void setSpinning(boolean spinning) {
        boolean old = isSpinning();
        if (spinning) {
            if (spinTimer == null) {
                spinTimer = new Timer(16, e -> {
                    camera.yaw += spinSpeed * 0.016f;
                    invalidateRender();
                    repaint();
                });
            }
            spinTimer.start();
        } else if (spinTimer != null) {
            spinTimer.stop();
        }
        firePropertyChange("spinning", old, spinning);
    }

    /** Drehgeschwindigkeit in Radiant je Sekunde. */
    public float getSpinSpeed() {
        return spinSpeed;
    }

    public void setSpinSpeed(float value) {
        float old = this.spinSpeed;
        this.spinSpeed = value;
        firePropertyChange("spinSpeed", old, value);
    }

    /** Dauer des zuletzt gezeichneten Bildes in Millisekunden. */
    public double getLastFrameMillis() {
        return lastFrameNanos / 1_000_000.0;
    }

    /** Wird vom Orbit-Controller gesetzt, um auf die grobe Stufe zu schalten. */
    public void setInteracting(boolean interacting) {
        if (this.interacting != interacting) {
            this.interacting = interacting;
            invalidateRender();
            repaint();
        }
    }

    /** Markiert die aktuelle Ansicht als veraendert und fordert ein neues Bild an. */
    void invalidateRender() {
        renderRevision++;
        renderer.cancel();
    }

    // ------------------------------------------------------------------
    // Zeichnen
    // ------------------------------------------------------------------

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) {
            return;
        }
        Graphics2D g2 = (Graphics2D) g.create();
        try {
            if (cloud == null || cloud.count == 0) {
                g2.setColor(getBackground());
                g2.fillRect(0, 0, w, h);
                return;
            }

            float scale = interacting ? Math.min(interactiveScale, renderScale) : renderScale;
            int rw = Math.max(16, Math.round(w * scale));
            int rh = Math.max(16, Math.round(h * scale));

            scheduleRender(rw, rh);

            g2.setColor(getBackground());
            g2.fillRect(0, 0, w, h);
            if (image == null) {
                return;
            }

            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                    image.getWidth() == w ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                            : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(image, 0, 0, w, h, null);
        } finally {
            g2.dispose();
        }
    }

    private void scheduleRender(int w, int h) {
        if (renderPending || (displayedRevision == renderRevision && image != null
                && image.getWidth() == w && image.getHeight() == h)) {
            return;
        }
        ensureRenderExecutor();

        final FSplatCloud requestedCloud = cloud;
        final FSplatHierarchy requestedHierarchy = hierarchy;
        final FCamera requestedCamera = copyCamera(camera);
        final boolean requestedInteracting = interacting;
        final float threshold = requestedInteracting
                ? interactiveLodThreshold : lodThreshold;
        final long requestedRevision = renderRevision;
        final int renderWidth = w;
        final int renderHeight = h;
        renderPending = true;

        renderExecutor.execute(() -> {
            long started = System.nanoTime();
            try {
                BufferedImage rendered = new BufferedImage(renderWidth, renderHeight,
                        BufferedImage.TYPE_INT_RGB);
                int[] renderedPixels = ((DataBufferInt) rendered.getRaster()
                        .getDataBuffer()).getData();
                if (requestedHierarchy == null) {
                    renderer.renderCancellable(requestedCloud, null, requestedCloud.count,
                            requestedCamera, renderedPixels, renderWidth, renderHeight);
                } else {
                    int n = requestedHierarchy.select(requestedCamera, renderHeight, threshold);
                    renderer.renderCancellable(requestedHierarchy.cloud(),
                            requestedHierarchy.selection(), n, requestedCamera,
                            renderedPixels, renderWidth, renderHeight);
                }
                long elapsed = System.nanoTime() - started;

                javax.swing.SwingUtilities.invokeLater(() -> {
                    renderPending = false;
                    if (requestedRevision == renderRevision
                            && requestedCloud == cloud
                            && requestedHierarchy == hierarchy) {
                        image = rendered;
                        displayedRevision = requestedRevision;
                        lastFrameNanos = elapsed;
                    }
                    repaint();
                });
            } catch (Throwable failure) {
                javax.swing.SwingUtilities.invokeLater(() -> {
                    renderPending = false;
                    repaint();
                });
            }
        });
    }

    private ExecutorService ensureRenderExecutor() {
        if (renderExecutor == null || renderExecutor.isShutdown()) {
            ThreadFactory factory = runnable -> {
                Thread thread = new Thread(runnable, "fgaussian-render");
                thread.setDaemon(true);
                return thread;
            };
            renderExecutor = Executors.newSingleThreadExecutor(factory);
        }
        return renderExecutor;
    }

    private static FCamera copyCamera(FCamera source) {
        FCamera copy = new FCamera();
        copy.targetX = source.targetX;
        copy.targetY = source.targetY;
        copy.targetZ = source.targetZ;
        copy.distance = source.distance;
        copy.yaw = source.yaw;
        copy.pitch = source.pitch;
        copy.fovY = source.fovY;
        copy.nearZ = source.nearZ;
        return copy;
    }

    @Override
    public void removeNotify() {
        if (renderExecutor != null) {
            renderExecutor.shutdownNow();
            renderExecutor = null;
            renderPending = false;
        }
        super.removeNotify();
    }

    private static float clamp(float value, float lo, float hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }
}
