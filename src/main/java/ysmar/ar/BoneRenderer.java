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
import ysmar.core.VertexSink;

import java.util.HashMap;
import java.util.Map;

/**
 * Draws one mesh class of one bone, or one shading part of it. The Accelerated Rendering mesh is built on the first
 * draw per vertex layout and kept for the life of this object.
 *
 * The mesh does not depend on anything a resource reload changes, and it is built without reloadSensitive: such
 * meshes live in buffers Accelerated Rendering neither resets nor frees before the game closes, so the cached
 * objects stay valid across a reload. The plain collector is used on purpose: the culled one downloads the texture
 * once per location and keeps that copy until the next reload.
 *
 * A bone that shares a face with another bone (BoneMesh.partners) has a second mesh of the same vertices, from the
 * builder whose meshes come out in the order of the draw calls; which of the two a draw writes is said before the
 * draw. It is one object either way: whoever follows a bone from frame to frame by this object keeps following it.
 */
final class BoneRenderer implements IAcceleratedRenderer<Void> {
    private static final int MAX_CACHED = 8;

    private final BoneMesh mesh;
    private final int meshClass;
    private final boolean reversed;
    private final IMesh.Builder builder;
    /** The builder of the second mesh, or null for a bone that needs none. */
    private final IMesh.Builder orderedBuilder;
    /** The shading part of the mesh this object draws (see BoneMesh.parts), or -1 for the whole mesh class. */
    private final int part;

    /** Set before each draw: the mesh of this draw has to come out in the order of the draw calls. */
    boolean inCallOrder;

    private Object lastKey;
    /** The mesh of the builder, then that of the ordered builder; an entry is null until it is built. */
    private IMesh[] lastMeshes;
    private Map<Object, IMesh[]> cached;

    BoneRenderer(BoneMesh mesh, int meshClass, boolean reversed, IMesh.Builder builder, IMesh.Builder orderedBuilder, int part) {
        this.mesh = mesh;
        this.meshClass = meshClass;
        this.reversed = reversed;
        this.builder = builder;
        this.orderedBuilder = orderedBuilder;
        this.part = part;
    }

    /**
     * What identifies the mesh a consumer needs. A plain builder changes nothing in what is collected, so its layout
     * object does; a decorating consumer (outline) changes it, and is its own key the way Accelerated Rendering
     * compares it.
     */
    static Object key(IAcceleratedVertexConsumer extension) {
        return extension instanceof AcceleratedBufferBuilder plain ? plain.getLayout() : extension;
    }

    /** True when every mesh this object can draw exists for the key. */
    boolean has(Object key) {
        IMesh[] built = key == lastKey ? lastMeshes : cached == null ? null : cached.get(key);
        return built != null && built[0] != null && (orderedBuilder == null || built[1] != null);
    }

    /** Builds one mesh that is missing for the layout of this consumer now, so that the first draw does not have to. */
    void prepare(IAcceleratedVertexConsumer extension) {
        IMesh[] built = meshesFor(extension);
        if (built[0] == null) {
            built[0] = build(extension, builder);
        } else if (orderedBuilder != null && built[1] == null) {
            built[1] = build(extension, orderedBuilder);
        }
    }

    private IMesh[] meshesFor(IAcceleratedVertexConsumer extension) {
        Object key = key(extension);
        IMesh[] built = lastMeshes;
        if (key != lastKey || built == null) {
            built = cached == null ? null : cached.get(key);
            if (built == null) {
                built = new IMesh[2];
                if (cached == null) {
                    cached = new HashMap<>();
                } else if (cached.size() >= MAX_CACHED) {
                    cached.clear();
                }
                cached.put(key, built);
            }
            lastKey = key;
            lastMeshes = built;
        }
        return built;
    }

    @Override
    public void render(VertexConsumer vertexConsumer, Void context, Matrix4f transform, Matrix3f normal, int light, int overlay, int color) {
        IAcceleratedVertexConsumer extension = VertexConsumerExtension.getAccelerated(vertexConsumer);
        IMesh[] meshes = meshesFor(extension);
        int way = inCallOrder && orderedBuilder != null ? 1 : 0;
        IMesh built = meshes[way];
        if (built == null) {
            built = build(extension, way == 0 ? builder : orderedBuilder);
            meshes[way] = built;
        }
        extension.beginTransform(transform, normal);
        try {
            built.write(extension, color, light, overlay);
        } finally {
            extension.endTransform();
        }
    }

    private IMesh build(IAcceleratedVertexConsumer extension, IMesh.Builder with) {
        SimpleMeshCollector collector = new SimpleMeshCollector(extension.getLayout());
        VertexConsumer target = extension.decorate(collector);
        // Colour white and light 0: Accelerated Rendering combines them with the colour and light of each draw.
        VertexSink sink = (x, y, z, u, v, normalX, normalY, normalZ) ->
                target.addVertex(x, y, z, -1, u, v, OverlayTexture.NO_OVERLAY, 0, normalX, normalY, normalZ);
        if (part < 0) {
            mesh.emit(meshClass, reversed, sink);
        } else {
            mesh.emitPart(part, reversed, sink);
        }
        collector.flush();
        return with.build(collector);
    }
}
