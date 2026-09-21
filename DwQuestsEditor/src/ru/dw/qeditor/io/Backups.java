package ru.dw.qeditor.io;

import ru.dw.qeditor.model.QuestTab;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Версии вкладок. Любое сохранение, рискованное действие и просто работа с
 * открытым файлом складывают копию в {@code .backups/<имя файла>/} рядом с
 * вкладкой, поэтому случайно потерять правки нельзя: старую версию всегда можно
 * посмотреть и вернуть.
 */
public final class Backups {

    /** Имя каталога с версиями — с точки, чтобы не попадал в список вкладок. */
    public static final String DIR_NAME = ".backups";

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final DateTimeFormatter HUMAN = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private Backups() { }

    /** Почему сделана копия — попадает в имя файла и в список версий. */
    public enum Reason {
        SAVE("сохранение"),
        AUTO("автосохранение"),
        BEFORE_OPEN("перед открытием другой вкладки"),
        BEFORE_ASSISTANT("перед правкой помощником"),
        BEFORE_LAYOUT("перед раскладкой сеткой"),
        BEFORE_RELOAD("перед перечитыванием файла"),
        BEFORE_RESTORE("перед восстановлением версии"),
        BEFORE_GAME("перед заменой вкладкой из игры"),
        MANUAL("вручную");

        public final String ru;

        Reason(String ru) { this.ru = ru; }

        static Reason bySlug(String slug) {
            for (Reason r : values()) if (r.slug().equals(slug)) return r;
            return MANUAL;
        }

        String slug() { return name().toLowerCase().replace('_', '-'); }
    }

    /** Одна сохранённая версия. */
    public static final class Version {
        public final File file;
        public final LocalDateTime time;
        public final Reason reason;
        public final long size;

        Version(File file, LocalDateTime time, Reason reason, long size) {
            this.file = file;
            this.time = time;
            this.reason = reason;
            this.size = size;
        }

        public String humanTime() { return time.format(HUMAN); }

        @Override
        public String toString() {
            return humanTime() + "  —  " + reason.ru + "  (" + (size / 1024 + 1) + " КБ)";
        }
    }

    // ------------------------------------------------------------------ запись

    /**
     * Сохранить копию содержимого вкладки.
     *
     * @param tabFile файл вкладки; если null — копия кладётся в каталог черновиков
     * @param json    содержимое на момент копии
     * @param keep    сколько последних версий хранить
     * @return созданный файл либо null, если записать не удалось
     */
    public static File save(File tabFile, String json, Reason reason, int keep) {
        try {
            File dir = versionsDir(tabFile);
            if (dir == null) return null;
            if (!dir.isDirectory() && !dir.mkdirs()) return null;

            String name = LocalDateTime.now().format(STAMP) + "_" + reason.slug() + ".json";
            File out = new File(dir, name);
            // в пределах секунды имя может повториться — добавляем счётчик
            int n = 2;
            while (out.exists()) {
                out = new File(dir, LocalDateTime.now().format(STAMP) + "_" + reason.slug() + "-" + n++ + ".json");
            }
            Files.write(out.toPath(), json.getBytes(StandardCharsets.UTF_8));
            prune(tabFile, keep);
            return out;
        } catch (IOException e) {
            return null;
        }
    }

    /** Не делать копию, если содержимое совпадает с последней версией. */
    public static File saveIfChanged(File tabFile, String json, Reason reason, int keep) {
        List<Version> existing = list(tabFile);
        if (!existing.isEmpty()) {
            try {
                String last = new String(Files.readAllBytes(existing.get(0).file.toPath()), StandardCharsets.UTF_8);
                if (last.equals(json)) return null;
            } catch (IOException ignored) { }
        }
        return save(tabFile, json, reason, keep);
    }

    // ------------------------------------------------------------------ чтение

    /** Каталог версий для файла вкладки (или общий каталог черновиков). */
    public static File versionsDir(File tabFile) {
        if (tabFile == null) return new File(draftsRoot(), "без-файла");
        File parent = tabFile.getParentFile();
        if (parent == null) parent = new File(".");
        return new File(new File(parent, DIR_NAME), stripExtension(tabFile.getName()));
    }

    /** Версии от новых к старым. */
    public static List<Version> list(File tabFile) {
        List<Version> out = new ArrayList<>();
        File dir = versionsDir(tabFile);
        if (dir == null || !dir.isDirectory()) return out;
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        if (files == null) return out;

        for (File f : files) {
            String name = stripExtension(f.getName());
            LocalDateTime time;
            Reason reason = Reason.MANUAL;
            int sep = name.indexOf('_', 11);
            try {
                time = LocalDateTime.parse(sep > 0 ? name.substring(0, sep) : name, STAMP);
                if (sep > 0) {
                    String slug = name.substring(sep + 1);
                    int dash = slug.lastIndexOf('-');
                    if (dash > 0 && slug.substring(dash + 1).matches("\\d+")) slug = slug.substring(0, dash);
                    reason = Reason.bySlug(slug);
                }
            } catch (RuntimeException e) {
                // имя не по нашему шаблону — берём время файла
                time = LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(f.lastModified()), java.time.ZoneId.systemDefault());
            }
            out.add(new Version(f, time, reason, f.length()));
        }
        out.sort(Comparator.comparing((Version v) -> v.time).reversed());
        return out;
    }

    public static QuestTab read(Version version) throws IOException {
        String text = new String(Files.readAllBytes(version.file.toPath()), StandardCharsets.UTF_8);
        return QuestTab.parse(TabIO.stripBom(text));
    }

    /** Оставить только keep последних версий. */
    public static void prune(File tabFile, int keep) {
        if (keep <= 0) return;
        List<Version> versions = list(tabFile);
        for (int i = keep; i < versions.size(); i++) {
            //noinspection ResultOfMethodCallIgnored
            versions.get(i).file.delete();
        }
    }

    /** Сколько версий и сколько места занимают. */
    public static String summary(File tabFile) {
        List<Version> versions = list(tabFile);
        if (versions.isEmpty()) return "версий пока нет";
        long total = 0;
        for (Version v : versions) total += v.size;
        return "версий: " + versions.size() + ", занимают " + (total / 1024 + 1) + " КБ, последняя "
                + versions.get(0).humanTime();
    }

    // ------------------------------------------------------------------ прочее

    /** Куда складывать копии, пока вкладка не сохранена в файл. */
    public static File draftsRoot() {
        File dir = ru.dw.qeditor.assets.Config.get().propertiesFile().getParentFile();
        return new File(dir == null ? new File(".") : dir, "черновики");
    }

    public static String stripExtension(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Все каталоги версий внутри папки проекта — для сводки по проекту. */
    public static List<File> backupDirs(File projectDir) {
        File root = new File(projectDir, DIR_NAME);
        File[] dirs = root.listFiles(File::isDirectory);
        return dirs == null ? new ArrayList<>() : new ArrayList<>(Arrays.asList(dirs));
    }
}
