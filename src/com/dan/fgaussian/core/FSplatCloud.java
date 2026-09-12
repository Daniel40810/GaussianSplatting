package com.dan.fgaussian.core;

/**
 * Eine Wolke aus 3D-Gauss-Verteilungen ("Splats") in Struct-of-Arrays-Anordnung.
 *
 * <p>Bewusst keine Splat-Objekte: bei sechsstelligen Splat-Zahlen kosten
 * Objektheader und Referenzen mehr als die Nutzdaten selbst. Parallele
 * {@code float[]}-Arrays liegen zusammenhaengend im Speicher, lassen sich
 * blockweise aus einem BLOB fuellen und werden vom JIT vektorisiert.</p>
 *
 * <p>Der Attributsatz ist bewusst der volle 3DGS-Satz (Position, drei Skalen,
 * Quaternion, Farbe, Opazitaet, optional Spherical-Harmonics-Koeffizienten),
 * damit ein spaeterer PLY-Import aus einer Foto-Rekonstruktion in dieselbe
 * Struktur laedt und derselbe Renderer sie zeichnet.</p>
 *
 * <p>Koordinatensystem der Szene: x = Ost, y = Hoehe, z = Sued (also -Nord),
 * Einheit Meter. Die Kachelmitte liegt im Ursprung.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSplatCloud {

    /** Anzahl der Koeffizienten je Farbkanal fuer SH-Grad 0..3. */
    private static final int[] SH_COEFFS = {1, 4, 9, 16};

    /** Normierungsfaktor der reellen SH-Basisfunktion Y00. */
    private static final float SH_C0 = 0.28209479177387814f;

    /** Maximale Anzahl Splats, fuer die Speicher reserviert ist. */
    public final int capacity;

    /** Tatsaechlich belegte Splats, Indizes 0..count-1 sind gueltig. */
    public int count;

    /** Mittelpunkt, Meter. */
    public final float[] px, py, pz;

    /** Standardabweichungen entlang der drei lokalen Achsen, Meter. */
    public final float[] sx, sy, sz;

    /** Rotation der lokalen Achsen als Einheitsquaternion. */
    public final float[] qx, qy, qz, qw;

    /** Basisfarbe, 0..1 linear. */
    public final float[] cr, cg, cb;

    /** Deckkraft im Splat-Zentrum, 0..1. */
    public final float[] opacity;

    /**
     * Optionale SH-Koeffizienten, Layout [splat][koeffizient][kanal],
     * flach als {@code sh[(i * coeffCount + k) * 3 + c]}. Null, solange die
     * Wolke keine richtungsabhaengige Farbe traegt.
     */
    public float[] sh;

    /** SH-Grad 0..3; 0 bedeutet: Farbe steckt vollstaendig in cr/cg/cb. */
    public int shDegree;

    /** Achsenparallele Huelle, gueltig nach {@link #computeBounds()}. */
    public float minX, minY, minZ, maxX, maxY, maxZ;

    public FSplatCloud(int capacity) {
        if (capacity < 0) {
            throw new IllegalArgumentException("capacity < 0: " + capacity);
        }
        this.capacity = capacity;
        px = new float[capacity];
        py = new float[capacity];
        pz = new float[capacity];
        sx = new float[capacity];
        sy = new float[capacity];
        sz = new float[capacity];
        qx = new float[capacity];
        qy = new float[capacity];
        qz = new float[capacity];
        qw = new float[capacity];
        cr = new float[capacity];
        cg = new float[capacity];
        cb = new float[capacity];
        opacity = new float[capacity];
    }

    /**
     * Haengt einen Splat an und liefert seinen Index.
     *
     * @param q Quaternion als {x, y, z, w}; wird beim Einfuegen normalisiert
     */
    public int add(float x, float y, float z,
                   float scaleU, float scaleV, float scaleN,
                   float[] q,
                   float r, float g, float b, float alpha) {
        if (count >= capacity) {
            throw new IllegalStateException("Splat-Wolke ist voll: " + capacity);
        }
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requirePositiveFinite(scaleU, "scaleU");
        requirePositiveFinite(scaleV, "scaleV");
        requirePositiveFinite(scaleN, "scaleN");
        if (q == null || q.length < 4) {
            throw new IllegalArgumentException("Quaternion muss vier Werte enthalten");
        }
        for (int k = 0; k < 4; k++) {
            requireFinite(q[k], "Quaternion[" + k + "]");
        }
        float qlen = (float) Math.sqrt(q[0] * q[0] + q[1] * q[1]
                + q[2] * q[2] + q[3] * q[3]);
        if (!(qlen > 1e-6f) || !Float.isFinite(qlen)) {
            throw new IllegalArgumentException("Quaternion ist unbrauchbar: Norm " + qlen);
        }
        float qInv = 1f / qlen;
        requireUnitInterval(r, "r");
        requireUnitInterval(g, "g");
        requireUnitInterval(b, "b");
        requireUnitInterval(alpha, "alpha");
        int i = count++;
        px[i] = x;  py[i] = y;  pz[i] = z;
        sx[i] = scaleU;  sy[i] = scaleV;  sz[i] = scaleN;
        // Kleine Rundungsfehler aus den Geometriequellen werden hier entfernt;
        // eine Rotation muss im Speicher immer als Einheitsquaternion liegen.
        qx[i] = q[0] * qInv;  qy[i] = q[1] * qInv;
        qz[i] = q[2] * qInv;  qw[i] = q[3] * qInv;
        cr[i] = r;  cg[i] = g;  cb[i] = b;
        opacity[i] = alpha;
        return i;
    }

    private static void requireFinite(float value, String name) {
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException(name + " ist nicht endlich: " + value);
        }
    }

    private static void requirePositiveFinite(float value, String name) {
        requireFinite(value, name);
        if (value <= 0f) {
            throw new IllegalArgumentException(name + " muss > 0 sein: " + value);
        }
    }

    private static void requireUnitInterval(float value, String name) {
        requireFinite(value, name);
        if (value < 0f || value > 1f) {
            throw new IllegalArgumentException(name + " ausserhalb 0..1: " + value);
        }
    }

    /** Reserviert den SH-Block fuer den angegebenen Grad (0..3). */
    public void allocateSh(int degree) {
        if (degree < 0 || degree > 3) {
            throw new IllegalArgumentException("SH-Grad ausserhalb 0..3: " + degree);
        }
        this.shDegree = degree;
        this.sh = degree == 0 ? null : new float[capacity * SH_COEFFS[degree] * 3];
    }

    /** Koeffizienten je Farbkanal fuer den aktuellen SH-Grad. */
    public int shCoeffCount() {
        return SH_COEFFS[shDegree];
    }

    /**
     * Fuegt mehrere Wolken zu einer zusammen - etwa Gelaende und aufgehende
     * Bauten. Traegt eine Quelle SH-Daten, bekommt die Ergebniswolke den
     * hoechsten vorkommenden Grad. Quellen mit kleinerem Grad werden mit
     * Nullen aufgefuellt; reine RGB-Quellen werden als richtungsunabhaengiger
     * Grad-0-Anteil uebernommen.
     */
    public static FSplatCloud concat(FSplatCloud... parts) {
        int total = 0;
        int targetDegree = 0;
        for (FSplatCloud p : parts) {
            if (p != null) {
                total += p.count;
                if (p.shDegree > 0 && p.sh != null) {
                    targetDegree = Math.max(targetDegree, p.shDegree);
                }
            }
        }
        FSplatCloud out = new FSplatCloud(total);
        if (targetDegree > 0) {
            out.allocateSh(targetDegree);
        }
        for (FSplatCloud p : parts) {
            if (p == null || p.count == 0) {
                continue;
            }
            int n = p.count;
            int at = out.count;
            System.arraycopy(p.px, 0, out.px, at, n);
            System.arraycopy(p.py, 0, out.py, at, n);
            System.arraycopy(p.pz, 0, out.pz, at, n);
            System.arraycopy(p.sx, 0, out.sx, at, n);
            System.arraycopy(p.sy, 0, out.sy, at, n);
            System.arraycopy(p.sz, 0, out.sz, at, n);
            System.arraycopy(p.qx, 0, out.qx, at, n);
            System.arraycopy(p.qy, 0, out.qy, at, n);
            System.arraycopy(p.qz, 0, out.qz, at, n);
            System.arraycopy(p.qw, 0, out.qw, at, n);
            System.arraycopy(p.cr, 0, out.cr, at, n);
            System.arraycopy(p.cg, 0, out.cg, at, n);
            System.arraycopy(p.cb, 0, out.cb, at, n);
            System.arraycopy(p.opacity, 0, out.opacity, at, n);

            if (targetDegree > 0) {
                copyOrPromoteSh(p, out, at);
            }
            out.count += n;
        }
        out.computeBounds();
        return out;
    }

    /** Kopiert vorhandene SH-Koeffizienten oder erzeugt einen Grad-0-Anteil. */
    private static void copyOrPromoteSh(FSplatCloud source, FSplatCloud target, int targetOffset) {
        int targetCoeffs = target.shCoeffCount();
        if (source.shDegree > 0 && source.sh != null) {
            int sourceCoeffs = source.shCoeffCount();
            int copyFloats = Math.min(sourceCoeffs, targetCoeffs) * 3;
            for (int i = 0; i < source.count; i++) {
                System.arraycopy(source.sh, i * sourceCoeffs * 3,
                        target.sh, (targetOffset + i) * targetCoeffs * 3,
                        copyFloats);
            }
            return;
        }

        for (int i = 0; i < source.count; i++) {
            int base = (targetOffset + i) * targetCoeffs * 3;
            target.sh[base] = baseColorToDc(source.cr[i]);
            target.sh[base + 1] = baseColorToDc(source.cg[i]);
            target.sh[base + 2] = baseColorToDc(source.cb[i]);
        }
    }

    private static float baseColorToDc(float color) {
        return (color - 0.5f) / SH_C0;
    }

    /** Berechnet die achsenparallele Huelle neu. */
    public void computeBounds() {
        if (count == 0) {
            minX = minY = minZ = maxX = maxY = maxZ = 0f;
            return;
        }
        float ax = Float.MAX_VALUE, ay = Float.MAX_VALUE, az = Float.MAX_VALUE;
        float bx = -Float.MAX_VALUE, by = -Float.MAX_VALUE, bz = -Float.MAX_VALUE;
        for (int i = 0; i < count; i++) {
            if (px[i] < ax) ax = px[i];
            if (py[i] < ay) ay = py[i];
            if (pz[i] < az) az = pz[i];
            if (px[i] > bx) bx = px[i];
            if (py[i] > by) by = py[i];
            if (pz[i] > bz) bz = pz[i];
        }
        minX = ax; minY = ay; minZ = az;
        maxX = bx; maxY = by; maxZ = bz;
    }

    /** Mittelpunkt der Huelle als {x, y, z}. */
    public float[] center() {
        return new float[] {
            (minX + maxX) * 0.5f,
            (minY + maxY) * 0.5f,
            (minZ + maxZ) * 0.5f
        };
    }

    /** Halbe Raumdiagonale der Huelle. */
    public float boundingRadius() {
        float dx = maxX - minX, dy = maxY - minY, dz = maxZ - minZ;
        return (float) Math.sqrt(dx * dx + dy * dy + dz * dz) * 0.5f;
    }
}
