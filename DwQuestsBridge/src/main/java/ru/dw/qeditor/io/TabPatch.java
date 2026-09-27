package ru.dw.qeditor.io;

import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.QuestTab;

/**
 * Слияние JSON-патча со вкладкой: объекты сливаются рекурсивно, {@code null}
 * удаляет ключ, массивы и скаляры заменяются целиком.
 *
 * <p>Одна и та же логика нужна и консольной команде {@code patch}, и помощнику
 * Claude: он отвечает только изменившимися квестами, а не всей вкладкой — на
 * вкладке в тысячу квестов это разница в сотни тысяч символов.
 */
public final class TabPatch {

    private TabPatch() { }

    /** Слить патч со вкладкой и разобрать результат обратно в модель. */
    public static QuestTab apply(QuestTab tab, JsonObject patch) {
        JsonObject base = tab.toJson();
        merge(base, patch);
        return QuestTab.parse(JsonWriter.write(base, true));
    }

    /** Разобрать патч из текста и применить. */
    public static QuestTab apply(QuestTab tab, String json) {
        Object parsed = JsonParser.parse(TabIO.stripBom(json));
        if (!(parsed instanceof JsonObject))
            throw new IllegalArgumentException("патч должен быть JSON-объектом");
        return apply(tab, (JsonObject) parsed);
    }

    public static void merge(JsonObject base, JsonObject patch) {
        for (String key : patch.keys()) {
            Object v = patch.get(key);
            if (v == null) {
                base.remove(key);
            } else if (v instanceof JsonObject && base.get(key) instanceof JsonObject) {
                merge((JsonObject) base.get(key), (JsonObject) v);
            } else {
                base.put(key, v);
            }
        }
    }
}
