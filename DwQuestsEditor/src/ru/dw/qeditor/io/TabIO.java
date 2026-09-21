package ru.dw.qeditor.io;

import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.model.Task;
import ru.dw.qeditor.util.ForgeName;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Чтение/запись вкладок, обмен через буфер обмена и проверка вкладки. */
public final class TabIO {

    private TabIO() { }

    // ------------------------------------------------------------------ файлы

    public static QuestTab load(File file) throws IOException {
        String text = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
        return QuestTab.parse(stripBom(text));
    }

    public static void save(File file, QuestTab tab) throws IOException {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory()) parent.mkdirs();
        Files.write(file.toPath(), tab.toJsonString().getBytes(StandardCharsets.UTF_8));
    }

    public static String stripBom(String s) {
        return s != null && !s.isEmpty() && s.charAt(0) == '﻿' ? s.substring(1) : s;
    }

    /** Имя файла по умолчанию для вкладки. */
    public static String fileName(QuestTab tab) {
        return fileName(tab == null ? null : tab.tabID);
    }

    /**
     * Имя файла вкладки: пробелы — подчёркиванием, как в командах мода
     * ({@code /dwq tab paste Имя_вкладки}); запрещённые системой знаки — тоже.
     */
    public static String fileName(String tabID) {
        String id = tabID == null || tabID.isEmpty() ? "Tab" : tabID;
        return id.replaceAll("[\\\\/:*?\"<>|\\s]", "_") + ".json";
    }

    // ------------------------------------------------------------------ буфер обмена

    public static void toClipboard(String text) {
        Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
        cb.setContents(new StringSelection(text), null);
    }

    public static String fromClipboard() {
        try {
            Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
            Object data = cb.getData(DataFlavor.stringFlavor);
            return data == null ? null : stripBom(String.valueOf(data));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Строка для команды {@code /dwq tab paste}: мод ждёт ту же структуру,
     * что и в файле, поэтому просто компактный JSON.
     */
    public static String toPasteString(QuestTab tab) {
        return JsonWriter.write(tab.toJson(), false);
    }

    // ------------------------------------------------------------------ проверка

    public enum Severity { ERROR, WARNING, INFO }

    public static final class Problem {
        public final Severity severity;
        public final String questID;
        public final String message;

        public Problem(Severity severity, String questID, String message) {
            this.severity = severity;
            this.questID = questID;
            this.message = message;
        }

        @Override
        public String toString() {
            String prefix = severity == Severity.ERROR ? "[ОШИБКА] "
                    : severity == Severity.WARNING ? "[ПРЕДУПР] " : "[ИНФО]   ";
            return prefix + (questID == null ? "" : questID + ": ") + message;
        }
    }

    /** Полная проверка вкладки: ссылки, иконки, задачи, награды. */
    public static List<Problem> validate(QuestTab tab) {
        List<Problem> out = new ArrayList<>();
        if (tab == null) {
            out.add(new Problem(Severity.ERROR, null, "Вкладка не загружена"));
            return out;
        }
        if (tab.tabID == null || tab.tabID.trim().isEmpty())
            out.add(new Problem(Severity.ERROR, null, "Пустой tabID"));
        else if (!ModAssets.get().hasTabBackground(tab.tabID))
            out.add(new Problem(Severity.WARNING, null,
                    "В jar мода нет фона для вкладки «" + tab.tabID + "» — в игре будет Default"));

        checkItem(out, null, tab.iconItem, "иконка вкладки");

        Set<String> positions = new HashSet<>();
        for (Map.Entry<String, Quest> e : tab.quests.entrySet()) {
            String id = e.getKey();
            Quest q = e.getValue();

            if (id.trim().isEmpty()) out.add(new Problem(Severity.ERROR, id, "Пустой идентификатор квеста"));
            String pos = q.displayX + ";" + q.displayY;
            if (!positions.add(pos))
                out.add(new Problem(Severity.WARNING, id,
                        "Другой квест стоит в той же клетке (" + fmt(q.displayX) + ", " + fmt(q.displayY) + ")"));

            if (q.displayName == null || q.displayName.isEmpty())
                out.add(new Problem(Severity.INFO, id, "Нет displayName — в игре покажется идентификатор"));

            checkItem(out, id, q.iconItem, "иконка квеста");

            for (Parent p : q.parents) {
                if (p.questID == null || p.questID.isEmpty()) {
                    out.add(new Problem(Severity.ERROR, id, "Зависимость без questID"));
                } else if (p.tabID == null || p.tabID.isEmpty() || p.tabID.equals(tab.tabID)) {
                    if (!tab.quests.containsKey(p.questID))
                        out.add(new Problem(Severity.ERROR, id,
                                "Зависимость от несуществующего квеста «" + p.questID + "»"));
                    else if (p.questID.equals(id))
                        out.add(new Problem(Severity.ERROR, id, "Квест зависит сам от себя"));
                }
            }

            if (q.tasks.isEmpty())
                out.add(new Problem(Severity.WARNING, id, "У квеста нет задач — его нельзя выполнить"));

            for (Map.Entry<Integer, Task> te : q.tasks.entrySet()) {
                int tid = te.getKey();
                Task t = te.getValue();
                String where = "задача " + tid;

                if (t.type == Defs.TaskType.UNKNOWN)
                    out.add(new Problem(Severity.ERROR, id, where + ": неизвестный тип"));
                else if (!t.type.serverSupported())
                    out.add(new Problem(Severity.WARNING, id,
                            where + ": тип " + t.type.name() + " не отслеживается сервером"));

                if (t.type.kind == Defs.TaskType.Kind.ITEM) {
                    if (t.requiredCount <= 0)
                        out.add(new Problem(Severity.ERROR, id, where + ": requiredCount = 0"));
                    checkItem(out, id, t.forgeName, where);
                } else if (t.type.kind == Defs.TaskType.Kind.KILL) {
                    if (t.className == null || t.className.isEmpty())
                        out.add(new Problem(Severity.ERROR, id, where + ": не задан className моба"));
                    if (t.requiredCount <= 0)
                        out.add(new Problem(Severity.ERROR, id, where + ": requiredCount = 0"));
                } else if (t.type.kind == Defs.TaskType.Kind.TIME && t.requiredCount <= 0) {
                    out.add(new Problem(Severity.ERROR, id, where + ": requiredCount = 0"));
                }

                for (int pid : t.parents) {
                    if (!q.tasks.containsKey(pid))
                        out.add(new Problem(Severity.ERROR, id,
                                where + ": зависит от несуществующей задачи " + pid));
                    else if (pid == tid)
                        out.add(new Problem(Severity.ERROR, id, where + ": зависит сама от себя"));
                }
            }

            for (String r : q.rewards) {
                if (r == null || r.trim().isEmpty()) {
                    out.add(new Problem(Severity.WARNING, id, "Пустая награда"));
                    continue;
                }
                if (r.startsWith("command:")) continue;
                checkItem(out, id, r, "награда");
            }
        }

        List<String> cycle = findCycle(tab);
        if (cycle != null)
            out.add(new Problem(Severity.ERROR, null, "Цикл в зависимостях: " + String.join(" → ", cycle)));

        return out;
    }

    private static void checkItem(List<Problem> out, String questID, String forgeString, String what) {
        if (forgeString == null || forgeString.trim().isEmpty()) {
            out.add(new Problem(Severity.WARNING, questID, what + ": пустая строка предмета"));
            return;
        }
        ForgeName fn = ForgeName.parse(forgeString);
        if (fn.isCommand() || fn.isGift() || fn.isScript()) return;
        if (!ItemRegistry.get().hasIcon(forgeString))
            // ForgeNameUtils.getForgeItem отдаёт BEDROCK на всё, чего нет в
            // реестре: бедрок в игре — это почти всегда опечатка или id из
            // мода, которого нет на сервере (либо база иконок устарела)
            out.add(new Problem(Severity.WARNING, questID,
                    what + ": id «" + fn.id + "» не найден в базе предметов — в игре будет бедрок"));
    }

    /** Поиск цикла в графе зависимостей внутри вкладки. */
    private static List<String> findCycle(QuestTab tab) {
        Set<String> visited = new HashSet<>();
        Set<String> stack = new HashSet<>();
        List<String> path = new ArrayList<>();
        for (String id : tab.quests.keySet()) {
            List<String> c = dfs(tab, id, visited, stack, path);
            if (c != null) return c;
        }
        return null;
    }

    private static List<String> dfs(QuestTab tab, String id, Set<String> visited, Set<String> stack, List<String> path) {
        if (stack.contains(id)) {
            List<String> cycle = new ArrayList<>(path.subList(path.indexOf(id), path.size()));
            cycle.add(id);
            return cycle;
        }
        if (!visited.add(id)) return null;
        Quest q = tab.quests.get(id);
        if (q == null) return null;
        stack.add(id);
        path.add(id);
        for (Parent p : q.parents) {
            if (p.questID == null) continue;
            if (p.tabID != null && !p.tabID.isEmpty() && !p.tabID.equals(tab.tabID)) continue;
            if (!tab.quests.containsKey(p.questID)) continue;
            List<String> c = dfs(tab, p.questID, visited, stack, path);
            if (c != null) return c;
        }
        stack.remove(id);
        path.remove(path.size() - 1);
        return null;
    }

    private static String fmt(float f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }
}
