package ru.dw.qeditor.model;

import ru.dw.qeditor.json.JsonArray;
import ru.dw.qeditor.json.JsonObject;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Одна задача квеста. Покрывает все четыре класса мода (ItemTask, KillTask,
 * PlayTimeTask, ConfirmTask) — набор полей зависит от type.
 */
public class Task {

    private static final Set<String> KNOWN = new LinkedHashSet<>();
    static {
        KNOWN.add("type");
        KNOWN.add("requiredCount");
        KNOWN.add("forgeName");
        KNOWN.add("customDescription");
        KNOWN.add("className");
        KNOWN.add("commandName");
        KNOWN.add("displayName");
        KNOWN.add("parents");
    }

    public Defs.TaskType type = Defs.TaskType.TASK_CONFIRM;
    public int requiredCount;
    public String forgeName;
    public String customDescription;
    /** KillTask */
    public String className;
    public String commandName;
    public String displayName;
    /** Задачи-предшественники внутри квеста (по taskID). */
    public final List<Integer> parents = new ArrayList<>();
    /** Неизвестные редактору поля — сохраняются при выгрузке без потерь. */
    public final JsonObject extra = new JsonObject();

    public static Task fromJson(JsonObject o) {
        Task t = new Task();
        t.type = Defs.TaskType.of(o.str("type", "TASK_CONFIRM"));
        t.requiredCount = o.i("requiredCount", 0);
        t.forgeName = o.str("forgeName", null);
        t.customDescription = o.str("customDescription", null);
        t.className = o.str("className", null);
        t.commandName = o.str("commandName", null);
        t.displayName = o.str("displayName", null);
        JsonArray pa = o.arr("parents");
        if (pa != null) {
            for (Object v : pa.list()) {
                if (v instanceof Number) t.parents.add(((Number) v).intValue());
                else if (v instanceof String) {
                    try { t.parents.add(Integer.parseInt(((String) v).trim())); } catch (NumberFormatException ignored) { }
                }
            }
        }
        for (String k : o.keys()) if (!KNOWN.contains(k)) t.extra.put(k, o.get(k));
        return t;
    }

    public JsonObject toJson() {
        JsonObject o = new JsonObject();
        o.put("type", type.name());
        if (type != Defs.TaskType.TASK_CONFIRM) o.put("requiredCount", (long) requiredCount);
        if (type.kind == Defs.TaskType.Kind.ITEM) {
            o.put("forgeName", forgeName == null ? "minecraft:dirt" : forgeName);
            if (customDescription != null && !customDescription.isEmpty()) o.put("customDescription", customDescription);
        } else if (type.kind == Defs.TaskType.Kind.KILL) {
            o.put("className", className == null ? "" : className);
            if (commandName != null && !commandName.isEmpty()) o.put("commandName", commandName);
            if (displayName != null && !displayName.isEmpty()) o.put("displayName", displayName);
        } else if (forgeName != null && !forgeName.isEmpty()) {
            o.put("forgeName", forgeName);
        }
        if (!parents.isEmpty()) {
            JsonArray pa = new JsonArray();
            for (int p : parents) pa.add((long) p);
            o.put("parents", pa);
        }
        for (String k : extra.keys()) o.put(k, extra.get(k));
        return o;
    }

    public Task copy() { return fromJson(toJson()); }

    /**
     * Текст задачи ровно так, как его строит мод
     * (ItemTask/KillTask/PlayTimeTask/ConfirmTask.getDescription()).
     *
     * @param itemDisplayName человекочитаемое имя предмета без цветовых кодов
     */
    public String description(String itemDisplayName) {
        switch (type.kind) {
            case ITEM:
                if (customDescription != null && !customDescription.isEmpty()) return customDescription;
                return type.prefix + itemDisplayName;
            case KILL:
                return "Ликвидация: " + killDisplayName();
            case TIME:
                return "Необходимо наиграть: " + requiredCount
                        + Defs.dependName(requiredCount, " час", " часа", " часов");
            case CONFIRM:
                return "ознакомление с информацией";
            default:
                if (customDescription != null && !customDescription.isEmpty()) return customDescription;
                return type.prefix + itemDisplayName;
        }
    }

    /** KillTask.getDisplayName() */
    public String killDisplayName() {
        if (displayName != null && !displayName.isEmpty()) return displayName;
        if (commandName != null && !commandName.isEmpty()) return commandName;
        return className == null ? "" : className;
    }
}
