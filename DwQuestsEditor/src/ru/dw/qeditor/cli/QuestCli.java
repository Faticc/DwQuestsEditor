package ru.dw.qeditor.cli;

import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.model.Task;

import java.io.File;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Работа с вкладками из командной строки — без GUI.
 *
 * <p>Нужна, чтобы вкладку можно было создавать и править скриптом или
 * ассистентом: все команды читают и пишут тот же JSON, что и редактор,
 * сохраняя неизвестные поля.
 *
 * <pre>
 * java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli help
 * </pre>
 */
public final class QuestCli {

    private static PrintStream out;

    public static void main(String[] args) {
        out = new PrintStream(System.out, true, StandardCharsets.UTF_8);
        if (args.length == 0 || args[0].equals("help") || args[0].equals("--help") || args[0].equals("-h")) {
            usage();
            return;
        }
        try {
            System.exit(run(args));
        } catch (UsageException e) {
            out.println("Ошибка: " + e.getMessage());
            System.exit(2);
        } catch (Exception e) {
            out.println("Ошибка: " + e);
            System.exit(1);
        }
    }

    private static int run(String[] argv) throws Exception {
        String cmd = argv[0];
        List<String> rest = new ArrayList<>(Arrays.asList(argv).subList(1, argv.length));
        Args a = new Args(rest);

        switch (cmd) {
            case "info":       return info(a);
            case "list":       return list(a);
            case "show":       return show(a);
            case "validate":   return validate(a);
            case "audit":      return audit(a);
            case "format":     return format(a);
            case "export":     return export(a);
            case "new":        return newTab(a);
            case "set-tab":    return setTab(a);
            case "add":        return addQuest(a);
            case "edit":       return editQuest(a);
            case "remove":     return removeQuest(a);
            case "move":       return moveQuest(a);
            case "parent-add": return parentAdd(a);
            case "parent-remove": return parentRemove(a);
            case "task-add":   return taskAdd(a);
            case "task-set":   return taskSet(a);
            case "task-remove": return taskRemove(a);
            case "reward-add": return rewardAdd(a);
            case "reward-remove": return rewardRemove(a);
            case "patch":      return patch(a);
            case "find-item":  return findItem(a);
            case "versions":   return versions(a);
            case "backup":     return backup(a);
            case "restore":    return restore(a);
            case "tabs":       return tabs(a);
            default:
                throw new UsageException("неизвестная команда «" + cmd + "», см. help");
        }
    }

    // ------------------------------------------------------------------ чтение

    private static int info(Args a) throws Exception {
        QuestTab tab = a.tab();
        out.println("tabID:     " + tab.tabID);
        out.println("iconItem:  " + tab.iconItem);
        out.println("sortIndex: " + tab.sortIndex);
        out.println("квестов:   " + tab.quests.size());
        out.println("границы:   X " + fmt(tab.minX()) + ".." + fmt(tab.maxX())
                + "   Y " + fmt(tab.minY()) + ".." + fmt(tab.maxY()));
        int tasks = 0, rewards = 0;
        for (Quest q : tab.quests.values()) { tasks += q.tasks.size(); rewards += q.rewards.size(); }
        out.println("задач:     " + tasks);
        out.println("наград:    " + rewards);
        return 0;
    }

    private static int list(Args a) throws Exception {
        QuestTab tab = a.tab();
        out.printf("%-24s %-28s %6s %6s %-10s %5s %5s%n",
                "questID", "displayName", "X", "Y", "rarity", "задач", "нагр");
        for (Quest q : tab.quests.values()) {
            out.printf("%-24s %-28s %6s %6s %-10s %5d %5d%n",
                    cut(q.questID, 24), cut(q.displayName == null ? "" : q.displayName, 28),
                    fmt(q.displayX), fmt(q.displayY), q.rarity.name(), q.tasks.size(), q.rewards.size());
        }
        return 0;
    }

    private static int show(Args a) throws Exception {
        QuestTab tab = a.tab();
        String id = a.positional(1, "questID");
        Quest q = tab.quests.get(id);
        if (q == null) throw new UsageException("нет квеста «" + id + "»");
        out.println(JsonWriter.write(q.toJson(), true));
        return 0;
    }

