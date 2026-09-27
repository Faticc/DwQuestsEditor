package ru.dw.qeditor.io;

import ru.dw.qeditor.model.QuestTab;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Папка с вкладками квестов. Редактор открывает её целиком: слева список всех
 * найденных вкладок, переключение между ними без диалогов «открыть файл».
 */
public class Workspace {

    /** Одна вкладка в папке — то, что видно в списке слева. */
    public static final class Entry {
        public final File file;
        public String tabID;
        public String iconItem;
        public int sortIndex;
        public int questCount;
        public String error;

        Entry(File file) { this.file = file; }

        public String title() {
            return tabID == null || tabID.isEmpty() ? Backups.stripExtension(file.getName()) : tabID;
        }

        @Override public String toString() { return title(); }
    }

    private File dir;
    private final List<Entry> entries = new ArrayList<>();

    public File dir() { return dir; }

    public boolean isOpen() { return dir != null && dir.isDirectory(); }

    public List<Entry> entries() { return entries; }

    public String name() { return dir == null ? "" : dir.getName(); }

    /** Открыть папку и прочитать заголовки всех вкладок. */
    public void open(File folder) {
        this.dir = folder;
        rescan();
    }

    public void close() {
        dir = null;
        entries.clear();
    }

    /**
     * Перечитать список. Полный разбор каждой вкладки нужен, чтобы показать
     * tabID и число квестов — файлы небольшие, это быстро.
     */
    public void rescan() {
        entries.clear();
        if (!isOpen()) return;
        File[] files = dir.listFiles((d, n) -> n.toLowerCase().endsWith(".json"));
        if (files == null) return;

        for (File f : files) {
            if (f.getName().startsWith(".")) continue;
            Entry e = new Entry(f);
            try {
                QuestTab tab = TabIO.load(f);
                e.tabID = tab.tabID;
                e.iconItem = tab.iconItem;
                e.sortIndex = tab.sortIndex;
                e.questCount = tab.quests.size();
            } catch (IOException | RuntimeException ex) {
                e.error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
            }
            entries.add(e);
        }
        entries.sort(Comparator
                .comparingInt((Entry e) -> e.error != null ? 1 : 0)
                .thenComparingInt(e -> e.sortIndex)
                .thenComparing(Entry::title, String.CASE_INSENSITIVE_ORDER));
    }

    public Entry find(File file) {
        if (file == null) return null;
        for (Entry e : entries) {
            try {
                if (e.file.getCanonicalFile().equals(file.getCanonicalFile())) return e;
            } catch (IOException ignored) {
                if (e.file.getAbsolutePath().equals(file.getAbsolutePath())) return e;
            }
        }
        return null;
    }

    /** Файл вкладки внутри папки по имени; расширение добавится само. */
    public File fileFor(String tabID) {
        return new File(dir, TabIO.fileName(tabID));
    }

    public boolean contains(File file) { return find(file) != null; }

    /** Все вкладки папки, разобранные целиком — нужно просмотру для левой колонки. */
    public List<QuestTab> loadAll() {
        List<QuestTab> out = new ArrayList<>();
        for (Entry e : entries) {
            if (e.error != null) continue;
            try {
                out.add(TabIO.load(e.file));
            } catch (IOException | RuntimeException ignored) { }
        }
        out.sort(Comparator.comparingInt(t -> t.sortIndex));
        return out;
    }

    public int totalQuests() {
        int n = 0;
        for (Entry e : entries) n += e.questCount;
        return n;
    }
}
