package ru.dw.qeditor.io;

import ru.dw.qeditor.json.JsonArray;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Что изменится в файле, если сохранить вкладку: квесты и поля, старое → новое.
 *
 * <p>Обе стороны сравниваются после разбора в модель и обратной записи, поэтому
 * отступы и порядок необязательных полей в файле отличиями не считаются — только
 * то, что действительно поменяет содержимое.
 */
public final class TabDiff {

    public enum Kind { QUEST, ADDED, REMOVED, CHANGED, NOTE }

    /** Одна строка сводки; {@code depth} — отступ: 0 у квеста, 1 у его полей. */
    public record Line(Kind kind, String text, int depth) { }

    /** Длиннее — обрезаем: описание квеста бывает на экран, а нужна суть. */
    private static final int MAX_VALUE = 300;

    public final List<Line> lines = new ArrayList<>();
    public int added;
    public int removed;
    public int changed;

    private TabDiff() { }

    public boolean isEmpty() { return lines.isEmpty(); }

    public String summary() {
        if (lines.isEmpty()) return "Отличий от файла нет";
        List<String> parts = new ArrayList<>();
        if (added > 0) parts.add("добавлено " + added);
        if (changed > 0) parts.add("изменено " + changed);
        if (removed > 0) parts.add("удалено " + removed);
        return parts.isEmpty() ? "Изменены свойства вкладки" : "Квесты: " + String.join(", ", parts);
    }

    /** @param old то, что лежит в файле; null — файла ещё нет */
    public static TabDiff compare(QuestTab old, QuestTab now) {
        TabDiff d = new TabDiff();
        if (old == null) {
            d.added = now.quests.size();
            d.note("Файла ещё нет — запишется вся вкладка: " + now.quests.size() + " квестов");
            return d;
        }

        JsonObject a = old.toJson();
        JsonObject b = now.toJson();
        a.remove("quests");
        b.remove("quests");
        List<Line> tabLines = new ArrayList<>();
        d.object("", a, b, tabLines);
        if (!tabLines.isEmpty()) {
            d.lines.add(new Line(Kind.QUEST, "Вкладка", 0));
            d.lines.addAll(tabLines);
        }

        for (Map.Entry<String, Quest> e : now.quests.entrySet()) {
            String id = e.getKey();
            Quest was = old.quests.get(id);
            if (was == null) {
                d.added++;
                d.lines.add(new Line(Kind.ADDED, "+ " + title(id, e.getValue()), 0));
                continue;
            }
            List<Line> fields = new ArrayList<>();
            d.quest(was.toJson(), e.getValue().toJson(), fields);
            if (fields.isEmpty()) continue;
            d.changed++;
            d.lines.add(new Line(Kind.QUEST, title(id, e.getValue()), 0));
            d.lines.addAll(fields);
        }
        for (Map.Entry<String, Quest> e : old.quests.entrySet()) {
            if (now.quests.containsKey(e.getKey())) continue;
            d.removed++;
            d.lines.add(new Line(Kind.REMOVED, "- " + title(e.getKey(), e.getValue()), 0));
        }

        if (d.lines.isEmpty() && !old.toJsonString().equals(now.toJsonString()))
            d.note("Поменялся только порядок квестов в файле");
        return d;
    }

    private static String title(String id, Quest q) {
        String name = q.displayName == null || q.displayName.isEmpty() ? null : q.displayName;
        return name == null || name.equals(id) ? id : id + " — " + name;
    }

    private void note(String text) { lines.add(new Line(Kind.NOTE, text, 0)); }

    private void quest(JsonObject a, JsonObject b, List<Line> out) {
        // координаты порознь читать неудобно: «displayX: 2 → 3» без Y ничего не говорит
        Object ax = a.get("displayX"), ay = a.get("displayY");
        Object bx = b.get("displayX"), by = b.get("displayY");
        if (!same(ax, bx) || !same(ay, by))
            out.add(new Line(Kind.CHANGED, "клетка: (" + text(ax) + ", " + text(ay) + ") → ("
                    + text(bx) + ", " + text(by) + ")", 1));
        a.remove("displayX");
        a.remove("displayY");
        b.remove("displayX");
        b.remove("displayY");
        object("", a, b, out);
    }

    private void object(String path, JsonObject a, JsonObject b, List<Line> out) {
        Set<String> keys = new LinkedHashSet<>(b.keys());
        keys.addAll(a.keys());
        for (String k : keys) {
            String p = path.isEmpty() ? k : path + "." + k;
            if (!a.has(k)) out.add(new Line(Kind.ADDED, p + ": " + text(b.get(k)), 1));
            else if (!b.has(k)) out.add(new Line(Kind.REMOVED, p + ": " + text(a.get(k)), 1));
            else value(p, a.get(k), b.get(k), out);
        }
    }

    private void value(String path, Object a, Object b, List<Line> out) {
        if (same(a, b)) return;
        if (a instanceof JsonObject oa && b instanceof JsonObject ob) {
            object(path, oa, ob, out);
        } else if (a instanceof JsonArray la && b instanceof JsonArray lb) {
            array(path, la, lb, out);
        } else {
            out.add(new Line(Kind.CHANGED, path + ": " + text(a) + " → " + text(b), 1));
        }
    }

    /**
     * Награды и зависимости — списки, где важен состав, а не место: показываем,
     * что ушло и что пришло. Если состав тот же, значит поменялся порядок.
     */
    private void array(String path, JsonArray a, JsonArray b, List<Line> out) {
        Map<String, Integer> left = counts(a);
        Map<String, Integer> right = counts(b);
        boolean any = false;
        for (Map.Entry<String, Integer> e : left.entrySet()) {
            int extra = e.getValue() - right.getOrDefault(e.getKey(), 0);
            for (int i = 0; i < extra; i++) {
                out.add(new Line(Kind.REMOVED, path + ": " + cut(e.getKey()), 1));
                any = true;
            }
        }
        for (Map.Entry<String, Integer> e : right.entrySet()) {
            int extra = e.getValue() - left.getOrDefault(e.getKey(), 0);
            for (int i = 0; i < extra; i++) {
                out.add(new Line(Kind.ADDED, path + ": " + cut(e.getKey()), 1));
                any = true;
            }
        }
        if (!any) out.add(new Line(Kind.CHANGED, path + ": другой порядок", 1));
    }

    private static Map<String, Integer> counts(JsonArray arr) {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (Object v : arr.list()) m.merge(raw(v), 1, Integer::sum);
        return m;
    }

    private static boolean same(Object a, Object b) {
        return Objects.equals(JsonWriter.write(a, false), JsonWriter.write(b, false));
    }

    /** Строки без кавычек — так их и пишут в редакторе; остальное компактным JSON. */
    private static String raw(Object v) {
        return v instanceof String s ? s : JsonWriter.write(v, false);
    }

    private static String text(Object v) {
        if (v instanceof String s && s.isEmpty()) return "«»";
        return cut(raw(v));
    }

    private static String cut(String s) {
        String one = s.replace("\r", "").replace("\n", "\\n");
        return one.length() <= MAX_VALUE ? one : one.substring(0, MAX_VALUE) + "…";
    }
}
