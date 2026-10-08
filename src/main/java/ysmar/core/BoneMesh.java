package ysmar.core;

import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.Map;

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

    // Shading parts, made on first use: the emitted copies of quads of one mesh class that carry the same normal.
    private int[] frontPart;
    private int[] backPart;
    private float[] partNormals;
    private byte[] partClasses;
    private int[] partners;

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

    int partition(int quad) {
        return flags[quad] >> 4 & 3;
    }

    /**
     * The other bones of the model that have a face exactly where this bone has one: the same four corners, seen
     * from the same side; in rising order, null for none. Which of two such faces shows is decided by the order
     * they are drawn in (Yes Steve Model draws bone after bone), so while this bone and one of those are both
     * drawn, their meshes have to come out in the order of the draw calls. The array is not to be changed.
     */
    public int[] partners() {
        return partners;
    }

    /** Finds the bones that have a face in the place of a face of another bone; returns how many there are. */
    static int markPartners(BoneMesh[] meshes) {
        Map<Place, int[]> seen = new HashMap<>();
        BitSet[] sharing = new BitSet[meshes.length];
        for (int bone = 0; bone < meshes.length; bone++) {
            BoneMesh mesh = meshes[bone];
            if (mesh == null) {
                continue;
            }
            for (int quad = 0; quad < mesh.flags.length; quad++) {
                int base = quad * FLOATS_PER_QUAD;
                int side = (mesh.flags[quad] & FLAG_DROPPED) != 0 ? 0 : side(mesh.data, base);
                if (side == 0) {
                    continue;
                }
                boolean doubled = (mesh.flags[quad] & FLAG_DOUBLE) != 0;
                if (doubled || side > 0) {
                    note(seen, sharing, Place.of(mesh.data, base, true), bone);
                }
                if (doubled || side < 0) {
                    note(seen, sharing, Place.of(mesh.data, base, false), bone);
                }
            }
        }
        int marked = 0;
        for (int bone = 0; bone < meshes.length; bone++) {
            if (sharing[bone] != null) {
                meshes[bone].partners = sharing[bone].stream().toArray();
                marked++;
            }
        }
        return marked;
    }

    /** The bones come in rising order, so the bones seen in a place are listed in that order, each once. */
    private static void note(Map<Place, int[]> seen, BitSet[] sharing, Place place, int bone) {
        int[] there = seen.get(place);
        if (there == null) {
            seen.put(place, new int[]{bone});
            return;
        }
        if (there[there.length - 1] == bone) {
            return;
        }
        if (sharing[bone] == null) {
            sharing[bone] = new BitSet();
        }
        for (int other : there) {
            if (sharing[other] == null) {
                sharing[other] = new BitSet();
            }
            sharing[other].set(bone);
            sharing[bone].set(other);
        }
        int[] grown = Arrays.copyOf(there, there.length + 1);
        grown[there.length] = bone;
        seen.put(place, grown);
    }

    /**
     * The side a quad is seen from as baked: the sign of the largest component of the normal of its corners, which
     * is the same for all quads of one plane that are seen from the same side. 0 for a quad without area.
     */
    private static int side(float[] data, int base) {
        for (int corner = 1; corner < 3; corner++) {
            float ax = data[base + corner * 3] - data[base];
            float ay = data[base + corner * 3 + 1] - data[base + 1];
            float az = data[base + corner * 3 + 2] - data[base + 2];
            float bx = data[base + corner * 3 + 3] - data[base];
            float by = data[base + corner * 3 + 4] - data[base + 1];
            float bz = data[base + corner * 3 + 5] - data[base + 2];
            float x = ay * bz - az * by;
            float y = az * bx - ax * bz;
            float z = ax * by - ay * bx;
            float largest = Math.abs(x) >= Math.abs(y) && Math.abs(x) >= Math.abs(z) ? x : Math.abs(y) >= Math.abs(z) ? y : z;
            if (largest != 0.0f) {
                return largest > 0.0f ? 1 : -1;
            }
        }
        return 0;
    }

    /** The four corners of a quad as a set, and the side it is seen from: the same for two quads in one place. */
    private record Place(long first, long second, long third, long fourth, boolean side) {
        static Place of(float[] data, int base, boolean side) {
            long[] corners = new long[4];
            for (int corner = 0; corner < 4; corner++) {
                long bits = 0;
                for (int axis = 0; axis < 3; axis++) {
                    // + 0.0f: the two zeros are one place.
                    bits = bits * 0x9E3779B97F4A7C15L + Float.floatToIntBits(data[base + corner * 3 + axis] + 0.0f);
                }
                corners[corner] = bits;
            }
            Arrays.sort(corners);
            return new Place(corners[0], corners[1], corners[2], corners[3], side);
        }
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

    /** The number of shading parts: the emitted copies of quads of one mesh class that carry the same normal are one part. */
    public int parts() {
        if (partClasses == null) {
            makeParts();
        }
        return partClasses.length;
    }

    public int partClass(int part) {
        return partClasses[part];
    }

    /** The normal the vertices of the part carry. */
    public float partNormal(int part, int axis) {
        return partNormals[part * 3 + axis];
    }

    /** As emit(), for the quad copies of one part. */
    public void emitPart(int part, boolean reversed, VertexSink sink) {
        float normalX = partNormals[part * 3];
        float normalY = partNormals[part * 3 + 1];
        float normalZ = partNormals[part * 3 + 2];
        for (int quad = 0; quad < flags.length; quad++) {
            int base = quad * FLOATS_PER_QUAD;
            if (frontPart[quad] == part) {
                sink.quad(quad, false);
                corners(sink, base, reversed ? REVERSED_ORDER : BAKED_ORDER, normalX, normalY, normalZ);
            }
            if (backPart[quad] == part) {
                sink.quad(quad, true);
                corners(sink, base, reversed ? BAKED_ORDER : REVERSED_ORDER, normalX, normalY, normalZ);
            }
        }
    }

    /** A mesh class and a normal to four digits: the copies of quads that agree in both are one part. */
    private record Direction(int meshClass, int x, int y, int z) {
    }

    private void makeParts() {
        Map<Direction, Integer> known = new HashMap<>();
        int[] fronts = new int[flags.length];
        int[] backs = new int[flags.length];
        float[] normals = new float[12];
        byte[] classes = new byte[4];
        for (int quad = 0; quad < flags.length; quad++) {
            int flag = flags[quad];
            fronts[quad] = -1;
            backs[quad] = -1;
            if ((flag & FLAG_DROPPED) != 0) {
                continue;
            }
            boolean doubled = (flag & FLAG_DOUBLE) != 0;
            float front = doubled && (flag & FLAG_ZERO_SIGN) != 0 ? -1.0f : 1.0f;
            for (int copy = 0; copy < (doubled ? 2 : 1); copy++) {
                float side = copy == 0 ? front : -1.0f;
                float x = side * data[quad * FLOATS_PER_QUAD + 20];
                float y = side * data[quad * FLOATS_PER_QUAD + 21];
                float z = side * data[quad * FLOATS_PER_QUAD + 22];
                Direction direction = new Direction(flag & FLAG_TRANSLUCENT, Math.round(x * 10000.0f), Math.round(y * 10000.0f), Math.round(z * 10000.0f));
                Integer part = known.get(direction);
                if (part == null) {
                    part = known.size();
                    known.put(direction, part);
                    if (part == classes.length) {
                        classes = Arrays.copyOf(classes, part * 2);
                        normals = Arrays.copyOf(normals, part * 6);
                    }
                    classes[part] = (byte) (flag & FLAG_TRANSLUCENT);
                    normals[part * 3] = x;
                    normals[part * 3 + 1] = y;
                    normals[part * 3 + 2] = z;
                }
                if (copy == 0) {
                    fronts[quad] = part;
                } else {
                    backs[quad] = part;
                }
            }
        }
        frontPart = fronts;
        backPart = backs;
        partNormals = Arrays.copyOf(normals, known.size() * 3);
        partClasses = Arrays.copyOf(classes, known.size());
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
