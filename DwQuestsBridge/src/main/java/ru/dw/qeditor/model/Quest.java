package ru.dw.qeditor.model;

import ru.dw.qeditor.json.JsonArray;
import ru.dw.qeditor.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Квест — соответствует ru.sky.dw.quests.common.quest.Quest. */
public class Quest {

    private static final Set<String> KNOWN = new LinkedHashSet<>();
    static {
        KNOWN.add("displayX");
        KNOWN.add("displayY");
        KNOWN.add("displayName");
        KNOWN.add("description");
        KNOWN.add("iconItem");
        KNOWN.add("rarity");
        KNOWN.add("iconSize");
        KNOWN.add("nodeType");
        KNOWN.add("tasksType");
        KNOWN.add("parents");
        KNOWN.add("broadcast");
        KNOWN.add("tasks");
        KNOWN.add("rewards");
    }

    /** Ключ квеста во вкладке (questID). Хранится снаружи, дублируется здесь для удобства. */
    public String questID;

    public float displayX;
    public float displayY;
    public String displayName;
    public String description = "";
    public String iconItem = "minecraft:bedrock";
    public Defs.Rarity rarity = Defs.Rarity.UNCOMMON;
    public Defs.IconSize iconSize = Defs.IconSize.DEFAULT;
    public Defs.NodeType nodeType = Defs.NodeType.NORMAL;
    public Defs.TasksType tasksType = Defs.TasksType.DEFAULT;
    public String broadcast;

    public final List<Parent> parents = new ArrayList<>();
    /** taskID -> задача, порядок важен (LinkedHashMap как в моде). */
    public final LinkedHashMap<Integer, Task> tasks = new LinkedHashMap<>();
    /** Строки наград в формате мода: "mod:item:meta=count{nbt}" или "command:...". */
    public final List<String> rewards = new ArrayList<>();
    public final JsonObject extra = new JsonObject();

    public static Quest fromJson(String questID, JsonObject o) {
        Quest q = new Quest();
        q.questID = questID;
        q.displayX = o.f("displayX", 0f);
        q.displayY = o.f("displayY", 0f);
        q.displayName = o.str("displayName", null);
        q.description = o.str("description", "");
        q.iconItem = o.str("iconItem", "minecraft:bedrock");
        q.rarity = Defs.Rarity.of(o.str("rarity", null));
        q.iconSize = Defs.IconSize.of(o.str("iconSize", null));
        q.nodeType = Defs.NodeType.of(o.str("nodeType", null));
        q.tasksType = Defs.TasksType.of(o.str("tasksType", null));
        q.broadcast = o.str("broadcast", null);

        JsonArray pa = o.arr("parents");
        if (pa != null) for (Object v : pa.list()) q.parents.add(Parent.fromJson(v));

        JsonObject ta = o.obj("tasks");
        if (ta != null) {
            // номер задачи — её место в списке, поэтому задачи читаются по
            // порядку файла и тут же получают номера 0, 1, 2…; старые номера
            // нужны только чтобы перевести ссылки «после задач»
            List<Integer> oldIds = new ArrayList<>();
            List<Task> list = new ArrayList<>();
            for (String key : ta.keys()) {
                int id;
                try { id = Integer.parseInt(key.trim()); } catch (NumberFormatException e) { continue; }
                JsonObject to = ta.obj(key);
                if (to == null) continue;
                oldIds.add(id);
                list.add(Task.fromJson(to));
            }
            q.setTasksInOrder(oldIds, list);
        }

        JsonArray ra = o.arr("rewards");
        if (ra != null) for (Object v : ra.list()) if (v != null) q.rewards.add(String.valueOf(v));

        for (String k : o.keys()) if (!KNOWN.contains(k)) q.extra.put(k, o.get(k));
        return q;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.put("displayX", num(displayX));
        o.put("displayY", num(displayY));
        if (displayName != null && !displayName.isEmpty()) o.put("displayName", displayName);
        o.put("description", description == null ? "" : description);
        o.put("iconItem", iconItem == null ? "minecraft:bedrock" : iconItem);
        if (rarity != Defs.Rarity.UNCOMMON) o.put("rarity", rarity.name());
        if (iconSize != Defs.IconSize.DEFAULT) o.put("iconSize", iconSize.name());
        if (nodeType != Defs.NodeType.NORMAL) o.put("nodeType", nodeType.name());
        if (tasksType != Defs.TasksType.DEFAULT) o.put("tasksType", tasksType.name());
        if (broadcast != null && !broadcast.isEmpty()) o.put("broadcast", broadcast);

        JsonArray pa = new JsonArray();
        for (Parent p : parents) pa.add(p.toJson());
        o.put("parents", pa);

        JsonObject ta = new JsonObject();
        for (Map.Entry<Integer, Task> e : tasks.entrySet()) ta.put(String.valueOf(e.getKey()), e.getValue().toJson());
        o.put("tasks", ta);

        JsonArray ra = new JsonArray();
        for (String r : rewards) ra.add(r);
        o.put("rewards", ra);

        for (String k : extra.keys()) o.put(k, extra.get(k));
        return o;
    }

