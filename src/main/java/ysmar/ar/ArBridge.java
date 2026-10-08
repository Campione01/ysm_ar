package ysmar.ar;

import com.elfmcys.ysm.natives.render.BonePoseView;
import com.elfmcys.ysm.natives.render.NativeModelState;
import com.github.argon4w.acceleratedrendering.core.CoreFeature;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.VertexConsumerExtension;
import com.github.argon4w.acceleratedrendering.core.meshes.ClientMesh;
import com.github.argon4w.acceleratedrendering.core.meshes.IMesh;
import com.github.argon4w.acceleratedrendering.core.meshes.ServerMesh;
import com.github.argon4w.acceleratedrendering.core.meshes.data.cache.MeshDataCacheType;
import com.github.argon4w.acceleratedrendering.features.entities.AcceleratedEntityRenderingFeature;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.resources.ResourceLocation;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import ysmar.ModelEntry;
import ysmar.PrewarmBudget;
import ysmar.YsmArConfig;
import ysmar.YsmArStats;
import ysmar.core.Attributes;
import ysmar.core.BoneMesh;
import ysmar.core.LegacyShade;
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
 *
 * Two things emit() does for the picture of Yes Steve Model 2.6.5. A bone whose scale differs between the axes is
 * drawn part by part where the game's own entity shader lights it, each part at the shade 2.6.5 gives it (see
 * LegacyShade). And two bones that share a face are handed over, while both are drawn, as meshes that come out in
 * the order of the draw calls (see BoneMesh.partners).
 */
public final class ArBridge {
    private static final Logger LOGGER = LogManager.getLogger("ysm_ar");
    private static final int WHITE = 0xFFFFFFFF;
    /** Part draws of one model in one submit; a model that would need more is drawn with unit normals as a whole. */
    private static final int MAX_PART_DRAWS = 4096;
    private static boolean bufferProblemLogged;

    private static final Matrix4f BONE_POSE = new Matrix4f();
    private static final Matrix4f FINAL_POSE = new Matrix4f();
    private static final Matrix3f BONE_NORMAL = new Matrix3f();
    private static final Matrix3f FINAL_NORMAL = new Matrix3f();
    private static final Matrix3f PART_NORMAL = new Matrix3f();
    private static final Vector3f PART_DIRECTION = new Vector3f();
    private static int[] partShades = new int[64];

    private static final MeshPrebuilder PREBUILDER = new MeshPrebuilder();
    // Set by warm(): MeshPrebuilder.vanillaKey of the opaque and of the translucent consumer of the submit.
    private static Object warmLegacyOpaque;
    private static Object warmLegacyTranslucent;

    /** Set by the offline check of this class only: a mesh builder that needs no GL context. */
    static IMesh.Builder builderOverride;
    /** Set by the offline check only: stands in for the builder of client meshes; without it builderOverride does. */
    static IMesh.Builder orderedBuilderOverride;

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
        /**
         * Per mesh class: the mesh key of its consumer when the game's own entity shader lights what it draws (no
         * shader pack), so that the shading of 2.6.5 can be reproduced; else null.
         */
        Object legacyOpaque;
        Object legacyTranslucent;
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

    /**
     * True when Accelerated Rendering hands out the mesh it already has for vertices it is given a second time (its
     * setting mesh_merge_type). Its mesh buffers never shrink: without that, a model that is read again would add
     * all its meshes to them once more.
     */
    public static boolean mergesMeshes() {
        return CoreFeature.getMeshMergeType() == MeshDataCacheType.MERGED;
    }

