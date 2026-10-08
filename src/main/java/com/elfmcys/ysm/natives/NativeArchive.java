// Modified copy of a Yes Steve Model source file (Apache-2.0), upstream commit 74c53b58b2f9.
// Change: does not implement VirtualFileSystem, which is not part of this copy (annotations dropped).
package com.elfmcys.ysm.natives;

import com.elfmcys.ysm.buffer.NativeBuffer;
import com.elfmcys.ysm.util.CleanerUtil;
import com.elfmcys.ysm.util.Closeable;

import java.lang.ref.Cleaner;
import java.lang.ref.Reference;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

// 目前支持 zip、7z、旧版 ysm
public class NativeArchive implements Closeable {
    private final long ptr;
    private final State state;
    private final Cleaner.Cleanable cleanable;

    public NativeArchive(String path) {
        ptr = nCreate(path);
        if (ptr == 0) {
            throw new IllegalArgumentException("Could not open archive: " + path);
        }
        state = new State(ptr);
        cleanable = CleanerUtil.ref(this, state, State::clean);
    }

    private void checkClosed() {
        if (!state.open.get()) {
            throw new IllegalStateException("Already Closed");
        }
    }

    public String[] listFiles(String path) {
        checkClosed();
        final String[] result;
        try {
            result = nList(ptr, path, 0);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (result == null) {
            throw new IllegalStateException("Failed to list files");
        }
        return result;
    }

    public String[] listDirectories(String path) {
        checkClosed();
        final String[] result;
        try {
            result = nList(ptr, path, 1);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (result == null) {
            throw new IllegalStateException("Failed to list directories");
        }
        return result;
    }

    public boolean hasFile(String fileName) {
        checkClosed();
        // 随便返回个东西
        try {
            return nGetFile(ptr, fileName, true) != null;
        } finally {
            Reference.reachabilityFence(this);
        }
    }

    public NativeBuffer getFile(String fileName) {
        checkClosed();
        final Object buf;
        try {
            buf = nGetFile(ptr, fileName, false);
        } finally {
            Reference.reachabilityFence(this);
        }
        if (buf == null) {
            return null;
        }
        return NativeBuffer.borrow((ByteBuffer) buf);
    }

    @Override
    public void close() {
        cleanable.clean();
    }

    private static final class State {
        private final long ptr;
        private final AtomicBoolean open = new AtomicBoolean(true);

        private State(long ptr) {
            this.ptr = ptr;
        }

        private void clean() {
            if (open.compareAndSet(true, false)) {
                nDestroy(ptr);
            }
        }
    }

    private static native long nCreate(String path);

    private static native void nDestroy(long ptr);

    private static native String[] nList(long ptr, String path, int type);

    private static native Object nGetFile(long ptr, String fileName, boolean dryRun);
}
