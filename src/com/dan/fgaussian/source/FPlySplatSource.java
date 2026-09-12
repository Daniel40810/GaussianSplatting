package com.dan.fgaussian.source;

import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.shade.FSHEvaluator;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Liest eine Splat-Wolke aus einer 3DGS-PLY-Datei, wie sie beim Training aus
 * Fotoserien entsteht.
 *
 * <p>Damit steht der zweite Weg offen, der im Konzept als Variante B stand: der
 * Renderer bleibt derselbe, nur die Quelle ist eine andere. Die
 * Attributumrechnung folgt der Konvention der gaengigen Implementierungen:</p>
 *
 * <ul>
 *   <li>{@code scale_i} ist logarithmiert, also {@code exp(scale_i)}</li>
 *   <li>{@code opacity} ist logit-transformiert, also Sigmoid</li>
 *   <li>{@code rot_0..3} ist ein Quaternion in der Reihenfolge <b>w, x, y, z</b> -
 *       nicht x, y, z, w wie im uebrigen Paket</li>
 *   <li>{@code f_dc_*} ist der SH-Koeffizient 0, {@code f_rest_*} sind die
 *       hoeheren Koeffizienten, gespeichert <b>kanalweise</b>:
 *       erst alle Koeffizienten von Rot, dann Gruen, dann Blau</li>
 * </ul>
 *
 * <p><b>Achsen.</b> Rekonstruktionen liegen in der COLMAP-Konvention vor: y
 * zeigt nach unten. Umgestellt wird mit einer Drehung um 180 Grad um die
 * x-Achse - nicht durch Spiegeln einzelner Achsen, denn eine Spiegelung kehrt
 * die Haendigkeit um und damit den Drehsinn saemtlicher Quaternionen. Die
 * Rotation wird deshalb auch auf jedes Quaternion angewandt.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FPlySplatSource {

    private enum Format { ASCII, BINARY_LE, BINARY_BE }

    private enum Type {
        CHAR(1), UCHAR(1), SHORT(2), USHORT(2), INT(4), UINT(4), FLOAT(4), DOUBLE(8);

        final int size;

        Type(int size) {
            this.size = size;
        }

        static Type of(String name) {
            return switch (name) {
                case "char", "int8"    -> CHAR;
                case "uchar", "uint8"  -> UCHAR;
                case "short", "int16"  -> SHORT;
                case "ushort", "uint16"-> USHORT;
                case "int", "int32"    -> INT;
                case "uint", "uint32"  -> UINT;
                case "float", "float32"-> FLOAT;
                case "double", "float64"-> DOUBLE;
                default -> throw new IllegalArgumentException("Unbekannter PLY-Typ: " + name);
            };
        }
    }

    /** {@code offset} ist der Index im Wertepuffer, nicht die Byteposition. */
    private record Property(String name, Type type, int offset) {
    }

    /** Achsen aus der COLMAP-Konvention umstellen. */
    private boolean convertAxes = true;

    /** Grundfarbe aus Koeffizient 0 vorberechnen. */
    private boolean bakeBaseColor = true;

    private int vertexCount;
    private int shDegree;

    public void setConvertAxes(boolean value) {
        this.convertAxes = value;
    }

    public void setBakeBaseColor(boolean value) {
        this.bakeBaseColor = value;
    }

    /** Splats in der zuletzt gelesenen Datei. */
    public int getVertexCount() {
        return vertexCount;
    }

    /** SH-Grad der zuletzt gelesenen Datei. */
    public int getShDegree() {
        return shDegree;
    }

    // ------------------------------------------------------------------

    public FSplatCloud read(File file) throws IOException {
        try (InputStream raw = new BufferedInputStream(new FileInputStream(file), 1 << 16)) {
            return read(raw);
        }
    }

    public FSplatCloud read(InputStream in) throws IOException {
        List<Property> properties = new ArrayList<>();
        Format format = parseHeader(in, properties);

        Map<String, Property> byName = new HashMap<>();
        int stride = 0;
        for (Property p : properties) {
            byName.put(p.name(), p);
            stride += p.type().size;
        }

        require(byName, "x", "y", "z", "opacity",
                "scale_0", "scale_1", "scale_2",
                "rot_0", "rot_1", "rot_2", "rot_3");

        int rest = 0;
        while (byName.containsKey("f_rest_" + rest)) {
            rest++;
        }
        shDegree = switch (rest) {
            case 0  -> byName.containsKey("f_dc_0") ? 0 : 0;
            case 9  -> 1;
            case 24 -> 2;
            case 45 -> 3;
            default -> throw new IOException("Unerwartete Zahl von f_rest-Eigenschaften: " + rest);
        };
        boolean hasDc = byName.containsKey("f_dc_0");

        FSplatCloud cloud = new FSplatCloud(vertexCount);
        if (hasDc) {
            cloud.allocateSh(shDegree);
        }

        float[] values = new float[properties.size()];
        DataInputStream data = format == Format.ASCII ? null : new DataInputStream(in);
        byte[] record = format == Format.ASCII ? null : new byte[stride];
        ByteBuffer buffer = record == null ? null
                : ByteBuffer.wrap(record).order(format == Format.BINARY_LE
                        ? ByteOrder.LITTLE_ENDIAN : ByteOrder.BIG_ENDIAN);

        AsciiReader ascii = format == Format.ASCII ? new AsciiReader(in) : null;
        final int coeffs = hasDc ? cloud.shCoeffCount() : 0;
        final int perChannel = coeffs > 0 ? coeffs - 1 : 0;
        final float[] quaternion = new float[4];

        for (int i = 0; i < vertexCount; i++) {
            if (format == Format.ASCII) {
                ascii.readRecord(values);
            } else {
                data.readFully(record);
                buffer.clear();
                for (int p = 0; p < properties.size(); p++) {
                    values[p] = readValue(buffer, properties.get(p).type());
                }
            }

            float x = values[byName.get("x").offset()];
            float y = values[byName.get("y").offset()];
            float z = values[byName.get("z").offset()];

            float s0 = (float) Math.exp(values[byName.get("scale_0").offset()]);
            float s1 = (float) Math.exp(values[byName.get("scale_1").offset()]);
            float s2 = (float) Math.exp(values[byName.get("scale_2").offset()]);

            // Quaternion der Datei: w, x, y, z
            float qw = values[byName.get("rot_0").offset()];
            float qx = values[byName.get("rot_1").offset()];
            float qy = values[byName.get("rot_2").offset()];
            float qz = values[byName.get("rot_3").offset()];
            float norm = (float) Math.sqrt(qx * qx + qy * qy + qz * qz + qw * qw);
            if (norm > 1e-12f) {
                qx /= norm; qy /= norm; qz /= norm; qw /= norm;
            } else {
                qx = 0f; qy = 0f; qz = 0f; qw = 1f;
            }

            if (convertAxes) {
                y = -y;
                z = -z;
                // Dieselbe Drehung auf das Quaternion: q' = qRot * q,
                // mit qRot = 180 Grad um x, also (x=1, y=0, z=0, w=0).
                float nx =  qw;
                float ny = -qz;
                float nz =  qy;
                float nw = -qx;
                qx = nx; qy = ny; qz = nz; qw = nw;
            }

            float alpha = sigmoid(values[byName.get("opacity").offset()]);

            quaternion[0] = qx;
            quaternion[1] = qy;
            quaternion[2] = qz;
            quaternion[3] = qw;

            int index = cloud.add(x, y, z, s0, s1, s2,
                    quaternion,
                    0.5f, 0.5f, 0.5f, alpha);

            if (hasDc) {
                int base = index * coeffs * 3;
                for (int c = 0; c < 3; c++) {
                    cloud.sh[base + c] = values[byName.get("f_dc_" + c).offset()];
                }
                for (int k = 1; k < coeffs; k++) {
                    for (int c = 0; c < 3; c++) {
                        // kanalweise abgelegt: erst alle Koeffizienten von Rot,
                        // dann Gruen, dann Blau
                        int flat = c * perChannel + (k - 1);
                        cloud.sh[base + k * 3 + c] = values[byName.get("f_rest_" + flat).offset()];
                    }
                }
            }
        }

        if (hasDc && bakeBaseColor) {
            FSHEvaluator.bakeBaseColor(cloud);
        }
        cloud.computeBounds();
        return cloud;
    }

    // ------------------------------------------------------------------

    private Format parseHeader(InputStream in, List<Property> properties) throws IOException {
        String magic = readLine(in);
        if (!"ply".equals(magic.trim())) {
            throw new IOException("Keine PLY-Datei");
        }
        Format format = null;
        boolean inVertex = false;
        vertexCount = 0;

        while (true) {
            String line = readLine(in);
            if (line == null) {
                throw new EOFException("Kopfteil endet nicht mit end_header");
            }
            line = line.trim();
            if (line.isEmpty() || line.startsWith("comment") || line.startsWith("obj_info")) {
                continue;
            }
            if (line.equals("end_header")) {
                break;
            }
            String[] parts = line.split("\\s+");
            switch (parts[0]) {
                case "format" -> format = switch (parts[1]) {
                    case "ascii" -> Format.ASCII;
                    case "binary_little_endian" -> Format.BINARY_LE;
                    case "binary_big_endian" -> Format.BINARY_BE;
                    default -> throw new IOException("Unbekanntes PLY-Format: " + parts[1]);
                };
                case "element" -> {
                    inVertex = "vertex".equals(parts[1]);
                    if (inVertex) {
                        vertexCount = Integer.parseInt(parts[2]);
                    }
                }
                case "property" -> {
                    if (!inVertex) {
                        continue;
                    }
                    if ("list".equals(parts[1])) {
                        throw new IOException("Listeneigenschaften werden nicht unterstuetzt: " + line);
                    }
                    Type type = Type.of(parts[1]);
                    properties.add(new Property(parts[2], type, properties.size()));
                }
                default -> { /* alles andere ist hier ohne Belang */ }
            }
        }
        if (format == null) {
            throw new IOException("Keine Formatangabe im Kopfteil");
        }
        if (vertexCount <= 0) {
            throw new IOException("Kein vertex-Element mit Inhalt");
        }
        return format;
    }

    private static void require(Map<String, Property> byName, String... names) throws IOException {
        for (String name : names) {
            if (!byName.containsKey(name)) {
                throw new IOException("PLY fehlt die Eigenschaft " + name
                        + " - das sieht nicht nach einer 3DGS-Datei aus");
            }
        }
    }

    private static float readValue(ByteBuffer buffer, Type type) {
        return switch (type) {
            case FLOAT  -> buffer.getFloat();
            case DOUBLE -> (float) buffer.getDouble();
            case CHAR   -> buffer.get();
            case UCHAR  -> buffer.get() & 0xFF;
            case SHORT  -> buffer.getShort();
            case USHORT -> buffer.getShort() & 0xFFFF;
            case INT    -> buffer.getInt();
            case UINT   -> (float) (buffer.getInt() & 0xFFFFFFFFL);
        };
    }

    private static float sigmoid(float value) {
        return (float) (1.0 / (1.0 + Math.exp(-value)));
    }

    /** Liest eine Zeile roh, ohne Puffer ueber das Zeilenende hinaus zu ziehen. */
    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder(64);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                sb.append((char) c);
            }
        }
        if (c == -1 && sb.length() == 0) {
            return null;
        }
        return sb.toString();
    }

    /** Liest Zahlen aus dem ASCII-Teil, eine Zeile je Vertex. */
    private static final class AsciiReader {

        private final InputStream in;

        AsciiReader(InputStream in) {
            this.in = in;
        }

        void readRecord(float[] out) throws IOException {
            String line;
            do {
                line = readLine(in);
                if (line == null) {
                    throw new EOFException("PLY endet vor der angekuendigten Vertexzahl");
                }
                line = line.trim();
            } while (line.isEmpty());

            String[] parts = line.split("\\s+");
            if (parts.length < out.length) {
                throw new IOException("Zeile hat " + parts.length + " statt "
                        + out.length + " Werten: " + line);
            }
            for (int i = 0; i < out.length; i++) {
                out[i] = Float.parseFloat(parts[i]);
            }
        }
    }

}
