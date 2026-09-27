package ru.dw.qeditor.app;

import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.json.JsonArray;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.Task;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Копирование частей квеста — задач, наград, зависимостей — между квестами.
 *
 * <p>Кладётся всё в системный буфер обмена, а не в поле класса: так часть
 * переносится и в другую вкладку, и в другое окно редактора, а заодно её
 * можно поправить текстом. Формат — {@code {"dwqParts":{"tasks":{…},
 * "rewards":[…], "parents":[…]}}}: задачи с номерами, как в файле вкладки,
 * иначе при вставке не восстановить связи «после задач» между ними.
 */
final class QuestParts {

    private static final String MARK = "dwqParts";

    private QuestParts() { }

    // ---------------------------------------------------------------- копия

    static void copyTasks(GlWindow window, Map<Integer, Task> tasks) {
        JsonObject o = new JsonObject();
        for (Map.Entry<Integer, Task> e : tasks.entrySet()) o.put(String.valueOf(e.getKey()), e.getValue().toJson());
        put(window, "tasks", o);
    }

    static void copyRewards(GlWindow window, List<String> rewards) {
        JsonArray a = new JsonArray();
        for (String r : rewards) a.add(r);
        put(window, "rewards", a);
    }

    static void copyParents(GlWindow window, List<Parent> parents) {
        JsonArray a = new JsonArray();
        for (Parent p : parents) a.add(p.toJson());
        put(window, "parents", a);
    }

    private static void put(GlWindow window, String key, Object value) {
        JsonObject parts = new JsonObject().put(key, value);
        window.setClipboard(JsonWriter.write(new JsonObject().put(MARK, parts), true));
    }

    // --------------------------------------------------------------- чтение

    /** Части из буфера или null, если там не они. */
    private static JsonObject read(GlWindow window) {
        String text = window.clipboard();
        if (text == null || !text.contains(MARK)) return null;
        try {
            Object parsed = JsonParser.parse(text);
            return parsed instanceof JsonObject o ? o.obj(MARK) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean has(GlWindow window, String key) {
        JsonObject parts = read(window);
        return parts != null && parts.has(key);
    }

    // --------------------------------------------------------------- вставка

    /**
     * Задачи из буфера — в конец квеста. Номера берутся свободные, а ссылки
     * «после задач» внутри вставленного набора переводятся на новые номера;
     * ссылки на задачи вне набора в чужом квесте смысла не имеют и выпадают.
     */
    static int pasteTasks(GlWindow window, Quest q) {
        JsonObject parts = read(window);
        JsonObject src = parts == null ? null : parts.obj("tasks");
        if (src == null || q == null) return 0;
        Map<Integer, Task> tasks = new LinkedHashMap<>();
        for (String key : src.keys()) {
            JsonObject t = src.obj(key);
            if (t == null) continue;
            try {
                tasks.put(Integer.parseInt(key.trim()), Task.fromJson(t));
            } catch (NumberFormatException ignored) {
                // ключ не номер — такую задачу мод тоже не прочтёт
            }
        }
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        int next = q.nextTaskID();
        for (Integer old : tasks.keySet()) remap.put(old, next++);
        for (Map.Entry<Integer, Task> e : tasks.entrySet()) {
            Task t = e.getValue();
            List<Integer> np = new ArrayList<>();
            for (int p : t.parents) {
                Integer r = remap.get(p);
                if (r != null) np.add(r);
            }
            t.parents.clear();
            t.parents.addAll(np);
            q.tasks.put(remap.get(e.getKey()), t);
        }
        q.renumberTasks();
        return tasks.size();
    }

    static int pasteRewards(GlWindow window, Quest q) {
        JsonObject parts = read(window);
        JsonArray src = parts == null ? null : parts.arr("rewards");
        if (src == null || q == null) return 0;
        int n = 0;
        for (int i = 0; i < src.size(); i++) {
            String r = src.str(i);
            if (r == null || r.isEmpty()) continue;
            q.rewards.add(r);
            n++;
        }
        return n;
    }

    /** Зависимость на самого себя и повторы пропускаются. */
    static int pasteParents(GlWindow window, Quest q) {
        JsonObject parts = read(window);
        JsonArray src = parts == null ? null : parts.arr("parents");
        if (src == null || q == null) return 0;
        int n = 0;
        for (int i = 0; i < src.size(); i++) {
            Parent p = Parent.fromJson(src.get(i));
            if (p == null || p.questID == null) continue;
            if (nz(p.tabID).isEmpty() && p.questID.equals(q.questID)) continue;
            boolean dup = false;
            for (Parent have : q.parents)
                dup |= p.questID.equals(have.questID) && nz(p.tabID).equals(nz(have.tabID));
            if (dup) continue;
            q.parents.add(p);
            n++;
        }
        return n;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