    private static int validate(Args a) throws Exception {
        QuestTab tab = a.tab();
        List<TabIO.Problem> problems = TabIO.validate(tab);
        int errors = 0;
        for (TabIO.Problem p : problems) {
            out.println(p);
            if (p.severity == TabIO.Severity.ERROR) errors++;
        }
        out.println("Всего замечаний: " + problems.size() + ", из них ошибок: " + errors);
        return errors > 0 ? 1 : 0;
    }

    /** Сверка id предметов с базой: что в игре превратится в бедрок. */
    private static int audit(Args a) throws Exception {
        QuestTab tab = a.tab();
        ru.dw.qeditor.io.IdAudit.Report r = ru.dw.qeditor.io.IdAudit.check(tab);
        out.print(ru.dw.qeditor.io.IdAudit.text(r));
        return r.unknown.isEmpty() ? 0 : 1;
    }

    private static int format(Args a) throws Exception {
        QuestTab tab = a.tab();
        File target = a.has("out") ? new File(a.str("out", null)) : a.file();
        TabIO.save(target, tab);
        out.println("Записано: " + target.getAbsolutePath());
        return 0;
    }

    private static int export(Args a) throws Exception {
        out.println(TabIO.toPasteString(a.tab()));
        return 0;
    }

    private static int findItem(Args a) throws Exception {
        String query = a.positional(0, "строка поиска");
        String mod = a.str("mod", null);
        int limit = a.integer("limit", 25);
        List<ItemRegistry.Entry> found = ItemRegistry.get().search(query, mod, limit);
        for (ItemRegistry.Entry e : found) out.println(e.key + "   —   " + e.name);
        out.println("Найдено: " + found.size());
        return 0;
    }

    /** Список сохранённых версий вкладки. */
    private static int versions(Args a) throws Exception {
        File f = a.file();
        java.util.List<ru.dw.qeditor.io.Backups.Version> list = ru.dw.qeditor.io.Backups.list(f);
        for (int i = 0; i < list.size(); i++) {
            ru.dw.qeditor.io.Backups.Version v = list.get(i);
            out.printf("%3d  %s  %-28s %s%n", i, v.humanTime(), v.reason.ru, v.file.getName());
        }
        out.println(ru.dw.qeditor.io.Backups.summary(f));
        return 0;
    }

    /** Сохранить копию текущего состояния файла. */
    private static int backup(Args a) throws Exception {
        File f = a.file();
        QuestTab tab = a.tab();
        File made = ru.dw.qeditor.io.Backups.save(f, tab.toJsonString(),
                ru.dw.qeditor.io.Backups.Reason.MANUAL, a.integer("keep", 60));
        if (made == null) {
            out.println("Не удалось сохранить копию");
            return 1;
        }
        out.println("Копия сохранена: " + made.getAbsolutePath());
        return 0;
    }

    /** Восстановить вкладку из версии по номеру из списка versions. */
    private static int restore(Args a) throws Exception {
        File f = a.file();
        int index = a.integer("version", -1);
        java.util.List<ru.dw.qeditor.io.Backups.Version> list = ru.dw.qeditor.io.Backups.list(f);
        if (index < 0 || index >= list.size())
            throw new UsageException("нужен --version N из списка versions (всего: " + list.size() + ")");

        ru.dw.qeditor.io.Backups.Version v = list.get(index);
        QuestTab restored = ru.dw.qeditor.io.Backups.read(v);
        if (f.isFile()) {
            ru.dw.qeditor.io.Backups.save(f, TabIO.load(f).toJsonString(),
                    ru.dw.qeditor.io.Backups.Reason.BEFORE_RESTORE, a.integer("keep", 60));
        }
        return write(a, restored, "Восстановлена версия от " + v.humanTime());
    }

