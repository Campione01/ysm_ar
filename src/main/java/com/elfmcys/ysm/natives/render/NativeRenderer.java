// Modified copy of a Yes Steve Model source file (Apache-2.0), upstream commit 74c53b58b2f9.
// Change: reduced to the class header and its native method declarations (annotations dropped).
package com.elfmcys.ysm.natives.render;

public class NativeRenderer {
    private NativeRenderer() {
    }

    private static native boolean nRender(Object vertexBuffer, int vertexBufferFlag, long matPtr, long modelStatePtr,
                                          long lightAndOverlay, int color, long flags, long irisEntityId);
}
