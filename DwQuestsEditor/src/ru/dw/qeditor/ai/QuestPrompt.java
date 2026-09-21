package ru.dw.qeditor.ai;

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
 * Что говорится помощнику.
 *
 * <p>Два куска. Системный промпт — формат вкладки и как работать с файлами:
 * он одинаков у всех запросов, поэтому со второго запроса читается из кэша
 * почти даром. Сообщение — короткое: задача, что выделено и в каких это
 * файлах. Сами квесты в запрос не вставляются — модель читает нужные файлы
 * сама (см. {@link TabWorkspace}).
 */
public final class QuestPrompt {

    private QuestPrompt() { }

    public static String system() {
        StringBuilder sb = new StringBuilder();
        sb.append("Ты помощник в редакторе вкладок квестов мода DwQuests (Minecraft 1.7.10).\n");
        sb.append("Пользователь пишет по-русски; отвечай по-русски и коротко.\n\n");

        sb.append("# Рабочий каталог\n");
        sb.append("В текущем каталоге — открытая в редакторе вкладка, разложенная по файлам:\n");
        sb.append("  tab.json          поля самой вкладки (tabID, iconItem, sortIndex…), без квестов\n");
        sb.append("  quests/*.json     по квесту на файл; id квеста — в поле \"questID\"\n");
        sb.append("  map.txt           по строке на квест: id (x,y) «название» <- родители [задач, наград] -> файл\n");
        sb.append("  tab.png           снимок вкладки, как в игре (есть, только если о нём сказано)\n");
        sb.append("Полный путь каталога — в сообщении пользователя. Read, Edit и Write принимают\n");
        sb.append("только абсолютные пути; Grep и Glob вызывай всегда с path (map.txt или quests).\n");
        sb.append("Файлы собраны из текущего состояния редактора перед каждым сообщением.\n\n");

        sb.append("# Как работать\n");
        sb.append("- Читай только то, что нужно для задачи. Квест по id или названию ищи Grep-ом по\n");
        sb.append("  map.txt или quests/, а не читай всё подряд — вкладка может быть на тысячу квестов.\n");
        sb.append("- Меняй файлы Edit-ом, точечной заменой. Write — только для новых файлов.\n");
        sb.append("- Новый квест: Write в quests/<id>.json, внутри обязательно \"questID\".\n");
        sb.append("- Удалить квест: Write в его файл одного слова null (удалять файлы нечем).\n");
        sb.append("- Переименовать квест: поменяй \"questID\" в его файле и ссылки в parents у\n");
        sb.append("  зависимых квестов (найди их Grep-ом по старому id).\n");
        sb.append("- map.txt и tab.png редактор обратно не читает — их не правь.\n");
        sb.append("- Изменения не применяются сразу: редактор соберёт файлы, покажет пользователю\n");
        sb.append("  разницу, и тот сам нажмёт «Применить». Поэтому не спрашивай подтверждения —\n");
        sb.append("  делай. Если задача неясна — сделай разумное и скажи, что предположил.\n");
        sb.append("- В конце ответь одной-тремя фразами: что сделал. JSON в ответ не пиши.\n\n");

        sb.append("# Файл квеста\n");
        sb.append("{\n");
        sb.append("  \"questID\": \"Начало\",\n");
        sb.append("  \"displayX\": 0, \"displayY\": 0,          // клетки; в игре ×24\n");
        sb.append("  \"displayName\": \"Первые шаги\",\n");
        sb.append("  \"description\": \"<BR>Текст\",             // <BR> — абзац, <RI> — отступ, §-коды цвета\n");
        sb.append("  \"iconItem\": \"minecraft:book\",           // mod:name[:meta]\n");
        sb.append("  \"rarity\": \"RARE\",                        // по умолчанию UNCOMMON — тогда поля нет\n");
        sb.append("  \"parents\": [\"ДругойКвест\"],             // либо {\"questID\":…,\"parentType\":…,\"lineType\":…}\n");
        sb.append("  \"tasks\": {\"0\": {\"type\": \"ITEM_DETECT\", \"requiredCount\": 16, \"forgeName\": \"minecraft:log\"}},\n");
        sb.append("  \"rewards\": [\"minecraft:diamond=3\", \"command:give @p minecraft:apple 1\"]\n");
        sb.append("}\n");
        sb.append("Комментарии // здесь только для пояснения — в файлах их быть не должно, это чистый JSON.\n");
        sb.append("Поля iconSize, nodeType, tasksType, broadcast пишутся, только когда не по умолчанию.\n");
        sb.append("Незнакомые поля в файле сохраняй как есть.\n\n");

        sb.append("# Значения\n");
        sb.append("  rarity:     ").append(names(Defs.Rarity.values())).append('\n');
        sb.append("  iconSize:   ").append(names(Defs.IconSize.values())).append('\n');
        sb.append("  nodeType:   ").append(names(Defs.NodeType.values())).append('\n');
        sb.append("  tasksType:  ").append(names(Defs.TasksType.values()))
                .append("  (ONE_OF — хватит одной задачи, ORDER — по порядку)\n");
        sb.append("  parentType: ").append(names(Defs.ParentType.values()))
                .append("; lineType: ").append(names(Defs.LineType.values())).append('\n');
        sb.append("  type задачи: ").append(taskTypes()).append('\n');
        sb.append("    * — сервер засчитывает сам; остальные предлагай, только если просят.\n\n");

        sb.append("# Правила\n");
        sb.append("- Задачи с предметом: forgeName и requiredCount > 0. ENTITY_KILL: className и requiredCount.\n");
        sb.append("  TASK_CONFIRM и PLAY_TIME без forgeName; у PLAY_TIME requiredCount — часы.\n");
        sb.append("- Ключи tasks — строки с числами, порядок важен.\n");
        sb.append("- parents ссылаются на квесты этой же вкладки; циклы запрещены.\n");
        sb.append("- Предмет: mod:name[:meta][=количество][{NBT}]. Незнакомый id в игре станет бедроком —\n");
        sb.append("  не выдумывай id, бери те, что уже встречаются во вкладке, или ванильные.\n");
        sb.append("- Награда-команда начинается с \"command:\".\n");
        sb.append("- Два квеста не должны стоять в одной клетке. Занятые клетки — в map.txt.\n");
        return sb.toString();
    }

