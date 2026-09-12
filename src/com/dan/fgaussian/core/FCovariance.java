package com.dan.fgaussian.core;

/**
 * Rechnen mit den 3x3-Kovarianzen der Splats: aus Skalen und Quaternion
 * aufbauen, mehrere zu einer verschmelzen, und wieder in Skalen und Quaternion
 * zerlegen.
 *
 * <p>Die Kovarianz wird durchgaengig als sechs Werte gefuehrt - {@code xx, xy,
 * xz, yy, yz, zz} - denn sie ist symmetrisch, und neun Zahlen zu speichern
 * hiesse, drei davon doppelt zu fuehren.</p>
 *
 * <p><b>Zum Verschmelzen.</b> Eine Gruppe von Gauss-Verteilungen durch eine
 * einzige zu ersetzen ist kein Mitteln der Kovarianzen: Die Ersatzverteilung
 * muss auch die <em>Streuung der Mittelpunkte</em> aufnehmen, sonst wird sie
 * viel zu klein und die Gruppe verschwindet, statt zu einem Fleck zu werden.
 * Richtig ist der Verschiebungssatz</p>
 *
 * <pre>Sigma = Summe w_i * (Sigma_i + d_i * d_i^T) / Summe w_i</pre>
 *
 * <p>mit {@code d_i} als Abstand des Einzelmittelpunkts vom gemeinsamen. Das
 * ist exakt die Kovarianz der Mischverteilung, nicht bloss eine Naeherung.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FCovariance {

    private static final int MAX_SWEEPS = 12;

    private FCovariance() {
    }

    /**
     * Baut die Kovarianz aus drei Skalen und einem Quaternion.
     *
     * @param out sechs Werte: xx, xy, xz, yy, yz, zz
     */
    public static void fromScaleRotation(float sx, float sy, float sz,
                                         float qx, float qy, float qz, float qw,
                                         float[] out) {
        float[] r = new float[9];
        FQuat.toMatrix3(qx, qy, qz, qw, r);

        float m00 = r[0] * sx, m01 = r[1] * sy, m02 = r[2] * sz;
        float m10 = r[3] * sx, m11 = r[4] * sy, m12 = r[5] * sz;
        float m20 = r[6] * sx, m21 = r[7] * sy, m22 = r[8] * sz;

        out[0] = m00 * m00 + m01 * m01 + m02 * m02;
        out[1] = m00 * m10 + m01 * m11 + m02 * m12;
        out[2] = m00 * m20 + m01 * m21 + m02 * m22;
        out[3] = m10 * m10 + m11 * m11 + m12 * m12;
        out[4] = m10 * m20 + m11 * m21 + m12 * m22;
        out[5] = m20 * m20 + m21 * m21 + m22 * m22;
    }

    /**
     * Zerlegt eine Kovarianz in drei Skalen und ein Quaternion - die Umkehrung
     * von {@link #fromScaleRotation}.
     *
     * @param sigma sechs Werte: xx, xy, xz, yy, yz, zz
     * @param scale Ziel der Laenge 3, absteigend sortiert
     * @param quat  Ziel der Laenge 4 als {x, y, z, w}
     */
    public static void toScaleRotation(float[] sigma, float[] scale, float[] quat) {
        float[] values = new float[3];
        float[] vectors = new float[9];
        eigenDecompose(sigma, values, vectors);

        for (int i = 0; i < 3; i++) {
            scale[i] = (float) Math.sqrt(Math.max(0f, values[i]));
        }
        matrixToQuaternion(vectors, quat);
    }

    /**
     * Jacobi-Eigenzerlegung einer symmetrischen 3x3-Matrix.
     *
     * <p>Fuer 3x3 waere auch eine geschlossene Loesung ueber das
     * charakteristische Polynom moeglich; die wird aber bei fast gleichen
     * Eigenwerten - genau der Fall bei nahezu kugelfoermigen Splats - ungenau.
     * Zwoelf Jacobi-Rotationen kosten hier nichts und sind numerisch gutmuetig.</p>
     *
     * @param sigma   Eingabe xx, xy, xz, yy, yz, zz
     * @param values  Eigenwerte, absteigend
     * @param vectors Eigenvektoren spaltenweise, zeilenweise abgelegt
     */
    public static void eigenDecompose(float[] sigma, float[] values, float[] vectors) {
        double[][] a = {
            { sigma[0], sigma[1], sigma[2] },
            { sigma[1], sigma[3], sigma[4] },
            { sigma[2], sigma[4], sigma[5] }
        };
        double[][] v = { { 1, 0, 0 }, { 0, 1, 0 }, { 0, 0, 1 } };

        for (int sweep = 0; sweep < MAX_SWEEPS; sweep++) {
            double off = Math.abs(a[0][1]) + Math.abs(a[0][2]) + Math.abs(a[1][2]);
            if (off < 1e-12) {
                break;
            }
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-15) {
                        continue;
                    }
                    double theta = (a[q][q] - a[p][p]) / (2.0 * a[p][q]);
                    double t = Math.signum(theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1.0));
                    if (theta == 0.0) {
                        t = 1.0;
                    }
                    double c = 1.0 / Math.sqrt(t * t + 1.0);
                    double s = t * c;

                    for (int k = 0; k < 3; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }

        // Absteigend sortieren, Eigenvektoren mitziehen.
        int[] order = { 0, 1, 2 };
        for (int i = 0; i < 2; i++) {
            for (int j = i + 1; j < 3; j++) {
                if (a[order[j]][order[j]] > a[order[i]][order[i]]) {
                    int tmp = order[i];
                    order[i] = order[j];
                    order[j] = tmp;
                }
            }
        }
        for (int i = 0; i < 3; i++) {
            values[i] = (float) a[order[i]][order[i]];
            for (int k = 0; k < 3; k++) {
                vectors[k * 3 + i] = (float) v[k][order[i]];
            }
        }

        // Rechtshaendig halten: sonst beschreibt die Matrix eine Spiegelung,
        // und daraus laesst sich kein Quaternion gewinnen.
        float det = determinant(vectors);
        if (det < 0f) {
            vectors[2] = -vectors[2];
            vectors[5] = -vectors[5];
            vectors[8] = -vectors[8];
        }
    }

    /**
     * Verschmilzt mehrere Splats zu einem. Position, Farbe und Deckkraft werden
     * mit der Deckkraft gewichtet gemittelt, die Kovarianz nach dem
     * Verschiebungssatz zusammengefasst.
     *
     * @param cloud   Quelle
     * @param indices Indizes der zu verschmelzenden Splats
     * @param count   wieviele Eintraege in {@code indices} gelten
     * @param out     Ziel: px, py, pz, sx, sy, sz, qx, qy, qz, qw, r, g, b, a
     */
    public static void merge(FSplatCloud cloud, int[] indices, int count, float[] out) {
        if (count == 1) {
            int i = indices[0];
            out[0] = cloud.px[i];  out[1] = cloud.py[i];  out[2] = cloud.pz[i];
            out[3] = cloud.sx[i];  out[4] = cloud.sy[i];  out[5] = cloud.sz[i];
            out[6] = cloud.qx[i];  out[7] = cloud.qy[i];  out[8] = cloud.qz[i];  out[9] = cloud.qw[i];
            out[10] = cloud.cr[i]; out[11] = cloud.cg[i]; out[12] = cloud.cb[i]; out[13] = cloud.opacity[i];
            return;
        }

        double weight = 0, cx = 0, cy = 0, cz = 0, cr = 0, cg = 0, cb = 0;
        for (int k = 0; k < count; k++) {
            int i = indices[k];
            double w = Math.max(1e-6f, cloud.opacity[i]);
            weight += w;
            cx += w * cloud.px[i];
            cy += w * cloud.py[i];
            cz += w * cloud.pz[i];
            cr += w * cloud.cr[i];
            cg += w * cloud.cg[i];
            cb += w * cloud.cb[i];
        }
        cx /= weight; cy /= weight; cz /= weight;

        double s00 = 0, s01 = 0, s02 = 0, s11 = 0, s12 = 0, s22 = 0;
        float[] sigma = new float[6];
        for (int k = 0; k < count; k++) {
            int i = indices[k];
            double w = Math.max(1e-6f, cloud.opacity[i]);
            fromScaleRotation(cloud.sx[i], cloud.sy[i], cloud.sz[i],
                    cloud.qx[i], cloud.qy[i], cloud.qz[i], cloud.qw[i], sigma);

            double dx = cloud.px[i] - cx;
            double dy = cloud.py[i] - cy;
            double dz = cloud.pz[i] - cz;

            s00 += w * (sigma[0] + dx * dx);
            s01 += w * (sigma[1] + dx * dy);
            s02 += w * (sigma[2] + dx * dz);
            s11 += w * (sigma[3] + dy * dy);
            s12 += w * (sigma[4] + dy * dz);
            s22 += w * (sigma[5] + dz * dz);
        }
        sigma[0] = (float) (s00 / weight);
        sigma[1] = (float) (s01 / weight);
        sigma[2] = (float) (s02 / weight);
        sigma[3] = (float) (s11 / weight);
        sigma[4] = (float) (s12 / weight);
        sigma[5] = (float) (s22 / weight);

        float[] scale = new float[3];
        float[] quat = new float[4];
        toScaleRotation(sigma, scale, quat);

        out[0] = (float) cx;  out[1] = (float) cy;  out[2] = (float) cz;
        out[3] = scale[0];    out[4] = scale[1];    out[5] = scale[2];
        out[6] = quat[0];     out[7] = quat[1];     out[8] = quat[2];  out[9] = quat[3];
        out[10] = (float) (cr / weight);
        out[11] = (float) (cg / weight);
        out[12] = (float) (cb / weight);

        // Deckkraft: die Gruppe deckt zusammen mehr als ihr Mittel, aber
        // weniger als die Summe. Der Mittelwert, leicht angehoben, trifft es
        // in der Praxis am besten und kann nicht ueber eins laufen.
        float mean = (float) (weight / count);
        out[13] = Math.min(1f, mean * 1.15f);
    }

    private static float determinant(float[] m) {
        return m[0] * (m[4] * m[8] - m[5] * m[7])
             - m[1] * (m[3] * m[8] - m[5] * m[6])
             + m[2] * (m[3] * m[7] - m[4] * m[6]);
    }

    /** Rotationsmatrix zu Quaternion, Verfahren nach Shepperd. */
    private static void matrixToQuaternion(float[] m, float[] out) {
        float trace = m[0] + m[4] + m[8];
        if (trace > 0f) {
            float s = (float) Math.sqrt(trace + 1f) * 2f;
            out[3] = 0.25f * s;
            out[0] = (m[7] - m[5]) / s;
            out[1] = (m[2] - m[6]) / s;
            out[2] = (m[3] - m[1]) / s;
        } else if (m[0] > m[4] && m[0] > m[8]) {
            float s = (float) Math.sqrt(1f + m[0] - m[4] - m[8]) * 2f;
            out[3] = (m[7] - m[5]) / s;
            out[0] = 0.25f * s;
            out[1] = (m[1] + m[3]) / s;
            out[2] = (m[2] + m[6]) / s;
        } else if (m[4] > m[8]) {
            float s = (float) Math.sqrt(1f + m[4] - m[0] - m[8]) * 2f;
            out[3] = (m[2] - m[6]) / s;
            out[0] = (m[1] + m[3]) / s;
            out[1] = 0.25f * s;
            out[2] = (m[5] + m[7]) / s;
        } else {
            float s = (float) Math.sqrt(1f + m[8] - m[0] - m[4]) * 2f;
            out[3] = (m[3] - m[1]) / s;
            out[0] = (m[2] + m[6]) / s;
            out[1] = (m[5] + m[7]) / s;
            out[2] = 0.25f * s;
        }
        float len = (float) Math.sqrt(out[0] * out[0] + out[1] * out[1] + out[2] * out[2] + out[3] * out[3]);
        if (len > 1e-12f) {
            out[0] /= len; out[1] /= len; out[2] /= len; out[3] /= len;
        } else {
            FQuat.identity(out);
        }
    }
}
