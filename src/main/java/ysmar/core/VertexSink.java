package ysmar.core;

/** Receives the vertices of a bone mesh, four per quad. */
public interface VertexSink {
    void vertex(float x, float y, float z, float u, float v, float normalX, float normalY, float normalZ);

    /** Announces the next four vertices: the baked quad they come from and whether this is its back-side copy. */
    default void quad(int sourceQuad, boolean backCopy) {
    }
}
