package com.elfmcys.ysm.natives.legacy;

import com.elfmcys.ysm.buffer.NativeBuffer;

import java.nio.file.Path;

// Not an upstream file. It lives in this package to reach the package-private importer,
// so that the upstream legacy classes can stay byte-identical.
public final class LegacyImport implements AutoCloseable {
    private final NativeLegacyProtocol.Response response;
    private final NativeLegacyProtocol.Success success;

    private LegacyImport(NativeLegacyProtocol.Response response) {
        this.response = response;
        this.success = response instanceof NativeLegacyProtocol.Success value ? value : null;
    }

    /** Throws IllegalStateException when the native result breaks the transport protocol. */
    public static LegacyImport open(Path source) {
        return new LegacyImport(NativeLegacyImporter.invoke(source));
    }

    public boolean succeeded() {
        return success != null;
    }

    public String statusName() {
        return response.status().name();
    }

    public int statusCode() {
        return response.status().code();
    }

    public String diagnostic() {
        return response instanceof NativeLegacyProtocol.Failure failure ? failure.diagnostic() : "";
    }

    public int innerVersion() {
        return descriptor().innerVersion();
    }

    public long sourceSize() {
        return descriptor().sourceSize();
    }

    public byte[] modelId() {
        return descriptor().modelId();
    }

    public int payloadCount() {
        return descriptor().records().size();
    }

    public String payloadKind(int index) {
        return record(index).kind().name();
    }

    public String payloadEncoding(int index) {
        return record(index).encoding().name();
    }

    public int payloadLogicalId(int index) {
        return record(index).logicalId();
    }

    public String payloadName(int index) {
        return record(index).name();
    }

    public int payloadMeta0(int index) {
        return record(index).meta0();
    }

    public int payloadMeta1(int index) {
        return record(index).meta1();
    }

    public int payloadSize(int index) {
        return record(index).payloadSize();
    }

    /** Borrowed view: valid until close(); acquire() or copy() it to keep the bytes longer. */
    public NativeBuffer payload(int index) {
        return requireSuccess().payloads().get(index);
    }

    @Override
    public void close() {
        response.close();
    }

    private NativeLegacyProtocol.Success requireSuccess() {
        if (success == null) {
            throw new IllegalStateException("Legacy import failed: " + statusName());
        }
        return success;
    }

    private NativeLegacyProtocol.Descriptor descriptor() {
        return requireSuccess().descriptor();
    }

    private NativeLegacyProtocol.PayloadRecord record(int index) {
        return descriptor().records().get(index);
    }
}
