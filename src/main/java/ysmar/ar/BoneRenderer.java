package ysmar.ar;

import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.AcceleratedBufferBuilder;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.IAcceleratedVertexConsumer;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.builders.VertexConsumerExtension;
import com.github.argon4w.acceleratedrendering.core.buffers.accelerated.renderers.IAcceleratedRenderer;
import com.github.argon4w.acceleratedrendering.core.meshes.IMesh;
import com.github.argon4w.acceleratedrendering.core.meshes.collectors.SimpleMeshCollector;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.renderer.texture.OverlayTexture;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import ysmar.core.BoneMesh;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws one mesh class of one bone. The Accelerated Rendering mesh is built on the first draw per vertex layout and
 * kept for the life of this object.
 *
 * The mesh does not depend on anything a resource reload changes, and it is built without reloadSensitive: such
 * meshes live in buffers Accelerated Rendering neither resets nor frees before the game closes, so the cached
 * objects stay valid across a reload. The plain collector is used on purpose: the culled one downloads the texture
 * once per location and keeps that copy until the next reload.
 */
final class BoneRenderer implements IAcceleratedRenderer<Void> {
    private static final int MAX_CACHED = 8;

    private final BoneMesh mesh;
    private final int meshClass;
    private final boolean reversed;
    private final IMesh.Builder builder;

    private Object lastKey;
    private IMesh lastMesh;
    private Map<Object, IMesh> cached;

    BoneRenderer(BoneMesh mesh, int meshClass, boolean reversed, IMesh.Builder builder) {
        this.mesh = mesh;
        this.meshClass = meshClass;
        this.reversed = reversed;
        this.builder = builder;
    }

    /**
     * What identifies the mesh a consumer needs. A plain builder changes nothing in what is collected, so its layout
     * object does; a decorating consumer (outline) changes it, and is its own key the way Accelerated Rendering
     * compares it.
     */
    static Object key(IAcceleratedVertexConsumer extension) {
        return extension instanceof AcceleratedBufferBuilder plain ? plain.getLayout() : extension;
    }

    boolean has(Object key) {
        return lastMesh != null && key == lastKey || cached != null && cached.containsKey(key);
    }

    /** Builds the mesh for the layout of this consumer now, so that the first draw does not have to. */
    void prepare(IAcceleratedVertexConsumer extension) {
        meshFor(extension);
    }

    private IMesh meshFor(IAcceleratedVertexConsumer extension) {
        Object key = key(extension);
        IMesh built = lastMesh;
        if (key != lastKey) {
            built = cached == null ? null : cached.get(key);
            if (built == null) {
                built = build(extension);
                if (cached == null) {
                    cached = new HashMap<>();
                } else if (cached.size() >= MAX_CACHED) {
                    cached.clear();
                }
                cached.put(key, built);
            }
            lastKey = key;
            lastMesh = built;
        }
        return built;
    }

    @Override
    public void render(VertexConsumer vertexConsumer, Void context, Matrix4f transform, Matrix3f normal, int light, int overlay, int color) {
        IAcceleratedVertexConsumer extension = VertexConsumerExtension.getAccelerated(vertexConsumer);
        IMesh built = meshFor(extension);
        extension.beginTransform(transform, normal);
        try {
            built.write(extension, color, light, overlay);
        } finally {
            extension.endTransform();
        }
    }

    private IMesh build(IAcceleratedVertexConsumer extension) {
        SimpleMeshCollector collector = new SimpleMeshCollector(extension.getLayout());
        VertexConsumer target = extension.decorate(collector);
        // Colour white and light 0: Accelerated Rendering combines them with the colour and light of each draw.
        mesh.emit(meshClass, reversed, (x, y, z, u, v, normalX, normalY, normalZ) ->
                target.addVertex(x, y, z, -1, u, v, OverlayTexture.NO_OVERLAY, 0, normalX, normalY, normalZ));
        collector.flush();
        return builder.build(collector);
    }
}
