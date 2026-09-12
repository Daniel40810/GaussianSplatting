package com.dan.fgaussian.render;

import java.util.Arrays;

/**
 * Radix-Sortierung der sichtbaren Splats nach Tiefe, nah zuerst.
 *
 * <p>Die Tiefensortierung ist der teuerste Einzelschritt der Pipeline.
 * {@code Arrays.sort} auf einem Indexarray zwingt zum Boxen oder zu einem
 * Komparator-Aufruf je Vergleich; eine Radix-Sortierung ueber zwei
 * 16-Bit-Durchgaenge laeuft dagegen in linearer Zeit ueber die Rohbits.</p>
 *
 * <p>Trick dabei: Fuer <em>positive</em> float-Werte ist die
 * IEEE-754-Bitdarstellung streng monoton. Da nur Splats vor der Kamera
 * sortiert werden, ist die Tiefe immer positiv, und
 * {@code Float.floatToRawIntBits} liefert direkt einen brauchbaren
 * Sortierschluessel.</p>
 *
 * <p>Die Instanz haelt ihre Puffer und ist wiederverwendbar, aber nicht
 * threadsicher: ein Sorter je Renderer.</p>
 *
 * @author com.dan.fgaussian
 */
public final class FSplatSorter {

    private static final int BITS = 16;
    private static final int BUCKETS = 1 << BITS;
    private static final int MASK = BUCKETS - 1;

    private int[] keys = new int[0];
    private int[] keysAlt = new int[0];
    private int[] idxAlt = new int[0];
    private final int[] histogram = new int[BUCKETS];

    /**
     * Sortiert {@code index[0..n-1]} aufsteigend nach {@code depth[index[i]]}.
     *
     * @param depth Tiefenwerte je Splat, muessen fuer die benutzten Indizes positiv sein
     * @param index Indexarray, wird in-place umgestellt
     * @param n     Anzahl gueltiger Eintraege in {@code index}
     */
    public void sortByDepth(float[] depth, int[] index, int n) {
        if (n < 2) {
            return;
        }
        ensureCapacity(n);

        for (int i = 0; i < n; i++) {
            keys[i] = Float.floatToRawIntBits(depth[index[i]]);
        }

        pass(keys, index, keysAlt, idxAlt, n, 0);
        pass(keysAlt, idxAlt, keys, index, n, BITS);
    }

    private void pass(int[] srcKeys, int[] srcIdx, int[] dstKeys, int[] dstIdx, int n, int shift) {
        Arrays.fill(histogram, 0);
        for (int i = 0; i < n; i++) {
            histogram[(srcKeys[i] >>> shift) & MASK]++;
        }
        int sum = 0;
        for (int b = 0; b < BUCKETS; b++) {
            int c = histogram[b];
            histogram[b] = sum;
            sum += c;
        }
        for (int i = 0; i < n; i++) {
            int bucket = (srcKeys[i] >>> shift) & MASK;
            int pos = histogram[bucket]++;
            dstKeys[pos] = srcKeys[i];
            dstIdx[pos] = srcIdx[i];
        }
    }

    private void ensureCapacity(int n) {
        if (keys.length < n) {
            keys = new int[n];
            keysAlt = new int[n];
            idxAlt = new int[n];
        }
    }
}
