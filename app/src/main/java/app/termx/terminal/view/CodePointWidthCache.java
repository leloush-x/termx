package app.termx.terminal.view;

import java.util.Arrays;

/** Small bounded direct-mapped cache for non-ASCII glyph widths within one renderer/font. */
final class CodePointWidthCache {

    private static final int CAPACITY = 1024;
    private static final int MASK = CAPACITY - 1;
    private final int[] codePoints = new int[CAPACITY];
    private final float[] widths = new float[CAPACITY];

    CodePointWidthCache() {
        Arrays.fill(codePoints, -1);
    }

    float get(int codePoint) {
        int slot = codePoint & MASK;
        return codePoints[slot] == codePoint ? widths[slot] : Float.NaN;
    }

    void put(int codePoint, float width) {
        int slot = codePoint & MASK;
        codePoints[slot] = codePoint;
        widths[slot] = width;
    }
}
