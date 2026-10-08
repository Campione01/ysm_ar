package ysmar.takeover;

import ysmar.core.ModelData;

/**
 * Whether a local model file is what Yes Steve Model 2.6.5 has loaded under the same id, beyond the bone names: the
 * same names can come with other content (another file of that id on a server, a file replaced after 2.6.5 read
 * it). Each guard compares one thing both sides expose and counts what differs; a guard whose value 2.6.5 does not
 * give is skipped. Evaluated once per binding. Texts carry counts and sizes only: the values are model content.
 */
public final class ContentGuards {
    /** Pivots are in model units (1/16 block), rotations in radians. */
    public static final float PIVOT_TOLERANCE = 1.0e-3f;
    public static final float ROTATION_TOLERANCE = 1.0e-4f;

    public enum Guard {
        TEXTURE_SIZE("texture size"),
        CHAINS("locator chains"),
        PIVOTS("bone pivots"),
        ROTATIONS("bone rest rotations"),
        FILE("file unchanged");

        public final String label;

        Guard(String label) {
            this.label = label;
        }
    }

    /**
     * What 2.6.5 exposes about the model it has loaded, in its own bone order. A null member, or a negative size:
     * not available.
     */
    public static final class Theirs {
        /** Three floats per bone. */
        public float[] pivots;
        public float[] rotations;
        /**
         * The bone chains 2.6.5 walks to place what hangs on a locator bone (held items and the like): each from a
         * root bone down to the locator, as bone indices; -1 for a bone of a chain that is not in the bone list.
         */
        public int[][] chains;
        public int textureWidth = -1;
        public int textureHeight = -1;
        /** Whether the file is still the one this model object was first bound to; null when that is not known. */
        public Boolean fileUnchanged;
        /** How many files and folders that is said of, and since when (for the status), or null. */
        public int fileEntries = 1;
        public String fileNote;
    }

    public static final class Result {
        /** Per guard: -1 not available, else how many of `compared` differ. */
        public final int[] differing = new int[Guard.values().length];
        public final int[] compared = new int[Guard.values().length];
        /** Why the file must not stand in for the model, or null. */
        public String refusal;
        /** Since when the file is unchanged, said after that guard; or null. */
        public String fileNote;

        Result() {
            java.util.Arrays.fill(differing, -1);
        }

        boolean available(Guard guard) {
            return differing[guard.ordinal()] >= 0;
        }

        int differing(Guard guard) {
            return differing[guard.ordinal()];
        }

        int compared(Guard guard) {
            return compared[guard.ordinal()];
        }

        /** For the binding line of the status: every guard with its numbers, or "not available". */
        public String describe() {
            StringBuilder text = new StringBuilder("identity strict:");
            for (Guard guard : Guard.values()) {
                int index = guard.ordinal();
                text.append(index == 0 ? " " : ", ").append(guard.label).append(' ');
                if (differing[index] < 0) {
                    text.append("not available");
                } else if (differing[index] == 0) {
                    text.append("ok (").append(compared[index]).append(')');
                    if (guard == Guard.FILE && fileNote != null) {
                        text.append(' ').append(fileNote);
                    }
                } else {
                    text.append("DIFFERS (").append(differing[index]).append(" of ").append(compared[index]).append(')');
                }
            }
            return text.toString();
        }
    }

    /** The three questions about a texture on the graphics card that the size guard asks. */
    public interface TextureQuery {
        /** True when the name is a texture that exists; asking this is never an error. */
        boolean exists(int id);

        int width(int id);

        int height(int id);
    }

    /**
     * Width and height of the texture, or null when they cannot be asked: a name the game has reserved but never
     * bound is no texture yet, and asking its size would be an error on the graphics card.
     */
    public static int[] textureSize(TextureQuery query, int id) {
        if (id <= 0 || !query.exists(id)) {
            return null;
        }
        int width = query.width(id);
        int height = query.height(id);
        return width > 0 && height > 0 ? new int[]{width, height} : null;
    }

