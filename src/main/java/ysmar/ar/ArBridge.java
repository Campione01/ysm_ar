package ysmar.ar;

import com.elfmcys.ysm.natives.render.BonePoseView;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.github.argon4w.acceleratedrendering.core.CoreFeature;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.VertexConsumerExtension;
import com.github.argon4w.acceleratedrendering.core.meshes.ClientMesh;
import com.github.argon4w.acceleratedrendering.core.meshes.IMesh;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.github.argon4w.acceleratedrendering.features.entities.AcceleratedEntityRenderingFeature;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import ysmar.ModelEntry;
import ysmar.PrewarmBudget;
import ysmar.YsmArConfig;
import ysmar.YsmArStats;
import ysmar.core.Attributes;
import ysmar.core.BoneMesh;
import ysmar.core.MeshVariants;
import ysmar.core.ModelData;

import java.nio.ShortBuffer;

/**
 * Everything that names Accelerated Rendering types. Render thread only.
 *
 * A submit has two halves. prepare() does all that can refuse: the model, the attributes, the two buffers of the
 * render, the bone meshes, and at last the Extract. emit() only multiplies matrices and hands the bones over, each
 * at the light of the submit unless the Extract gave the bone a light level of its own (glow). A
 * caller that has to decide early and draw late keeps what prepare() returns in between.
 */
public final class ArBridge {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final int WHITE = 0xFFFFFFFF;
    private static boolean bufferProblemLogged;

    private static final Matrix4f BONE_POSE = new Matrix4f();
    private static final Matrix4f FINAL_POSE = new Matrix4f();
    private static final Matrix3f BONE_NORMAL = new Matrix3f();
    private static final Matrix3f FINAL_NORMAL = new Matrix3f();

    private static final MeshPrebuilder PREBUILDER = new MeshPrebuilder();

    /** Set by the offline check of this class only: a mesh builder that needs no GL context. */
    static IMesh.Builder builderOverride;

    /** A submit between its two halves: the model, the buffers of this render and which Extract its poses are from. */
    static final class Prepared {
        ModelEntry entry;
        ModelData data;
        ArModel model;
        IAcceleratedVertexConsumer opaque;
        IAcceleratedVertexConsumer translucent;
        /** ModelData.extracts right after the Extract whose poses emit() may use. */
        long generation;
        long extractNanos;
    }

    private ArBridge() {
    }

    public static boolean togglesOn() {
        return CoreFeature.isLoaded() && AcceleratedEntityRenderingFeature.isEnabled()
                && AcceleratedEntityRenderingFeature.shouldUseAcceleratedPipeline();
    }

    /** True while Accelerated Rendering is inside LevelRenderer.renderLevel: not for screens, the HUD or previews. */
    public static boolean renderingLevel() {
        return CoreFeature.isRenderingLevel();
    }

    public static String describe() {
        if (!CoreFeature.isLoaded()) {
            return "core not loaded (config, GL capabilities or compute programs missing)";
        }
        return "core loaded, entity acceleration " + (AcceleratedEntityRenderingFeature.isEnabled() ? "on" : "OFF")
                + ", entity pipeline " + (AcceleratedEntityRenderingFeature.shouldUseAcceleratedPipeline() ? "accelerated" : "VANILLA")
                + ", translucent acceleration " + (CoreFeature.shouldForceAccelerateTranslucent() ? "forced on" : "off (models with partly transparent quads are not taken)")
                + ", AR entity mesh type " + AcceleratedEntityRenderingFeature.getMeshType();
    }

    public static boolean submit(ModelEntry entry, float[] attributes, PoseStack.Pose root, MultiBufferSource buffers,
                                 ResourceLocation texture, int light, int overlay) {
        if (root == null) {
            YsmArStats.reject(YsmArStats.Rejection.BAD_ARGUMENTS);
            return false;
        }
        Object prepared = prepare(entry, attributes, buffers, texture);
        return prepared != null && emit(prepared, root, light, overlay);
    }

    /** The first half of a submit while Accelerated Rendering draws the level with its entity pipeline; else null. */
    public static Object prepare(ModelEntry entry, float[] attributes, MultiBufferSource buffers, ResourceLocation texture) {
        if (!CoreFeature.isLoaded()) {
            YsmArStats.reject(YsmArStats.Rejection.AR_NOT_LOADED);
            return null;
        }
        if (!CoreFeature.isRenderingLevel()) {
            YsmArStats.reject(YsmArStats.Rejection.NOT_LEVEL);
            return null;
        }
        if (!AcceleratedEntityRenderingFeature.isEnabled()) {
            YsmArStats.reject(YsmArStats.Rejection.ENTITIES_OFF);
            return null;
        }
        if (!AcceleratedEntityRenderingFeature.shouldUseAcceleratedPipeline()) {
            YsmArStats.reject(YsmArStats.Rejection.VANILLA_PIPELINE);
            return null;
        }
        return prepareUnguarded(entry, attributes, buffers, texture);
    }

