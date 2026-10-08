package ysmar.core;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.CRC32;
import java.util.zip.DataFormatException;
import java.util.zip.Inflater;

/**
 * What stands between a PNG file of a model folder and the native decoder. The file is taken apart chunk by chunk
 * and put together again from the chunks a decoder needs to make the picture: the header, a palette and its
 * transparency, the picture data, the end. Text, colour profiles, EXIF, animation frames and every other chunk are
 * left out: a decoder unpacks some of them, and a few bytes can then ask it for gigabytes. The picture data is
 * unpacked once here, without being kept, and has to be exactly as long as the header says. What is handed on ends
 * with the end chunk; chunks a decoder may skip that a tool has written behind it are left out like the others.
 *
 * Whatever is not a PNG as the specification writes it is refused: a decoder that is lenient where another is not
 * would show another picture than Yes Steve Model. Messages carry numbers and chunk roles only.
 */
final class PngGate {
    private static final byte[] SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final int IHDR = 0x49484452;
    private static final int PLTE = 0x504C5445;
    private static final int TRNS = 0x74524E53;
    private static final int IDAT = 0x49444154;
    private static final int IEND = 0x49454E44;
    /** Adam7: where each of the seven passes starts and how far its pixels are apart. */
    private static final int[] PASS_X = {0, 4, 0, 2, 0, 1, 0};
    private static final int[] PASS_Y = {0, 0, 4, 0, 2, 0, 1};
    private static final int[] PASS_DX = {8, 8, 4, 4, 2, 2, 1};
    private static final int[] PASS_DY = {8, 8, 8, 4, 4, 2, 2};

    /** The picture as its header describes it, and the file made of the chunks that were kept. */
    static final class Picture {
        byte[] stream;
        int width;
        int height;
        int bitDepth;
        int colourType;
        boolean interlaced;
        /** The chunks that were left out, by name, with how often each came. */
        final Map<String, Integer> leftOut = new TreeMap<>();
    }

    private PngGate() {
    }

    static Picture rebuild(byte[] file) throws ModelLoader.Refusal {
        if (file.length < SIGNATURE.length || !Arrays.equals(file, 0, SIGNATURE.length, SIGNATURE, 0, SIGNATURE.length)) {
            throw new ModelLoader.Refusal("texture: the texture file is not a PNG image");
        }
        Picture picture = new Picture();
        ByteArrayOutputStream kept = new ByteArrayOutputStream(file.length);
        kept.write(file, 0, SIGNATURE.length);
        List<int[]> data = new ArrayList<>();
        CRC32 sum = new CRC32();
        boolean header = false;
        boolean palette = false;
        boolean transparency = false;
        boolean dataOver = false;
        boolean end = false;
        int paletteEntries = 0;
        int position = SIGNATURE.length;
        while (position < file.length) {
            if (file.length - position < 12) {
                throw no("a chunk is cut off");
            }
            long length = unsigned(file, position);
            if (length > file.length - position - 12) {
                throw no("a chunk is longer than what is left of the file");
            }
            int size = (int) length;
            int type = (int) unsigned(file, position + 4);
            for (int letter = 0; letter < 4; letter++) {
                int code = file[position + 4 + letter] & 0xFF;
                if ((code < 'A' || code > 'Z') && (code < 'a' || code > 'z')) {
                    throw no("a chunk has a name that is no chunk name");
                }
            }
            int content = position + 8;
            if (!header && type != IHDR) {
                throw no("the header is not the first chunk");
            }
            if (end) {
                // Some tools write a text chunk behind the end; a decoder stops at the end and never sees it.
                if ((file[position + 4] & 0x20) == 0) {
                    throw no("a chunk no decoder may skip follows the end of the picture");
                }
                picture.leftOut.merge(new String(file, position + 4, 4, java.nio.charset.StandardCharsets.US_ASCII), 1, Integer::sum);
                position = content + size + 4;
                continue;
            }
            boolean keep = true;
            switch (type) {
                case IHDR -> {
                    if (header || size != 13) {
                        throw no(header ? "there are two headers" : "the header has the wrong length");
                    }
                    header(picture, file, content);
                    header = true;
                }
                case PLTE -> {
                    if (palette || transparency || !data.isEmpty() || picture.colourType == 0 || picture.colourType == 4) {
                        throw no("a palette where none belongs");
                    }
                    if (size == 0 || size % 3 != 0 || size / 3 > 256 || picture.colourType == 3 && size / 3 > 1 << picture.bitDepth) {
                        throw no("a palette of a length it cannot have");
                    }
                    palette = true;
                    paletteEntries = size / 3;
                    // Only a picture of palette indices is made with it; for the others it is a suggestion.
                    keep = picture.colourType == 3;
                }
                case TRNS -> {
                    if (transparency || !data.isEmpty() || picture.colourType == 4 || picture.colourType == 6 || picture.colourType == 3 && !palette) {
                        throw no("transparency where none belongs");
                    }
                    int wanted = picture.colourType == 0 ? 2 : picture.colourType == 2 ? 6 : -1;
                    if (wanted >= 0 ? size != wanted : size == 0 || size > paletteEntries) {
                        throw no("transparency of a length it cannot have");
                    }
                    transparency = true;
                }
                case IDAT -> {
                    if (dataOver || picture.colourType == 3 && !palette) {
                        throw no(dataOver ? "the picture data lies in two places" : "a picture of palette indices without a palette");
                    }
                    data.add(new int[]{content, size});
                }
                case IEND -> {
                    if (data.isEmpty() || size != 0) {
                        throw no(data.isEmpty() ? "the picture has no data" : "the end chunk is not empty");
                    }
                    end = true;
                }
                default -> {
                    // A name that starts with a capital: a decoder may not skip the chunk, and it is none a PNG has.
                    if ((file[position + 4] & 0x20) == 0) {
                        throw no("a chunk no decoder may skip is none a PNG has");
                    }
                    keep = false;
                }
            }
            dataOver |= !data.isEmpty() && type != IDAT;
            if (keep) {
                sum.reset();
                sum.update(file, position + 4, size + 4);
                if (sum.getValue() != unsigned(file, content + size)) {
                    throw no("a chunk has the wrong check sum");
                }
                kept.write(file, position, size + 12);
            } else {
                picture.leftOut.merge(new String(file, position + 4, 4, java.nio.charset.StandardCharsets.US_ASCII), 1, Integer::sum);
            }
            position = content + size + 4;
        }
        if (!end) {
            throw no("the picture has no end chunk");
        }
        unpack(picture, file, data);
        picture.stream = kept.toByteArray();
        return picture;
    }

