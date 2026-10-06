package ysmar.takeover;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import ysmar.ModelEntry;
import ysmar.YsmArSubmitter;

import java.util.Arrays;

/**
 * The two steps of one taken-over render, without anything of Yes Steve Model in them.
 *
 * DECIDE, at the visibility test, does all that can refuse and keeps copies of the bone values and of the pose as
 * they are then. EMIT draws. Code of other mods may run between the two and change both; EMIT is handed the bone
 * values and the pose as they are when it runs and draws those. When the values are as they were, the Extract of
 * DECIDE is drawn as it is. Render thread only.
 */
public final class LateRead {
    /** The pose at EMIT counts as changed when an element is this far from the pose of DECIDE with the model scale. */
    public static final float POSE_TOLERANCE = 1.0e-5f;

    /** A submit in two halves, as YsmArSubmitter offers it. The offline check puts its own entry points here. */
    public interface Submit {
        Object prepare(ModelEntry entry, float[] attributes, MultiBufferSource buffers, ResourceLocation texture);

        boolean holdsExtract(ModelEntry entry, Object prepared);

        boolean extractAgain(ModelEntry entry, Object prepared, float[] attributes);

        boolean emit(ModelEntry entry, Object prepared, PoseStack.Pose root, int light, int overlay);
    }

    public static final Submit GAME = new Submit() {
        @Override
        public Object prepare(ModelEntry entry, float[] attributes, MultiBufferSource buffers, ResourceLocation texture) {
            return YsmArSubmitter.prepare(entry, attributes, buffers, texture);
        }

        @Override
        public boolean holdsExtract(ModelEntry entry, Object prepared) {
            return YsmArSubmitter.holdsExtract(entry, prepared);
        }

        @Override
        public boolean extractAgain(ModelEntry entry, Object prepared, float[] attributes) {
            return YsmArSubmitter.extractAgain(entry, prepared, attributes);
        }

        @Override
        public boolean emit(ModelEntry entry, Object prepared, PoseStack.Pose root, int light, int overlay) {
            return YsmArSubmitter.emit(entry, prepared, root, light, overlay);
        }
    };

    /**
     * What DECIDE leaves for EMIT. The caller fills the first group. The three arrays may belong to the binding of
     * the model: only one render is armed at a time (see Windows).
     */
    public static final class Armed {
        public ModelEntry entry;
        /** permutation[sorted bake index] = bone index of 2.6.5. */
        public int[] permutation;
        /** glow[sorted bake index]: the bone is drawn at full light whatever the light of the entity; null for none. */
        public boolean[] glow;
        /** 14 floats per bone, scratch for the Extract. */
        public float[] attributes;
        /** 12 floats per bone: the copy of the live values DECIDE read. */
        public float[] liveCopy;
        /** Values with slot 11 set on some bone may be drawn (the caller provides what that flag stands for). */
        public boolean flagsAllowed;
        public float width;
        public float height;
        public int light;
        public int overlay;
        /** Applied to the root pose last; null for none. */
        public Matrix4f shift;

        final Matrix4f pose = new Matrix4f();
        final Matrix3f normal = new Matrix3f();
        Object prepared;

        /** What EMIT found: values other than those of DECIDE, another pose, an Extract of its own, the copy drawn after all. */
        public boolean bonesChanged;
        public boolean poseChanged;
        public boolean extractedAgain;
        public boolean copyDrawn;
        /** Bones with slot 11 set among the values of the last Extract. */
        public int flagged;
    }

    private static final PoseStack ROOT = new PoseStack();

    private LateRead() {
    }

    /**
     * DECIDE. live: the bone values of 2.6.5 as they are now; pose: the pose of the entity before the model scale.
     * False: refused, nothing may be hidden (the submitter has counted why).
     */
    public static boolean decide(Submit submit, Armed armed, float[] live, PoseStack.Pose pose, MultiBufferSource buffers,
                                 ResourceLocation texture) {
        if (!fill(armed, live)) {
            return false;
        }
        armed.prepared = submit.prepare(armed.entry, armed.attributes, buffers, texture);
        if (armed.prepared == null) {
            return false;
        }
        System.arraycopy(live, 0, armed.liveCopy, 0, armed.liveCopy.length);
        armed.pose.set(pose.pose());
        armed.normal.set(pose.normal());
        return true;
    }

    /**
     * EMIT. liveNow: the bone values as they are now, or null when they cannot be read any more. late: the pose with
     * the model scale already applied by Yes Steve Model, or null, in which case the pose of DECIDE gets that scale
     * here. False: nothing was drawn.
     */
    public static boolean emit(Submit submit, Armed armed, float[] liveNow, PoseStack.Pose late) {
        armed.bonesChanged = liveNow != null && !Arrays.equals(liveNow, armed.liveCopy);
        if (armed.bonesChanged || !submit.holdsExtract(armed.entry, armed.prepared)) {
            armed.extractedAgain = true;
            boolean fresh = armed.bonesChanged && fill(armed, liveNow) && submit.extractAgain(armed.entry, armed.prepared, armed.attributes);
            if (!fresh) {
                // Values of now that Extract does not take: what passed every test at DECIDE is drawn instead.
                armed.copyDrawn = armed.bonesChanged;
                if (!fill(armed, armed.liveCopy) || !submit.extractAgain(armed.entry, armed.prepared, armed.attributes)) {
                    return false;
                }
            }
        }
        PoseStack.Pose root = ROOT.last();
        root.pose().set(armed.pose);
        root.normal().set(armed.normal);
        ROOT.scale(armed.width, armed.height, armed.width);
        if (late != null) {
            armed.poseChanged = !root.pose().equals(late.pose(), POSE_TOLERANCE);
            root.pose().set(late.pose());
            root.normal().set(late.normal());
        }
        if (armed.shift != null) {
            armed.shift.mul(root.pose(), root.pose());
        }
        return submit.emit(armed.entry, armed.prepared, root, armed.light, armed.overlay);
    }

    private static boolean fill(Armed armed, float[] live) {
        int bones = armed.permutation.length;
        if (!AttributeMap.sized(live, bones)) {
            return false;
        }
        int flagged = AttributeMap.flagged(live, bones);
        if (flagged > 0 && !armed.flagsAllowed) {
            return false;
        }
        AttributeMap.fill(live, armed.permutation, armed.glow, armed.attributes);
        if (flagged > 0) {
            AttributeMap.markFlagged(live, armed.permutation, armed.attributes);
        }
        armed.flagged = flagged;
        return true;
    }
}
