/*
 * Click nbfs://nbhost/SystemFileSystem/Templates/Licenses/license-default.txt to change this license
 * Click nbfs://nbhost/SystemFileSystem/Templates/Classes/Class.java to edit this template
 */

package org.stillword;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 *
 * @author veronique
 */

/** Small strict JSON codec, so the desktop application needs no external libraries. */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("JSON text is missing.");
        Parser parser = new Parser(text);
        Object value = parser.value(0);
        parser.space();
        if (parser.index != text.length()) throw parser.error("Unexpected trailing content");
        return value;
    }

    public static String stringify(Object value) {
        StringBuilder output = new StringBuilder();
        write(value, output, 0);
        return output.toString();
    }

    private static void write(Object value, StringBuilder out, int depth) {
        if (depth > 100) throw new IllegalArgumentException("JSON nesting is too deep.");
        if (value == null) out.append("null");
        else if (value instanceof String string) quote(string, out);
        else if (value instanceof Boolean) out.append(value);
        else if (value instanceof Number number) {
            String token = number.toString();
            try { new BigDecimal(token); }
            catch (NumberFormatException exception) {
                throw new IllegalArgumentException("JSON requires a finite number.");
            }
            out.append(token);
        } else if (value instanceof Map<?, ?> map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key))
                    throw new IllegalArgumentException("JSON object keys must be strings.");
                if (!first) out.append(',');
                first = false;
                quote(key, out);
                out.append(':');
                write(entry.getValue(), out, depth + 1);
            }
            out.append('}');
        } else if (value instanceof Iterable<?> list) {
            out.append('[');
            boolean first = true;
            for (Object item : list) {
                if (!first) out.append(',');
                first = false;
                write(item, out, depth + 1);
            }
            out.append(']');
        } else {
            throw new IllegalArgumentException("Unsupported JSON value type.");
        }
    }

    private static void quote(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char character = text.charAt(i);
            switch (character) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (character < 0x20 || Character.isSurrogate(character)) {
                        out.append("\\u");
                        String hex = Integer.toHexString(character);
                        out.append("0".repeat(4 - hex.length())).append(hex);
                    } else out.append(character);
                }
            }
        }
        out.append('"');
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) { this.text = text; }

        private Object value(int depth) {
            if (depth > 100) throw error("JSON nesting is too deep");
            space();
            if (index == text.length()) throw error("Expected a value");
            return switch (text.charAt(index)) {
                case '"' -> string();
                case '{' -> object(depth + 1);
                case '[' -> array(depth + 1);
                case 't' -> literal("true", Boolean.TRUE);
                case 'f' -> literal("false", Boolean.FALSE);
                case 'n' -> literal("null", null);
                default -> number();
            };
        }

        private Map<String, Object> object(int depth) {
            index++;
            Map<String, Object> values = new LinkedHashMap<>();
            space();
            if (take('}')) return values;
            do {
                space();
                if (index == text.length() || text.charAt(index) != '"')
                    throw error("Expected an object key");
                String key = string();
                space();
                if (!take(':')) throw error("Expected a colon");
                if (values.containsKey(key)) throw error("Duplicate object key");
                values.put(key, value(depth));
                space();
                if (take('}')) return values;
                if (!take(',')) throw error("Expected a comma or closing brace");
            } while (true);
        }

        private List<Object> array(int depth) {
            index++;
            List<Object> values = new ArrayList<>();
            space();
            if (take(']')) return values;
            do {
                values.add(value(depth));
                space();
                if (take(']')) return values;
                if (!take(',')) throw error("Expected a comma or closing bracket");
            } while (true);
        }

        private String string() {
            index++;
            StringBuilder result = new StringBuilder();
            while (index < text.length()) {
                char character = text.charAt(index++);
                if (character == '"') return result.toString();
                if (character < 0x20) throw error("Unescaped control character");
                if (character != '\\') {
                    result.append(character);
                    continue;
                }
                if (index == text.length()) throw error("Incomplete escape");
                switch (text.charAt(index++)) {
                    case '"' -> result.append('"');
                    case '\\' -> result.append('\\');
                    case '/' -> result.append('/');
                    case 'b' -> result.append('\b');
                    case 'f' -> result.append('\f');
                    case 'n' -> result.append('\n');
                    case 'r' -> result.append('\r');
                    case 't' -> result.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) throw error("Incomplete Unicode escape");
                        int code = 0;
                        for (int i = 0; i < 4; i++) {
                            int digit = Character.digit(text.charAt(index++), 16);
                            if (digit < 0) throw error("Invalid Unicode escape");
                            code = code * 16 + digit;
                        }
                        result.append((char) code);
                    }
                    default -> throw error("Invalid escape");
                }
            }
            throw error("Unterminated string");
        }

        private Object literal(String expected, Object value) {
            if (!text.startsWith(expected, index)) throw error("Invalid value");
            index += expected.length();
            return value;
        }

        private BigDecimal number() {
            int start = index;
            take('-');
            if (!take('0')) {
                if (index >= text.length() || text.charAt(index) < '1' || text.charAt(index) > '9')
                    throw error("Invalid number");
                digits();
            }
            if (take('.')) {
                int before = index;
                digits();
                if (before == index) throw error("Expected fractional digits");
            }
            if (take('e') || take('E')) {
                if (!take('+')) take('-');
                int before = index;
                digits();
                if (before == index) throw error("Expected exponent digits");
            }
            try { return new BigDecimal(text.substring(start, index)); }
            catch (NumberFormatException exception) { throw error("Invalid number"); }
        }

        private void digits() {
            while (index < text.length() && text.charAt(index) >= '0' && text.charAt(index) <= '9') index++;
        }

        private boolean take(char character) {
            if (index < text.length() && text.charAt(index) == character) {
                index++;
                return true;
            }
            return false;
        }

        private void space() {
            while (index < text.length()) {
                char character = text.charAt(index);
                if (character != ' ' && character != '\n' && character != '\r' && character != '\t') return;
                index++;
            }
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " at JSON character " + index + ".");
        }
    }
}