    /** Все вкладки в папке. */
    private static int tabs(Args a) {
        File dir = a.file();
        if (!dir.isDirectory()) throw new UsageException("не папка: " + dir.getAbsolutePath());
        ru.dw.qeditor.io.Workspace ws = new ru.dw.qeditor.io.Workspace();
        ws.open(dir);
        out.printf("%-28s %-28s %7s %6s%n", "файл", "tabID", "квестов", "sort");
        for (ru.dw.qeditor.io.Workspace.Entry e : ws.entries()) {
            out.printf("%-28s %-28s %7s %6d%n", cut(e.file.getName(), 28),
                    e.error != null ? "(не читается)" : cut(e.title(), 28),
                    e.error != null ? "-" : String.valueOf(e.questCount), e.sortIndex);
        }
        out.println("Вкладок: " + ws.entries().size() + ", квестов всего: " + ws.totalQuests());
        return 0;
    }

    // ------------------------------------------------------------------ запись

    private static int newTab(Args a) throws Exception {
        File f = a.file();
        String id = a.str("tab", null);
        if (id == null) throw new UsageException("нужен --tab <tabID>");
        QuestTab tab = QuestTab.empty(id);
        tab.iconItem = a.str("icon", "minecraft:book");
        tab.sortIndex = a.integer("sort", 0);
        TabIO.save(f, tab);
        out.println("Создана вкладка «" + id + "»: " + f.getAbsolutePath());
        return 0;
    }

    private static int setTab(Args a) throws Exception {
        QuestTab tab = a.tab();
        if (a.has("id")) tab.tabID = a.str("id", tab.tabID);
        if (a.has("icon")) tab.iconItem = a.str("icon", tab.iconItem);
        if (a.has("sort")) tab.sortIndex = a.integer("sort", tab.sortIndex);
        return write(a, tab, "Вкладка обновлена");
    }

    private static int addQuest(Args a) throws Exception {
        QuestTab tab = a.tab();
        String id = a.positional(1, "questID");
        if (tab.quests.containsKey(id)) throw new UsageException("квест «" + id + "» уже есть");
        Quest q = new Quest();
        q.questID = id;
        q.displayName = id;
        applyQuestArgs(q, a, tab);
        tab.quests.put(id, q);
        return write(a, tab, "Добавлен квест «" + id + "»");
    }

    private static int editQuest(Args a) throws Exception {
        QuestTab tab = a.tab();
        String id = a.positional(1, "questID");
        Quest q = tab.quests.get(id);
        if (q == null) throw new UsageException("нет квеста «" + id + "»");
        applyQuestArgs(q, a, tab);
        if (a.has("rename")) {
            String newID = a.str("rename", null);
            if (!tab.renameQuest(id, newID))
                throw new UsageException("не удалось переименовать в «" + newID + "»");
            id = newID;
        }
        return write(a, tab, "Изменён квест «" + id + "»");
    }

    private static void applyQuestArgs(Quest q, Args a, QuestTab tab) {
        if (a.has("name")) q.displayName = a.str("name", q.displayName);
        if (a.has("x")) q.displayX = a.number("x", q.displayX);
        if (a.has("y")) q.displayY = a.number("y", q.displayY);
        if (a.has("icon")) q.iconItem = a.str("icon", q.iconItem);
        if (a.has("rarity")) q.rarity = Defs.Rarity.of(a.str("rarity", q.rarity.name()));
        if (a.has("size")) q.iconSize = Defs.IconSize.of(a.str("size", q.iconSize.name()));
        if (a.has("node")) q.nodeType = Defs.NodeType.of(a.str("node", q.nodeType.name()));
        if (a.has("tasks-type")) q.tasksType = Defs.TasksType.of(a.str("tasks-type", q.tasksType.name()));
        if (a.has("desc")) q.description = a.str("desc", q.description);
        if (a.has("broadcast")) q.broadcast = a.str("broadcast", q.broadcast);
        for (String p : a.all("parent")) {
            boolean exists = false;
            for (Parent existing : q.parents) if (p.equals(existing.questID)) exists = true;
            if (!exists) q.parents.add(new Parent(p));
        }
        for (String r : a.all("reward")) q.rewards.add(r);
    }

