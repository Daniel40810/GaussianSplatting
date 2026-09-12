package com.dan.fgaussian.core;

/**
 * Quaternion-Hilfsfunktionen ohne eigene Objekte: alle Methoden schreiben in
 * uebergebene Arrays, damit im Aufbau von Millionen Splats kein Muell entsteht.
 *
 * <p>Konvention durchgaengig {x, y, z, w} mit w als Realteil.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FQuat {

    private FQuat() {
    }

    /** Einheitsquaternion (keine Drehung). */
    public static void identity(float[] out) {
        out[0] = 0f; out[1] = 0f; out[2] = 0f; out[3] = 1f;
    }

    /**
     * Drehung, die die lokale z-Achse (0,0,1) auf die angegebene Richtung legt.
     * Genau das braucht ein Terrain-Splat: die duenne dritte Achse zeigt in
     * Richtung der Gelaendenormale, die beiden dicken liegen in der Hangebene.
     *
     * @param nx,ny,nz Zielrichtung, muss nicht normiert sein
     */
    public static void fromUnitZTo(float nx, float ny, float nz, float[] out) {
        float len = (float) Math.sqrt(nx * nx + ny * ny + nz * nz);
        if (len < 1e-12f) {
            identity(out);
            return;
        }
        nx /= len; ny /= len; nz /= len;

        // Achse = z x n, Winkel ueber das Skalarprodukt.
        float dot = nz;
        if (dot > 0.9999999f) {
            identity(out);
            return;
        }
        if (dot < -0.9999999f) {
            // 180 Grad: irgendeine Achse senkrecht zu z.
            out[0] = 1f; out[1] = 0f; out[2] = 0f; out[3] = 0f;
            return;
        }
        float ax = -ny;   // z x n = (0,0,1) x (nx,ny,nz)
        float ay = nx;
        float az = 0f;

        // Halbwinkelform ohne trigonometrische Funktionen.
        float w = 1f + dot;
        float inv = 1f / (float) Math.sqrt(2f * w);
        out[0] = ax * inv;
        out[1] = ay * inv;
        out[2] = az * inv;
        out[3] = w * inv;
    }

    /** Drehung um eine beliebige Achse. */
    public static void fromAxisAngle(float ax, float ay, float az, float angleRad, float[] out) {
        float len = (float) Math.sqrt(ax * ax + ay * ay + az * az);
        if (len < 1e-12f) {
            identity(out);
            return;
        }
        float s = (float) Math.sin(angleRad * 0.5f) / len;
        out[0] = ax * s;
        out[1] = ay * s;
        out[2] = az * s;
        out[3] = (float) Math.cos(angleRad * 0.5f);
    }

    /**
     * Schreibt die Rotationsmatrix zeilenweise nach {@code m} (Laenge 9).
     * Das Quaternion wird dabei normiert, damit aus der Datenbank gelesene
     * Werte mit Rundungsfehlern keine Skalierung einschleppen.
     */
    public static void toMatrix3(float x, float y, float z, float w, float[] m) {
        float n = x * x + y * y + z * z + w * w;
        float s = n < 1e-12f ? 0f : 2f / n;

        float xs = x * s,  ys = y * s,  zs = z * s;
        float wx = w * xs, wy = w * ys, wz = w * zs;
        float xx = x * xs, xy = x * ys, xz = x * zs;
        float yy = y * ys, yz = y * zs, zz = z * zs;

        m[0] = 1f - (yy + zz);  m[1] = xy - wz;         m[2] = xz + wy;
        m[3] = xy + wz;         m[4] = 1f - (xx + zz);  m[5] = yz - wx;
        m[6] = xz - wy;         m[7] = yz + wx;         m[8] = 1f - (xx + yy);
    }
}
