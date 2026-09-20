package ru.dw.qeditor.json;

/**
 * Небольшой парсер JSON. Числа хранятся как Long/Double,
 * порядок ключей объектов сохраняется.
 */
public final class JsonParser {
    private final String s;
    private int p;

    private JsonParser(String s) { this.s = s; }

    public static Object parse(String text) {
        JsonParser jp = new JsonParser(text);
        jp.ws();
        Object v = jp.value();
        jp.ws();
        if (jp.p < jp.s.length()) throw new JsonException("Лишние символы в позиции " + jp.p);
        return v;
    }

    public static JsonObject parseObject(String text) {
        Object v = parse(text);
        if (!(v instanceof JsonObject))
            throw new JsonException("Ожидался JSON-объект, получено: " + (v == null ? "null" : v.getClass().getSimpleName()));
        return (JsonObject) v;
    }

    private void ws() {
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '﻿') p++;
            else break;
        }
    }

    private Object value() {
        if (p >= s.length()) throw new JsonException("Неожиданный конец данных");
        char c = s.charAt(p);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private void expect(String lit) {
        if (!s.startsWith(lit, p)) throw new JsonException("Ожидалось '" + lit + "' в позиции " + p);
        p += lit.length();
    }

    private JsonObject object() {
        JsonObject o = new JsonObject();
        p++;
        ws();
        if (p < s.length() && s.charAt(p) == '}') { p++; return o; }
        while (true) {
            ws();
            if (p >= s.length() || s.charAt(p) != '"') throw new JsonException("Ожидался ключ в позиции " + p);
            String key = string();
            ws();
            if (p >= s.length() || s.charAt(p) != ':') throw new JsonException("Ожидалось ':' в позиции " + p);
            p++;
            ws();
            o.put(key, value());
            ws();
            if (p >= s.length()) throw new JsonException("Неожиданный конец объекта");
            char c = s.charAt(p);
            if (c == ',') { p++; continue; }
            if (c == '}') { p++; return o; }
            throw new JsonException("Ожидалось ',' или '}' в позиции " + p);
        }
    }

    private JsonArray array() {
        JsonArray a = new JsonArray();
        p++;
        ws();
        if (p < s.length() && s.charAt(p) == ']') { p++; return a; }
        while (true) {
            ws();
            a.add(value());
            ws();
            if (p >= s.length()) throw new JsonException("Неожиданный конец массива");
            char c = s.charAt(p);
            if (c == ',') { p++; continue; }
            if (c == ']') { p++; return a; }
            throw new JsonException("Ожидалось ',' или ']' в позиции " + p);
        }
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        p++;
        while (true) {
            if (p >= s.length()) throw new JsonException("Незакрытая строка");
            char c = s.charAt(p++);
            if (c == '"') return sb.toString();
            if (c != '\\') { sb.append(c); continue; }
            if (p >= s.length()) throw new JsonException("Незакрытый escape");
            char e = s.charAt(p++);
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (p + 4 > s.length()) throw new JsonException("Обрезанный \\u escape");
                    sb.append((char) Integer.parseInt(s.substring(p, p + 4), 16));
                    p += 4;
                    break;
                default: sb.append(e);
            }
        }
    }

    private Object number() {
        int start = p;
        if (p < s.length() && (s.charAt(p) == '-' || s.charAt(p) == '+')) p++;
        boolean fp = false;
        while (p < s.length()) {
            char c = s.charAt(p);
            if (c >= '0' && c <= '9') { p++; continue; }
            if (c == '.' || c == 'e' || c == 'E' || c == '-' || c == '+') {
                fp |= (c == '.' || c == 'e' || c == 'E');
                p++;
                continue;
            }
            break;
        }
        String num = s.substring(start, p);
        if (num.isEmpty()) throw new JsonException("Некорректное значение в позиции " + start);
        if (!fp) {
            try { return Long.parseLong(num); } catch (NumberFormatException ignored) { }
        }
        try { return Double.parseDouble(num); }
        catch (NumberFormatException e) { throw new JsonException("Некорректное число: " + num); }
    }

    public static class JsonException extends RuntimeException {
        public JsonException(String m) { super(m); }
    }
}
