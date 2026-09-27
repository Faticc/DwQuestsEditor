package ru.dw.qeditor.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Порядок ключей сохраняется — важно для побайтовой близости к исходнику вкладки. */
public class JsonObject {
    private final LinkedHashMap<String, Object> map = new LinkedHashMap<>();

    public Map<String, Object> map() { return map; }

    public boolean has(String key) { return map.containsKey(key); }

    public Object get(String key) { return map.get(key); }

    public JsonObject obj(String key) {
        Object v = map.get(key);
        return v instanceof JsonObject ? (JsonObject) v : null;
    }

    public JsonArray arr(String key) {
        Object v = map.get(key);
        return v instanceof JsonArray ? (JsonArray) v : null;
    }

    public String str(String key, String def) {
        Object v = map.get(key);
        return v instanceof String ? (String) v : def;
    }

    public int i(String key, int def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).intValue();
        if (v instanceof String) try { return (int) Double.parseDouble(((String) v).trim()); } catch (Exception ignored) {}
        return def;
    }

    public float f(String key, float def) {
        Object v = map.get(key);
        if (v instanceof Number) return ((Number) v).floatValue();
        if (v instanceof String) try { return Float.parseFloat(((String) v).trim()); } catch (Exception ignored) {}
        return def;
    }

    public boolean bool(String key, boolean def) {
        Object v = map.get(key);
        return v instanceof Boolean ? (Boolean) v : def;
    }

    public JsonObject put(String key, Object value) { map.put(key, value); return this; }

    public JsonObject putIfNotNull(String key, Object value) {
        if (value != null) map.put(key, value);
        return this;
    }

    public void remove(String key) { map.remove(key); }

    public List<String> keys() { return new ArrayList<>(map.keySet()); }

    public int size() { return map.size(); }

    @Override public String toString() { return JsonWriter.write(this, true); }
}
