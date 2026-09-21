package ru.dw.qeditor.ai;

import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Вкладка, разложенная по файлам, — рабочий каталог помощника.
 *
 * <p>Раньше квесты уходили в запрос текстом, а назад приходил JSON-патч: модель
 * платила выходными токенами за каждый изменённый квест целиком. Теперь у неё
 * файлы: читает она только нужные, правит {@code Edit}-ом (в ответе — лишь
 * заменённый кусок), новые квесты создаёт {@code Write}-ом. Редактор потом
 * собирает вкладку обратно и показывает разницу до применения.
 *
 * <pre>
 *   tab.json            поля вкладки без квестов
 *   quests/&lt;id&gt;.json    по квесту на файл, id — в поле "questID"
 *   map.txt             сводка по строке на квест — искать Grep-ом
 *   tab.png             снимок, если его просили
 * </pre>
 */
public final class TabWorkspace {

    public static final String TAB_FILE = "tab.json";
    public static final String QUESTS_DIR = "quests";
    public static final String MAP_FILE = "map.txt";
    /** Поле с id квеста внутри файла: в самой вкладке id — это ключ. */
    public static final String ID_FIELD = "questID";

    private final File dir;
    /** Какой файл какому квесту выдан при последней раскладке. */
    private final Map<String, String> fileOf = new LinkedHashMap<>();
    /**
     * Файлы квестов, у которых «questID» был и в самом квесте: такое пишет
     * часть вкладок. Сборка должна его оставить, а не принять за служебное
     * поле раскладки — иначе все такие квесты выглядят изменёнными.
     */
    private final Set<String> ownIdField = new HashSet<>();

    public TabWorkspace(File dir) { this.dir = dir; }

    public File dir() { return dir; }

    public File questsDir() { return new File(dir, QUESTS_DIR); }

    /** Относительный путь файла квеста — им квест называется в запросе. */
    public String pathOf(String questID) {
        String f = fileOf.get(questID);
        return f == null ? null : QUESTS_DIR + "/" + f;
    }

    // ------------------------------------------------------------------ раскладка

    /**
     * Разложить вкладку. Файл переписывается, только если его текст
     * поменялся: CLI помнит, что уже читал, и в продолжении разговора
     * нетронутые файлы перечитывать не придётся.
     */
    public void write(QuestTab tab) throws IOException {
        File qd = questsDir();
        if (!qd.isDirectory() && !qd.mkdirs()) throw new IOException("Не создать " + qd);

        JsonObject meta = tab.toJson();
        meta.remove("quests");
        put(new File(dir, TAB_FILE), JsonWriter.write(meta, true));

        Set<String> taken = plan(tab);
        for (Quest q : tab.quests.values()) {
            String name = fileOf.get(q.questID);
            JsonObject o = new JsonObject();
            o.put(ID_FIELD, q.questID);
            JsonObject body = q.toJson();            for (String k : body.keys()) o.put(k, body.get(k));
            put(new File(qd, name), JsonWriter.write(o, true));
        }
        // остатки прошлых раскладок (удалённые квесты, прежние ответы модели)
        File[] old = qd.listFiles();
        if (old != null) for (File f : old) {
            if (!taken.contains(f.getName().toLowerCase(Locale.ROOT))) Files.deleteIfExists(f.toPath());
        }
        put(new File(dir, MAP_FILE), map(tab));
    }

    /**
     * Раздать квестам имена файлов, ничего не записывая: так окно помощника
     * до отправки показывает ровно те пути, что уйдут в запрос.
     *
     * @return занятые имена в нижнем регистре
     */
    public Set<String> plan(QuestTab tab) {
        fileOf.clear();
        ownIdField.clear();
        Set<String> taken = new HashSet<>();
        for (Quest q : tab.quests.values()) {
            String name = fileName(q.questID, taken);
            fileOf.put(q.questID, name);
            if (q.extra.has(ID_FIELD)) ownIdField.add(name);
        }
        return taken;
    }

    /**
     * Имя файла из id: запрещённые в Windows символы — в «_», а имена,
     * отличающиеся только регистром, там один и тот же файл — отсюда хвост ~N.
     */
    static String fileName(String id, Set<String> taken) {
        String base = id.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").replaceAll("[. ]+$", "");
        if (base.isEmpty()) base = "_";
        if (base.length() > 80) base = base.substring(0, 80);
        String name = base + ".json";
        for (int n = 2; taken.contains(name.toLowerCase(Locale.ROOT)); n++) name = base + "~" + n + ".json";
        taken.add(name.toLowerCase(Locale.ROOT));
        return name;
    }

