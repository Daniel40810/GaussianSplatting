package com.dan.fgaussian;

import com.dan.fgaussian.core.FCovariance;
import com.dan.fgaussian.core.FSplatCloud;
import com.dan.fgaussian.core.FSplatCodec;
import com.dan.fgaussian.render.FCamera;
import com.dan.fgaussian.render.FSplatHierarchy;
import com.dan.fgaussian.render.FSplatRenderer;
import com.dan.fgaussian.source.FPlySplatSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Bibliotheksfreie Regressionstests fuer die wichtigsten Daten- und
 * Renderpfade. Ausfuehren mit {@code java ...FGaussianSelfTest}; ein Fehler
 * beendet den Lauf mit {@link AssertionError}.
 */
public final class FGaussianSelfTest {

    private static final float EPS = 1e-5f;

    private FGaussianSelfTest() {
    }

    public static void main(String[] args) throws Exception {
        testCodecRoundTrip();
        testPlyAsciiLittleEndianBigEndianAndSh();
        testCovarianceMerge();
        testQuaternionNormalization();
        testShConcat();
        testLodGroupingAndSelection();
        testRendererSmoke();
        System.out.println("FGaussianSelfTest: OK");
    }

    private static void testCodecRoundTrip() {
        FSplatCloud source = sampleCloud(3);
        source.allocateSh(1);
        for (int i = 0; i < source.sh.length; i++) {
            source.sh[i] = i * 0.125f - 1f;
        }

        FSplatCloud copy = new FSplatCloud(source.count);
        copy.allocateSh(1);
        for (FSplatCodec.Block block : FSplatCodec.Block.values()) {
            FSplatCodec.decode(FSplatCodec.encode(source, block), copy, block, source.count);
        }
        FSplatCodec.decodeSh(FSplatCodec.encodeSh(source), copy, source.count);
        copy.count = source.count;

        for (int i = 0; i < source.count; i++) {
            near(source.px[i], copy.px[i], "codec px");
            near(source.sx[i], copy.sx[i], "codec sx");
            near(source.qw[i], copy.qw[i], "codec qw");
            near(source.opacity[i], copy.opacity[i], "codec opacity");
        }
        for (int i = 0; i < source.sh.length; i++) {
            near(source.sh[i], copy.sh[i], "codec sh");
        }
    }

    private static void testPlyAsciiLittleEndianBigEndianAndSh() throws IOException {
        String[] names = {"x", "y", "z", "opacity", "scale_0", "scale_1", "scale_2",
                "rot_0", "rot_1", "rot_2", "rot_3"};
        float[] values = {1f, 2f, 3f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f};

        FPlySplatSource ascii = new FPlySplatSource();
        FSplatCloud a = ascii.read(new ByteArrayInputStream(
                asciiPly(names, values).getBytes(StandardCharsets.US_ASCII)));
        check(a.count == 1, "PLY ASCII count");
        near(a.sx[0], 1f, "PLY ASCII scale");
        near(a.opacity[0], 0.5f, "PLY ASCII opacity");

        FPlySplatSource little = new FPlySplatSource();
        FSplatCloud le = little.read(new ByteArrayInputStream(binaryPly(names, values, ByteOrder.LITTLE_ENDIAN)));
        near(le.px[0], 1f, "PLY little endian");

        FPlySplatSource big = new FPlySplatSource();
        FSplatCloud be = big.read(new ByteArrayInputStream(binaryPly(names, values, ByteOrder.BIG_ENDIAN)));
        near(be.pz[0], -3f, "PLY big endian");

        String[] shNames = new String[names.length + 12];
        System.arraycopy(names, 0, shNames, 0, names.length);
        shNames[11] = "f_dc_0";
        shNames[12] = "f_dc_1";
        shNames[13] = "f_dc_2";
        for (int i = 0; i < 9; i++) {
            shNames[14 + i] = "f_rest_" + i;
        }
        float[] shValues = new float[shNames.length];
        System.arraycopy(values, 0, shValues, 0, values.length);
        for (int i = 0; i < 12; i++) {
            shValues[11 + i] = i + 0.25f;
        }
        FPlySplatSource shSource = new FPlySplatSource();
        shSource.setConvertAxes(false);
        shSource.setBakeBaseColor(false);
        FSplatCloud sh = shSource.read(new ByteArrayInputStream(
                asciiPly(shNames, shValues).getBytes(StandardCharsets.US_ASCII)));
        check(sh.shDegree == 1 && sh.sh.length == 12, "PLY SH degree/layout");
        near(sh.sh[11], 11.25f, "PLY SH coefficient");
    }

