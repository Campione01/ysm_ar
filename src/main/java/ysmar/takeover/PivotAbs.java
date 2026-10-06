package ysmar.takeover;

import com.elfmcys.ysm.natives.render.BonePoseView;
import com.elfmcys.ysm.natives.render.NativeModelState;
import it.unimi.dsi.fastutil.shorts.ShortList;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ysmar.core.ModelData;

/**
 * The values of the Molang function bone_pivot_abs. Yes Steve Model 2.6.5 reads them from an array of 4 floats per
 * bone that only its own native draw fills; a model this mod draws instead needs them from here.
 *
 * Measured against 2.6.5: for a bone whose slot 11 is set and which is visible, the array holds the pivot of the
 * bone, moved by the pose of the bone, in model units (1/16 block) with x mirrored: (-16x, 16y, 16z) of
 * pose * (pivot / 16). The fourth float is not used, and nothing is written for other bones.
 *
 * Which bones are visible is asked from Extract itself: AttributeMap.markFlagged gives a flagged bone a locator
 * number, and Extract lists the locator bones it reached, which leaves out a bone in a subtree it pruned (a zero
 * scale, hidden children) and a bone whose own cubes are hidden. Poses of bones Extract did not reach are left over
 * from earlier calls and must not be read. Render thread only.
 */
public final class PivotAbs {
    public static final int FLOATS = 4;
    private static final float MODEL_UNIT = 1.0f / 16.0f;

    private static final Matrix4f POSE = new Matrix4f();
    private static final Vector3f POINT = new Vector3f();

    private PivotAbs() {
    }

    public static boolean sized(float[] values, int bones) {
        return values != null && bones > 0 && values.length == bones * FLOATS;
    }

    /**
     * Writes the values of every flagged bone the last Extract of the model's state reached. permutation[sorted bake
     * index] = bone index of 2.6.5, the index of the bone in `values`. Returns the number of bones written.
     */
    public static int write(ModelData data, int[] permutation, float[] values) {
        NativeModelState state = data == null ? null : data.state;
        if (state == null || !state.isValid()) {
            return 0;
        }
        ShortList reached = state.getLocatorBoneIndices();
        BonePoseView poses = state.getBonePoses();
        float[] pivots = data.pivots;
        int count = reached.size();
        for (int index = 0; index < count; index++) {
            int sorted = reached.getShort(index) & 0xFFFF;
            Vector3f point = POINT.set(pivots[sorted * 3] * MODEL_UNIT, pivots[sorted * 3 + 1] * MODEL_UNIT, pivots[sorted * 3 + 2] * MODEL_UNIT);
            poses.getPose(sorted, POSE).transformPosition(point);
            int base = permutation[sorted] * FLOATS;
            values[base] = -16.0f * point.x;
            values[base + 1] = 16.0f * point.y;
            values[base + 2] = 16.0f * point.z;
        }
        return count;
    }
}