    private static void put(File f, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        if (f.isFile() && java.util.Arrays.equals(Files.readAllBytes(f.toPath()), bytes)) return;
        Files.write(f.toPath(), bytes);
    }

    /** Сводка: id, клетка, название, зависимости, файл — по строке на квест. */
    private String map(QuestTab tab) {
        StringBuilder sb = new StringBuilder();
        sb.append("# id (x,y) «название» <- родители [задач, наград] -> файл\n");
        for (Quest q : tab.quests.values()) {
            sb.append(q.questID).append(" (").append(fmt(q.displayX)).append(',')
                    .append(fmt(q.displayY)).append(')');
            if (q.displayName != null && !q.displayName.isEmpty())
                sb.append(" «").append(q.displayName.replaceAll("§.", "")).append('»');
            List<String> ps = new ArrayList<>();
            for (Parent p : q.parents) if (p.questID != null) ps.add(p.questID);
            if (!ps.isEmpty()) sb.append(" <- ").append(String.join(", ", ps));
            sb.append(" [").append(q.tasks.size()).append(", ").append(q.rewards.size()).append("] -> ")
                    .append(pathOf(q.questID)).append('\n');
        }
        return sb.toString();
    }

    private static String fmt(float f) {
        return f == Math.rint(f) ? String.valueOf((long) f) : String.valueOf(f);
    }

    // ------------------------------------------------------------------ сборка

    /** Что осталось от раскладки после работы модели. */
    public static final class ReadResult {
        public final QuestTab tab;
        public final List<String> errors;

        ReadResult(QuestTab tab, List<String> errors) {
            this.tab = tab;
            this.errors = errors;
        }
    }

    /**
     * Собрать вкладку обратно. Порядок квестов прежний, новые — в конце.
     * Файл с одним словом {@code null} (или пустой) — квест удалён: удалять
     * файлы инструментами модели нечем.
     */
    public ReadResult read() {
        List<String> errors = new ArrayList<>();
        JsonObject root;
        try {
            root = JsonParser.parseObject(read(new File(dir, TAB_FILE)));
        } catch (Exception e) {
            errors.add(TAB_FILE + ": " + e.getMessage());
            return new ReadResult(null, errors);
        }

        File[] files = questsDir().listFiles((d, n) -> n.toLowerCase(Locale.ROOT).endsWith(".json"));
        Map<String, File> byName = new LinkedHashMap<>();
        if (files != null) {
            java.util.Arrays.sort(files);
            for (File f : files) byName.put(f.getName(), f);
        }
        // сначала файлы прежних квестов в прежнем порядке, потом новые
        List<File> ordered = new ArrayList<>();
        for (String name : fileOf.values()) {
            File f = byName.remove(name);
            if (f != null) ordered.add(f);
        }
        ordered.addAll(byName.values());

        JsonObject quests = new JsonObject();
        for (File f : ordered) {
            String text;
            try {
                text = read(f).trim();
            } catch (IOException e) {
                errors.add(QUESTS_DIR + "/" + f.getName() + ": " + e.getMessage());
                continue;
            }
            if (text.isEmpty() || text.equals("null")) continue;
            try {
                JsonObject o = JsonParser.parseObject(text);
                String id = o.str(ID_FIELD, null);
                if (id == null || id.trim().isEmpty()) {
                    String n = f.getName();
                    id = n.substring(0, n.length() - 5);
                }
                if (!ownIdField.contains(f.getName())) o.remove(ID_FIELD);
                if (quests.has(id)) errors.add(QUESTS_DIR + "/" + f.getName() + ": id «" + id + "» уже занят другим файлом");
                quests.put(id, o);
            } catch (RuntimeException e) {
                errors.add(QUESTS_DIR + "/" + f.getName() + ": " + e.getMessage());
            }
        }
        root.put("quests", quests);
        try {
            return new ReadResult(QuestTab.parse(JsonWriter.write(root, false)), errors);
        } catch (RuntimeException e) {
            errors.add(e.getMessage());
            return new ReadResult(null, errors);
        }
    }

    private static String read(File f) throws IOException {
        String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        return s.startsWith("﻿") ? s.substring(1) : s;
    }

    /** Размер раскладки для показа: файлов квестов и байт всего. */
    public long[] size() {
        long bytes = 0;
        int count = 0;
        File[] qs = questsDir().listFiles();
        if (qs != null) for (File f : qs) {
            bytes += f.length();
            count++;
        }
        bytes += new File(dir, TAB_FILE).length() + new File(dir, MAP_FILE).length();
        return new long[]{count, bytes};
    }
}
