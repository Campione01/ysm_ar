package ysmar.ar;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.meshes.IMesh;
import ysmar.ModelEntry;
import ysmar.core.BoneMesh;
import ysmar.core.ModelData;

/**
 * The renderer objects of one model: one per bone, mesh class and winding variant, created on first use and then
 * the same object for every draw. Motion vector history of other mods is keyed by these objects.
 */
final class ArModel {
    private static final int MAX_WARM = 16;

    private final ModelData data;
    private final IMesh.Builder builder;
    private final BoneRenderer[] renderers;

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
        this.renderers = new BoneRenderer[data.boneCount * 4];
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
            renderer = new BoneRenderer(data.meshes[bone], meshClass, reversed, builder);
            renderers[index] = renderer;
        }
        return renderer;
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
     * variant are left to their first draw: the default settings never use them.
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
                if (!renderer.has(key)) {
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

    private int find(int meshClass, Object key) {
        for (int slot = 0; slot < warmCount; slot++) {
            if (warmClass[slot] == meshClass && (warmKey[slot] == key || warmKey[slot].equals(key))) {
                return slot;
            }
        }
        return -1;
    }
}
