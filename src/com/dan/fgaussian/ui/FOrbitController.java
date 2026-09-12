package com.dan.fgaussian.ui;

import com.dan.fgaussian.render.FCamera;

import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;

/**
 * Maussteuerung fuer {@link FGaussianView}.
 *
 * <p>Links ziehen dreht, rechts oder mit Umschalttaste ziehen verschiebt den
 * Zielpunkt in der Bildebene, das Mausrad zoomt. Waehrend des Ziehens schaltet
 * die Ansicht auf die grobe Renderstufe und faellt kurz nach dem Loslassen
 * wieder auf die volle zurueck.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FOrbitController extends MouseAdapter {

    private final FGaussianView view;
    private final Timer settleTimer;

    private int lastX, lastY;
    private boolean panning;

    private float rotateSpeed = 0.006f;
    private float zoomSpeed = 0.12f;

    private FOrbitController(FGaussianView view) {
        this.view = view;
        this.settleTimer = new Timer(140, e -> view.setInteracting(false));
        this.settleTimer.setRepeats(false);
    }

    /** Haengt einen Controller an die Ansicht und liefert ihn zurueck. */
    public static FOrbitController install(FGaussianView view) {
        FOrbitController c = new FOrbitController(view);
        view.addMouseListener(c);
        view.addMouseMotionListener(c);
        view.addMouseWheelListener(c);
        return c;
    }

    public void setRotateSpeed(float value) {
        this.rotateSpeed = value;
    }

    public void setZoomSpeed(float value) {
        this.zoomSpeed = value;
    }

    @Override
    public void mousePressed(MouseEvent e) {
        lastX = e.getX();
        lastY = e.getY();
        panning = SwingUtilities.isRightMouseButton(e) || e.isShiftDown();
        view.requestFocusInWindow();
        view.setInteracting(true);
        settleTimer.stop();
    }

    @Override
    public void mouseReleased(MouseEvent e) {
        settleTimer.restart();
    }

    @Override
    public void mouseDragged(MouseEvent e) {
        int dx = e.getX() - lastX;
        int dy = e.getY() - lastY;
        lastX = e.getX();
        lastY = e.getY();
        if (dx == 0 && dy == 0) {
            return;
        }
        view.setInteracting(true);

        FCamera cam = view.getCamera();
        if (panning) {
            pan(cam, dx, dy);
        } else {
            cam.yaw -= dx * rotateSpeed;
            cam.pitch += dy * rotateSpeed;
            cam.clampPitch();
        }
        view.invalidateRender();
        view.repaint();
    }

    @Override
    public void mouseWheelMoved(MouseWheelEvent e) {
        FCamera cam = view.getCamera();
        float factor = (float) Math.exp(e.getPreciseWheelRotation() * zoomSpeed);
        cam.distance = clamp(cam.distance * factor, 5f, 200_000f);
        view.setInteracting(true);
        view.invalidateRender();
        settleTimer.restart();
        view.repaint();
    }

    /**
     * Verschiebt den Zielpunkt entlang der Bildachsen. Der Massstab richtet
     * sich nach dem Abstand, damit ein Mauspixel aus der Naehe weniger Meter
     * bewegt als aus der Ferne.
     */
    private void pan(FCamera cam, int dx, int dy) {
        cam.update();
        float metersPerPixel = 2f * cam.distance
                * (float) Math.tan(cam.fovY * 0.5) / Math.max(1, view.getHeight());

        float rx = cam.view[0], ry = cam.view[1], rz = cam.view[2];
        float ux = cam.view[3], uy = cam.view[4], uz = cam.view[5];

        float mx = -dx * metersPerPixel;
        float my = -dy * metersPerPixel;

        cam.targetX += rx * mx + ux * my;
        cam.targetY += ry * mx + uy * my;
        cam.targetZ += rz * mx + uz * my;
    }

    private static float clamp(float value, float lo, float hi) {
        return value < lo ? lo : (value > hi ? hi : value);
    }
}
