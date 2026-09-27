package ru.dw.qeditor.app;

import me.eigenraven.lwjgl3ify.api.Lwjgl3Aware;

import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.util.tinyfd.TinyFileDialogs;

import java.io.File;

/**
 * Системные диалоги открытия и сохранения файла.
 *
 * <p>Своё окно выбора файла рисовать незачем: tinyfd открывает настоящий
 * проводник Windows, с закладками и вводом пути, — ровно то, чего ждёт человек.
 */
@Lwjgl3Aware
public final class FileDialogs {

    private FileDialogs() { }

    public static File openJson(String title, File startDir) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.json"));
            filters.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                    title, start(startDir), filters, "Вкладки квестов (*.json)", false);
            return path == null ? null : new File(path);
        }
    }

    public static File saveJson(String title, File suggested) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*.json"));
            filters.flip();
            String path = TinyFileDialogs.tinyfd_saveFileDialog(
                    title, suggested == null ? "" : suggested.getAbsolutePath(),
                    filters, "Вкладки квестов (*.json)");
            if (path == null) return null;
            if (!path.toLowerCase().endsWith(".json")) path = path + ".json";
            return new File(path);
        }
    }

    public static File savePng(String title, File suggested) {
        return saveAs(title, suggested, "png", "Картинка PNG (*.png)");
    }

    public static File saveGif(String title, File suggested) {
        return saveAs(title, suggested, "gif", "Анимация GIF (*.gif)");
    }

    private static File saveAs(String title, File suggested, String ext, String description) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8("*." + ext));
            filters.flip();
            String path = TinyFileDialogs.tinyfd_saveFileDialog(
                    title, suggested == null ? "" : suggested.getAbsolutePath(), filters, description);
            if (path == null) return null;
            if (!path.toLowerCase().endsWith("." + ext)) path = path + "." + ext;
            return new File(path);
        }
    }

    public static File folder(String title, File startDir) {
        String path = TinyFileDialogs.tinyfd_selectFolderDialog(title, start(startDir));
        return path == null ? null : new File(path);
    }

    /** Любой файл — этим выбирают jar-ы клиента и папку иконок. */
    public static File openAny(String title, File startDir, String pattern, String description) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            PointerBuffer filters = stack.mallocPointer(1);
            filters.put(stack.UTF8(pattern));
            filters.flip();
            String path = TinyFileDialogs.tinyfd_openFileDialog(
                    title, start(startDir), filters, description, false);
            return path == null ? null : new File(path);
        }
    }

    private static String start(File dir) {
        if (dir == null) return "";
        // tinyfd отличает папку от файла по завершающему разделителю
        return dir.isDirectory() ? dir.getAbsolutePath() + File.separator : dir.getAbsolutePath();
    }
}