    private static void header(Picture picture, byte[] file, int at) throws ModelLoader.Refusal {
        long width = unsigned(file, at);
        long height = unsigned(file, at + 4);
        if (width == 0 || height == 0) {
            throw no("the picture has no width or no height");
        }
        if (width > ModelLoader.MAX_TEXTURE_SIZE || height > ModelLoader.MAX_TEXTURE_SIZE) {
            throw new ModelLoader.Refusal("texture: " + width + "x" + height + " is larger than " + ModelLoader.MAX_TEXTURE_SIZE + "x" + ModelLoader.MAX_TEXTURE_SIZE);
        }
        int depth = file[at + 8] & 0xFF;
        int colour = file[at + 9] & 0xFF;
        boolean pair = switch (colour) {
            case 0 -> depth == 1 || depth == 2 || depth == 4 || depth == 8 || depth == 16;
            case 3 -> depth == 1 || depth == 2 || depth == 4 || depth == 8;
            case 2, 4, 6 -> depth == 8 || depth == 16;
            default -> false;
        };
        if (!pair) {
            throw no("a colour type and bit depth no PNG has together");
        }
        if (file[at + 10] != 0 || file[at + 11] != 0 || (file[at + 12] & 0xFF) > 1) {
            throw no("a compression, filter or interlace method no PNG has");
        }
        picture.width = (int) width;
        picture.height = (int) height;
        picture.bitDepth = depth;
        picture.colourType = colour;
        picture.interlaced = file[at + 12] == 1;
    }

    /** Unpacks the picture data without keeping it: it has to end where the header says the picture is complete. */
    private static void unpack(Picture picture, byte[] file, List<int[]> data) throws ModelLoader.Refusal {
        long expected = expectedBytes(picture);
        Inflater inflater = new Inflater();
        try {
            byte[] scratch = new byte[1 << 16];
            long unpacked = 0;
            boolean finished = false;
            for (int[] part : data) {
                if (finished) {
                    if (part[1] != 0) {
                        throw no("picture data follows its own end");
                    }
                    continue;
                }
                inflater.setInput(file, part[0], part[1]);
                while (true) {
                    int count = inflater.inflate(scratch);
                    unpacked += count;
                    if (unpacked > expected) {
                        throw no("the picture data unpacks to more than the " + expected + " bytes the header says");
                    }
                    if (inflater.finished()) {
                        finished = true;
                        if (inflater.getRemaining() != 0) {
                            throw no("picture data follows its own end");
                        }
                        break;
                    }
                    if (inflater.needsDictionary()) {
                        throw no("the picture data cannot be unpacked");
                    }
                    if (count == 0) {
                        break;
                    }
                }
            }
            if (!finished || unpacked != expected) {
                throw no("the picture data ends after " + unpacked + " of the " + expected + " bytes the header says");
            }
        } catch (DataFormatException broken) {
            throw no("the picture data cannot be unpacked");
        } finally {
            inflater.end();
        }
    }

    /** What the picture data unpacks to: a filter byte and the packed pixels of every row, of every pass when interlaced. */
    static long expectedBytes(Picture picture) {
        int channels = switch (picture.colourType) {
            case 2 -> 3;
            case 4 -> 2;
            case 6 -> 4;
            default -> 1;
        };
        long bits = (long) channels * picture.bitDepth;
        if (!picture.interlaced) {
            return picture.height * (1 + (picture.width * bits + 7) / 8);
        }
        long expected = 0;
        for (int pass = 0; pass < 7; pass++) {
            long columns = (picture.width - PASS_X[pass] + PASS_DX[pass] - 1) / PASS_DX[pass];
            long rows = (picture.height - PASS_Y[pass] + PASS_DY[pass] - 1) / PASS_DY[pass];
            if (columns > 0 && rows > 0) {
                expected += rows * (1 + (columns * bits + 7) / 8);
            }
        }
        return expected;
    }

    private static long unsigned(byte[] bytes, int at) {
        return (bytes[at] & 0xFFL) << 24 | (bytes[at + 1] & 0xFFL) << 16 | (bytes[at + 2] & 0xFFL) << 8 | bytes[at + 3] & 0xFFL;
    }

    private static ModelLoader.Refusal no(String what) {
        return new ModelLoader.Refusal("texture: the PNG file is refused: " + what);
    }
}