    private static Object num(float f) {
        if (f == Math.rint(f) && Math.abs(f) < 1e9) return (long) f;
        return (double) Math.round(f * 1000f) / 1000d;
    }

    public Quest copy() { return fromJson(questID, toJson()); }

    /** Quest.getDisplayName() — если имя не задано, мод показывает questID. */
    public String displayNameOrID() {
        return (displayName == null || displayName.isEmpty()) ? (questID == null ? "" : questID) : displayName;
    }

    /** Quest.getTasksCount() */
    public int tasksCount() {
        return tasksType == Defs.TasksType.ONE_OF ? 1 : tasks.size();
    }

    /** Номер для новой задачи в конце списка — он же её место. */
    public int nextTaskID() {
        return tasks.size();
    }

    /**
     * Номер задачи всегда равен её месту в списке: 0, 1, 2… Этим держится
     * всё, что меняет список задач, — чтение файла, добавление, удаление,
     * перестановка и вставка: после правки задачи перенумеровываются подряд
     * в текущем порядке, а ссылки «после задач» переводятся на новые номера.
     * Ссылки на задачи, которых нет, выпадают — они указали бы на чужую.
     */
    public void renumberTasks() {
        setTasksInOrder(new ArrayList<>(tasks.keySet()), new ArrayList<>(tasks.values()));
    }

    /** Задачи по порядку со старыми номерами (для ссылок) — номера станут местами. */
    public void setTasksInOrder(List<Integer> oldIds, List<Task> list) {
        Map<Integer, Integer> remap = new LinkedHashMap<>();
        for (int i = 0; i < oldIds.size(); i++) remap.putIfAbsent(oldIds.get(i), i);
        tasks.clear();
        for (int i = 0; i < list.size(); i++) {
            Task t = list.get(i);
            List<Integer> np = new ArrayList<>();
            for (int p : t.parents) {
                Integer r = remap.get(p);
                if (r != null && r != i && !np.contains(r)) np.add(r);
            }
            t.parents.clear();
            t.parents.addAll(np);
            tasks.put(i, t);
        }
    }

    /** Вставить задачу на место pos (в конец, если pos вне списка); следующие сдвинутся. */
    public void insertTask(int pos, Task task) {
        List<Integer> ids = new ArrayList<>(tasks.keySet());
        List<Task> list = new ArrayList<>(tasks.values());
        int at = pos < 0 || pos > list.size() ? list.size() : pos;
        list.add(at, task);
        // у новой задачи старого номера нет: -1 ни с чем не совпадёт
        ids.add(at, -1);
        setTasksInOrder(ids, list);
    }

    /** Убрать задачу с места pos; ссылки на неё выпадут, остальные сдвинутся. */
    public void removeTask(int pos) {
        if (!tasks.containsKey(pos)) return;
        tasks.remove(pos);
        renumberTasks();
    }

    /** Переставить задачу с места from на место to. */
    public void moveTask(int from, int to) {
        List<Integer> ids = new ArrayList<>(tasks.keySet());
        List<Task> list = new ArrayList<>(tasks.values());
        if (from < 0 || to < 0 || from >= list.size() || to >= list.size() || from == to) return;
        ids.add(to, ids.remove(from));
        list.add(to, list.remove(from));
        setTasksInOrder(ids, list);
    }
}