    private static int removeQuest(Args a) throws Exception {
        QuestTab tab = a.tab();
        String id = a.positional(1, "questID");
        if (!tab.quests.containsKey(id)) throw new UsageException("нет квеста «" + id + "»");
        tab.removeQuest(id);
        return write(a, tab, "Удалён квест «" + id + "»");
    }

    private static int moveQuest(Args a) throws Exception {
        QuestTab tab = a.tab();
        String id = a.positional(1, "questID");
        Quest q = tab.quests.get(id);
        if (q == null) throw new UsageException("нет квеста «" + id + "»");
        q.displayX = a.number("x", q.displayX);
        q.displayY = a.number("y", q.displayY);
        return write(a, tab, "Квест «" + id + "» → (" + fmt(q.displayX) + ", " + fmt(q.displayY) + ")");
    }

    private static int parentAdd(Args a) throws Exception {
        QuestTab tab = a.tab();
        Quest q = quest(a, tab);
        String pid = a.str("parent", null);
        if (pid == null) throw new UsageException("нужен --parent <questID>");
        Parent p = new Parent();
        p.questID = pid;
        p.plainString = !a.has("type") && !a.has("line") && !a.has("tab-id");
        if (a.has("tab-id")) p.tabID = a.str("tab-id", null);
        if (a.has("type")) p.parentType = Defs.ParentType.of(a.str("type", null));
        if (a.has("line")) p.lineType = Defs.LineType.of(a.str("line", null));
        q.parents.add(p);
        return write(a, tab, "Добавлена зависимость от «" + pid + "»");
    }

    private static int parentRemove(Args a) throws Exception {
        QuestTab tab = a.tab();
        Quest q = quest(a, tab);
        String pid = a.str("parent", null);
        if (pid == null) throw new UsageException("нужен --parent <questID>");
        q.parents.removeIf(p -> pid.equals(p.questID));
        return write(a, tab, "Удалена зависимость от «" + pid + "»");
    }

    private static int taskAdd(Args a) throws Exception {
        QuestTab tab = a.tab();
        Quest q = quest(a, tab);
        Task t = new Task();
        t.type = Defs.TaskType.of(a.str("type", "TASK_CONFIRM"));
        applyTaskArgs(t, a);
        int id = a.has("task") ? a.integer("task", 0) : q.nextTaskID();
        q.tasks.put(id, t);
        return write(a, tab, "Добавлена задача " + id + " (" + t.type.name() + ")");
    }

    private static int taskSet(Args a) throws Exception {
        QuestTab tab = a.tab();
        Quest q = quest(a, tab);
        int id = a.integer("task", -1);
        Task t = q.tasks.get(id);
        if (t == null) throw new UsageException("нет задачи " + id);
        if (a.has("type")) t.type = Defs.TaskType.of(a.str("type", t.type.name()));
        applyTaskArgs(t, a);
        return write(a, tab, "Изменена задача " + id);
    }

    private static void applyTaskArgs(Task t, Args a) {
        if (a.has("count")) t.requiredCount = a.integer("count", t.requiredCount);
        if (a.has("item")) t.forgeName = a.str("item", t.forgeName);
        if (a.has("desc")) t.customDescription = a.str("desc", t.customDescription);
        if (a.has("class")) t.className = a.str("class", t.className);
        if (a.has("command-name")) t.commandName = a.str("command-name", t.commandName);
        if (a.has("display-name")) t.displayName = a.str("display-name", t.displayName);
        List<String> parents = a.all("task-parent");
        if (!parents.isEmpty()) {
            t.parents.clear();
            for (String p : parents) t.parents.add(Integer.parseInt(p.trim()));
        }
    }

    private static int taskRemove(Args a) throws Exception {
        QuestTab tab = a.tab();
        Quest q = quest(a, tab);
        int id = a.integer("task", -1);
        if (q.tasks.remove(id) == null) throw new UsageException("нет задачи " + id);
        for (Task t : q.tasks.values()) t.parents.removeIf(p -> p == id);
        if (a.flag("renumber")) q.renumberTasks();
        return write(a, tab, "Удалена задача " + id);
    }