    /** Both halves in one call, without asking Accelerated Rendering whether it draws the level. */
    static boolean submitUnguarded(ModelEntry entry, float[] attributes, PoseStack.Pose root, MultiBufferSource buffers,
                                   ResourceLocation texture, int light, int overlay) {
        if (entry == null || attributes == null || root == null || buffers == null || texture == null) {
            YsmArStats.reject(YsmArStats.Rejection.BAD_ARGUMENTS);
            return false;
        }
        Prepared prepared = prepareUnguarded(entry, attributes, buffers, texture);
        return prepared != null && emit(prepared, root, light, overlay);
    }

    /**
     * Everything of a submit that can refuse, the cheap tests first and the Extract last. A model needs the buffer
     * of a mesh class when it has quads of that class at all, whichever bones the pose shows. What the caller hands
     * in is checked here and a buffer source that throws is answered with null: an exception that leaves this
     * method comes from the model or from Accelerated Rendering.
     */
    static Prepared prepareUnguarded(ModelEntry entry, float[] attributes, MultiBufferSource buffers, ResourceLocation texture) {
        if (entry == null || attributes == null || buffers == null || texture == null) {
            YsmArStats.reject(YsmArStats.Rejection.BAD_ARGUMENTS);
            return null;
        }
        ModelData data = entry.data();
        if (data == null) {
            YsmArStats.reject(YsmArStats.Rejection.NOT_READY);
            return null;
        }
        if (!Attributes.sanitise(attributes, data.boneCount, YsmArConfig.current().takeoverPruneNonFinite)) {
            YsmArStats.reject(YsmArStats.Rejection.BAD_ATTRIBUTES);
            return null;
        }
        IAcceleratedVertexConsumer opaque = null;
        IAcceleratedVertexConsumer translucent = null;
        try {
            if (data.emittedQuads[BoneMesh.OPAQUE] > 0) {
                opaque = VertexConsumerExtension.getAccelerated(buffers.getBuffer(RenderType.entityCutout(texture)));
            }
            if (data.emittedQuads[BoneMesh.TRANSLUCENT] > 0 && (opaque == null || opaque.isAccelerated())) {
                translucent = VertexConsumerExtension.getAccelerated(buffers.getBuffer(RenderType.entityTranslucentCull(texture)));
            }
        } catch (RuntimeException fromTheCaller) {
            YsmArStats.reject(YsmArStats.Rejection.BAD_ARGUMENTS);
            if (!bufferProblemLogged) {
                bufferProblemLogged = true;
                LOGGER.warn("ysm_ar: the buffer source handed to a submit threw; the model stays in use and further such submits are only counted", fromTheCaller);
            }
            return null;
        }
        if (opaque != null && !opaque.isAccelerated()) {
            YsmArStats.reject(YsmArStats.Rejection.NOT_ACCELERATED);
            return null;
        }
        if (translucent != null && !translucent.isAccelerated()) {
            YsmArStats.reject(YsmArStats.Rejection.NOT_ACCELERATED);
            return null;
        }

        ArModel model = ArModel.of(entry, data);
        if (!warm(model, opaque, translucent)) {
            YsmArStats.reject(YsmArStats.Rejection.PREWARMING);
            return null;
        }
        Prepared prepared = new Prepared();
        prepared.entry = entry;
        prepared.data = data;
        prepared.model = model;
        prepared.opaque = opaque;
        prepared.translucent = translucent;
        if (!extract(prepared, attributes)) {
            YsmArStats.reject(YsmArStats.Rejection.EXTRACT_FAILED);
            return null;
        }
        return prepared;
    }

    /** True while the state of the model still holds the Extract of this submit: nobody extracted since. */
    public static boolean holdsExtract(Object handle) {
        Prepared prepared = (Prepared) handle;
        return prepared.entry.data() == prepared.data && prepared.data.extracts == prepared.generation;
    }

