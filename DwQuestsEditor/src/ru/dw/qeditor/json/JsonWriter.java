package ru.dw.qeditor.json;

import java.util.Map;

/** Вывод JSON в том же стиле, что отдаёт панель/мод: отступ 2 пробела. */
public final class JsonWriter {

    public static String write(Object value, boolean pretty) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, pretty, 0);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v, boolean pretty, int depth) {
        if (v == null) { sb.append("null"); return; }
        if (v instanceof String) { quote(sb, (String) v); return; }
        if (v instanceof Boolean) { sb.append(v.toString()); return; }
        if (v instanceof Number) { number(sb, (Number) v); return; }
        if (v instanceof JsonObject) { object(sb, (JsonObject) v, pretty, depth); return; }
        if (v instanceof JsonArray) { array(sb, (JsonArray) v, pretty, depth); return; }
        quote(sb, String.valueOf(v));
    }

    private static void number(StringBuilder sb, Number n) {
        if (n instanceof Long || n instanceof Integer) { sb.append(n.longValue()); return; }
        double d = n.doubleValue();
        if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
            sb.append((long) d);
        } else {
            String s = String.valueOf(d);
            if (s.endsWith(".0")) s = s.substring(0, s.length() - 2);
            sb.append(s);
        }
    }

    private static void object(StringBuilder sb, JsonObject o, boolean pretty, int depth) {
        if (o.size() == 0) { sb.append("{}"); return; }
        sb.append('{');
        int i = 0;
        for (Map.Entry<String, Object> e : o.map().entrySet()) {
            if (i++ > 0) sb.append(',');
            if (pretty) { sb.append('\n'); indent(sb, depth + 1); }
            quote(sb, e.getKey());
            sb.append(':');
            if (pretty) sb.append(' ');
            write(sb, e.getValue(), pretty, depth + 1);
        }
        if (pretty) { sb.append('\n'); indent(sb, depth); }
        sb.append('}');
    }

    private static void array(StringBuilder sb, JsonArray a, boolean pretty, int depth) {
        if (a.size() == 0) { sb.append("[]"); return; }
        sb.append('[');
        for (int i = 0; i < a.size(); i++) {
            if (i > 0) sb.append(',');
            if (pretty) { sb.append('\n'); indent(sb, depth + 1); }
            write(sb, a.get(i), pretty, depth + 1);
        }
        if (pretty) { sb.append('\n'); indent(sb, depth); }
        sb.append(']');
    }

    private static void indent(StringBuilder sb, int depth) {
        for (int i = 0; i < depth; i++) sb.append("  ");
    }

    private static void quote(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': sb.append('\\').append('"'); break;
                case '\\': sb.append('\\').append('\\'); break;
                case '\n': sb.append('\\').append('n'); break;
                case '\r': sb.append('\\').append('r'); break;
                case '\t': sb.append('\\').append('t'); break;
                case '\b': sb.append('\\').append('b'); break;
                case '\f': sb.append('\\').append('f'); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append('"');
    }
}