    public static String describe() {
        if (!CoreFeature.isLoaded()) {
            return "core not loaded (config, GL capabilities or compute programs missing)";
        }
        return "core loaded, entity acceleration " + (AcceleratedEntityRenderingFeature.isEnabled() ? "on" : "OFF")
                + ", entity pipeline " + (AcceleratedEntityRenderingFeature.shouldUseAcceleratedPipeline() ? "accelerated" : "VANILLA")
                + ", translucent acceleration " + (CoreFeature.shouldForceAccelerateTranslucent() ? "forced on" : "off (models with partly transparent quads are not taken)")
                + ", AR entity mesh type " + AcceleratedEntityRenderingFeature.getMeshType()
                + ", equal meshes " + (mergesMeshes() ? "merged" : "NOT merged (mesh_merge_type: models are kept in memory whatever models.idle_seconds says)");
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
        prepared.legacyOpaque = warmLegacyOpaque;
        prepared.legacyTranslucent = warmLegacyTranslucent;
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
     * The second half: the draws of every render bone and mesh class of the Extract the submit holds, all opaque
     * draws first, then the translucent ones; one doRender per bone and class, or one per shading part of it.
     * False, with nothing drawn, when that Extract is gone.
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
        YsmArConfig config = YsmArConfig.current();
        boolean reverseMirrored = config.reverseMirrored;
        boolean legacyShading = config.takeoverLegacyShading && (prepared.legacyOpaque != null || prepared.legacyTranslucent != null)
                && partDraws(poses, renderBones, meshes) <= MAX_PART_DRAWS;
        boolean constantAmbient = legacyShading && constantAmbientLight();
        int[] shown = model.markShown(renderBones);
        int mark = model.mark();
        Matrix4f rootPose = root.pose();
        Matrix3f rootNormal = root.normal();
        int draws = 0;
        long vertices = 0;
        for (int meshClass = BoneMesh.OPAQUE; meshClass <= BoneMesh.TRANSLUCENT; meshClass++) {
            IAcceleratedVertexConsumer consumer = meshClass == BoneMesh.OPAQUE ? prepared.opaque : prepared.translucent;
            if (consumer == null) {
                continue;
            }
            Object legacyKey = !legacyShading ? null : meshClass == BoneMesh.OPAQUE ? prepared.legacyOpaque : prepared.legacyTranslucent;
            for (int index = 0; index < count; index++) {
                int bone = renderBones.get(index) & 0xFFFF;
                BoneMesh mesh = meshes[bone];
                if (mesh == null || mesh.emittedQuads(meshClass) == 0) {
                    continue;
                }
                rootPose.mul(poses.getPose(bone, BONE_POSE), FINAL_POSE);
                rootNormal.mul(poses.getNormal(bone, BONE_NORMAL), FINAL_NORMAL);
                boolean reversed = MeshVariants.useReversed(FINAL_POSE, reverseMirrored);
                // In call order while a bone it shares a face with is drawn too; alone, the order does not show.
                boolean inCallOrder = shown != null && mesh.partners() != null && anyShown(mesh.partners(), shown, mark);
                int glow = poses.getLightLevel(bone);
                // Block and sky light both at the level of the bone, as the official renderer packs it.
                int boneLight = glow < 0 ? light : glow << 4 | glow << 20;
                int partDraws = legacyKey == null || poses.isUniformScale(bone) ? 0 : legacyParts(consumer, model, mesh, bone, meshClass, reversed,
                        inCallOrder, legacyKey, poses.getNormalScale(bone), constantAmbient, boneLight, overlay);
                if (partDraws == 0) {
                    BoneRenderer renderer = model.renderer(bone, meshClass, reversed);
                    renderer.inCallOrder = inCallOrder;
                    consumer.doRender(renderer, null, FINAL_POSE, FINAL_NORMAL, boneLight, overlay, WHITE);
                    draws++;
                } else {
                    draws += partDraws;
                }
                vertices += mesh.emittedVertices(meshClass);
            }
        }
        YsmArStats.submitted(draws, vertices, prepared.extractNanos, System.nanoTime() - start);
        return true;
    }

    private static boolean anyShown(int[] partners, int[] shown, int mark) {
        for (int partner : partners) {
            if (shown[partner] == mark) {
                return true;
            }
        }
        return false;
    }

    /** How many parts the render bones whose scale differs between the axes have; not counted on beyond the limit. */
    private static int partDraws(BonePoseView poses, ShortBuffer renderBones, BoneMesh[] meshes) {
        int parts = 0;
        for (int index = 0, count = renderBones.capacity(); index < count && parts <= MAX_PART_DRAWS; index++) {
            int bone = renderBones.get(index) & 0xFFFF;
            if (meshes[bone] != null && !poses.isUniformScale(bone)) {
                parts += meshes[bone].parts();
            }
        }
        return parts;
    }

    /** True in a level the game lights like the nether (Lighting.setupNetherLevel). */
    private static boolean constantAmbientLight() {
        Minecraft minecraft = Minecraft.getInstance();
        ClientLevel level = minecraft == null ? null : minecraft.level;
        return level != null && level.effects().constantAmbientLight();
    }

