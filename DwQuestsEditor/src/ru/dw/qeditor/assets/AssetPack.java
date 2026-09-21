package ru.dw.qeditor.assets;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;

/**
 * Откуда редактор берёт картинки мода и иконки предметов.
 *
 * <p>Вариантов два: папка на диске (в репозитории это {@code runtime/}) и
 * ресурсы внутри собственного jar-а — чтобы редактор можно было отдать
 * человеку одним файлом. Обоим нужно одно и то же: проверить, есть ли запись,
 * прочитать её байты и перечислить записи по префиксу, — это и есть весь
 * интерфейс.
 */
public interface AssetPack {

    /** Есть ли такая запись. Путь — с прямыми слэшами, без ведущего. */
    boolean has(String path);

    /** Байты записи либо null. */
    byte[] bytes(String path);

    /** Имена записей, начинающихся с префикса и кончающихся суффиксом. */
    List<String> list(String prefix, String suffix);

    /** Что это за источник — для диагностики. */
    String describe();

    default void close() { }

    // ------------------------------------------------------------------ фабрики

    /** Пустой набор: путь не задан или файл не открылся. */
    static AssetPack empty(String why) { return new Empty(why); }

    /** Папка на диске. */
    static AssetPack dir(File root) { return new Dir(root); }

    /**
     * Ресурсы внутри собственного jar-а, под каталогом {@code root}.
     *
     * <p>Перечислить содержимое каталога в classpath штатно нельзя, поэтому
     * рядом с ресурсами лежит список всех записей (`index.txt`) — его пишет
     * сборщик дистрибутива.
     */
    static AssetPack classpath(String root) { return new Classpath(root); }

    /** Есть ли в jar-е встроенные ресурсы. */
    static boolean hasEmbedded() {
        return AssetPack.class.getResource("/" + Classpath.INDEX) != null;
    }

    // ------------------------------------------------------------------ реализации

    final class Empty implements AssetPack {
        private final String why;

        Empty(String why) { this.why = why; }

        @Override public boolean has(String path) { return false; }

        @Override public byte[] bytes(String path) { return null; }

        @Override public List<String> list(String prefix, String suffix) { return List.of(); }

        @Override public String describe() { return why; }
    }

    final class Dir implements AssetPack {
        private final File root;
        /** Списки имён по каталогам: `list` у файловой системы не бесплатный. */
        private final Map<String, List<String>> listings = new HashMap<>();

        Dir(File root) { this.root = root; }

        private File file(String path) { return new File(root, path); }

        @Override public boolean has(String path) { return file(path).isFile(); }

        @Override
        public byte[] bytes(String path) {
            File f = file(path);
            if (!f.isFile()) return null;
            try {
                return Files.readAllBytes(f.toPath());
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public List<String> list(String prefix, String suffix) {
            // в папке перечисляем только один каталог — дальше вглубь не ходим:
            // всем, кто этим пользуется, нужен ровно один уровень
            int slash = prefix.lastIndexOf('/');
            String dir = slash < 0 ? "" : prefix.substring(0, slash);
            String start = slash < 0 ? prefix : prefix.substring(slash + 1);

            List<String> names = listings.get(dir);
            if (names == null) {
                names = new ArrayList<>();
                String[] raw = new File(root, dir).list();
                if (raw != null) Collections.addAll(names, raw);
                listings.put(dir, names);
            }

            List<String> out = new ArrayList<>();
            for (String n : names) {
                if (!n.startsWith(start)) continue;
                if (!suffix.isEmpty() && !n.toLowerCase(Locale.ROOT).endsWith(suffix)) continue;
                out.add(dir.isEmpty() ? n : dir + "/" + n);
            }
            return out;
        }

        @Override public String describe() { return root.getAbsolutePath(); }
    }

    final class Classpath implements AssetPack {
        /** Список всех встроенных записей, относительных к `dwq-assets/`. */
        static final String INDEX = "dwq-assets/index.txt";

        private final String root;
        private List<String> index;

        Classpath(String root) { this.root = root.isEmpty() || root.endsWith("/") ? root : root + "/"; }

        private String full(String path) { return "dwq-assets/" + root + path; }

        /**
         * По списку, а не через {@code getResource}: в именах есть пробелы и
         * кириллица, а список — точные имена записей, без превращения в URL.
         */
        @Override
        public boolean has(String path) {
            List<String> all = index();
            if (all.isEmpty()) return AssetPack.class.getResource("/" + full(path)) != null;
            return all.contains(root + path);
        }

        @Override
        public byte[] bytes(String path) {
            try (InputStream in = AssetPack.class.getResourceAsStream("/" + full(path))) {
                return in == null ? null : readAll(in, 0);
            } catch (IOException e) {
                return null;
            }
        }

        @Override
        public List<String> list(String prefix, String suffix) {
            List<String> all = index();
            String want = root + prefix;
            List<String> out = new ArrayList<>();
            for (String n : all) {
                if (!n.startsWith(want)) continue;
                if (!suffix.isEmpty() && !n.toLowerCase(Locale.ROOT).endsWith(suffix)) continue;
                out.add(n.substring(root.length()));
            }
            return out;
        }

        private List<String> index() {
            if (index != null) return index;
            index = new ArrayList<>();
            try (InputStream in = AssetPack.class.getResourceAsStream("/" + INDEX)) {
                if (in != null) {
                    String text = new String(readAll(in, 0), StandardCharsets.UTF_8);
                    for (String line : text.split("\n")) {
                        String n = line.trim();
                        if (!n.isEmpty()) index.add(n);
                    }
                }
            } catch (IOException ignored) {
                // без списка останутся доступны прямые обращения по пути
            }
            return index;
        }

        @Override public String describe() { return "встроено в jar (" + root + ")"; }
    }

    // ------------------------------------------------------------------ общее

    private static byte[] readAll(InputStream in, int sizeHint) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(sizeHint > 0 ? sizeHint : 8192);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }
}
