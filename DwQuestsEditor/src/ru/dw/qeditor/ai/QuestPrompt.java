package ru.dw.qeditor.ai;

import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Сборка запроса для Claude: правила формата вкладки DwQuests + нужный кусок
 * вкладки + пожелание пользователя.
 *
 * <p>Раньше сюда уходила вся открытая вкладка, а обратно требовался её полный
 * JSON. На «Испытаниях» это мегабайт в одну сторону и мегабайт в другую —
 * деньги за токены при правке одного квеста. Теперь отправляется только то,
 * что относится к делу ({@link Scope}), а ответ — патч: изменившиеся квесты и
 * ничего больше.
 */
public final class QuestPrompt {

    private QuestPrompt() { }

    /** Что из вкладки попадает в запрос. */
    public enum Scope {
        SELECTED("Выделенные квесты"),
        OUTLINE("Карта без описаний"),
        ALL("Вся вкладка"),
        NONE("Ничего (новая вкладка)");

        public final String ru;

        Scope(String ru) { this.ru = ru; }
    }

    public static String rules() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ты помогаешь редактировать вкладку квестов мода DwQuests для Minecraft 1.7.10.\n");
        sb.append("Формат вкладки (JSON):\n");
        sb.append("{\n");
        sb.append("  \"tabID\": \"имя вкладки\",            // совпадает с именем фона в jar мода\n");
        sb.append("  \"iconItem\": \"minecraft:book\",\n");
        sb.append("  \"sortIndex\": 0,\n");
        sb.append("  \"quests\": {\n");
        sb.append("    \"ИдКвеста\": {\n");
        sb.append("      \"displayX\": 0, \"displayY\": 0,   // клетки, в игре умножаются на 24\n");
        sb.append("      \"displayName\": \"Название\",\n");
        sb.append("      \"description\": \"<BR>Текст\",     // <BR> — абзац, <RI> — отступ, §-коды цвета\n");
        sb.append("      \"iconItem\": \"mod:item:meta\",\n");
        sb.append("      \"rarity\": \"UNCOMMON\",\n");
        sb.append("      \"iconSize\": \"DEFAULT\",\n");
        sb.append("      \"nodeType\": \"NORMAL\",\n");
        sb.append("      \"tasksType\": \"DEFAULT\",\n");
        sb.append("      \"parents\": [\"ДругойКвест\"],     // либо {\"questID\":..,\"parentType\":..,\"lineType\":..}\n");
        sb.append("      \"tasks\": { \"0\": { \"type\": \"ITEM_DETECT\", \"requiredCount\": 16,\n");
        sb.append("                          \"forgeName\": \"minecraft:log\" } },\n");
        sb.append("      \"rewards\": [\"minecraft:diamond=3\", \"command:give @p minecraft:apple 1\"]\n");
        sb.append("    }\n");
        sb.append("  }\n");
        sb.append("}\n\n");

        sb.append("Значения перечислений:\n");
        sb.append("  rarity:    ").append(names(Defs.Rarity.values())).append('\n');
        sb.append("  iconSize:  ").append(names(Defs.IconSize.values())).append('\n');
        sb.append("  nodeType:  ").append(names(Defs.NodeType.values())).append('\n');
        sb.append("  tasksType: ").append(names(Defs.TasksType.values()))
                .append("   (ONE_OF — достаточно одной задачи, ORDER — по порядку)\n");
        sb.append("  parentType: ").append(names(Defs.ParentType.values()))
                .append("   lineType: ").append(names(Defs.LineType.values())).append('\n');
        sb.append("  type задачи: ").append(taskTypes()).append('\n');
        sb.append("    Звёздочкой отмечены типы, которые сервер реально отслеживает; остальные\n");
        sb.append("    в игре не засчитаются сами — предлагай их только если пользователь просит.\n\n");

