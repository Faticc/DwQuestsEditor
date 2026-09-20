package ru.dw.qeditor.model;

import ru.dw.qeditor.json.JsonObject;

/**
 * Зависимость квеста. В JSON может быть как строкой (только questID),
 * так и объектом — ровно как это разбирает ParentDeserializer мода.
 */
public class Parent {

    public String tabID;
    public String questID;
    public Defs.ParentType parentType = Defs.ParentType.DEFAULT;
    public Defs.LineType lineType = Defs.LineType.DEFAULT;
    /** true, если в исходном JSON зависимость была записана просто строкой. */
    public boolean plainString;

    public Parent() { }

    public Parent(String questID) {
        this.questID = questID;
        this.plainString = true;
    }

    public static Parent fromJson(Object v) {
        if (v instanceof String) return new Parent((String) v);
        if (v instanceof JsonObject) {
            JsonObject o = (JsonObject) v;
            Parent p = new Parent();
            p.tabID = o.str("tabID", null);
            p.questID = o.str("questID", null);
            p.parentType = Defs.ParentType.of(o.str("parentType", null));
            p.lineType = Defs.LineType.of(o.str("lineType", null));
            return p;
        }
        return new Parent(String.valueOf(v));
    }

    public Object toJson() {
        boolean simple = tabID == null
                && parentType == Defs.ParentType.DEFAULT
                && lineType == Defs.LineType.DEFAULT;
        if (simple && plainString) return questID;
        JsonObject o = new JsonObject();
        if (tabID != null && !tabID.isEmpty()) o.put("tabID", tabID);
        o.put("questID", questID == null ? "" : questID);
        if (parentType != Defs.ParentType.DEFAULT) o.put("parentType", parentType.name());
        if (lineType != Defs.LineType.DEFAULT) o.put("lineType", lineType.name());
        return o;
    }

    public Parent copy() {
        Parent p = new Parent();
        p.tabID = tabID;
        p.questID = questID;
        p.parentType = parentType;
        p.lineType = lineType;
        p.plainString = plainString;
        return p;
    }
}
