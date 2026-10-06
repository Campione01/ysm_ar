// Modified copy of a Yes Steve Model source file (Apache-2.0), upstream commit 74c53b58b2f9.
// Change: reduced to the class header and its native method declarations (annotations dropped).
package com.elfmcys.ysm.natives;

public final class Zstd {
    private Zstd() {
    }

    private static native Object nZstd(Object source, long sourceFlags, byte[] hashResult, int outputParam, int outputType, int op);
}
