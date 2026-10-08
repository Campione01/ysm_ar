package ysmar.ar;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.meshes.IMesh;
import ysmar.ModelEntry;
import ysmar.YsmArConfig;
import ysmar.core.BoneMesh;
import ysmar.core.ModelData;

import java.nio.ShortBuffer;
import java.util.Arrays;

/**
 * The renderer objects of one model: one per bone, mesh class and winding variant, created on first use and then
 * the same object for every draw. Motion vector history of other mods is keyed by these objects.
 */
final class ArModel {
    private static final int MAX_WARM = 16;

    private final ModelData data;
    private final IMesh.Builder builder;
    /** For the meshes that have to come out in the order of the draw calls (BoneMesh.partners); the builder itself when there are none. */
    private final IMesh.Builder orderedBuilder;
    private final BoneRenderer[] renderers;
    /** Per bone, made on first use: the renderers of its shading parts, two per part (winding as baked, reversed). */
    private final BoneRenderer[][] partRenderers;
    /** Per bone, mesh class and winding: the mesh key (see BoneRenderer.key) every part mesh of them exists for, or null. */
    private final Object[] partsKey;
    /** Per bone that shares a face with another bone: the mark of the last draw of the model it was among the render bones of. */
    private final int[] shown;
    private int mark;

    // How far the meshes of one class are built for one mesh key (see BoneRenderer.key): entry i is about class
    // warmClass[i] and key warmKey[i]; bones below warmNext[i] have their mesh.
    private final Object[] warmKey = new Object[MAX_WARM];
    private final int[] warmClass = new int[MAX_WARM];
    private final int[] warmNext = new int[MAX_WARM];
    private final boolean[] warmDone = new boolean[MAX_WARM];
    private int warmCount;

    private ArModel(ModelData data) {
        this.data = data;
        this.builder = ArBridge.meshBuilder();
        this.orderedBuilder = YsmArConfig.current().takeoverFaceOrder ? ArBridge.orderedMeshBuilder() : builder;
        this.renderers = new BoneRenderer[data.boneCount * 4];
        this.partRenderers = new BoneRenderer[data.boneCount][];
        this.partsKey = new Object[data.boneCount * 4];
        this.shown = orderedBuilder != builder && data.orderedBones > 0 ? new int[data.boneCount] : null;
    }

    static ArModel of(ModelEntry entry, ModelData data) {
        if (entry.renderData() instanceof ArModel existing && existing.data == data) {
            return existing;
        }
        ArModel created = new ArModel(data);
        entry.renderData(created);
        return created;
    }

    BoneRenderer renderer(int bone, int meshClass, boolean reversed) {
        int index = bone * 4 + meshClass * 2 + (reversed ? 1 : 0);
        BoneRenderer renderer = renderers[index];
        if (renderer == null) {
            BoneMesh mesh = data.meshes[bone];
            renderer = new BoneRenderer(mesh, meshClass, reversed, builder, shown != null && mesh.partners() != null ? orderedBuilder : null, -1);
            renderers[index] = renderer;
        }
        return renderer;
    }

    /** The renderer of one shading part of a bone (see BoneMesh.parts). */
    BoneRenderer partRenderer(int bone, int part, boolean reversed) {
        BoneMesh mesh = data.meshes[bone];
        BoneRenderer[] ofBone = partRenderers[bone];
        if (ofBone == null) {
            ofBone = new BoneRenderer[mesh.parts() * 2];
            partRenderers[bone] = ofBone;
        }
        int index = part * 2 + (reversed ? 1 : 0);
        BoneRenderer renderer = ofBone[index];
        if (renderer == null) {
            renderer = new BoneRenderer(mesh, mesh.partClass(part), reversed, builder, shown != null && mesh.partners() != null ? orderedBuilder : null, part);
            ofBone[index] = renderer;
        }
        return renderer;
    }

