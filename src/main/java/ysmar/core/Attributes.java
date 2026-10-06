package ysmar.core;

import java.util.ArrayList;
import java.util.List;

/** The per-bone attribute array of the native Extract call: 14 floats per bone, in sorted bone order. */
public final class Attributes {
    public static final int PER_BONE = 14;
    private static final float WHITE = 16777215.0f;
    private static final float OPAQUE_NO_GLOW = 65535.0f;
    private static final int LOCATOR_SLOT = 11;
    private static final String[] SLOTS = {"rotation x", "rotation y", "rotation z", "position x", "position y", "position z",
            "scale x", "scale y", "scale z", "cubes hidden", "children hidden", "locator", "colour", "transparency and glow"};

    private Attributes() {
    }

    /** Rest values as the official animated bone starts with: static rotation, no offset, scale 1, nothing hidden. */
    public static float[] rest(float[] restRotations) {
        int bones = restRotations.length / 3;
        float[] attributes = new float[bones * PER_BONE];
        for (int bone = 0; bone < bones; bone++) {
            int base = bone * PER_BONE;
            attributes[base] = restRotations[bone * 3];
            attributes[base + 1] = restRotations[bone * 3 + 1];
            attributes[base + 2] = restRotations[bone * 3 + 2];
            attributes[base + 6] = 1.0f;
            attributes[base + 7] = 1.0f;
            attributes[base + 8] = 1.0f;
            attributes[base + 12] = WHITE;
            attributes[base + 13] = OPAQUE_NO_GLOW;
        }
        return attributes;
    }

    /**
     * True when Extract can take the array: every value finite, and the three packed slots within what the native
     * asserts (a violation there fails the whole call): locator sequence 0..255, colour 0..0xFFFFFF, transparency
     * and glow 0..0xFFFF with a glow byte of 0xFF or at most 15.
     */
    public static boolean sanitise(float[] attributes, int boneCount) {
        return sanitise(attributes, boneCount, false);
    }

    /**
     * pruneNonFinite: a rotation, position, scale or hidden flag that is not finite is let through. That is what
     * the native itself accepts: Extract leaves such a bone and everything below it out, as it does for a zero
     * scale, and the call does not fail. The three packed slots are checked all the same.
     */
    public static boolean sanitise(float[] attributes, int boneCount, boolean pruneNonFinite) {
        if (attributes == null || attributes.length != boneCount * PER_BONE) {
            return false;
        }
        float probe = 0.0f;
        if (!pruneNonFinite) {
            for (float value : attributes) {
                probe += value * 0.0f;
            }
        }
        if (probe != 0.0f) {
            return false;
        }
        for (int base = 0; base < attributes.length; base += PER_BONE) {
            float locator = attributes[base + 11];
            float colour = attributes[base + 12];
            float glow = attributes[base + 13];
            if (!(locator >= 0.0f && locator <= 255.0f && locator == (int) locator)
                    || !(colour >= 0.0f && colour <= WHITE && colour == (int) colour)
                    || !(glow >= 0.0f && glow <= OPAQUE_NO_GLOW && glow == (int) glow)) {
                return false;
            }
            int level = (int) glow >> 8;
            if (level != 0xFF && level > 15) {
                return false;
            }
        }
        return true;
    }

    /**
     * Why sanitise() refuses the array, or null when it accepts it: the first bone (its index in the array), the
     * slot, the rule and the value. Numbers only; for a line of the log, asked after a refusal and not per frame.
     * otherOrder[bone]: the index of that bone in an order of the caller's own, which is added; or null.
     */
    public static String refusal(float[] attributes, int boneCount, boolean pruneNonFinite, int[] otherOrder) {
        if (attributes == null) {
            return "no array";
        }
        if (attributes.length != boneCount * PER_BONE) {
            return "array of " + attributes.length + " floats, " + boneCount * PER_BONE + " expected for " + boneCount + " bones";
        }
        for (int index = 0; index < attributes.length; index++) {
            int slot = index % PER_BONE;
            float value = attributes[index];
            String rule = null;
            if (slot < LOCATOR_SLOT) {
                rule = pruneNonFinite || Float.isFinite(value) ? null : "not finite";
            } else if (slot == LOCATOR_SLOT) {
                rule = whole(value, 255.0f) ? null : "not a whole number from 0 to 255";
            } else if (slot == LOCATOR_SLOT + 1) {
                rule = whole(value, WHITE) ? null : "not a whole number from 0 to 16777215";
            } else if (!whole(value, OPAQUE_NO_GLOW)) {
                rule = "not a whole number from 0 to 65535";
            } else if ((int) value >> 8 != 0xFF && (int) value >> 8 > 15) {
                rule = "glow level above 15";
            }
            if (rule != null) {
                int bone = index / PER_BONE;
                return "bone " + bone + (otherOrder != null && bone < otherOrder.length ? " (bone " + otherOrder[bone] + " of the caller)" : "")
                        + " slot " + slot + " (" + SLOTS[slot] + "): " + rule + ", value " + value;
            }
        }
        return null;
    }

    private static boolean whole(float value, float maximum) {
        return value >= 0.0f && value <= maximum && value == (int) value;
    }

    /**
     * A sparse set of bones for the test animation: the top-most bones whose subtree holds between 2 % and 35 %
     * of the model's vertices (limbs and the like), at most six, largest first.
     */
    public static int[] waveBones(ModelData data) {
        int count = data.boneCount;
        long[] subtree = new long[count];
        long total = 0;
        for (int bone = 0; bone < count; bone++) {
            BoneMesh mesh = data.meshes[bone];
            if (mesh != null) {
                subtree[bone] = mesh.emittedVertices(BoneMesh.OPAQUE) + mesh.emittedVertices(BoneMesh.TRANSLUCENT);
                total += subtree[bone];
            }
        }
        for (int bone = count - 1; bone >= 0; bone--) {
            if (data.parents[bone] >= 0) {
                subtree[data.parents[bone]] += subtree[bone];
            }
        }
        List<Integer> chosen = new ArrayList<>();
        if (total > 0) {
            for (int bone = 0; bone < count; bone++) {
                int parent = data.parents[bone];
                boolean limb = subtree[bone] * 100 >= total * 2 && subtree[bone] * 100 <= total * 35;
                boolean parentIsLarger = parent < 0 || subtree[parent] * 100 > total * 35;
                if (limb && parentIsLarger) {
                    chosen.add(bone);
                }
            }
            if (chosen.isEmpty()) {
                for (int bone = 0; bone < count; bone++) {
                    if (data.parents[bone] >= 0 && subtree[bone] > 0) {
                        chosen.add(bone);
                    }
                }
            }
        }
        chosen.sort((first, second) -> subtree[first] != subtree[second] ? Long.compare(subtree[second], subtree[first]) : Integer.compare(first, second));
        int size = Math.min(6, chosen.size());
        int[] result = new int[size];
        for (int index = 0; index < size; index++) {
            result[index] = chosen.get(index);
        }
        return result;
    }

    /** Rest values plus a swing of at most 0.3 rad about X and 0.45 rad about Z on the given bones. */
    public static void wave(float[] rest, int[] bones, double time, float[] destination) {
        System.arraycopy(rest, 0, destination, 0, rest.length);
        for (int index = 0; index < bones.length; index++) {
            int base = bones[index] * PER_BONE;
            destination[base] += (float) (0.30 * Math.sin(time * 0.12 + index * 1.7));
            destination[base + 2] += (float) (0.45 * Math.sin(time * 0.20 + index * 0.9));
        }
    }
}
