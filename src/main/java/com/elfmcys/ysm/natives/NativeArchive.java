// Modified copy of a Yes Steve Model source file (Apache-2.0), upstream commit 74c53b58b2f9.
// Change: reduced to the class header and its native method declarations (annotations dropped).
package com.elfmcys.ysm.natives;

public class NativeArchive {
    private NativeArchive() {
    }

    private static native long nCreate(String path);

    private static native void nDestroy(long ptr);

    private static native String[] nList(long ptr, String path, int type);

    private static native Object nGetFile(long ptr, String fileName, boolean dryRun);
}
