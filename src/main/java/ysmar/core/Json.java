package ysmar.core;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reader for the JSON files of a model folder: objects become maps in the order of the file, arrays lists, numbers
 * doubles. Only what RFC 8259 allows is taken (a UTF-8 byte order mark in front is skipped): a text that needs a
 * lenient reader is refused, and so are a name twice in one object, nesting deeper than MAX_DEPTH, more than
 * MAX_VALUES values and a text whose values would take more than MAX_WEIGHT bytes of memory. Messages carry a
 * position, never text of the file.
 */
final class Json {
    static final int MAX_DEPTH = 64;
    static final int MAX_VALUES = 4_000_000;
    /**
     * What the values of one text may weigh in memory, by an estimate that errs upwards: NUMBER bytes for a number,
     * STRING and two per character for a string, ARRAY and ELEMENT per element for an array, OBJECT and MEMBER per
     * member for an object. A name counts as a string the first time it is read; the same name again is the same
     * string. Without it the size limit of a file would allow some thirty times the file in memory.
     */
    static final long MAX_WEIGHT = 192L << 20;
    private static final int NUMBER = 24;
    private static final int WORD = 8;
    private static final int STRING = 56;
    private static final int ARRAY = 88;
    private static final int ELEMENT = 6;
    private static final int OBJECT = 144;
    private static final int MEMBER = 48;
    private static final int MAX_NAMES = 4096;
    /** The value null of a text. */
    static final Object NULL = new Object();