    private static int rewardAdd(Args a) throws Exception {
        QuestTab tab = a.tab();
        Quest q = quest(a, tab);
        List<String> items = a.all("item");
        if (items.isEmpty()) throw new UsageException("нужен --item <строка награды>");
        q.rewards.addAll(items);
        return write(a, tab, "Добавлено наград: " + items.size());
    }

    private static int rewardRemove(Args a) throws Exception {
        QuestTab tab = a.tab();
        Quest q = quest(a, tab);
        if (a.has("index")) {
            int i = a.integer("index", -1);
            if (i < 0 || i >= q.rewards.size()) throw new UsageException("нет награды с номером " + i);
            q.rewards.remove(i);
        } else if (a.has("item")) {
            q.rewards.remove(a.str("item", null));
        } else {
            throw new UsageException("нужен --index N или --item <строка>");
        }
        return write(a, tab, "Награда удалена");
    }

    /**
     * Слияние JSON-патча с вкладкой. Объекты сливаются рекурсивно, значение
     * {@code null} удаляет ключ, массивы и скаляры заменяются целиком.
     */
    private static int patch(Args a) throws Exception {
        QuestTab tab = a.tab();
        String json = a.str("json", null);
        if (json == null && a.has("patch-file"))
            json = new String(Files.readAllBytes(new File(a.str("patch-file", null)).toPath()), StandardCharsets.UTF_8);
        if (json == null) throw new UsageException("нужен --json <строка> или --patch-file <файл>");

        Object parsed = JsonParser.parse(TabIO.stripBom(json));
        if (!(parsed instanceof JsonObject)) throw new UsageException("патч должен быть JSON-объектом");

        QuestTab merged = ru.dw.qeditor.io.TabPatch.apply(tab, (JsonObject) parsed);
        return write(a, merged, "Патч применён");
    }

    // ------------------------------------------------------------------ вспомогательное

    private static Quest quest(Args a, QuestTab tab) {
        String id = a.positional(1, "questID");
        Quest q = tab.quests.get(id);
        if (q == null) throw new UsageException("нет квеста «" + id + "»");
        return q;
    }

    private static int write(Args a, QuestTab tab, String message) throws Exception {
        if (a.flag("dry-run")) {
            out.println(tab.toJsonString());
            return 0;
        }
        File target = a.has("out") ? new File(a.str("out", null)) : a.file();
        TabIO.save(target, tab);
        out.println(message + " → " + target.getAbsolutePath());
        if (a.flag("validate")) {
            for (TabIO.Problem p : TabIO.validate(tab)) out.println("  " + p);
        }
        return 0;
    }

    private static String fmt(float f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }

