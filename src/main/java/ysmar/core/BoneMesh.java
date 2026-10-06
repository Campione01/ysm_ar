package ysmar.core;

import java.util.Arrays;

/**
 * The baked quads of one bone and the rule that turns them into GL geometry.
 *
 * The official renderer shows a face when facing > 0, with facing = dot(plane, facing coefficient) * winding sign.
 * The facing coefficient is the generalised cross product of the x, y and w rows of the clip matrix, the plane is
 * (normal, -normal . p0) and the winding sign is the sign of normal . ((p1 - p0) x (p2 - p0)). Their product has
 * the sign of the projected area of (p0, p1, p2), whatever the sign and whatever the matrix: facing > 0 is the same
 * as "the baked vertex order is counter-clockwise on screen". So with GL back-face culling:
 *   cutout, translucent_culling    the quad as baked; a zero sign is never visible and is left out
 *   cutout_no_culling, translucent the quad as baked plus a copy with reversed order and negated normal
 *                                  (a zero sign is a back face from both sides: both copies get the negated normal)
 */
public final class BoneMesh {
    public static final int OPAQUE = 0;
    public static final int TRANSLUCENT = 1;

    public static final int CUTOUT = 0;
    public static final int CUTOUT_NO_CULLING = 1;
    public static final int TRANSLUCENT_PARTITION = 2;
    public static final int TRANSLUCENT_CULLING = 3;

    /** Four positions, four UV pairs, one normal. */
    public static final int FLOATS_PER_QUAD = 23;

    private static final int FLAG_TRANSLUCENT = 1;
    private static final int FLAG_DOUBLE = 2;
    private static final int FLAG_ZERO_SIGN = 4;
    private static final int FLAG_DROPPED = 8;

    private static final int[] BAKED_ORDER = {0, 1, 2, 3};
    private static final int[] REVERSED_ORDER = {0, 3, 2, 1};

    private final float[] data;
    private final byte[] flags;
    private final int[] emittedQuads = new int[2];
    private final int droppedQuads;

    private BoneMesh(float[] data, byte[] flags) {
        this.data = data;
        this.flags = flags;
        int dropped = 0;
        for (byte flag : flags) {
            if ((flag & FLAG_DROPPED) != 0) {
                dropped++;
            } else {
                emittedQuads[flag & FLAG_TRANSLUCENT] += (flag & FLAG_DOUBLE) != 0 ? 2 : 1;
            }
        }
        droppedQuads = dropped;
    }

    /** Baked quads that are in no mesh: the official renderer never shows them. */
    public int droppedQuads() {
        return droppedQuads;
    }

    /** Baked quads of this bone, in the order of the cube walk (partition, cube, quad). */
    public int quadCount() {
        return flags.length;
    }

    public int emittedQuads(int meshClass) {
        return emittedQuads[meshClass];
    }

    public int emittedVertices(int meshClass) {
        return emittedQuads[meshClass] * 4;
    }

    public int partition(int quad) {
        return flags[quad] >> 4 & 3;
    }

    public void emit(int meshClass, boolean reversed, VertexSink sink) {
        for (int quad = 0; quad < flags.length; quad++) {
            int flag = flags[quad];
            if ((flag & FLAG_DROPPED) != 0 || (flag & FLAG_TRANSLUCENT) != meshClass) {
                continue;
            }
            int base = quad * FLOATS_PER_QUAD;
            float normalX = data[base + 20];
            float normalY = data[base + 21];
            float normalZ = data[base + 22];
            boolean doubled = (flag & FLAG_DOUBLE) != 0;
            float front = doubled && (flag & FLAG_ZERO_SIGN) != 0 ? -1.0f : 1.0f;
            sink.quad(quad, false);
            corners(sink, base, reversed ? REVERSED_ORDER : BAKED_ORDER, front * normalX, front * normalY, front * normalZ);
            if (doubled) {
                sink.quad(quad, true);
                corners(sink, base, reversed ? BAKED_ORDER : REVERSED_ORDER, -normalX, -normalY, -normalZ);
            }
        }
    }

    private void corners(VertexSink sink, int base, int[] order, float normalX, float normalY, float normalZ) {
        for (int corner : order) {
            int position = base + corner * 3;
            int uv = base + 12 + corner * 2;
            sink.vertex(data[position], data[position + 1], data[position + 2], data[uv], data[uv + 1], normalX, normalY, normalZ);
        }
    }

    static final class Builder {
        private float[] data = new float[FLOATS_PER_QUAD * 16];
        private byte[] flags = new byte[16];
        private int count;

        /** Returns the array and offset where the caller writes the 23 floats of the next quad. */
        int reserve() {
            if (count == flags.length) {
                flags = Arrays.copyOf(flags, count * 2);
                data = Arrays.copyOf(data, count * 2 * FLOATS_PER_QUAD);
            }
            return count * FLOATS_PER_QUAD;
        }

        float[] data() {
            return data;
        }

        void commit(int partition, float windingSign, boolean partlyTransparent) {
            boolean culling = partition == CUTOUT || partition == TRANSLUCENT_CULLING;
            boolean translucentPartition = partition == TRANSLUCENT_PARTITION || partition == TRANSLUCENT_CULLING;
            int flag = partition << 4;
            if (translucentPartition && partlyTransparent) {
                flag |= FLAG_TRANSLUCENT;
            }
            if (!culling) {
                flag |= FLAG_DOUBLE;
            }
            if (windingSign == 0.0f) {
                flag |= FLAG_ZERO_SIGN;
                if (culling) {
                    flag |= FLAG_DROPPED;
                }
            }
            flags[count++] = (byte) flag;
        }

        int count() {
            return count;
        }

        BoneMesh build() {
            return new BoneMesh(Arrays.copyOf(data, count * FLOATS_PER_QUAD), Arrays.copyOf(flags, count));
        }
    }
}
