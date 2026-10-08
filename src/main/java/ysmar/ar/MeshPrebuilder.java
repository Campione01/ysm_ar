package ysmar.ar;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.VertexConsumerExtension;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.buffers.memory.VertexLayout;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Builds the bone meshes of a model ahead of its first draw. What a buffer source hands out only answers
 * isAccelerated() and doRender(); the consumer that knows its vertex layout, which a mesh is built for, is the one
 * Accelerated Rendering passes to a renderer inside doRender. So this is a renderer that draws nothing: it is run
 * once per mesh class, sees the consumer or consumers of the pass and builds what is missing for them, as far as
 * the budget of the frame goes. The part meshes of a bone (see BoneMesh.parts) are built the same way, when a draw
 * first needs them.
 */
final class MeshPrebuilder implements IAcceleratedRenderer<ArModel> {
    private static final Matrix4f NO_TRANSFORM = new Matrix4f();
    private static final Matrix3f NO_NORMAL = new Matrix3f();

    private int meshClass;
    /** Not negative: only the part meshes of this bone are built, for this winding. */
    private int partBone = -1;
    private boolean partReversed;
    private long startNanos;
    private long budgetNanos;
    /** Meshes built since begin(). */
    int built;
    /** False as soon as a consumer was seen for which a mesh is still missing. */
    boolean complete;

    // Since run(): how many consumers the pass had, the mesh key of the last one, and whether each of them had the
    // vertex layout of the game's own entity shader.
    private int consumers;
    private Object key;
    private boolean vanilla;
    private VertexLayout lastLayout;
    private boolean lastVanilla;

    void begin(long budget) {
        startNanos = System.nanoTime();
        budgetNanos = budget;
        built = 0;
        complete = true;
    }

    void run(IAcceleratedVertexConsumer consumer, ArModel model, int meshClassOfConsumer) {
        meshClass = meshClassOfConsumer;
        partBone = -1;
        consumers = 0;
        key = null;
        vanilla = true;
        consumer.doRender(this, model, NO_TRANSFORM, NO_NORMAL, 0, 0, 0);
    }

    /** As run(), for the part meshes of one bone and nothing else. */
    void runParts(IAcceleratedVertexConsumer consumer, ArModel model, int meshClassOfConsumer, int bone, boolean reversed) {
        meshClass = meshClassOfConsumer;
        partBone = bone;
        partReversed = reversed;
        try {
            consumer.doRender(this, model, NO_TRANSFORM, NO_NORMAL, 0, 0, 0);
        } finally {
            partBone = -1;
        }
    }

    /**
     * After run(): the mesh key of the consumer of the pass when it is one consumer with the vertex layout of the
     * game's own entity shader, which is the case while no shader pack draws the entities; else null.
     */
    Object vanillaKey() {
        return consumers == 1 && vanilla ? key : null;
    }

    long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    @Override
    public void render(VertexConsumer vertexConsumer, ArModel model, Matrix4f transform, Matrix3f normal, int light, int overlay, int color) {
        IAcceleratedVertexConsumer extension = VertexConsumerExtension.getAccelerated(vertexConsumer);
        Object key = BoneRenderer.key(extension);
        boolean exhausted = budgetNanos <= 0 || built > 0 && System.nanoTime() - startNanos >= budgetNanos;
        if (partBone >= 0) {
            built += model.warmUpParts(partBone, meshClass, partReversed, extension, startNanos, budgetNanos, exhausted);
            complete &= model.partsBuilt(partBone, meshClass, partReversed, key);
            return;
        }
        consumers++;
        this.key = key;
        vanilla &= isVanilla(extension.getLayout());
        if (model.isWarm(meshClass, key)) {
            return;
        }
        built += model.warmUp(meshClass, extension, startNanos, budgetNanos, exhausted);
        complete &= model.isWarm(meshClass, key);
    }

    private boolean isVanilla(VertexLayout layout) {
        if (layout != lastLayout) {
            lastLayout = layout;
            lastVanilla = sameAs(layout, DefaultVertexFormat.NEW_ENTITY);
        }
        return lastVanilla;
    }

    /** True for the layout of exactly this vertex format: its size, and every element of it where the format has it. */
    static boolean sameAs(VertexLayout layout, VertexFormat format) {
        if (layout == null || layout.getSize() != format.getVertexSize()) {
            return false;
        }
        for (VertexFormatElement element : format.getElements()) {
            if (layout.getElementOffset(element) != format.getOffset(element)) {
                return false;
            }
        }
        return true;
    }
}
