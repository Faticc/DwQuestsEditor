package ru.dw.qeditor.link;

import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Перенос иконок, выгруженных игрой, в папку иконок редактора.
 *
 * <p>Мод складывает выгрузку в {@code <клиент>/dwq_export/icons}, а редактор
 * читает свою папку — и сам по себе туда ничего не переносит. Картинки просто
 * копируются поверх, а {@code items.json} сливается: выгрузка «только иконки
 * вкладок» пишет урезанный список, и заменить им полный значило бы потерять
 * остальные предметы. Строки из игры главнее — там свежие названия и NBT.
 */
public final class IconImport {

    public static final class Result {
        public int copied;
        public int same;
        public int itemsAdded;
        public int itemsTotal;

        @Override
        public String toString() {
            return "картинок обновлено " + copied + ", не изменилось " + same
                    + "; предметов в базе " + itemsTotal + " (новых " + itemsAdded + ")";
        }
    }

    private IconImport() { }

    /** Папка выгрузки иконок у клиента или null, если там пусто. */
    public static File source(String clientDir) {
        if (clientDir == null || clientDir.trim().isEmpty()) return null;
        File dir = new File(new File(clientDir), "dwq_export/icons");
        return new File(dir, "img").isDirectory() ? dir : null;
    }

    public static Result run(File from, File to) throws IOException {
        Result r = new Result();
        copyTree(new File(from, "img"), new File(to, "img"), r);
        mergeItems(new File(from, "json/items.json"), new File(to, "json/items.json"), r);
        return r;
    }

    private static void copyTree(File from, File to, Result r) throws IOException {
        File[] list = from.listFiles();
        if (list == null) return;
        for (File f : list) {
            File target = new File(to, f.getName());
            if (f.isDirectory()) {
                copyTree(f, target, r);
                continue;
            }
            // сверяем содержимое, а не дату: копия с диска на диск дату меняет,
            // а перерисованная игрой картинка может совпасть байт в байт
            if (target.isFile() && target.length() == f.length()
                    && java.util.Arrays.equals(Files.readAllBytes(f.toPath()), Files.readAllBytes(target.toPath()))) {
                r.same++;
                continue;
            }
            if (!to.isDirectory() && !to.mkdirs()) throw new IOException("нет папки " + to);
            Files.copy(f.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            r.copied++;
        }
    }

    private static void mergeItems(File from, File to, Result r) throws IOException {
        if (!from.isFile()) return;
        JsonObject merged = to.isFile() ? read(to) : new JsonObject();
        JsonObject fresh = read(from);
        for (String key : fresh.keys()) {
            if (!merged.has(key)) r.itemsAdded++;
            merged.put(key, fresh.get(key));
        }
        r.itemsTotal = merged.size();
        File parent = to.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("нет папки " + parent);
        // сначала во временный файл: оборванная запись не должна оставить
        // редактор без базы предметов
        File tmp = new File(parent, to.getName() + ".tmp");
        try (Writer w = new OutputStreamWriter(new java.io.FileOutputStream(tmp), StandardCharsets.UTF_8)) {
            w.write(JsonWriter.write(merged, true));
        }
        Files.move(tmp.toPath(), to.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static JsonObject read(File f) throws IOException {
        try (FileInputStream in = new FileInputStream(f)) {
            return JsonParser.parseObject(new String(GameLink.readAll(in), StandardCharsets.UTF_8));
        }
    }
}
