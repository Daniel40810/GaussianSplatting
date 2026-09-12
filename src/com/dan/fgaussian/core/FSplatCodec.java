package com.dan.fgaussian.core;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Wandelt eine {@link FSplatCloud} blockweise in Bytefolgen um und zurueck.
 *
 * <p>Bewusst ohne jede Datenbankabhaengigkeit: dieselbe Kodierung dient zum
 * Speichern in einem BLOB, zum Schreiben einer Datei und zum Testen. Der
 * Datenbankzugriff darf sich um SQL kuemmern und nicht um Bytereihenfolgen.</p>
 *
 * <p>Format je Block: dicht gepackte {@code float32} in <em>Little Endian</em>,
 * Splat fuer Splat, innerhalb eines Splats in der Reihenfolge der
 * Blockkomponenten. Little Endian, weil die Daten auf x86 erzeugt und gelesen
 * werden und ein BLOB ohnehin nicht menschenlesbar ist - Netzwerkreihenfolge
 * waere hier nur unnoetige Byteschieberei.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSplatCodec {

    /** Ein Attributblock der Wolke. */
    public enum Block {

        /** Mittelpunkt: x, y, z. */
        POS(3),

        /** Standardabweichungen: drei Achsen. */
        SCALE(3),

        /** Rotationsquaternion: x, y, z, w. */
        ROT(4),

        /** Farbe und Deckkraft: r, g, b, a. */
        COL(4);

        private final int components;

        Block(int components) {
            this.components = components;
        }

        /** Anzahl float-Werte je Splat. */
        public int components() {
            return components;
        }

        /** Bytes, die dieser Block fuer die angegebene Splatzahl belegt. */
        public int byteSize(int count) {
            return count * components * Float.BYTES;
        }
    }

    private FSplatCodec() {
    }

    /** Kodiert einen Block der Wolke. */
    public static byte[] encode(FSplatCloud cloud, Block block) {
        return encode(cloud, block, 0, cloud.count);
    }

    /** Kodiert einen Bereich eines Blocks fuer grosse Szenen. */
    public static byte[] encode(FSplatCloud cloud, Block block, int offset, int count) {
        checkEncodeRange(cloud, offset, count);
        ByteBuffer buffer = ByteBuffer.allocate(block.byteSize(count)).order(ByteOrder.LITTLE_ENDIAN);
        FloatBuffer f = buffer.asFloatBuffer();

        switch (block) {
            case POS -> {
                for (int i = offset; i < offset + count; i++) {
                    f.put(cloud.px[i]).put(cloud.py[i]).put(cloud.pz[i]);
                }
            }
            case SCALE -> {
                for (int i = offset; i < offset + count; i++) {
                    f.put(cloud.sx[i]).put(cloud.sy[i]).put(cloud.sz[i]);
                }
            }
            case ROT -> {
                for (int i = offset; i < offset + count; i++) {
                    f.put(cloud.qx[i]).put(cloud.qy[i]).put(cloud.qz[i]).put(cloud.qw[i]);
                }
            }
            case COL -> {
                for (int i = offset; i < offset + count; i++) {
                    f.put(cloud.cr[i]).put(cloud.cg[i]).put(cloud.cb[i]).put(cloud.opacity[i]);
                }
            }
        }
        return buffer.array();
    }

    /**
     * Schreibt einen kodierten Block in die Wolke. Die Wolke muss gross genug
     * sein; {@code count} wird nicht veraendert, damit mehrere Bloecke
     * nacheinander gelesen werden koennen.
     *
     * @throws IllegalArgumentException wenn die Bytelaenge nicht zu Blockbreite
     *                                  und Splatzahl passt
     */
    public static void decode(byte[] data, FSplatCloud cloud, Block block, int count) {
        decode(data, cloud, block, 0, count);
    }

    /** Liest einen Blockbereich direkt an die passende Stelle der Wolke. */
    public static void decode(byte[] data, FSplatCloud cloud, Block block,
                              int offset, int count) {
        int expected = block.byteSize(count);
        if (data == null || data.length != expected) {
            throw new IllegalArgumentException("Block " + block + ": " + expected
                    + " Bytes erwartet, " + (data == null ? "null" : data.length) + " erhalten");
        }
        checkDecodeRange(cloud, offset, count);
        FloatBuffer f = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer();

        switch (block) {
            case POS -> {
                for (int i = offset; i < offset + count; i++) {
                    cloud.px[i] = f.get();
                    cloud.py[i] = f.get();
                    cloud.pz[i] = f.get();
                }
            }
            case SCALE -> {
                for (int i = offset; i < offset + count; i++) {
                    cloud.sx[i] = f.get();
                    cloud.sy[i] = f.get();
                    cloud.sz[i] = f.get();
                }
            }
            case ROT -> {
                for (int i = offset; i < offset + count; i++) {
                    cloud.qx[i] = f.get();
                    cloud.qy[i] = f.get();
                    cloud.qz[i] = f.get();
                    cloud.qw[i] = f.get();
                }
            }
            case COL -> {
                for (int i = offset; i < offset + count; i++) {
                    cloud.cr[i] = f.get();
                    cloud.cg[i] = f.get();
                    cloud.cb[i] = f.get();
                    cloud.opacity[i] = f.get();
                }
            }
        }
    }

    /** Kodiert den SH-Block; liefert {@code null}, wenn die Wolke keinen hat. */
    public static byte[] encodeSh(FSplatCloud cloud) {
        return encodeSh(cloud, 0, cloud.count);
    }

    /** Kodiert einen SH-Bereich fuer grosse Szenen. */
    public static byte[] encodeSh(FSplatCloud cloud, int offset, int count) {
        if (cloud.shDegree == 0 || cloud.sh == null) {
            return null;
        }
        checkEncodeRange(cloud, offset, count);
        int values = count * cloud.shCoeffCount() * 3;
        ByteBuffer buffer = ByteBuffer.allocate(values * Float.BYTES).order(ByteOrder.LITTLE_ENDIAN);
        int start = offset * cloud.shCoeffCount() * 3;
        buffer.asFloatBuffer().put(cloud.sh, start, values);
        return buffer.array();
    }

    /** Liest den SH-Block; die Wolke muss den passenden Grad bereits belegt haben. */
    public static void decodeSh(byte[] data, FSplatCloud cloud, int count) {
        decodeSh(data, cloud, 0, count);
    }

    /** Liest einen SH-Bereich direkt an die passende Stelle der Wolke. */
    public static void decodeSh(byte[] data, FSplatCloud cloud, int offset, int count) {
        if (data == null || cloud.sh == null) {
            return;
        }
        checkDecodeRange(cloud, offset, count);
        int values = count * cloud.shCoeffCount() * 3;
        if (data.length != values * Float.BYTES) {
            throw new IllegalArgumentException("SH-Block passt nicht zu Grad " + cloud.shDegree);
        }
        ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                .get(cloud.sh, offset * cloud.shCoeffCount() * 3, values);
    }

    private static void checkEncodeRange(FSplatCloud cloud, int offset, int count) {
        if (offset < 0 || count < 0 || offset > cloud.count || count > cloud.count - offset) {
            throw new IllegalArgumentException("Kodierbereich ausserhalb der Wolke: "
                    + offset + " + " + count + " bei " + cloud.count);
        }
    }

    private static void checkDecodeRange(FSplatCloud cloud, int offset, int count) {
        if (offset < 0 || count < 0 || offset > cloud.capacity
                || count > cloud.capacity - offset) {
            throw new IllegalArgumentException("Dekodierbereich ausserhalb der Wolke: "
                    + offset + " + " + count + " bei " + cloud.capacity);
        }
    }
}