    private static String cut(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n - 1) + "…";
    }

    private static final class UsageException extends RuntimeException {
        UsageException(String m) { super(m); }
    }

    /** Разбор аргументов: позиционные + повторяемые --ключ значение + --флаг. */
    private static final class Args {
        private final List<String> positional = new ArrayList<>();
        private final Map<String, List<String>> options = new LinkedHashMap<>();

        Args(List<String> argv) {
            for (int i = 0; i < argv.size(); i++) {
                String s = argv.get(i);
                if (s.startsWith("--")) {
                    String key = s.substring(2);
                    String value = null;
                    int eq = key.indexOf('=');
                    if (eq >= 0) {
                        value = key.substring(eq + 1);
                        key = key.substring(0, eq);
                    } else if (i + 1 < argv.size() && !argv.get(i + 1).startsWith("--")) {
                        value = argv.get(++i);
                    }
                    options.computeIfAbsent(key, k -> new ArrayList<>()).add(value == null ? "" : value);
                } else {
                    positional.add(s);
                }
            }
        }

        boolean has(String key) { return options.containsKey(key); }

        boolean flag(String key) { return options.containsKey(key); }

        String str(String key, String def) {
            List<String> v = options.get(key);
            return v == null || v.isEmpty() ? def : v.get(v.size() - 1);
        }

        List<String> all(String key) {
            List<String> v = options.get(key);
            return v == null ? new ArrayList<>() : v;
        }

        int integer(String key, int def) {
            String s = str(key, null);
            if (s == null || s.isEmpty()) return def;
            return Integer.parseInt(s.trim());
        }

        float number(String key, float def) {
            String s = str(key, null);
            if (s == null || s.isEmpty()) return def;
            return Float.parseFloat(s.trim().replace(',', '.'));
        }

        String positional(int index, String what) {
            if (index >= positional.size()) throw new UsageException("не указан " + what);
            return positional.get(index);
        }

        File file() {
            if (positional.isEmpty()) throw new UsageException("не указан файл вкладки");
            return new File(positional.get(0));
        }

        QuestTab tab() throws Exception {
            File f = file();
            if (!f.isFile()) throw new UsageException("файл не найден: " + f.getAbsolutePath());
            return TabIO.load(f);
        }
    }

    private static void usage() {
        out.println("DwQuests Editor — работа с вкладками из командной строки.");
        out.println();
        out.println("  java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli <команда> <файл> [ключи]");
        out.println();
        out.println("Чтение:");
        out.println("  info <файл>                       сводка по вкладке");
        out.println("  list <файл>                       таблица квестов");
        out.println("  show <файл> <questID>             JSON одного квеста");
        out.println("  validate <файл>                   проверка (код возврата 1, если есть ошибки)");
        out.println("  export <файл>                     компактный JSON для /dwq tab paste");
        out.println("  find-item <строка> [--mod M] [--limit N]   поиск предмета по имени или id");
        out.println("  tabs <папка>                      список вкладок в папке");
        out.println("  versions <файл>                   сохранённые версии вкладки");
        out.println("  backup <файл> [--keep N]          сохранить копию сейчас");
        out.println("  restore <файл> --version N        вернуть вкладку к версии из списка");
        out.println();
        out.println("Запись (общие ключи: --out <файл>, --dry-run, --validate):");
        out.println("  new <файл> --tab <tabID> [--icon ID] [--sort N]");
        out.println("  set-tab <файл> [--id X] [--icon ID] [--sort N]");
        out.println("  add <файл> <questID> [ключи квеста]");
        out.println("  edit <файл> <questID> [ключи квеста] [--rename NEW]");
        out.println("  remove <файл> <questID>");
        out.println("  move <файл> <questID> --x N --y N");
        out.println("  parent-add <файл> <questID> --parent ID [--type DEFAULT|HIDDEN|FAKE]");
        out.println("                              [--line DEFAULT|INVERTED|STRAIGHT] [--tab-id T]");
        out.println("  parent-remove <файл> <questID> --parent ID");
        out.println("  task-add <файл> <questID> --type T [--count N] [--item ID] [--desc S]");
        out.println("                            [--class C] [--command-name C] [--display-name D]");
        out.println("                            [--task-parent N]... [--task N]");
        out.println("  task-set <файл> <questID> --task N [те же ключи]");
        out.println("  task-remove <файл> <questID> --task N [--renumber]");
        out.println("  reward-add <файл> <questID> --item \"minecraft:diamond=3\" [--item ...]");
        out.println("  reward-remove <файл> <questID> (--index N | --item S)");
        out.println("  patch <файл> (--json '{...}' | --patch-file F)   слияние JSON-патча");
        out.println("  audit <файл>                   сверка id предметов с базой (бедрок в игре)");
        out.println();
        out.println("Ключи квеста: --name, --x, --y, --icon, --rarity, --size, --node,");
        out.println("              --tasks-type, --desc, --broadcast, --parent ID, --reward S");
        out.println();
        out.println("Типы задач: " + typeList());
    }

    private static String typeList() {
        StringBuilder sb = new StringBuilder();
        for (Defs.TaskType t : Defs.TaskType.values()) {
            if (t == Defs.TaskType.UNKNOWN) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(t.name());
            if (t.serverSupported()) sb.append('*');
        }
        sb.append("   (* — работает на сервере)");
        return sb.toString();
    }
}
