// Modified copy of a Yes Steve Model source file (Apache-2.0), upstream commit 74c53b58b2f9.
// Change: reduced to the class header and its native method declarations (annotations dropped).
package com.elfmcys.ysm.natives.image;

public class ImageEncoder {
    private ImageEncoder() {
    }

    private static native long nEncode(long pixels, int width, int height,
                                             long dst, long dst_size,
                                             boolean lossless, int maxWidth, int maxHeight);
}