    /**
     * Сообщение пользователя: вкладка одной строкой, выделенное с файлами,
     * соседи по ветке — только id. Этого хватает, чтобы модель сразу открыла
     * нужные файлы, не разыскивая их.
     */
    public static String message(QuestTab tab, TabWorkspace ws, Collection<String> selection,
                                 String request, boolean withShot) {
        StringBuilder sb = new StringBuilder();
        if (ws != null) sb.append("Каталог: ").append(abs(ws.dir())).append('\n');
        sb.append("Вкладка «").append(tab.tabID).append("», квестов ").append(tab.quests.size()).append(".\n");

        Set<String> picked = new LinkedHashSet<>();
        for (String id : selection) if (tab.quests.containsKey(id)) picked.add(id);
        if (picked.isEmpty()) {
            sb.append("В редакторе ничего не выделено.\n");
        } else {
            sb.append("Выделено в редакторе (о них, скорее всего, и речь):\n");
            int n = 0;
            for (String id : picked) {
                if (++n > 40) {
                    sb.append("  …и ещё ").append(picked.size() - 40).append('\n');
                    break;
                }
                sb.append("  ").append(id).append(" — ").append(path(ws, id)).append('\n');
            }
            List<String> near = neighbours(tab, picked);
            if (!near.isEmpty()) {
                sb.append("Связаны с ними: ").append(String.join(", ", near.subList(0, Math.min(30, near.size()))));
                if (near.size() > 30) sb.append(" …");
                sb.append('\n');
            }
        }
        if (withShot) sb.append("Снимок вкладки — tab.png; посмотри его, если важно расположение.\n");
        sb.append("\nЗадача: ").append(request.trim()).append('\n');
        return sb.toString();
    }

    private static String path(TabWorkspace ws, String id) {
        String p = ws == null ? null : ws.pathOf(id);
        if (p == null) p = "quests/" + id + ".json";
        return ws == null ? p : abs(ws.dir()) + "/" + p;
    }

    /** Путь с прямыми косыми: так модель не путается с экранированием «\\». */
    private static String abs(java.io.File f) {
        return f.getAbsolutePath().replace('\\', '/');
    }

    private static List<String> neighbours(QuestTab tab, Set<String> picked) {
        Set<String> out = new LinkedHashSet<>();
        for (String id : picked) {
            for (Parent p : tab.quests.get(id).parents)
                if (p.questID != null && !picked.contains(p.questID)) out.add(p.questID);
        }
        for (Quest q : tab.quests.values()) {
            if (picked.contains(q.questID)) continue;
            for (Parent p : q.parents)
                if (p.questID != null && picked.contains(p.questID)) out.add(q.questID);
        }
        out.removeIf(id -> !tab.quests.containsKey(id));
        return new ArrayList<>(out);
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