    /**
     * Extracts for a prepared submit with other attributes: its emit then draws those. False: the attributes were
     * refused or the Extract failed, and the state holds no poses to draw.
     */
    public static boolean extractAgain(Object handle, float[] attributes) {
        Prepared prepared = (Prepared) handle;
        if (prepared.entry.data() != prepared.data || attributes == null
                || !Attributes.sanitise(attributes, prepared.data.boneCount, YsmArConfig.current().takeoverPruneNonFinite)) {
            return false;
        }
        return extract(prepared, attributes);
    }

    private static boolean extract(Prepared prepared, float[] attributes) {
        long start = System.nanoTime();
        boolean extracted = prepared.data.extract(attributes);
        prepared.extractNanos += System.nanoTime() - start;
        prepared.generation = extracted ? prepared.data.extracts : -1;
        return extracted;
    }

    /**
     * The second half: one doRender per render bone and mesh class of the Extract the submit holds, all opaque draws
     * first, then the translucent ones. False, with nothing drawn, when that Extract is gone.
     */
    public static boolean emit(Object handle, PoseStack.Pose root, int light, int overlay) {
        Prepared prepared = (Prepared) handle;
        if (root == null || !holdsExtract(prepared)) {
            YsmArStats.reject(root == null ? YsmArStats.Rejection.BAD_ARGUMENTS : YsmArStats.Rejection.EXTRACT_FAILED);
            return false;
        }
        long start = System.nanoTime();
        ModelData data = prepared.data;
        ArModel model = prepared.model;
        NativeModelState state = data.state;
        // Both views are only valid until the next extract of this state; they are read here and nowhere else.
        BonePoseView poses = state.getBonePoses();
        ShortBuffer renderBones = state.getRenderBoneIndices();
        int count = renderBones.capacity();
        BoneMesh[] meshes = data.meshes;
        boolean reverseMirrored = YsmArConfig.current().reverseMirrored;
        Matrix4f rootPose = root.pose();
        Matrix3f rootNormal = root.normal();
        int draws = 0;
        long vertices = 0;
        for (int meshClass = BoneMesh.OPAQUE; meshClass <= BoneMesh.TRANSLUCENT; meshClass++) {
            IAcceleratedVertexConsumer consumer = meshClass == BoneMesh.OPAQUE ? prepared.opaque : prepared.translucent;
            if (consumer == null) {
                continue;
            }
            for (int index = 0; index < count; index++) {
                int bone = renderBones.get(index) & 0xFFFF;
                BoneMesh mesh = meshes[bone];
                if (mesh == null || mesh.emittedQuads(meshClass) == 0) {
                    continue;
                }
                rootPose.mul(poses.getPose(bone, BONE_POSE), FINAL_POSE);
                rootNormal.mul(poses.getNormal(bone, BONE_NORMAL), FINAL_NORMAL);
                BoneRenderer renderer = model.renderer(bone, meshClass, MeshVariants.useReversed(FINAL_POSE, reverseMirrored));
                int glow = poses.getLightLevel(bone);
                // Block and sky light both at the level of the bone, as the official renderer packs it.
                consumer.doRender(renderer, null, FINAL_POSE, FINAL_NORMAL, glow < 0 ? light : glow << 4 | glow << 20, overlay, WHITE);
                draws++;
                vertices += mesh.emittedVertices(meshClass);
            }
        }
        YsmArStats.submitted(draws, vertices, prepared.extractNanos, System.nanoTime() - start);
        return true;
    }

    /**
     * True when every bone mesh of the model exists for the vertex layouts of this pass. Otherwise builds missing ones
     * as far as the budget of the frame goes, all bones of both classes, and returns false until none is missing:
     * nothing of the model goes to Accelerated Rendering before that, so that no frame has to build hundreds of
     * meshes at once.
     */
    private static boolean warm(ArModel model, IAcceleratedVertexConsumer opaque, IAcceleratedVertexConsumer translucent) {
        MeshPrebuilder prebuilder = PREBUILDER;
        prebuilder.begin(PrewarmBudget.remaining());
        if (opaque != null) {
            prebuilder.run(opaque, model, BoneMesh.OPAQUE);
        }
        if (translucent != null) {
            prebuilder.run(translucent, model, BoneMesh.TRANSLUCENT);
        }
        if (prebuilder.built > 0) {
            long spent = prebuilder.elapsedNanos();
            PrewarmBudget.spend(spent);
            YsmArStats.prebuilt(prebuilder.built, spent);
        }
        return prebuilder.complete;
    }

    static IMesh.Builder meshBuilder() {
        if (builderOverride != null) {
            return builderOverride;
        }
        return YsmArConfig.current().clientMeshes ? ClientMesh.Builder.INSTANCE : ServerMesh.Builder.INSTANCE;
    }
}
