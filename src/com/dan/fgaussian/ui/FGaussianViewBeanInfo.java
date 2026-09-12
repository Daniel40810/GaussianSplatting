package com.dan.fgaussian.ui;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.geom.Ellipse2D;
import java.awt.image.BufferedImage;
import java.beans.BeanDescriptor;
import java.beans.IntrospectionException;
import java.beans.PropertyDescriptor;
import java.beans.SimpleBeanInfo;

/**
 * Palette-Beschreibung fuer {@link FGaussianView}.
 *
 * <p>Das Icon wird gemalt statt geladen: drei ueberlappende Gaussellipsen, wie
 * der Rasterisierer sie zeichnet. Ein gemaltes Icon kann bei der
 * JAR-Verpackung nicht verlorengehen und skaliert auf jede Groesse, die
 * NetBeans anfragt.</p>
 *
 * @author com.dan.fgaussian
 */
public class FGaussianViewBeanInfo extends SimpleBeanInfo {

    private static final Color WATER = new Color(0x2E93A0);
    private static final Color SAND  = new Color(0xE3C489);
    private static final Color DUNE  = new Color(0x8AA06B);

    @Override
    public BeanDescriptor getBeanDescriptor() {
        BeanDescriptor descriptor = new BeanDescriptor(FGaussianView.class);
        descriptor.setDisplayName("FGaussianView");
        descriptor.setShortDescription(
                "Zeigt eine Wolke aus 3D-Gauss-Splats: Gelaendemodelle, Oracle-Spatial-Flaechen "
              + "oder eingelesene 3DGS-Rekonstruktionen.");
        return descriptor;
    }

    @Override
    public PropertyDescriptor[] getPropertyDescriptors() {
        try {
            PropertyDescriptor renderScale = property("renderScale",
                    "Renderaufloesung",
                    "Anteil der Komponentengroesse, in dem gerendert wird (0,25 bis 1,0).");

            PropertyDescriptor interactiveScale = property("interactiveScale",
                    "Aufloesung beim Ziehen",
                    "Renderaufloesung waehrend einer Mausbewegung. Halbe Kantenlaenge "
                  + "bedeutet ein Viertel der Pixelarbeit.");

            PropertyDescriptor spinning = new PropertyDescriptor(
                    "spinning", FGaussianView.class, "isSpinning", "setSpinning");
            spinning.setDisplayName("Eigendrehung");
            spinning.setShortDescription("Laesst die Kamera langsam um die Szene kreisen.");

            PropertyDescriptor spinSpeed = property("spinSpeed",
                    "Drehgeschwindigkeit",
                    "Radiant je Sekunde bei eingeschalteter Eigendrehung.");

            PropertyDescriptor lod = property("lodThreshold",
                    "Detailfehler",
                    "Zulaessiger Lagefehler in Pixeln im Ruhezustand, wenn Detailstufen gesetzt sind.");

            PropertyDescriptor lodDrag = property("interactiveLodThreshold",
                    "Detailfehler beim Ziehen",
                    "Zulaessiger Lagefehler in Pixeln waehrend einer Mausbewegung. "
                  + "Ab etwa 6 Pixeln wird die Zusammenfassung als Raster sichtbar.");

            return new PropertyDescriptor[] {
                renderScale, interactiveScale, spinning, spinSpeed, lod, lodDrag
            };
        } catch (IntrospectionException ex) {
            // Lieber die Standardermittlung als gar keine Palette-Eigenschaften.
            return null;
        }
    }

    private static PropertyDescriptor property(String name, String displayName, String description)
            throws IntrospectionException {
        PropertyDescriptor descriptor = new PropertyDescriptor(name, FGaussianView.class);
        descriptor.setDisplayName(displayName);
        descriptor.setShortDescription(description);
        return descriptor;
    }

    @Override
    public Image getIcon(int kind) {
        return switch (kind) {
            case ICON_COLOR_16x16, ICON_MONO_16x16 -> paintIcon(16);
            case ICON_COLOR_32x32, ICON_MONO_32x32 -> paintIcon(32);
            default -> null;
        };
    }

    /** Malt drei perspektivisch gekippte Gaussellipsen mit weichem Abfall. */
    private static Image paintIcon(int size) {
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setComposite(AlphaComposite.SrcOver);

            float unit = size / 16f;
            splat(g, 5.2f * unit,  9.8f * unit, 7.4f * unit, WATER, size);
            splat(g, 10.6f * unit, 7.4f * unit, 6.2f * unit, DUNE,  size);
            splat(g, 8.0f * unit,  5.0f * unit, 5.0f * unit, SAND,  size);
        } finally {
            g.dispose();
        }
        return image;
    }

    private static void splat(Graphics2D g, float cx, float cy, float radius, Color color, int size) {
        // Erst kippen, dann faerben: ein RadialGradientPaint wird von der
        // aktuellen Transformation mitverzerrt. Wird er vor dem translate
        // gesetzt, wandert sein Mittelpunkt mit - und es bleibt nur der
        // ausgefranste Rand der Glocke im Bild.
        AffineTransform old = g.getTransform();
        g.translate(cx, cy);
        g.rotate(-0.32);
        g.scale(1.0, 0.62);

        float r = Math.max(1f, radius);
        g.setPaint(new RadialGradientPaint(
                0f, 0f, r,
                new float[] { 0f, 0.55f, 1f },
                new Color[] {
                    new Color(color.getRed(), color.getGreen(), color.getBlue(), 255),
                    new Color(color.getRed(), color.getGreen(), color.getBlue(), 205),
                    new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)
                }));
        g.fill(new Ellipse2D.Float(-r, -r, r * 2f, r * 2f));
        g.setTransform(old);
    }
}