    private static void testCovarianceMerge() {
        FSplatCloud cloud = new FSplatCloud(2);
        float[] q = {0f, 0f, 0f, 1f};
        cloud.add(-5f, 0f, 0f, 1f, 1f, 1f, q, 1f, 1f, 1f, 1f);
        cloud.add(5f, 0f, 0f, 1f, 1f, 1f, q, 1f, 1f, 1f, 1f);
        float[] out = new float[14];
        FCovariance.merge(cloud, new int[] {0, 1}, 2, out);
        near(out[0], 0f, "covariance center");
        near(out[3], (float) Math.sqrt(26), 1e-4f, "covariance spread");
        near(out[4], 1f, "covariance y scale");
    }

    private static void testShConcat() {
        FSplatCloud rgb = new FSplatCloud(1);
        rgb.add(0f, 0f, 0f, 1f, 1f, 1f, new float[] {0f, 0f, 0f, 1f},
                0.7f, 0.2f, 0.4f, 1f);
        FSplatCloud sh = new FSplatCloud(1);
        sh.allocateSh(1);
        sh.add(2f, 0f, 0f, 1f, 1f, 1f, new float[] {0f, 0f, 0f, 1f},
                0.5f, 0.5f, 0.5f, 1f);
        for (int i = 0; i < sh.sh.length; i++) {
            sh.sh[i] = i;
        }

        FSplatCloud merged = FSplatCloud.concat(rgb, sh);
        check(merged.shDegree == 1 && merged.sh.length == 24, "SH concat degree");
        near(merged.sh[0] * 0.28209479177387814f + 0.5f, 0.7f,
                1e-5f, "RGB promoted to SH");
        near(merged.sh[12], sh.sh[0], "SH source preserved");
    }

    private static void testQuaternionNormalization() {
        FSplatCloud cloud = new FSplatCloud(1);
        cloud.add(0f, 0f, 0f, 1f, 1f, 1f,
                new float[] {0f, 0f, 0f, 1.007835f},
                1f, 1f, 1f, 1f);
        float norm = (float) Math.sqrt(cloud.qx[0] * cloud.qx[0]
                + cloud.qy[0] * cloud.qy[0]
                + cloud.qz[0] * cloud.qz[0]
                + cloud.qw[0] * cloud.qw[0]);
        near(norm, 1f, "quaternion normalization");
    }

