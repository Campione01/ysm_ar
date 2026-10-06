package ysmar.takeover;

import ysmar.core.Attributes;

import java.util.List;

/**
 * From the per-bone array of Yes Steve Model 2.6.5 (12 floats per bone, its own bone order) to the attribute array
 * of the official Extract (14 floats per bone, sorted bake order). Slots 0 to 10 mean the same on both sides:
 * rotation, position, scale, cubes hidden, children hidden. Slot 11 is a flag of 2.6.5 with no counterpart, and the
 * official slots 12 and 13 (colour, transparency and glow) do not exist in 2.6.5. Its glow is a rule on bone names
 * instead, which the official Extract does not know: it is put into slot 13 here.
 */
public final class AttributeMap {
    public static final int LIVE_FLOATS = 12;
    private static final int SHARED = 11;
    private static final float NO_LOCATOR = 0.0f;
    private static final float LOCATOR = 1.0f;
    private static final float WHITE = 16777215.0f;
    private static final float OPAQUE_NO_GLOW = 65535.0f;
    /** Alpha 255 in the low byte, light level 15 in the one above it. */
    private static final float OPAQUE_FULL_GLOW = 4095.0f;
    /** The naming convention of models made for 2.6.5: the cubes of such a bone are drawn at full light. */
    public static final String GLOW_PREFIX = "ysmGlow";

    private AttributeMap() {
    }

    public static boolean sized(float[] live, int bones) {
        return live != null && bones > 0 && live.length == bones * LIVE_FLOATS;
    }

    /**
     * Bones whose slot 11 is set: the Molang function bone_pivot_abs was used on them. Its values are written by the
     * native draw of 2.6.5, so such a model keeps that draw unless this mod writes them (see PivotAbs).
     */
    public static int flagged(float[] live, int bones) {
        int count = 0;
        for (int bone = 0; bone < bones; bone++) {
            if (!(live[bone * LIVE_FLOATS + SHARED] == 0.0f)) {
                count++;
            }
        }
        return count;
    }

    /**
     * Which bones carry the glow prefix, in the order of the names; null when none does. The bone itself only: its
     * children are drawn at the light of the entity unless they carry the prefix too.
     */
    public static boolean[] legacyGlow(List<String> names) {
        boolean[] glow = null;
        for (int bone = 0; bone < names.size(); bone++) {
            String name = names.get(bone);
            if (name != null && name.startsWith(GLOW_PREFIX)) {
                if (glow == null) {
                    glow = new boolean[names.size()];
                }
                glow[bone] = true;
            }
        }
        return glow;
    }

    public static int count(boolean[] glow) {
        int count = 0;
        if (glow != null) {
            for (boolean set : glow) {
                count += set ? 1 : 0;
            }
        }
        return count;
    }

    /** permutation[sorted bake index] = bone index of 2.6.5; glow[sorted bake index], or null for no glowing bone. */
    public static void fill(float[] live, int[] permutation, boolean[] glow, float[] attributes) {
        for (int sorted = 0; sorted < permutation.length; sorted++) {
            int target = sorted * Attributes.PER_BONE;
            System.arraycopy(live, permutation[sorted] * LIVE_FLOATS, attributes, target, SHARED);
            attributes[target + 11] = NO_LOCATOR;
            attributes[target + 12] = WHITE;
            attributes[target + 13] = glow != null && glow[sorted] ? OPAQUE_FULL_GLOW : OPAQUE_NO_GLOW;
        }
    }

    /**
     * After fill(): gives every bone whose slot 11 is set a locator number, which makes Extract list the bone when it
     * reaches it and changes nothing else. Returns the number of such bones.
     */
    public static int markFlagged(float[] live, int[] permutation, float[] attributes) {
        int marked = 0;
        for (int sorted = 0; sorted < permutation.length; sorted++) {
            if (!(live[permutation[sorted] * LIVE_FLOATS + SHARED] == 0.0f)) {
                attributes[sorted * Attributes.PER_BONE + 11] = LOCATOR;
                marked++;
            }
        }
        return marked;
    }
}
