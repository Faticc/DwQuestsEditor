package ru.dw.qbridge.quests;

import com.google.gson.stream.JsonWriter;
import net.minecraft.item.ItemStack;
import ru.sky.dw.quests.common.quest.Parent;
import ru.sky.dw.quests.common.quest.Quest;
import ru.sky.dw.quests.common.quest.QuestTab;
import ru.sky.dw.quests.common.quest.task.IQuestTask;
import ru.sky.dw.quests.common.quest.task.ItemTask;
import ru.sky.dw.quests.common.quest.task.KillTask;
import ru.dw.qbridge.export.Names;

import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Вкладка из памяти клиента → JSON того же вида, что читает редактор.
 *
 * <p><b>Что теряется.</b> Клиенту присылают не файл вкладки, а разобранные
 * объекты ({@code QuestTab.readFromByte}), и часть полей в пакет не попадает
 * вовсе:
 * <ul>
 *   <li>{@code broadcast} — не передаётся, в выгрузке его не будет;</li>
 *   <li>строки наград — на клиенте от них остались {@code ItemStack} и список
 *       команд, поэтому строка собирается обратно из стека (NBT сохраняется),
 *       но исходный порядок «предмет-команда-предмет» не восстановить:
 *       сначала идут предметы, потом команды;</li>
 *   <li>{@code iconItem} — тоже превращён в стек, и незнакомый id стал бедроком:
 *       в выгрузке будет {@code minecraft:bedrock}, а не исходная опечатка.</li>
 * </ul>
 * Точная копия файла берётся другим путём — {@link TabDumpJob} через
 * {@code /dwq tab copy}, но он требует прав на сервере.
 */
public final class TabJson {

    private TabJson() {}

    public static String write(QuestTab tab) {
        StringWriter sw = new StringWriter();
        try {
            JsonWriter w = new JsonWriter(sw);
            w.setIndent("  ");
            w.beginObject();
            w.name("tabID").value(tab.getTabID());
            w.name("iconItem").value(stackName(tab.getIconStack()));
            w.name("sortIndex").value(tab.getSortIndex());
            w.name("quests").beginObject();

            List<Quest> quests = new ArrayList<Quest>(tab.getQuests());
            Collections.sort(quests, new java.util.Comparator<Quest>() {
                public int compare(Quest a, Quest b) {
                    return a.getQuestID().compareTo(b.getQuestID());
                }
            });
            for (Quest quest : quests) {
                w.name(quest.getQuestID());
                quest(w, quest);
            }

            w.endObject();
            w.endObject();
            w.flush();
        } catch (Exception e) {
            throw new RuntimeException("вкладка " + tab.getTabID() + " не записалась: " + e, e);
        }
        return sw.toString();
    }

    private static void quest(JsonWriter w, Quest q) throws Exception {
        w.beginObject();
        w.name("displayX").value(trim(q.getX()));
        w.name("displayY").value(trim(q.getY()));
        w.name("displayName").value(q.getDisplayName());
        String description = q.getDescription();
        if (description != null && !description.isEmpty()) w.name("description").value(description);
        w.name("iconItem").value(stackName(q.getIconStack()));
        w.name("rarity").value(q.getRarity().name());
        w.name("iconSize").value(q.getIconSize().name());
        w.name("nodeType").value(q.getNodeType().name());
        w.name("tasksType").value(q.getTasksType().name());

        List<Parent> parents = q.getParents();
        if (parents != null && !parents.isEmpty()) {
            w.name("parents").beginArray();
            for (Parent p : parents) parent(w, p, q.getTabID());
            w.endArray();
        }

        if (!q.getTasks().isEmpty()) {
            w.name("tasks").beginObject();
            for (IQuestTask task : q.getTasks()) {
                w.name(String.valueOf(task.getTaskID()));
                task(w, task);
            }
            w.endObject();
        }

        List<String> rewards = rewards(q);
        if (!rewards.isEmpty()) {
            w.name("rewards").beginArray();
            for (String r : rewards) w.value(r);
            w.endArray();
        }
        w.endObject();
    }

    /** Родитель из той же вкладки и без особенностей пишется просто строкой. */
    private static void parent(JsonWriter w, Parent p, String tabID) throws Exception {
        boolean plain = p.getParentType() == Parent.ParentType.DEFAULT
                && p.getLineType() == Parent.LineType.DEFAULT
                && (p.getTabID() == null || p.getTabID().equals(tabID));
        if (plain) {
            w.value(p.getQuestID());
            return;
        }
        w.beginObject();
        if (p.getTabID() != null && !p.getTabID().equals(tabID)) w.name("tabID").value(p.getTabID());
        w.name("questID").value(p.getQuestID());
        if (p.getParentType() != Parent.ParentType.DEFAULT) w.name("parentType").value(p.getParentType().name());
        if (p.getLineType() != Parent.LineType.DEFAULT) w.name("lineType").value(p.getLineType().name());
        w.endObject();
    }

    private static void task(JsonWriter w, IQuestTask task) throws Exception {
        w.beginObject();
        w.name("type").value(task.getType().name());
        if (task.getType() != IQuestTask.Type.TASK_CONFIRM) {
            w.name("requiredCount").value(task.getRequiredCount());
        }
        if (task instanceof ItemTask) {
            ItemTask item = (ItemTask) task;
            ItemStack stack = item.getItemInfo() == null ? null : item.getItemInfo().getItemStack();
            w.name("forgeName").value(stackName(stack));
            String custom = item.getCustomDescription();
            if (custom != null && !custom.isEmpty()) w.name("customDescription").value(custom);
        } else if (task instanceof KillTask) {
            KillTask kill = (KillTask) task;
            if (notEmpty(kill.getClassName())) w.name("className").value(kill.getClassName());
            if (notEmpty(kill.getCommandName())) w.name("commandName").value(kill.getCommandName());
            if (notEmpty(kill.getDisplayName())) w.name("displayName").value(kill.getDisplayName());
        }
        List<Integer> parents = task.getParents();
        if (parents != null && !parents.isEmpty()) {
            w.name("parents").beginArray();
            for (Integer p : parents) w.value(p.intValue());
            w.endArray();
        }
        w.endObject();
    }

    /**
     * Награды собираются обратно из того, что осталось на клиенте: стеки (с NBT
     * и количеством) и команды. Порядок между ними исходному может не совпадать.
     */
    private static List<String> rewards(Quest q) {
        List<String> out = new ArrayList<String>();
        for (ItemStack stack : q.getRewardStacks()) {
            String name = Names.full(stack);
            if (name == null) continue;
            out.add(stack.stackSize > 1 ? withCount(name, stack.stackSize) : name);
        }
        for (String command : q.getCommandRewards()) {
            out.add("command:" + command);
        }
        return out;
    }

    /** Количество вставляется перед NBT: {@code mod:name=16{...}}. */
    private static String withCount(String name, int count) {
        int brace = name.indexOf('{');
        if (brace < 0) return name + "=" + count;
        return name.substring(0, brace) + "=" + count + name.substring(brace);
    }

    public static String stackName(ItemStack stack) {
        if (stack == null) return "";
        String name = Names.full(stack);
        return name == null ? "" : name;
    }

    private static boolean notEmpty(String s) {
        return s != null && !s.isEmpty();
    }

    /** Координаты в клетках почти всегда целые — не пишем "3.0". */
    private static Number trim(float value) {
        if (value == Math.rint(value) && Math.abs(value) < 1e7) return Integer.valueOf((int) value);
        return Float.valueOf(value);
    }
}
