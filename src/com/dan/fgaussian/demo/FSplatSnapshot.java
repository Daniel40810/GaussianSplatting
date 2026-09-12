package com.dan.fgaussian.demo;

import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.render.FCamera;
import com.dan.fgaussian.render.FSplatRenderer;
import com.dan.fgaussian.shade.FHeightRamp;
import com.dan.fgaussian.source.FXyzGridSource;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.File;
import java.io.IOException;

/**
 * Rendert eine XYZ-Kachel ohne Fenster in eine PNG-Datei und misst dabei die
 * Bildzeit.
 *
 * <p>Nuetzlich zum Nachmessen nach Aenderungen am Rasterisierer: kein EDT,
 * keine Fensterverwaltung, reproduzierbare Kamera. Laeuft auch headless.</p>
 *
 * <p>Aufruf:
 * {@code java com.dan.fgaussian.demo.FSplatSnapshot datei.xyz ausgabe.png [stride] [ueberhoehung]}</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSplatSnapshot {

    private static final int WIDTH = 1280;
    private static final int HEIGHT = 720;
    private static final int WARMUP = 5;
    private static final int RUNS = 10;

    private FSplatSnapshot() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 2) {
            System.out.println("Aufruf: FSplatSnapshot <datei.xyz> <ausgabe.png> [stride] [ueberhoehung]");
            return;
        }
        int stride = args.length > 2 ? Integer.parseInt(args[2]) : 1;
        float exaggeration = args.length > 3 ? Float.parseFloat(args[3]) : 12f;

        FXyzGridSource source = FXyzGridSource.read(new File(args[0]));
        System.out.printf("Raster %d x %d, Zellweite %.2f m, Hoehen %.2f .. %.2f m%n",
                source.gridWidth(), source.gridHeight(), source.cellSize(),
                source.minHeight(), source.maxHeight());

        long t0 = System.nanoTime();
        FSplatCloud cloud = source.buildCloud(stride, exaggeration, FHeightRamp.balticCoast());
        System.out.printf("Wolke: %,d Splats in %.1f ms%n",
                cloud.count, (System.nanoTime() - t0) / 1e6);

        BufferedImage image = new BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_INT_RGB);
        int[] pixels = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();

        FCamera camera = new FCamera();
        float[] c = cloud.center();
        camera.frame(c[0], c[1], c[2], cloud.boundingRadius());
        camera.pitch = 0.38f;
        camera.yaw = 0.75f;

        FSplatRenderer renderer = new FSplatRenderer();
        for (int i = 0; i < WARMUP; i++) {
            renderer.render(cloud, camera, pixels, WIDTH, HEIGHT);
        }
        long t1 = System.nanoTime();
        for (int i = 0; i < RUNS; i++) {
            renderer.render(cloud, camera, pixels, WIDTH, HEIGHT);
        }
        System.out.printf("Render: %.1f ms/Bild bei %d x %d, %,d sichtbar, %,d Kacheleintraege, %d Kerne%n",
                (System.nanoTime() - t1) / 1e6 / RUNS, WIDTH, HEIGHT,
                renderer.lastVisible, renderer.lastTileEntries,
                Runtime.getRuntime().availableProcessors());

        File out = new File(args[1]);
        ImageIO.write(image, "png", out);
        System.out.println("Geschrieben: " + out.getAbsolutePath());
    }
}