    /**
     * Marks the bones among these render bones that share a face with another bone, and returns the marks, or null
     * for a model none of whose meshes has to keep the order of the draw calls. A bone is among them when its entry
     * is mark().
     */
    int[] markShown(ShortBuffer renderBones) {
        if (shown == null) {
            return null;
        }
        if (++mark == 0) {
            Arrays.fill(shown, 0);
            mark = 1;
        }
        BoneMesh[] meshes = data.meshes;
        for (int index = 0, count = renderBones.capacity(); index < count; index++) {
            int bone = renderBones.get(index) & 0xFFFF;
            BoneMesh mesh = meshes[bone];
            if (mesh != null && mesh.partners() != null) {
                shown[bone] = mark;
            }
        }
        return shown;
    }

    int mark() {
        return mark;
    }

    /** True when every bone mesh of the class exists for that mesh key. */
    boolean isWarm(int meshClass, Object key) {
        int slot = find(meshClass, key);
        return slot >= 0 && warmDone[slot];
    }

    /**
     * Builds bone meshes of one class for the layout of the consumer, in bone order, from where the last call stopped.
     * Stops when `budgetNanos` have passed since `startNanos`, but only after at least one mesh, unless `exhausted`.
     * Returns how many were built; isWarm() tells whether any is still missing. Meshes of the reversed-winding
     * variant and of shading parts are left for later: the first are never used by the default settings, the
     * second only by bones an animation scales by different factors along the axes (see warmUpParts).
     */
    int warmUp(int meshClass, IAcceleratedVertexConsumer consumer, long startNanos, long budgetNanos, boolean exhausted) {
        Object key = BoneRenderer.key(consumer);
        int slot = find(meshClass, key);
        if (slot < 0) {
            if (warmCount == MAX_WARM) {
                warmCount = 0;
            }
            slot = warmCount++;
            warmKey[slot] = key;
            warmClass[slot] = meshClass;
            warmNext[slot] = 0;
            warmDone[slot] = false;
        }
        if (warmDone[slot]) {
            return 0;
        }
        BoneMesh[] meshes = data.meshes;
        int built = 0;
        while (warmNext[slot] < meshes.length) {
            int bone = warmNext[slot];
            BoneMesh mesh = meshes[bone];
            if (mesh != null && mesh.emittedQuads(meshClass) > 0) {
                BoneRenderer renderer = renderer(bone, meshClass, false);
                while (!renderer.has(key)) {
                    if (exhausted || built > 0 && System.nanoTime() - startNanos >= budgetNanos) {
                        return built;
                    }
                    renderer.prepare(consumer);
                    built++;
                }
            }
            warmNext[slot] = bone + 1;
        }
        warmDone[slot] = true;
        return built;
    }

    /** True when every part mesh of the bone exists for that class, winding and mesh key. */
    boolean partsBuilt(int bone, int meshClass, boolean reversed, Object key) {
        Object built = partsKey[bone * 4 + meshClass * 2 + (reversed ? 1 : 0)];
        return built != null && (built == key || built.equals(key));
    }

    /**
     * Builds the part meshes of one bone and class for the layout of the consumer, under the same rule as warmUp().
     * Returns how many were built; partsBuilt() tells whether any is still missing.
     */
    int warmUpParts(int bone, int meshClass, boolean reversed, IAcceleratedVertexConsumer consumer, long startNanos, long budgetNanos, boolean exhausted) {
        Object key = BoneRenderer.key(consumer);
        BoneMesh mesh = data.meshes[bone];
        int built = 0;
        for (int part = 0, parts = mesh.parts(); part < parts; part++) {
            if (mesh.partClass(part) != meshClass) {
                continue;
            }
            BoneRenderer renderer = partRenderer(bone, part, reversed);
            while (!renderer.has(key)) {
                if (exhausted || built > 0 && System.nanoTime() - startNanos >= budgetNanos) {
                    return built;
                }
                renderer.prepare(consumer);
                built++;
            }
        }
        partsKey[bone * 4 + meshClass * 2 + (reversed ? 1 : 0)] = key;
        return built;
    }

    private int find(int meshClass, Object key) {
        for (int slot = 0; slot < warmCount; slot++) {
            if (warmClass[slot] == meshClass && (warmKey[slot] == key || warmKey[slot].equals(key))) {
                return slot;
            }
        }
        return -1;
    }
}
