package ru.dw.qeditor.app;

import ru.dw.qeditor.android.Platform;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Выбор файла и папки на телефоне — Android-вариант общего {@code app/FileDialogs}.
 *
 * <p>На компьютере диалог рисует система (tinyfd) и отвечает сразу. Здесь
 * такого нет: системный выбор Android отдаёт не путь, а адрес документа, а
 * редактор весь построен на файлах — папка вкладок, версии рядом с файлом,
 * слежение за изменениями. Поэтому выбор рисуется своим окном поверх кадра и
 * отвечает обратным вызовом. Синхронные методы общего кода (ими пользуется
 * только мост с игрой, которого тут нет) оставлены и ничего не выбирают.
 */
public final class FileDialogs {

    private FileDialogs() { }

    // ------------------------------------------------------------------ для общего кода

    public static File openJson(String title, File startDir) { return null; }

    public static File saveJson(String title, File suggested) { return null; }

    public static File savePng(String title, File suggested) { return null; }

    public static File saveGif(String title, File suggested) { return null; }

    public static File folder(String title, File startDir) { return null; }

    public static File openAny(String title, File startDir, String pattern, String description) { return null; }

    // ------------------------------------------------------------------ окна выбора

    /** Открыть вкладку (*.json). */
    public static Dialog openJson(String title, File startDir, Consumer<File> onPick) {
        return new Browser(title, Mode.OPEN, startDir, null, ".json", onPick);
    }

    /** Сохранить с расширением ext (".json", ".png", ".gif"). */
    public static Dialog save(String title, File suggested, String ext, Consumer<File> onPick) {
        File dir = suggested == null ? null : suggested.getParentFile();
        String name = suggested == null ? "" : suggested.getName();
        return new Browser(title, Mode.SAVE, dir, name, ext, onPick);
    }

    /** Выбрать папку. */
    public static Dialog folder(String title, File startDir, Consumer<File> onPick) {
        return new Browser(title, Mode.FOLDER, startDir, null, ".json", onPick);
    }

    private enum Mode { OPEN, SAVE, FOLDER }

    private static final class Browser extends Dialog {
        private static final float ROW = 40;

        private final Mode mode;
        private final String ext;
        private final Consumer<File> onPick;
        private File dir;
        private String name;
        private List<File> entries = new ArrayList<>();
        private String error;
        private boolean creatingFolder;
        private String newFolder = "";
        private File confirmOverwrite;
        private final Object scrollId = new Object();
        private final SimpleDateFormat dateFormat = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);

        Browser(String title, Mode mode, File start, String name, String ext, Consumer<File> onPick) {
            super(title);
            this.mode = mode;
            this.ext = ext;
            this.onPick = onPick;
            this.name = name == null ? "" : name;
            this.width = 760;
            this.height = 720;
            File d = start;
            while (d != null && !d.isDirectory()) d = d.getParentFile();
            if (d == null) d = Platform.get().defaultFolder();
            enter(d);
        }

        private void enter(File d) {
            dir = d;
            error = null;
            confirmOverwrite = null;
            File[] list = d.listFiles();
            if (list == null) {
                entries = new ArrayList<>();
                error = Platform.get().hasStorageAccess()
                        ? "Папка не читается"
                        : "Нет доступа к памяти телефона";
                return;
            }
            List<File> dirs = new ArrayList<>(), files = new ArrayList<>();
            for (File f : list) {
                if (f.getName().startsWith(".")) continue;
                if (f.isDirectory()) dirs.add(f);
                else if (mode != Mode.FOLDER && f.getName().toLowerCase(Locale.ROOT).endsWith(ext)) files.add(f);
            }
            java.util.Comparator<File> byName = (a, b) -> a.getName().compareToIgnoreCase(b.getName());
            dirs.sort(byName);
            files.sort(byName);
            entries = new ArrayList<>(dirs);
            entries.addAll(files);
        }

