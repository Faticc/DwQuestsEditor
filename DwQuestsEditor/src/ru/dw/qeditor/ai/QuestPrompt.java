package ru.dw.qeditor.ai;

import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.QuestTab;

/**
 * Сборка запроса для Claude: правила формата вкладки DwQuests + текущая вкладка
 * + пожелание пользователя. Модель обязана вернуть только JSON вкладки.
 */
public final class QuestPrompt {

    private QuestPrompt() { }

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
        sb.append("      \"displayX\": 0, \"displayY\": 0,   // целые клетки, в игре умножаются на 24\n");
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
        sb.append("  • Строка предмета: mod:name[:meta][=количество][{NBT}].\n");
        sb.append("  • Награда-команда начинается с \"command:\".\n");
        sb.append("  • Координаты не должны совпадать у двух квестов, иначе узлы наложатся.\n");
        sb.append("  • Сохраняй поля, которых не просили менять, и не выдумывай id предметов.\n\n");
        return sb.toString();
    }

    /** Полный запрос: правила + текущая вкладка + задача. */
    public static String build(QuestTab tab, String request) {
        StringBuilder sb = new StringBuilder(rules());
        sb.append("Текущая вкладка:\n```json\n");
        sb.append(tab.toJsonString());
        sb.append("\n```\n\n");
        sb.append("Задача: ").append(request).append("\n\n");
        sb.append("Ответь ТОЛЬКО итоговым JSON всей вкладки целиком, без пояснений и без ```-обрамления. ");
        sb.append("Не используй инструменты и не создавай файлы.");
        return sb.toString();
    }

    /** Запрос без текущей вкладки — создать новую с нуля. */
    public static String buildNew(String request) {
        StringBuilder sb = new StringBuilder(rules());
        sb.append("Задача: создай новую вкладку. ").append(request).append("\n\n");
        sb.append("Ответь ТОЛЬКО итоговым JSON всей вкладки целиком, без пояснений и без ```-обрамления. ");
        sb.append("Не используй инструменты и не создавай файлы.");
        return sb.toString();
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
