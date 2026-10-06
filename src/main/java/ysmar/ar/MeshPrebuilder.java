package ysmar.ar;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.VertexConsumerExtension;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import org.joml.Matrix3f;
import org.joml.Matrix4f;

/**
 * Builds the bone meshes of a model ahead of its first draw. What a buffer source hands out only answers
 * isAccelerated() and doRender(); the consumer that knows its vertex layout, which a mesh is built for, is the one
 * Accelerated Rendering passes to a renderer inside doRender. So this is a renderer that draws nothing: it is run
 * once per mesh class, sees the consumer or consumers of the pass and builds what is missing for them, as far as
 * the budget of the frame goes.
 */
final class MeshPrebuilder implements IAcceleratedRenderer<ArModel> {
    private static final Matrix4f NO_TRANSFORM = new Matrix4f();
    private static final Matrix3f NO_NORMAL = new Matrix3f();

    private int meshClass;
    private long startNanos;
    private long budgetNanos;
    /** Meshes built since begin(). */
    int built;
    /** False as soon as a consumer was seen for which a mesh is still missing. */
    boolean complete;

    void begin(long budget) {
        startNanos = System.nanoTime();
        budgetNanos = budget;
        built = 0;
        complete = true;
    }

    void run(IAcceleratedVertexConsumer consumer, ArModel model, int meshClassOfConsumer) {
        meshClass = meshClassOfConsumer;
        consumer.doRender(this, model, NO_TRANSFORM, NO_NORMAL, 0, 0, 0);
    }

    long elapsedNanos() {
        return System.nanoTime() - startNanos;
    }

    @Override
    public void render(VertexConsumer vertexConsumer, ArModel model, Matrix4f transform, Matrix3f normal, int light, int overlay, int color) {
        IAcceleratedVertexConsumer extension = VertexConsumerExtension.getAccelerated(vertexConsumer);
        Object key = BoneRenderer.key(extension);
        if (model.isWarm(meshClass, key)) {
            return;
        }
        boolean exhausted = budgetNanos <= 0 || built > 0 && System.nanoTime() - startNanos >= budgetNanos;
        built += model.warmUp(meshClass, extension, startNanos, budgetNanos, exhausted);
        complete &= model.isWarm(meshClass, key);
    }
}
