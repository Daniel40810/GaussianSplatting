package com.dan.fgaussian.render;

/**
 * Orbit-Kamera ueber einem Zielpunkt.
 *
 * <p>Die Viewmatrix liefert direkt Bildkonvention: x nach rechts, y nach
 * <em>unten</em>, z nach vorn. Damit hat die perspektivische Abbildung
 * u = cx + f*x/z, v = cy + f*y/z kein Vorzeichen mehr, und die Jacobimatrix
 * in {@link FSplatRenderer} bleibt frei von Sonderfaellen.</p>
 *
 * <p>Weltsystem: y ist oben.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FCamera {

    /** Zielpunkt, um den gekreist wird. */
    public float targetX, targetY, targetZ;

    /** Abstand des Auges vom Zielpunkt, Meter. */
    public float distance = 1500f;

    /** Drehung um die Hochachse, Radiant. */
    public float yaw = 0.7f;

    /** Neigung ueber die Horizontale, Radiant, sinnvoll 0.02 .. 1.5. */
    public float pitch = 0.45f;

    /** Vertikaler Oeffnungswinkel, Radiant. */
    public float fovY = (float) Math.toRadians(45.0);

    /** Splats naeher als dieser Abstand werden verworfen. */
    public float nearZ = 1.0f;

    /** Augpunkt in Weltkoordinaten, gueltig nach {@link #update()}. */
    public final float[] eye = new float[3];

    /** Viewrotation zeilenweise: Zeile 0 rechts, Zeile 1 unten, Zeile 2 vorwaerts. */
    public final float[] view = new float[9];

    /** Berechnet Augpunkt und Viewrotation aus yaw/pitch/distance neu. */
    public void update() {
        float cp = (float) Math.cos(pitch);
        float sp = (float) Math.sin(pitch);
        float cy = (float) Math.cos(yaw);
        float sy = (float) Math.sin(yaw);

        eye[0] = targetX + distance * cp * sy;
        eye[1] = targetY + distance * sp;
        eye[2] = targetZ + distance * cp * cy;

        // Blickrichtung
        float fx = targetX - eye[0];
        float fy = targetY - eye[1];
        float fz = targetZ - eye[2];
        float fl = (float) Math.sqrt(fx * fx + fy * fy + fz * fz);
        if (fl < 1e-9f) {
            fx = 0f; fy = 0f; fz = -1f; fl = 1f;
        }
        fx /= fl; fy /= fl; fz /= fl;

        // rechts = forward x weltOben
        float rx = fy * 0f - fz * 1f;
        float ry = fz * 0f - fx * 0f;
        float rz = fx * 1f - fy * 0f;
        float rl = (float) Math.sqrt(rx * rx + ry * ry + rz * rz);
        if (rl < 1e-6f) {
            // Blick genau senkrecht: Ausrichtung aus yaw ableiten.
            rx = cy; ry = 0f; rz = -sy;
            rl = 1f;
        }
        rx /= rl; ry /= rl; rz /= rl;

        // unten = forward x rechts
        float dx = fy * rz - fz * ry;
        float dy = fz * rx - fx * rz;
        float dz = fx * ry - fy * rx;

        view[0] = rx; view[1] = ry; view[2] = rz;
        view[3] = dx; view[4] = dy; view[5] = dz;
        view[6] = fx; view[7] = fy; view[8] = fz;
    }

    /** Brennweite in Pixeln fuer eine Bildhoehe. */
    public float focalLength(int viewportHeight) {
        return (float) (0.5 * viewportHeight / Math.tan(fovY * 0.5));
    }

    /** Setzt Ziel und Abstand so, dass eine Kugel dieses Radius ins Bild passt. */
    public void frame(float cx, float cy, float cz, float radius) {
        targetX = cx;
        targetY = cy;
        targetZ = cz;
        distance = Math.max(1f, radius / (float) Math.tan(fovY * 0.5) * 0.72f);
    }

    /** Begrenzt die Neigung auf einen brauchbaren Bereich. */
    public void clampPitch() {
        float limit = 1.52f;
        if (pitch > limit) pitch = limit;
        if (pitch < 0.02f) pitch = 0.02f;
    }
}
