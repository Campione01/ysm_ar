package ysmar.takeover;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The bone order of the bake against the bone order of Yes Steve Model 2.6.5, matched by name. The two orders never
 * agree, so every attribute goes through this table. Messages carry counts only: bone names are model content.
 */
public final class BonePermutation {
    /** indices[sorted bake index] = index in the bone list of 2.6.5; null when the lists do not match. */
    public final int[] indices;
    /** Why there is no table, else null. */
    public final String failure;

    private BonePermutation(int[] indices, String failure) {
        this.indices = indices;
        this.failure = failure;
    }

    /** Both lists must hold the same names, each exactly once. */
    public static BonePermutation build(List<String> theirs, List<String> baked) {
        return build(theirs, baked, null);
    }

    /**
     * emptyNameInFile: the name the file's bone with an empty name has in the baked list (the bake takes no empty
     * name; 2.6.5 keeps it), or null when the file has no such bone. The empty name of 2.6.5 is matched to it.
     */
    public static BonePermutation build(List<String> theirs, List<String> baked, String emptyNameInFile) {
        if (theirs == null || baked == null || baked.isEmpty()) {
            return refused("no bone list");
        }
        if (theirs.size() != baked.size()) {
            return refused("bone count differs: 2.6.5 has " + theirs.size() + ", the file has " + baked.size());
        }
        Map<String, Integer> positions = new HashMap<>(theirs.size() * 2);
        int unnamed = 0;
        int repeated = 0;
        for (int position = 0; position < theirs.size(); position++) {
            String name = theirs.get(position);
            if (emptyNameInFile != null && name != null && name.isEmpty()) {
                name = emptyNameInFile;
            }
            if (name == null) {
                unnamed++;
            } else if (positions.putIfAbsent(name, position) != null) {
                repeated++;
            }
        }
        if (unnamed > 0) {
            return refused(unnamed + " of the " + theirs.size() + " bones of 2.6.5 have no name");
        }
        if (repeated > 0) {
            return refused(repeated + " bone names occur more than once in 2.6.5");
        }
        int[] indices = new int[baked.size()];
        boolean[] taken = new boolean[baked.size()];
        int unknown = 0;
        int twice = 0;
        for (int sorted = 0; sorted < indices.length; sorted++) {
            String name = baked.get(sorted);
            Integer position = name == null ? null : positions.get(name);
            if (position == null) {
                unknown++;
            } else if (taken[position]) {
                twice++;
            } else {
                taken[position] = true;
                indices[sorted] = position;
            }
        }
        if (twice > 0) {
            return refused(twice + " bone names occur more than once in the file");
        }
        if (unknown > 0) {
            return refused(unknown + " of the " + indices.length + " bone names of the file are unknown to 2.6.5");
        }
        return new BonePermutation(indices, null);
    }

    private static BonePermutation refused(String reason) {
        return new BonePermutation(null, reason);
    }
}
