package ru.dw.qeditor.model;

import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** Вкладка квестов — ru.sky.dw.quests.common.quest.QuestTab. */
public class QuestTab {

    private static final Set<String> KNOWN = new LinkedHashSet<>();
    static {
        KNOWN.add("tabID");
        KNOWN.add("iconItem");
        KNOWN.add("sortIndex");
        KNOWN.add("quests");
    }

    public String tabID = "NewTab";
    public String iconItem = "minecraft:book";
    public int sortIndex;
    public final LinkedHashMap<String, Quest> quests = new LinkedHashMap<>();
    public final JsonObject extra = new JsonObject();

    public static QuestTab empty(String tabID) {
        QuestTab t = new QuestTab();
        t.tabID = tabID;
        return t;
    }

    /**
     * Разбор JSON вкладки. Панель иногда отдаёт содержимое двойным кодированием
     * (весь объект — одна JSON-строка); мод это разворачивает, делаем так же.
     */
    public static QuestTab parse(String json) {
        String candidate = json;
        Object root = JsonParser.parse(json);
        if (root instanceof String) {
            candidate = (String) root;
            root = JsonParser.parse(candidate);
        }
        if (!(root instanceof JsonObject)) throw new JsonParser.JsonException("Ожидался объект вкладки");
        JsonObject o = (JsonObject) root;

        QuestTab tab = new QuestTab();
        tab.tabID = o.str("tabID", null);
        if (tab.tabID == null || tab.tabID.trim().isEmpty())
            throw new JsonParser.JsonException("Отсутствует поле tabID");
        tab.iconItem = o.str("iconItem", "");
        tab.sortIndex = o.i("sortIndex", 0);

        JsonObject qs = o.obj("quests");
        if (qs != null) {
            for (String key : qs.keys()) {
                JsonObject qo = qs.obj(key);
                if (qo != null) tab.quests.put(key, Quest.fromJson(key, qo));
            }
        }
        for (String k : o.keys()) if (!KNOWN.contains(k)) tab.extra.put(k, o.get(k));
        return tab;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.put("tabID", tabID);
        o.put("iconItem", iconItem == null ? "" : iconItem);
        o.put("sortIndex", (long) sortIndex);
        JsonObject qs = new JsonObject();
        for (Map.Entry<String, Quest> e : quests.entrySet()) {
            e.getValue().questID = e.getKey();
            qs.put(e.getKey(), e.getValue().toJson());
        }
        o.put("quests", qs);
        for (String k : extra.keys()) o.put(k, extra.get(k));
        return o;
    }

    public String toJsonString() { return JsonWriter.write(toJson(), true); }

    /**
     * Без отступов и переносов — так вкладка уходит в игру. {@code /dwq tab paste}
     * шлёт буфер одним пакетом (CSPEditTab, zstd), а пакет от клиента в 1.7.10 не
     * больше 32 КБ: отступы стоят килобайт после сжатия, и большие вкладки не влезали.
     */
    public String toCompactJson() { return JsonWriter.write(toJson(), false); }

    public QuestTab copy() { return parse(toJsonString()); }

    /** Переименовать квест, поправив ссылки в зависимостях других квестов. */
    public boolean renameQuest(String oldID, String newID) {
        if (oldID == null || newID == null || oldID.equals(newID)) return false;
        if (!quests.containsKey(oldID) || quests.containsKey(newID)) return false;
        LinkedHashMap<String, Quest> copy = new LinkedHashMap<>(quests);
        quests.clear();
        for (Map.Entry<String, Quest> e : copy.entrySet()) {
            String key = e.getKey().equals(oldID) ? newID : e.getKey();
            e.getValue().questID = key;
            quests.put(key, e.getValue());
        }
        for (Quest q : quests.values())
            for (Parent p : q.parents)
                if (oldID.equals(p.questID) && (p.tabID == null || p.tabID.equals(tabID))) p.questID = newID;
        return true;
    }

    /** Удалить квест и все ссылки на него. */
    public void removeQuest(String id) {
        quests.remove(id);
        for (Quest q : quests.values())
            q.parents.removeIf(p -> id.equals(p.questID) && (p.tabID == null || p.tabID.equals(tabID)));
    }

    public String freeQuestName() {
        int n = quests.size();
        String name = "Квест" + n;
        while (quests.containsKey(name)) name = "Квест" + (++n);
        return name;
    }

    public float minX() {
        float v = 0f;
        for (Quest q : quests.values()) v = Math.min(v, q.displayX);
        return v;
    }

    public float minY() {
        float v = 0f;
        for (Quest q : quests.values()) v = Math.min(v, q.displayY);
        return v;
    }

    public float maxX() {
        float v = 0f;
        for (Quest q : quests.values()) v = Math.max(v, q.displayX);
        return v;
    }

    public float maxY() {
        float v = 0f;
        for (Quest q : quests.values()) v = Math.max(v, q.displayY);
        return v;
    }
}