    private static void testLodGroupingAndSelection() {
        FSplatCloud cloud = new FSplatCloud(64);
        float[][] cells = {
                {22, 23, 45}, {56, 17, 13}, // collide under the old XOR hash
                {0, 0, 0}, {1, 0, 0}, {2, 0, 0}, {3, 0, 0}, {4, 0, 0},
                {5, 0, 0}, {6, 0, 0}, {7, 0, 0}, {8, 0, 0}, {9, 0, 0},
                {10, 0, 0}, {11, 0, 0}, {12, 0, 0}, {13, 0, 0}, {14, 0, 0},
                {15, 0, 0}, {16, 0, 0}, {17, 0, 0}, {18, 0, 0}, {19, 0, 0},
                {20, 0, 0}, {21, 0, 0}, {22, 0, 0}, {23, 0, 0}, {24, 0, 0},
                {25, 0, 0}, {26, 0, 0}, {27, 0, 0}, {28, 0, 0}, {29, 0, 0}
        };
        for (float[] cell : cells) {
            cloud.add(cell[0] + 0.1f, cell[1] + 0.1f, cell[2] + 0.1f,
                    0.2f, 0.2f, 0.2f, new float[] {0f, 0f, 0f, 1f},
                    1f, 1f, 1f, 1f);
            cloud.add(cell[0] + 0.2f, cell[1] + 0.2f, cell[2] + 0.2f,
                    0.2f, 0.2f, 0.2f, new float[] {0f, 0f, 0f, 1f},
                    1f, 1f, 1f, 1f);
        }
        FSplatHierarchy hierarchy = FSplatHierarchy.build(cloud, 1.0, 1);
        check(hierarchy.size(1) == cells.length, "LOD cell collision/grouping");

        FCamera camera = new FCamera();
        camera.targetZ = 100f;
        camera.distance = 80f;
        int selected = hierarchy.select(camera, 600, 4f);
        check(selected > 0 && selected <= hierarchy.totalSplats(), "LOD selection range");
        int[] indices = hierarchy.selection();
        for (int i = 0; i < selected; i++) {
            check(indices[i] >= 0 && indices[i] < hierarchy.totalSplats(), "LOD index range");
            for (int j = 0; j < i; j++) {
                check(indices[i] != indices[j], "LOD duplicate selection");
            }
        }
    }

    private static void testRendererSmoke() {
        FSplatCloud cloud = new FSplatCloud(1);
        cloud.add(0f, 0f, 0f, 1f, 1f, 1f, new float[] {0f, 0f, 0f, 1f},
                1f, 0f, 0f, 1f);
        FCamera camera = new FCamera();
        camera.frame(0f, 0f, 0f, 1f);
        int[] pixels = new int[64 * 64];
        FSplatRenderer renderer = new FSplatRenderer();
        renderer.parallel = false;
        renderer.render(cloud, camera, pixels, 64, 64);
        int background = (int) (0.04f * 255f + 0.5f) << 16
                | (int) (0.06f * 255f + 0.5f) << 8
                | (int) (0.08f * 255f + 0.5f);
        boolean changed = false;
        for (int pixel : pixels) {
            if (pixel != background) {
                changed = true;
                break;
            }
        }
        check(changed, "renderer smoke image");
    }

    private static FSplatCloud sampleCloud(int count) {
        FSplatCloud cloud = new FSplatCloud(count);
        float[] q = {0f, 0f, 0f, 1f};
        for (int i = 0; i < count; i++) {
            cloud.add(i, i + 1f, i + 2f, 1f + i, 2f, 3f, q,
                    0.1f * i, 0.2f, 0.3f, 0.5f + i * 0.1f);
        }
        return cloud;
    }

    private static String asciiPly(String[] names, float[] values) {
        StringBuilder out = new StringBuilder("ply\nformat ascii 1.0\nelement vertex 1\n");
        for (String name : names) {
            out.append("property float ").append(name).append('\n');
        }
        out.append("end_header\n");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append(' ');
            out.append(values[i]);
        }
        return out.append('\n').toString();
    }

    private static byte[] binaryPly(String[] names, float[] values, ByteOrder order) {
        String header = "ply\nformat "
                + (order == ByteOrder.LITTLE_ENDIAN ? "binary_little_endian" : "binary_big_endian")
                + " 1.0\nelement vertex 1\n";
        StringBuilder text = new StringBuilder(header);
        for (String name : names) {
            text.append("property float ").append(name).append('\n');
        }
        byte[] prefix = text.append("end_header\n").toString().getBytes(StandardCharsets.US_ASCII);
        ByteBuffer data = ByteBuffer.allocate(values.length * Float.BYTES).order(order);
        for (float value : values) data.putFloat(value);
        ByteArrayOutputStream out = new ByteArrayOutputStream(prefix.length + data.array().length);
        out.writeBytes(prefix);
        out.writeBytes(data.array());
        return out.toByteArray();
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void near(float actual, float expected, String message) {
        near(actual, expected, EPS, message);
    }

    private static void near(float actual, float expected, float tolerance, String message) {
        if (Math.abs(actual - expected) > tolerance) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }
}