    /**
     * Draws one mesh class of a bone whose scale differs between the axes part by part, each part at the shade Yes
     * Steve Model 2.6.5 gives it (see LegacyShade): the vertices get a normal the game lights fully, the shade goes
     * into the colour. BONE_POSE, FINAL_POSE and FINAL_NORMAL are those of the bone. Returns the number of draws;
     * 0, with nothing drawn, when no part comes out differently from the ordinary draw of the bone, or while the
     * part meshes are still being built.
     */
    private static int legacyParts(IAcceleratedVertexConsumer consumer, ArModel model, BoneMesh mesh, int bone, int meshClass, boolean reversed,
                                   boolean inCallOrder, Object key, float uniformScale, boolean constantAmbient, int light, int overlay) {
        float length = LegacyShade.length(BONE_POSE, uniformScale);
        if (!(length > 0.0f) || !Float.isFinite(length)) {
            return 0;
        }
        int parts = mesh.parts();
        if (partShades.length < parts) {
            partShades = new int[parts];
        }
        boolean differs = false;
        for (int part = 0; part < parts; part++) {
            if (mesh.partClass(part) != meshClass) {
                continue;
            }
            PART_DIRECTION.set(mesh.partNormal(part, 0), mesh.partNormal(part, 1), mesh.partNormal(part, 2)).mul(FINAL_NORMAL);
            partShades[part] = LegacyShade.shade(PART_DIRECTION.x * length, PART_DIRECTION.y * length, PART_DIRECTION.z * length, constantAmbient);
            PART_DIRECTION.normalize();
            differs |= partShades[part] != LegacyShade.shade(PART_DIRECTION.x, PART_DIRECTION.y, PART_DIRECTION.z, constantAmbient);
        }
        if (!differs || !model.partsBuilt(bone, meshClass, reversed, key) && !buildParts(consumer, model, bone, meshClass, reversed)) {
            return 0;
        }
        int draws = 0;
        for (int part = 0; part < parts; part++) {
            if (mesh.partClass(part) != meshClass) {
                continue;
            }
            BoneRenderer renderer = model.partRenderer(bone, part, reversed);
            renderer.inCallOrder = inCallOrder;
            LegacyShade.towardsLight(mesh.partNormal(part, 0), mesh.partNormal(part, 1), mesh.partNormal(part, 2), PART_NORMAL);
            consumer.doRender(renderer, null, FINAL_POSE, PART_NORMAL, light, overlay, 0xFF000000 | partShades[part] * 0x010101);
            draws++;
        }
        return draws;
    }

    /**
     * Builds the part meshes of one bone and class as far as what is left of the budget of the frame goes. False
     * while one is missing: the bone is drawn as a whole until then, so that no frame builds hundreds of meshes.
     */
    private static boolean buildParts(IAcceleratedVertexConsumer consumer, ArModel model, int bone, int meshClass, boolean reversed) {
        MeshPrebuilder prebuilder = PREBUILDER;
        prebuilder.begin(PrewarmBudget.remaining());
        prebuilder.runParts(consumer, model, meshClass, bone, reversed);
        if (prebuilder.built > 0) {
            long spent = prebuilder.elapsedNanos();
            PrewarmBudget.spend(spent);
            YsmArStats.prebuilt(prebuilder.built, spent);
        }
        return prebuilder.complete;
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
        warmLegacyOpaque = null;
        warmLegacyTranslucent = null;
        if (opaque != null) {
            prebuilder.run(opaque, model, BoneMesh.OPAQUE);
            warmLegacyOpaque = prebuilder.vanillaKey();
        }
        if (translucent != null) {
            prebuilder.run(translucent, model, BoneMesh.TRANSLUCENT);
            warmLegacyTranslucent = prebuilder.vanillaKey();
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

    /**
     * For the meshes that have to come out in the order of the draw calls: a client mesh is written where it is
     * drawn, while server meshes are filled in mesh by mesh in an order of Accelerated Rendering's own, whatever
     * order they were drawn in.
     */
    static IMesh.Builder orderedMeshBuilder() {
        if (builderOverride != null) {
            return orderedBuilderOverride != null ? orderedBuilderOverride : builderOverride;
        }
        return ClientMesh.Builder.INSTANCE;
    }
}
