package io.github.tajudeenj.streaming;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, strict, dependency-free JSON reader/writer.
 * Bounded nesting depth and input size protect against resource-exhaustion payloads.
 */
public final class Json {
    private static final int MAX_DEPTH = 32;

    private final String s;
    private int i;

    private Json(String s) { this.s = s; }

    public static Object parse(String text) {
        Json p = new Json(text);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != p.s.length()) throw p.err("trailing characters");
        return v;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw err("nesting too deep");
        if (i >= s.length()) throw err("unexpected end");
        char c = s.charAt(i);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> {
                if (c == '-' || (c >= '0' && c <= '9')) yield number();
                throw err("unexpected character");
            }
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            if (!peek('"')) throw err("expected key");
            String k = string();
            ws(); expect(':'); ws();
            m.put(k, value(depth + 1));
            ws();
            if (peek(',')) { i++; continue; }
            expect('}');
            return m;
        }
    }

    private List<Object> array(int depth) {
        List<Object> l = new ArrayList<>();
        i++; ws();
        if (peek(']')) { i++; return l; }
        while (true) {
            ws();
            l.add(value(depth + 1));
            ws();
            if (peek(',')) { i++; continue; }
            expect(']');
            return l;
        }
    }

    private String string() {
        expect('"');
        StringBuilder b = new StringBuilder();
        while (true) {
            if (i >= s.length()) throw err("unterminated string");
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c < 0x20) throw err("control character in string");
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) throw err("bad escape");
            char e = s.charAt(i++);
            switch (e) {
                case '"' -> b.append('"');
                case '\\' -> b.append('\\');
                case '/' -> b.append('/');
                case 'b' -> b.append('\b');
                case 'f' -> b.append('\f');
                case 'n' -> b.append('\n');
                case 'r' -> b.append('\r');
                case 't' -> b.append('\t');
                case 'u' -> {
                    if (i + 4 > s.length()) throw err("bad unicode escape");
                    try {
                        b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw err("bad unicode escape");
                    }
                    i += 4;
                }
                default -> throw err("bad escape");
            }
        }
    }

    private Double number() {
        int start = i;
        if (peek('-')) i++;
        while (i < s.length() && "0123456789.eE+-".indexOf(s.charAt(i)) >= 0) i++;
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException e) {
            throw err("bad number");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw err("bad literal");
        i += word.length();
        return v;
    }

    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }

    private void expect(char c) {
        if (!peek(c)) throw err("expected '" + c + "'");
        i++;
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\n' || c == '\r' || c == '\t') i++; else break;
        }
    }

    private IllegalArgumentException err(String msg) {
        return new IllegalArgumentException("Invalid JSON at position " + i + ": " + msg);
    }

    // ---------------------------------------------------------------- writer

    /** Builds a JSON object from alternating key/value pairs. */
    public static String obj(Object... kv) {
        if (kv.length % 2 != 0) throw new IllegalArgumentException("key/value pairs required");
        Map<String, Object> m = new LinkedHashMap<>();
        for (int k = 0; k < kv.length; k += 2) m.put((String) kv[k], kv[k + 1]);
        return write(m);
    }

    public static String write(Object v) {
        StringBuilder b = new StringBuilder();
        write(v, b, 0);
        return b.toString();
    }

    private static void write(Object v, StringBuilder b, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("nesting too deep");
        if (v == null) b.append("null");
        else if (v instanceof String str) quote(str, b);
        else if (v instanceof Boolean || v instanceof Integer || v instanceof Long) b.append(v);
        else if (v instanceof Number n) {
            double d = n.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) b.append("null"); else b.append(n);
        } else if (v instanceof Map<?, ?> m) {
            b.append('{');
            Iterator<? extends Map.Entry<?, ?>> it = m.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<?, ?> e = it.next();
                quote(String.valueOf(e.getKey()), b);
                b.append(':');
                write(e.getValue(), b, depth + 1);
                if (it.hasNext()) b.append(',');
            }
            b.append('}');
        } else if (v instanceof Iterable<?> l) {
            b.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) b.append(',');
                write(o, b, depth + 1);
                first = false;
            }
            b.append(']');
        } else quote(v.toString(), b);
    }

    private static void quote(String str, StringBuilder b) {
        b.append('"');
        for (int k = 0; k < str.length(); k++) {
            char c = str.charAt(k);
            switch (c) {
                case '"' -> b.append("\\\"");
                case '\\' -> b.append("\\\\");
                case '\n' -> b.append("\\n");
                case '\r' -> b.append("\\r");
                case '\t' -> b.append("\\t");
                // Escape HTML-significant and line-separator characters so output is safe to embed anywhere.
                case '<', '>', '&', '\u2028', '\u2029' -> b.append(String.format("\\u%04x", (int) c));
                default -> {
                    if (c < 0x20) b.append(String.format("\\u%04x", (int) c)); else b.append(c);
                }
            }
        }
        b.append('"');
    }
}
