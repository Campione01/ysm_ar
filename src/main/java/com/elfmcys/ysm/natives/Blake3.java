// Modified copy of a Yes Steve Model source file (Apache-2.0), upstream commit 74c53b58b2f9.
// Change: reduced to the class header and its native method declarations (annotations dropped).
package com.elfmcys.ysm.natives;

public class Blake3 {
    private Blake3() {
    }

    private static native int nBlake3(Object source, long sourceFlags, byte[] hash, int op);
}
