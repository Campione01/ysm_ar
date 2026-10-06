// Modified copy of a Yes Steve Model source file (Apache-2.0), upstream commit 74c53b58b2f9.
// Change: reduced to the class header and its native method declarations (annotations dropped).
package com.elfmcys.ysm.natives.sound;

import java.nio.ByteBuffer;

public final class OpusDecoder {
    private OpusDecoder() {
    }

    private static native long nCreate(long expectedFrames);

    private static native boolean nFeed(long ptr, ByteBuffer data);

    private static native void nEndInput(long ptr);

    private static native int nDecode(long ptr, ByteBuffer destination);

    private static native void nDestroy(long ptr);
}
