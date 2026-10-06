package ysmar;

/**
 * Text that comes from another mod, from a server or from a file name, made fit for one line of the log or of chat:
 * model ids, texture names, class names, reasons. Whoever sends such text cannot start a new line with it, hide part
 * of it or turn its direction, and cannot make it longer than MAX_LENGTH.
 */
public final class Text {
    public static final int MAX_LENGTH = 96;
    private static final String CUT = "...";
    private static final char REPLACEMENT = '?';
    private static final char FORMATTING_CODE = '\u00A7';
    private static final char PLAIN_CODE = '&';

    private Text() {
    }

    /** Control and format characters, line separators and lone surrogates become '?'; what is longer is cut. */
    public static String clean(String text) {
        if (text == null) {
            return "(null)";
        }
        int length = text.length();
        boolean plain = length <= MAX_LENGTH;
        for (int index = 0; plain && index < length; index++) {
            char letter = text.charAt(index);
            plain = letter >= ' ' && letter < 0x7F;
        }
        if (plain) {
            return text;
        }
        StringBuilder result = new StringBuilder(Math.min(length, MAX_LENGTH + 2));
        int index = 0;
        while (index < length && result.length() <= MAX_LENGTH) {
            int point = text.codePointAt(index);
            if (unsafe(point)) {
                result.append(REPLACEMENT);
            } else {
                result.appendCodePoint(point);
            }
            index += Character.charCount(point);
        }
        if (result.length() > MAX_LENGTH) {
            int keep = MAX_LENGTH - CUT.length();
            if (Character.isHighSurrogate(result.charAt(keep - 1))) {
                keep--;
            }
            result.setLength(keep);
            result.append(CUT);
        }
        return result.toString();
    }

    /**
     * A line for chat: the game reads a section sign and the character after it as a formatting code wherever they
     * stand in a text, so a model id or texture name could colour or hide the rest of the line. The log keeps them.
     */
    public static String chat(String line) {
        return line.indexOf(FORMATTING_CODE) < 0 ? line : line.replace(FORMATTING_CODE, PLAIN_CODE);
    }

    private static boolean unsafe(int point) {
        switch (Character.getType(point)) {
            case Character.CONTROL, Character.FORMAT, Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR, Character.SURROGATE -> {
                return true;
            }
            default -> {
                return false;
            }
        }
    }
}
