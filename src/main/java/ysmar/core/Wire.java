package ysmar.core;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;

/** Protobuf wire-format cursor over a region of a ByteBuffer. Uses absolute reads only. */
final class Wire {
    static final int VARINT = 0;
    static final int FIXED64 = 1;
    static final int BYTES = 2;
    static final int FIXED32 = 5;

    static final class WireException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        WireException(String message) {
            super(message);
        }
    }

    private final ByteBuffer data;
    private final int end;
    private int position;

    int field;
    int type;
    /** Absolute offset of the tag of the field that was read last; the field ends where the cursor stands. */
    int fieldStart;
    /** VARINT, FIXED64, or the zero-extended bits of a FIXED32. */
    long value;
    /** BYTES: absolute offset and length of the content inside the underlying buffer. */
    int start;
    int length;

    Wire(ByteBuffer source, int offset, int length) {
        if (offset < 0 || length < 0 || offset > source.limit() - length) {
            throw new WireException("message region is out of range");
        }
        this.data = source.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        this.position = offset;
        this.end = offset + length;
    }

    boolean next() {
        if (position >= end) {
            return false;
        }
        fieldStart = position;
        long tag = readVarint();
        field = (int) (tag >>> 3);
        type = (int) (tag & 7);
        if (tag < 0 || tag > 0xFFFFFFFFL || field == 0) {
            throw new WireException("invalid field tag");
        }
        switch (type) {
            case VARINT -> value = readVarint();
            case FIXED64 -> {
                require(8);
                value = data.getLong(position);
                position += 8;
            }
            case FIXED32 -> {
                require(4);
                value = data.getInt(position) & 0xFFFFFFFFL;
                position += 4;
            }
            case BYTES -> {
                long size = readVarint();
                if (size < 0 || size > end - position) {
                    throw new WireException("length-delimited field overruns its message");
                }
                start = position;
                length = (int) size;
                position += length;
            }
            default -> throw new WireException("unsupported wire type " + type);
        }
        return true;
    }

    Wire message() {
        expect(BYTES);
        return new Wire(data, start, length);
    }

    int uint32() {
        expect(VARINT);
        return (int) value;
    }

    boolean bool() {
        expect(VARINT);
        return value != 0;
    }

    float float32() {
        expect(FIXED32);
        return Float.intBitsToFloat((int) value);
    }

    /** Appends a repeated float field that may be packed or not. Returns the new count. */
    int floats(float[] destination, int count) {
        if (type == FIXED32) {
            if (count < destination.length) {
                destination[count] = Float.intBitsToFloat((int) value);
            }
            return count + 1;
        }
        expect(BYTES);
        if (length % 4 != 0) {
            throw new WireException("packed float field has a length that is not a multiple of 4");
        }
        for (int offset = 0; offset < length; offset += 4) {
            if (count < destination.length) {
                destination[count] = data.getFloat(start + offset);
            }
            count++;
        }
        return count;
    }

    /** Strict UTF-8; returns null when the bytes are not valid UTF-8. */
    String string() {
        expect(BYTES);
        byte[] bytes = new byte[length];
        data.get(start, bytes, 0, length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException failure) {
            return null;
        }
    }

    /** Appends the field that was read last exactly as it stands in the source: tag, length and content. */
    void copyField(ByteArrayOutputStream destination) {
        byte[] raw = new byte[position - fieldStart];
        data.get(fieldStart, raw, 0, raw.length);
        destination.write(raw, 0, raw.length);
    }

    /** Appends a length-delimited field. */
    static void writeBytes(ByteArrayOutputStream destination, int field, byte[] content, int length) {
        writeVarint(destination, ((long) field << 3) | BYTES);
        writeVarint(destination, length);
        destination.write(content, 0, length);
    }

    private static void writeVarint(ByteArrayOutputStream destination, long value) {
        while ((value & ~0x7FL) != 0) {
            destination.write((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        destination.write((int) value);
    }

    private void expect(int expected) {
        if (type != expected) {
            throw new WireException("field " + field + " has wire type " + type + ", expected " + expected);
        }
    }

    private void require(int count) {
        if (count > end - position) {
            throw new WireException("truncated field");
        }
    }

    private long readVarint() {
        long result = 0;
        for (int shift = 0; shift < 70; shift += 7) {
            require(1);
            byte current = data.get(position++);
            result |= (long) (current & 0x7F) << shift;
            if (current >= 0) {
                return result;
            }
        }
        throw new WireException("varint is longer than 10 bytes");
    }
}
