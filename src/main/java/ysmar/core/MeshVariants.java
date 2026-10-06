package ysmar.core;

import org.joml.Matrix4f;

public final class MeshVariants {
    private MeshVariants() {
    }

    /**
     * Whether a draw with this final matrix takes the reversed-winding mesh. The official renderer judges a face by
     * the winding of its projection, exactly as GL does, so a mirroring matrix needs no other mesh to match it.
     * The reversed mesh is only for the optional mode that keeps the outer faces of mirrored bones visible.
     */
    public static boolean useReversed(Matrix4f finalMatrix, boolean reverseMirrored) {
        return reverseMirrored && finalMatrix.determinant3x3() < 0.0f;
    }
}