    static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        Malformed(String reason) {
            super(reason, null, false, false);
        }
    }

    private final String text;
    private int position;
    private int values;
    private long weight;
    private final HashMap<String, String> names = new HashMap<>();

    private Json(String text) {
        this.text = text;
    }

    static Object parse(byte[] bytes) throws Malformed {
        return parse(bytes, null);
    }

    /** weight: told what the values of the text weigh by the estimate of MAX_WEIGHT; null when nobody asks. */
    static Object parse(byte[] bytes, long[] weight) throws Malformed {
        int start = bytes.length >= 3 && bytes[0] == (byte) 0xEF && bytes[1] == (byte) 0xBB && bytes[2] == (byte) 0xBF ? 3 : 0;
        String text;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes, start, bytes.length - start)).toString();
        } catch (CharacterCodingException notUtf8) {
            throw new Malformed("not UTF-8 text");
        }
        Json reader = new Json(text);
        Object value = reader.value(0);
        reader.blanks();
        if (reader.position != text.length()) {
            throw reader.problem("text after the value");
        }
        if (weight != null) {
            weight[0] = reader.weight;
        }
        return value;
    }

    private void weigh(long bytes) throws Malformed {
        weight += bytes;
        if (weight > MAX_WEIGHT) {
            throw new Malformed("values that take more than " + (MAX_WEIGHT >> 20) + " MiB of memory");
        }
    }

    private Object value(int depth) throws Malformed {
        if (++values > MAX_VALUES) {
            throw new Malformed("more than " + MAX_VALUES + " values");
        }
        blanks();
        if (position >= text.length()) {
            throw problem("the text ends inside a value");
        }
        char first = text.charAt(position);
        switch (first) {
            case '{' -> {
                return object(depth);
            }
            case '[' -> {
                return array(depth);
            }
            case '"' -> {
                String value = string();
                weigh(STRING + 2L * value.length());
                return value;
            }
            case 't' -> {
                weigh(WORD);
                return word("true", Boolean.TRUE);
            }
            case 'f' -> {
                weigh(WORD);
                return word("false", Boolean.FALSE);
            }
            case 'n' -> {
                weigh(WORD);
                return word("null", NULL);
            }
            default -> {
                weigh(NUMBER);
                return number();
            }
        }
    }

    private Map<String, Object> object(int depth) throws Malformed {
        if (depth >= MAX_DEPTH) {
            throw problem("nested deeper than " + MAX_DEPTH + " levels");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        weigh(OBJECT);
        position++;
        blanks();
        if (peek() == '}') {
            position++;
            return result;
        }
        while (true) {
            blanks();
            if (peek() != '"') {
                throw problem("a name is expected");
            }
            String name = string();
            String known = names.get(name);
            if (known != null) {
                name = known;
            } else {
                weigh(STRING + 2L * name.length());
                if (names.size() < MAX_NAMES) {
                    names.put(name, name);
                }
            }
            weigh(MEMBER);
            blanks();
            if (peek() != ':') {
                throw problem("a colon is expected");
            }
            position++;
            if (result.put(name, value(depth + 1)) != null) {
                throw problem("a name occurs twice in one object");
            }
            blanks();
            char next = peek();
            position++;
            if (next == '}') {
                return result;
            }
            if (next != ',') {
                throw problem("a comma or the end of the object is expected");
            }
        }
    }

    private List<Object> array(int depth) throws Malformed {
        if (depth >= MAX_DEPTH) {
            throw problem("nested deeper than " + MAX_DEPTH + " levels");
        }
        List<Object> result = new ArrayList<>();
        weigh(ARRAY);
        position++;
        blanks();
        if (peek() == ']') {
            position++;
            return result;
        }
        while (true) {
            weigh(ELEMENT);
            result.add(value(depth + 1));
            blanks();
            char next = peek();
            position++;
            if (next == ']') {
                return result;
            }
            if (next != ',') {
                throw problem("a comma or the end of the array is expected");
            }
        }
    }

    private String string() throws Malformed {
        int start = ++position;
        StringBuilder escaped = null;
        while (true) {
            if (position >= text.length()) {
                throw problem("the text ends inside a string");
            }
            char letter = text.charAt(position);
            if (letter == '"') {
                String result = escaped == null ? text.substring(start, position) : escaped.append(text, start, position).toString();
                position++;
                return result;
            }
            if (letter < ' ') {
                throw problem("a control character inside a string");
            }
            if (letter != '\\') {
                position++;
                continue;
            }
            if (escaped == null) {
                escaped = new StringBuilder();
            }
            escaped.append(text, start, position);
            if (position + 1 >= text.length()) {
                throw problem("the text ends inside a string");
            }
            char kind = text.charAt(position + 1);
            position += 2;
            switch (kind) {
                case '"', '\\', '/' -> escaped.append(kind);
                case 'b' -> escaped.append('\b');
                case 'f' -> escaped.append('\f');
                case 'n' -> escaped.append('\n');
                case 'r' -> escaped.append('\r');
                case 't' -> escaped.append('\t');
                case 'u' -> {
                    if (position + 4 > text.length()) {
                        throw problem("the text ends inside a string");
                    }
                    int code = 0;
                    for (int index = 0; index < 4; index++) {
                        // The sixteen digits of ASCII only: Character.digit takes the digits of every script.
                        char hex = text.charAt(position + index);
                        int digit = hex >= '0' && hex <= '9' ? hex - '0' : hex >= 'a' && hex <= 'f' ? hex - 'a' + 10
                                : hex >= 'A' && hex <= 'F' ? hex - 'A' + 10 : -1;
                        if (digit < 0) {
                            throw problem("an escape that is not four hex digits");
                        }
                        code = code * 16 + digit;
                    }
                    position += 4;
                    escaped.append((char) code);
                }
                default -> throw problem("an unknown escape");
            }
            start = position;
        }
    }

    private Object word(String word, Object value) throws Malformed {
        if (!text.startsWith(word, position)) {
            throw problem("an unknown word");
        }
        position += word.length();
        return value;
    }

    // number = [ "-" ] ( "0" | digit1-9 *digit ) [ "." 1*digit ] [ ( "e" | "E" ) [ "+" | "-" ] 1*digit ]
    private Double number() throws Malformed {
        int start = position;
        if (peek() == '-') {
            position++;
        }
        if (peek() == '0') {
            position++;
        } else if (digits() == 0) {
            throw problem("a value is expected");
        }
        if (peek() == '.') {
            position++;
            if (digits() == 0) {
                throw problem("a number without digits after its point");
            }
        }
        if (peek() == 'e' || peek() == 'E') {
            position++;
            if (peek() == '+' || peek() == '-') {
                position++;
            }
            if (digits() == 0) {
                throw problem("a number without digits in its exponent");
            }
        }
        double value = Double.parseDouble(text.substring(start, position));
        if (Double.isInfinite(value)) {
            throw problem("a number out of range");
        }
        return value;
    }

    private int digits() {
        int start = position;
        while (position < text.length() && text.charAt(position) >= '0' && text.charAt(position) <= '9') {
            position++;
        }
        return position - start;
    }

    private void blanks() {
        while (position < text.length()) {
            char letter = text.charAt(position);
            if (letter != ' ' && letter != '\t' && letter != '\n' && letter != '\r') {
                return;
            }
            position++;
        }
    }

    /** The character at the cursor; 0 at the end of the text, which is no character of the grammar. */
    private char peek() {
        return position < text.length() ? text.charAt(position) : 0;
    }

    private Malformed problem(String what) {
        return new Malformed(what + " (character " + Math.min(position, text.length()) + ")");
    }
}
