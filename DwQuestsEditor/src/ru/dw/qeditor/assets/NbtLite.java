package ru.dw.qeditor.assets;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Чтение и запись NBT-строк в том виде, в каком их понимает 1.7.10
 * (JsonToNBT): {@code {display:{Name:"Гифт"},Items:[0:{Slot:0b,ID:"minecraft:dirt=1"}],MinRandom:1}}.
 *
 * Значения хранятся как String, Num, Compound или ListTag — этого достаточно
 * для гифтов и обычных предметов с NBT, и запись получается той же формы.
 */
public final class NbtLite {

    public static class Compound extends LinkedHashMap<String, Object> {
        public Compound comp(String key) {
            Object v = get(key);
            return v instanceof Compound ? (Compound) v : null;
        }

        public ListTag list(String key) {
            Object v = get(key);
            return v instanceof ListTag ? (ListTag) v : null;
        }

        public String string(String key) {
            Object v = get(key);
            if (v instanceof String) return (String) v;
            if (v instanceof Num) return ((Num) v).text;
            return null;
        }

        public int i(String key, int def) {
            Object v = get(key);
            if (v instanceof Num) return ((Num) v).intValue(def);
            if (v instanceof String) {
                try { return Integer.parseInt(((String) v).trim()); } catch (NumberFormatException ignored) { }
            }
            return def;
        }

        public boolean hasKey(String key) { return containsKey(key); }
    }

    public static class ListTag extends ArrayList<Object> {
        /** true — список пишется с индексами: [0:{...},1:{...}] (формат 1.7.10 для списка компаундов). */
        public boolean indexed;
    }

    /** Число с сохранённым суффиксом типа (b/s/l/f/d), чтобы запись не меняла смысл. */
    public static class Num {
        public final String text;
        public final char suffix;

        public Num(String text, char suffix) { this.text = text; this.suffix = suffix; }

        public static Num of(int v) { return new Num(String.valueOf(v), (char) 0); }

        public static Num of(int v, char suffix) { return new Num(String.valueOf(v), suffix); }

        public int intValue(int def) {
            try { return (int) Double.parseDouble(text); } catch (NumberFormatException e) { return def; }
        }

        @Override public String toString() { return suffix == 0 ? text : text + suffix; }
    }

    // ------------------------------------------------------------------ разбор

