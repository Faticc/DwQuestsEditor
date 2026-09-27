package ru.dw.qeditor.json;

import java.util.ArrayList;
import java.util.List;

public class JsonArray {
    private final List<Object> list = new ArrayList<>();

    public List<Object> list() { return list; }

    public int size() { return list.size(); }

    public Object get(int i) { return list.get(i); }

    public JsonObject obj(int i) {
        Object v = list.get(i);
        return v instanceof JsonObject ? (JsonObject) v : null;
    }

    public String str(int i) {
        Object v = list.get(i);
        return v instanceof String ? (String) v : null;
    }

    public JsonArray add(Object v) { list.add(v); return this; }

    @Override public String toString() { return JsonWriter.write(this, true); }
}