        sb.append("Правила:\n");
        sb.append("  • Задачи с предметом требуют forgeName и requiredCount > 0.\n");
        sb.append("  • ENTITY_KILL требует className (класс моба) и requiredCount.\n");
        sb.append("  • TASK_CONFIRM и PLAY_TIME не используют forgeName; у PLAY_TIME requiredCount — часы.\n");
        sb.append("  • Ключи в tasks — строки с числами, порядок важен.\n");
        sb.append("  • parents ссылаются на существующие квесты этой же вкладки; циклы запрещены.\n");
        sb.append("  • Строка предмета: mod:name[:meta][=количество][{NBT}]. Неизвестный мод id\n");
        sb.append("    в игре превращается в бедрок, поэтому id не выдумывай.\n");
        sb.append("  • Награда-команда начинается с \"command:\".\n");
        sb.append("  • Координаты не должны совпадать у двух квестов, иначе узлы наложатся.\n\n");
        return sb.toString();
    }

    /**
     * Полный запрос.
     *
     * @param selection выделенные квесты — они и их соседи попадают в запрос
     *                  при {@link Scope#SELECTED}
     */
    public static String build(QuestTab tab, Scope scope, Collection<String> selection, String request) {
        return build(tab, scope, selection, request, null);
    }

    /**
     * @param shotName имя файла со снимком вкладки в рабочем каталоге либо
     *                 null; со снимком видно расположение узлов и иконки —
     *                 словами это описывать дороже, чем показать
     */
    public static String build(QuestTab tab, Scope scope, Collection<String> selection,
                               String request, String shotName) {
        StringBuilder sb = new StringBuilder(rules());
        sb.append(context(tab, scope, selection));
        if (shotName != null) {
            sb.append("В текущем каталоге лежит «").append(shotName)
                    .append("» — снимок вкладки в том виде, в каком её видят в игре.\n")
                    .append("Открой его инструментом Read, прежде чем отвечать: на нём видно\n")
                    .append("расположение узлов, их иконки, цвета редкости и связи.\n\n");
        }
        sb.append("Задача: ").append(request).append("\n\n");
        sb.append(answerFormat(scope, shotName != null));
        return sb.toString();
    }

    /** Кусок вкладки, который уходит в запрос. */
    public static String context(QuestTab tab, Scope scope, Collection<String> selection) {
        if (scope == Scope.NONE) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("Вкладка: tabID=").append(tab.tabID)
                .append(", iconItem=").append(tab.iconItem)
                .append(", sortIndex=").append(tab.sortIndex)
                .append(", квестов ").append(tab.quests.size()).append(".\n\n");

        if (scope == Scope.ALL) {
            sb.append("Вкладка целиком:\n```json\n").append(tab.toJsonString()).append("\n```\n\n");
            return sb.toString();
        }
        if (scope == Scope.OUTLINE) {
            sb.append("Карта вкладки (id, координаты, название, зависимости):\n");
            for (Quest q : tab.quests.values()) sb.append("  ").append(outline(q)).append('\n');
            sb.append('\n');
            return sb.toString();
        }

        // SELECTED: сами квесты целиком, соседи — для понимания ветки,
        // остальные — одной строкой, чтобы модель не заняла чужую клетку
        Set<String> picked = new LinkedHashSet<>();
        for (String id : selection) if (tab.quests.containsKey(id)) picked.add(id);
        if (picked.isEmpty()) {
            sb.append("Квесты не выделены — работай по карте вкладки.\n\n");
            sb.append("Карта вкладки (id, координаты, название, зависимости):\n");
            for (Quest q : tab.quests.values()) sb.append("  ").append(outline(q)).append('\n');
            sb.append('\n');
            return sb.toString();
        }

        Set<String> neighbours = new LinkedHashSet<>();
        for (String id : picked) {
            Quest q = tab.quests.get(id);
            for (Parent p : q.parents) if (p.questID != null && !picked.contains(p.questID)) neighbours.add(p.questID);
        }
        for (Quest q : tab.quests.values()) {
            for (Parent p : q.parents)
                if (p.questID != null && picked.contains(p.questID) && !picked.contains(q.questID))
                    neighbours.add(q.questID);
        }
        neighbours.removeIf(id -> !tab.quests.containsKey(id));

        sb.append("Выделенные квесты (их и надо править):\n```json\n");
        sb.append(JsonWriter.write(subset(tab, picked), true)).append("\n```\n\n");

        if (!neighbours.isEmpty()) {
            sb.append("Соседние квесты (для понимания ветки, менять их не просили):\n");
            for (String id : neighbours) sb.append("  ").append(outline(tab.quests.get(id))).append('\n');
            sb.append('\n');
        }

        // Остальные нужны лишь затем, чтобы не занять чужую клетку и не
        // придумать существующий id. На вкладке в тысячу квестов их полный
        // список — тридцать килобайт, поэтому берутся только ближние
        List<String> rest = new ArrayList<>();
        int far = 0;
        for (Quest q : tab.quests.values()) {
            if (picked.contains(q.questID) || neighbours.contains(q.questID)) continue;
            if (rest.size() < NEAR_LIMIT && near(tab, picked, q))
                rest.add(q.questID + " (" + fmt(q.displayX) + "," + fmt(q.displayY) + ")");
            else far++;
        }
        if (!rest.isEmpty()) {
            sb.append("Соседи по карте — id и занятые клетки (не занимай их):\n  ");
            sb.append(String.join("; ", rest)).append('\n');
        }
        if (far > 0)
            sb.append("Ещё ").append(far).append(" квестов вкладки лежат дальше — они не мешают.\n");
        sb.append('\n');
        return sb.toString();
    }

    /** Сколько клеток вокруг выделенного считается «рядом». */
    private static final float NEAR_CELLS = 12f;
    /** И сколько таких соседей максимум попадёт в запрос. */
    private static final int NEAR_LIMIT = 80;

    private static boolean near(QuestTab tab, Set<String> picked, Quest other) {
        for (String id : picked) {
            Quest q = tab.quests.get(id);
            if (q == null) continue;
            if (Math.abs(q.displayX - other.displayX) <= NEAR_CELLS
                    && Math.abs(q.displayY - other.displayY) <= NEAR_CELLS) return true;
        }
        return false;
    }

    private static String answerFormat(Scope scope, boolean withShot) {
        String tools = withShot
                ? "Из инструментов используй только Read для снимка; файлов не создавай."
                : "Не используй инструменты и не создавай файлы.";
        if (scope == Scope.NONE) {
            return "Ответь ТОЛЬКО итоговым JSON новой вкладки целиком, без пояснений и без "
                    + "```-обрамления. " + tools;
        }
        return "Ответь ТОЛЬКО JSON-патчем — тем, что меняется, и ничем больше:\n"
                + "{\"quests\": {\"НовыйИлиИзменённый\": { …квест целиком… }, \"Лишний\": null}}\n"
                + "Правила патча: объекты сливаются рекурсивно, null удаляет ключ, массивы\n"
                + "(parents, rewards) заменяются целиком, изменённый квест приводи полностью.\n"
                + "Не повторяй неизменившиеся квесты — это только лишние токены.\n"
                + "Без пояснений и без ```-обрамления. " + tools;
    }

    /** Одна строка про квест: id, клетка, название, зависимости. */
    private static String outline(Quest q) {
        StringBuilder sb = new StringBuilder();
        sb.append(q.questID).append(" (").append(fmt(q.displayX)).append(',')
                .append(fmt(q.displayY)).append(')');
        if (q.displayName != null && !q.displayName.isEmpty())
            sb.append(" «").append(strip(q.displayName)).append('»');
        if (!q.parents.isEmpty()) {
            sb.append(" ← ");
            List<String> ids = new ArrayList<>();
            for (Parent p : q.parents) if (p.questID != null) ids.add(p.questID);
            sb.append(String.join(", ", ids));
        }
        sb.append(" [задач ").append(q.tasks.size()).append(", наград ").append(q.rewards.size()).append(']');
        return sb.toString();
    }

    private static JsonObject subset(QuestTab tab, Set<String> ids) {
        JsonObject quests = new JsonObject();
        for (String id : ids) {
            Quest q = tab.quests.get(id);
            if (q != null) quests.put(id, q.toJson());
        }
        JsonObject out = new JsonObject();
        out.put("quests", quests);
        return out;
    }

    /** Запрос без текущей вкладки — создать новую с нуля. */
    public static String buildNew(String request) {
        return build(QuestTab.empty("NewTab"), Scope.NONE, List.of(), request);
    }

    private static String strip(String s) {
        return s.replaceAll("§.", "");
    }

    private static String fmt(float f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }

    private static String names(Enum<?>[] values) {
        StringBuilder sb = new StringBuilder();
        for (Enum<?> v : values) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(v.name());
        }
        return sb.toString();
    }

    private static String taskTypes() {
        StringBuilder sb = new StringBuilder();
        for (Defs.TaskType t : Defs.TaskType.values()) {
            if (t == Defs.TaskType.UNKNOWN) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.name());
            if (t.serverSupported()) sb.append('*');
        }
        return sb.toString();
    }
}