    public static Compound parse(String nbt) {
        if (nbt == null) return null;
        String s = nbt.trim();
        if (s.isEmpty()) return null;
        int open = s.indexOf('{');
        if (open < 0) return null;
        s = s.substring(open);
        try {
            P p = new P(s);
            Object v = p.value();
            return v instanceof Compound ? (Compound) v : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static class P {
        private final String s;
        private int i;

        P(String s) { this.s = s; }

        void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

        Object value() {
            ws();
            if (i >= s.length()) throw new IllegalStateException("конец данных");
            char c = s.charAt(i);
            if (c == '{') return compound();
            if (c == '[') return list();
            if (c == '"' || c == '\'') return quoted(c);
            return bare();
        }

        Compound compound() {
            Compound out = new Compound();
            i++; // {
            ws();
            if (i < s.length() && s.charAt(i) == '}') { i++; return out; }
            while (true) {
                ws();
                String key = key();
                ws();
                if (i >= s.length() || s.charAt(i) != ':') throw new IllegalStateException("ожидалось ':'");
                i++;
                out.put(key, value());
                ws();
                if (i >= s.length()) throw new IllegalStateException("конец компаунда");
                char c = s.charAt(i++);
                if (c == ',') continue;
                if (c == '}') return out;
                throw new IllegalStateException("ожидалось ',' или '}'");
            }
        }

        ListTag list() {
            ListTag out = new ListTag();
            i++; // [
            ws();
            if (i < s.length() && s.charAt(i) == ']') { i++; return out; }
            while (true) {
                ws();
                int save = i;
                // возможный префикс индекса "0:"
                int j = i;
                while (j < s.length() && Character.isDigit(s.charAt(j))) j++;
                if (j > i && j < s.length() && s.charAt(j) == ':') {
                    i = j + 1;
                    out.indexed = true;
                } else {
                    i = save;
                }
                out.add(value());
                ws();
                if (i >= s.length()) throw new IllegalStateException("конец списка");
                char c = s.charAt(i++);
                if (c == ',') continue;
                if (c == ']') return out;
                throw new IllegalStateException("ожидалось ',' или ']'");
            }
        }

        String key() {
            ws();
            char c = s.charAt(i);
            if (c == '"' || c == '\'') return quoted(c);
            int start = i;
            while (i < s.length() && s.charAt(i) != ':') i++;
            return s.substring(start, i).trim();
        }

        String quoted(char q) {
            StringBuilder sb = new StringBuilder();
            i++; // кавычка
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '\\' && i < s.length()) { sb.append(s.charAt(i++)); continue; }
                if (c == q) return sb.toString();
                sb.append(c);
            }
            return sb.toString();
        }

        Object bare() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if (c == ',' || c == '}' || c == ']') break;
                i++;
            }
            String raw = s.substring(start, i).trim();
            if (raw.isEmpty()) return "";
            char last = raw.charAt(raw.length() - 1);
            String body = raw;
            char suffix = 0;
            if (raw.length() > 1 && "bslfdBSLFD".indexOf(last) >= 0) {
                body = raw.substring(0, raw.length() - 1);
                suffix = last;
            }
            if (isNumeric(body)) return new Num(body, suffix);
            return raw;
        }

        static boolean isNumeric(String t) {
            if (t.isEmpty()) return false;
            boolean digit = false;
            for (int k = 0; k < t.length(); k++) {
                char c = t.charAt(k);
                if (c >= '0' && c <= '9') { digit = true; continue; }
                if ((c == '-' || c == '+') && k == 0) continue;
                if (c == '.') continue;
                return false;
            }
            return digit;
        }
    }

    // ------------------------------------------------------------------ запись

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(sb, v);
        return sb.toString();
    }

    private static void write(StringBuilder sb, Object v) {
        if (v == null) { sb.append("\"\""); return; }
        if (v instanceof Compound) {
            Compound c = (Compound) v;
            sb.append('{');
            int n = 0;
            for (java.util.Map.Entry<String, Object> e : c.entrySet()) {
                if (n++ > 0) sb.append(',');
                sb.append(writeKey(e.getKey())).append(':');
                write(sb, e.getValue());
            }
            sb.append('}');
            return;
        }
        if (v instanceof ListTag) {
            ListTag l = (ListTag) v;
            sb.append('[');
            for (int k = 0; k < l.size(); k++) {
                if (k > 0) sb.append(',');
                if (l.indexed) sb.append(k).append(':');
                write(sb, l.get(k));
            }
            sb.append(']');
            return;
        }
        if (v instanceof Num) { sb.append(v.toString()); return; }
        sb.append('"').append(escape(String.valueOf(v))).append('"');
    }

    private static String writeKey(String k) {
        boolean simple = !k.isEmpty();
        for (int i = 0; i < k.length() && simple; i++) {
            char c = k.charAt(i);
            simple = Character.isLetterOrDigit(c) || c == '_' || c == '-' || c == '.' || c == '+';
        }
        return simple ? k : "\"" + escape(k) + "\"";
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    // --------------------------------------------------------- удобные выборки

    /** display.Name из NBT предмета. */
    public static String displayName(String nbt) {
        Compound c = parse(nbt);
        if (c == null) return null;
        Compound d = c.comp("display");
        return d == null ? null : d.string("Name");
    }

    /** Имя гифта (display.Name) либо null. */
    public static String giftName(String nbt) { return displayName(nbt); }

    /** display.Lore из NBT предмета. */
    public static String lore(String nbt) {
        Compound c = parse(nbt);
        if (c == null) return null;
        Compound d = c.comp("display");
        if (d == null) return null;
        Object v = d.get("Lore");
        if (v instanceof ListTag) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (ListTag) v) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(o instanceof Num ? o.toString() : String.valueOf(o));
            }
            return sb.toString();
        }
        return d.string("Lore");
    }

    /** Гифт по умолчанию — как его создаёт редактор при выборе типа награды «Гифт». */
    public static Compound newGift() {
        Compound c = new Compound();
        c.put("MinRandom", Num.of(1));
        Compound display = new Compound();
        display.put("Lore", "");
        display.put("Name", "Гифт");
        c.put("display", display);
        ListTag items = new ListTag();
        items.indexed = true;
        c.put("Items", items);
        c.put("DropType", "RANDOM");
        ListTag layers = new ListTag();
        layers.add(Num.of(0));
        layers.add(Num.of(0));
        c.put("Layers", layers);
        c.put("MaxRandom", Num.of(1));
        return c;
    }
}