        @Override
        protected void content(Ui ui, float x, float y, float w, float h) {
            float row = Theme.ROW_LARGE;
            float cy = y;

            // быстрые места
            float px = x;
            for (Platform.Place p : Platform.get().places()) {
                float pw = ui.textWidth(p.label()) + 24;
                if (px + pw > x + w) break;
                boolean here = p.dir().equals(dir);
                if (ui.toggleButton(px, cy, pw, row, p.label(), here)) {
                    p.dir().mkdirs();
                    enter(p.dir());
                }
                px += pw + 6;
            }
            cy += row + 8;

            // путь и «вверх»
            File parent = dir.getParentFile();
            if (ui.button(x, cy, 44, row, "↑", Ui.NORMAL, parent != null)) enter(parent);
            float nfw = mode == Mode.OPEN ? 0 : 120;
            ui.textIn(ui.ellipsize(dir.getAbsolutePath(), w - 52 - nfw - 8, Theme.FONT_SMALL, false),
                    x + 52, cy, w - 52 - nfw, row, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
            if (nfw > 0 && ui.button(x + w - nfw, cy, nfw, row, "+ Папка", Ui.GHOST, true)) {
                creatingFolder = true;
                newFolder = "";
                ui.focus(this + "newFolder");
            }
            cy += row + 6;

            if (creatingFolder) {
                newFolder = ui.field(this + "newFolder", x, cy, w - 220, row, newFolder, "имя новой папки");
                if (ui.button(x + w - 212, cy, 100, row, "Создать", Ui.PRIMARY, !newFolder.trim().isEmpty())) {
                    File nd = new File(dir, newFolder.trim());
                    if (nd.mkdirs() || nd.isDirectory()) enter(nd);
                    else error = "Не удалось создать папку";
                    creatingFolder = false;
                }
                if (ui.button(x + w - 104, cy, 104, row, "Отмена")) creatingFolder = false;
                cy += row + 6;
            }

            float bottom = mode == Mode.OPEN ? row + 8 : (row + 8) * 2;
            float listH = Math.max(60, y + h - bottom - cy);
            drawList(ui, x, cy, w, listH);
            cy += listH + 8;

            if (mode == Mode.SAVE) {
                ui.textIn("Имя файла", x, cy, 90, row, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
                name = ui.field(this + "name", x + 92, cy, w - 92, row, name, "имя" + ext);
                cy += row + 8;
            }

            String[] labels = switch (mode) {
                case OPEN -> new String[]{"Отмена"};
                case SAVE -> new String[]{"Отмена", confirmOverwrite != null ? "Заменить" : "Сохранить"};
                case FOLDER -> new String[]{"Отмена", "Выбрать"};
            };
            if (mode == Mode.SAVE && confirmOverwrite != null)
                ui.textIn("Файл уже есть — заменить?", x, cy, w / 2, row, Theme.WARNING,
                        Ui.LEFT, Theme.FONT_SMALL, false);
            int pressed = buttons(ui, x, y + h - row, w, labels);
            if (pressed == 0) close();
            else if (pressed == 1) confirm();
        }

        private void confirm() {
            if (mode == Mode.FOLDER) {
                close();
                onPick.accept(dir);
                return;
            }
            String n = name.trim();
            if (n.isEmpty()) return;
            if (!n.toLowerCase(Locale.ROOT).endsWith(ext)) n += ext;
            File f = new File(dir, n);
            if (f.exists() && !f.equals(confirmOverwrite)) {
                confirmOverwrite = f;
                return;
            }
            close();
            onPick.accept(f);
        }

        private void drawList(Ui ui, float x, float y, float w, float h) {
            ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
            if (error != null) {
                ui.textIn(error, x, y, w, h / 2, Theme.TEXT_MUTED, Ui.CENTER);
                if (!Platform.get().hasStorageAccess()
                        && ui.button(x + w / 2 - 110, y + h / 2, 220, Theme.ROW_LARGE, "Дать доступ", Ui.PRIMARY, true))
                    Platform.get().requestStorageAccess();
                if (Platform.get().hasStorageAccess() && error.startsWith("Нет доступа")) enter(dir);
                return;
            }
            if (entries.isEmpty()) {
                ui.textIn(mode == Mode.FOLDER ? "вложенных папок нет" : "здесь нет ни папок, ни файлов " + ext,
                        x, y, w, h, Theme.TEXT_DISABLED, Ui.CENTER, Theme.FONT_SMALL, false);
                return;
            }
            float content = entries.size() * ROW;
            float innerW = w - 2;
            ui.beginScroll(scrollId, x + 1, y + 1, innerW, h - 2, innerW, content);
            float from = ui.scrollY(scrollId);
            File picked = null;
            for (int i = 0; i < entries.size(); i++) {
                float iy = i * ROW;
                if (iy + ROW < from || iy > from + h) continue;
                File f = entries.get(i);
                boolean isDir = f.isDirectory();
                boolean hovered = ui.over(0, iy, innerW, ROW);
                boolean chosen = !isDir && f.getName().equals(name);
                if (chosen) ui.rect(0, iy, innerW, ROW, Theme.ACCENT_SOFT);
                else if (hovered) ui.rect(0, iy, innerW, ROW, Theme.HOVER);
                ui.textIn(isDir ? "▸" : "•", 8, iy, 18, ROW, isDir ? Theme.ACCENT : Theme.TEXT_DISABLED, Ui.CENTER);
                ui.text(ui.ellipsize(f.getName(), innerW - 40, Theme.FONT, !isDir),
                        32, iy + 5, Theme.TEXT, Theme.FONT, !isDir);
                String sub = isDir ? "папка" : sizeOf(f) + "   " + dateFormat.format(new Date(f.lastModified()));
                ui.text(sub, 32, iy + 22, Theme.TEXT_DISABLED, Theme.FONT_SMALL, false);
                if (hovered) {
                    ui.cursor(GlWindow.CURSOR_HAND);
                    if (ui.in().leftPressed()) picked = f;
                }
            }
            ui.endScroll(scrollId, x + 1, y + 1, innerW, h - 2, innerW, content);

            if (picked != null) {
                if (picked.isDirectory()) {
                    enter(picked);
                    ui.setScrollY(scrollId, 0);
                } else if (mode == Mode.OPEN) {
                    close();
                    onPick.accept(picked);
                } else {
                    name = picked.getName();
                    ui.forgetState(this + "name");
                    confirmOverwrite = null;
                }
            }
        }

        private static String sizeOf(File f) {
            long n = f.length();
            if (n < 1024) return n + " Б";
            if (n < 1024 * 1024) return (n / 1024) + " КБ";
            return String.format(Locale.ROOT, "%.1f МБ", n / 1048576.0);
        }
    }
}
