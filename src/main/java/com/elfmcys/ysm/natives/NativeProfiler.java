// Modified copy of a Yes Steve Model source file (Apache-2.0), upstream commit 74c53b58b2f9.
// Change: reduced to the class header and its native method declarations (annotations dropped).
package com.elfmcys.ysm.natives;

public final class NativeProfiler {
    private NativeProfiler() {
    }

    private static native long nCreateSourceLocation(String name, String function, String file, int line);

    private static native long nBeginZone(long sourceLocation);

    private static native void nEndZone(long token);

    private static native long nBeginFrame();

    private static native void nEndFrame();
}
