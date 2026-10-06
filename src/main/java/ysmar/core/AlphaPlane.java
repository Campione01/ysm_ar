package ysmar.core;

/**
 * Alpha values of the decoded model texture. Decides for a baked quad whether its texels are binary (0 or 255)
 * or partly transparent, over the same texel rectangle the bake inspected. For a quad whose UVs have no height the
 * bake of origin versions 28 and later looks at the texel row above (and moves two corners there), older versions
 * at the row below; seen from the UVs the bake hands back, that is the one difference between the versions.
 */
public final class AlphaPlane {
    public static final int EMPTY = 0;
    public static final int TRANSLUCENT = 1;
    public static final int CUTOUT = 2;
    public static final int SOLID = 3;

    private static final int FIRST_VERSION_THAT_LOOKS_UP = 28;

    private final byte[] alpha;
    private final int width;
    private final int height;
    private final boolean looksUp;

    public AlphaPlane(byte[] alpha, int width, int height) {
        this(alpha, width, height, ModelLoader.ORIGIN_VERSION);
    }

    public AlphaPlane(byte[] alpha, int width, int height, int originVersion) {
        this.alpha = alpha;
        this.width = width;
        this.height = height;
        this.looksUp = originVersion >= FIRST_VERSION_THAT_LOOKS_UP;
    }

    /** uv: four corners, u then v, starting at offset. */
    public int classify(float[] uv, int offset) {
        int minU = Integer.MAX_VALUE;
        int maxU = Integer.MIN_VALUE;
        int minV = Integer.MAX_VALUE;
        int maxV = Integer.MIN_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            int u = Math.max(0, Math.min(width, Math.round(uv[offset + corner * 2] * width)));
            int v = Math.max(0, Math.min(height, Math.round(uv[offset + corner * 2 + 1] * height)));
            minU = Math.min(minU, u);
            maxU = Math.max(maxU, u);
            minV = Math.min(minV, v);
            maxV = Math.max(maxV, v);
        }
        if (minU == maxU) {
            if (maxU < width) {
                maxU++;
            } else if (minU > 0) {
                minU--;
            }
        }
        if (minV == maxV) {
            if (looksUp && minV > 0) {
                minV--;
            } else if (maxV < height) {
                maxV++;
            }
        }
        boolean empty = true;
        boolean hasTransparent = false;
        for (int y = minV; y < maxV; y++) {
            int row = y * width;
            for (int x = minU; x < maxU; x++) {
                int value = alpha[row + x] & 0xFF;
                if (value == 0) {
                    hasTransparent = true;
                } else {
                    empty = false;
                    if (value != 255) {
                        return TRANSLUCENT;
                    }
                }
            }
        }
        return empty ? EMPTY : hasTransparent ? CUTOUT : SOLID;
    }
}