    private ContentGuards() {
    }

    /** permutation[sorted bake index] = bone index of 2.6.5. */
    public static Result evaluate(Theirs theirs, ModelData ours, int[] permutation) {
        Result result = new Result();
        int bones = permutation.length;
        if (theirs.textureWidth > 0 && theirs.textureHeight > 0) {
            boolean same = theirs.textureWidth == ours.textureWidth && theirs.textureHeight == ours.textureHeight;
            set(result, Guard.TEXTURE_SIZE, 1, same ? 0 : 1);
            if (!same) {
                refuse(result, Guard.TEXTURE_SIZE, "2.6.5 has " + theirs.textureWidth + "x" + theirs.textureHeight + ", the file "
                        + ours.textureWidth + "x" + ours.textureHeight);
            }
        }
        if (theirs.chains != null && ours.parents != null && ours.parents.length == bones) {
            int[] sortedOf = new int[bones];
            for (int sorted = 0; sorted < bones; sorted++) {
                sortedOf[permutation[sorted]] = sorted;
            }
            int links = 0;
            int wrong = 0;
            for (int[] chain : theirs.chains) {
                int parent = -1;
                for (int bone : chain) {
                    links++;
                    if (bone < 0 || bone >= bones) {
                        wrong++;
                        parent = -2;
                        continue;
                    }
                    int sorted = sortedOf[bone];
                    wrong += ours.parents[sorted] == parent ? 0 : 1;
                    parent = sorted;
                }
            }
            set(result, Guard.CHAINS, links, wrong);
            if (wrong > 0) {
                refuse(result, Guard.CHAINS, wrong + " of " + links + " bones on the chains have another parent in the file");
            }
        }
        compare(result, Guard.PIVOTS, theirs.pivots, ours.pivots, permutation, PIVOT_TOLERANCE);
        compare(result, Guard.ROTATIONS, theirs.rotations, ours.restRotations, permutation, ROTATION_TOLERANCE);
        if (theirs.fileUnchanged != null) {
            set(result, Guard.FILE, Math.max(1, theirs.fileEntries), theirs.fileUnchanged ? 0 : 1);
            result.fileNote = theirs.fileNote;
            if (!theirs.fileUnchanged) {
                refuse(result, Guard.FILE, "the file was changed after 2.6.5 loaded this model (/ysm model reload makes 2.6.5 read it again)");
            }
        }
        return result;
    }

    private static void compare(Result result, Guard guard, float[] theirs, float[] ours, int[] permutation, float tolerance) {
        int bones = permutation.length;
        if (theirs == null || ours == null || theirs.length != bones * 3 || ours.length != bones * 3) {
            return;
        }
        int wrong = 0;
        for (int sorted = 0; sorted < bones; sorted++) {
            int their = permutation[sorted] * 3;
            int our = sorted * 3;
            if (differs(theirs[their], ours[our], tolerance) || differs(theirs[their + 1], ours[our + 1], tolerance)
                    || differs(theirs[their + 2], ours[our + 2], tolerance)) {
                wrong++;
            }
        }
        set(result, guard, bones, wrong);
        if (wrong > 0) {
            refuse(result, guard, wrong + " of " + bones + " bones differ");
        }
    }

    private static boolean differs(float theirs, float ours, float tolerance) {
        return !(Math.abs(theirs - ours) <= tolerance) && Float.floatToIntBits(theirs) != Float.floatToIntBits(ours);
    }

    private static void set(Result result, Guard guard, int compared, int differing) {
        result.compared[guard.ordinal()] = compared;
        result.differing[guard.ordinal()] = differing;
    }

    private static void refuse(Result result, Guard guard, String numbers) {
        if (result.refusal == null) {
            result.refusal = "identity guard " + guard.label + ": " + numbers + " (takeover.identity=names lifts the guards)";
        }
    }
}
