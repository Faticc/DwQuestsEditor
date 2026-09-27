package ru.dw.qeditor.app;

import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.io.Backups;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * История версий вкладки: список копий, сводка отличий от текущего состояния и
 * восстановление. Перед самим восстановлением делается ещё одна копия, так что
 * откат тоже обратим.
 */
public final class VersionsDialog extends Dialog {

    private static final float ROW = 30;

    private final File tabFile;
    private final QuestTab current;
    private final Consumer<QuestTab> onRestore;
    private final Consumer<Dialog> openDialog;

    private List<Backups.Version> versions;
    private int selected;
    /** Сводка по выбранной версии — считается один раз на выбор, не каждый кадр. */
    private String details = "";
    private int detailsFor = -1;

    private VersionsDialog(File tabFile, QuestTab current,
                           Consumer<QuestTab> onRestore, Consumer<Dialog> openDialog) {
        super("История версий");
        this.tabFile = tabFile;
        this.current = current;
        this.onRestore = onRestore;
        this.openDialog = openDialog;
        this.width = 960;
        this.height = 620;
        reload();
    }

    public static Dialog open(File tabFile, QuestTab current,
                              Consumer<QuestTab> onRestore, Consumer<Dialog> openDialog) {
        return new VersionsDialog(tabFile, current, onRestore, openDialog);
    }

    private void reload() {
        versions = Backups.list(tabFile);
        selected = versions.isEmpty() ? -1 : 0;
        detailsFor = -1;
    }

    @Override
    protected void content(Ui ui, float x, float y, float w, float h) {
        ui.text(tabFile == null
                        ? "Вкладка ещё не сохранена в файл — копии лежат в каталоге черновиков"
                        : tabFile.getName(),
                x, y, Theme.TEXT);
        Widgets.caption(ui, Backups.summary(tabFile), x, y + 18);

        float top = y + 40;
        float bottom = Theme.ROW_LARGE + 10;
        float areaH = h - (top - y) - bottom;
        float listW = Math.round(w * 0.4f);

        drawList(ui, x, top, listW, areaH);
        drawDetails(ui, x + listW + 10, top, w - listW - 10, areaH);

        float by = y + h - Theme.ROW_LARGE;
        if (ui.button(x, by, 170, Theme.ROW_LARGE, "Папка версий", Ui.GHOST, true)) openFolder();
        if (ui.button(x + 178, by, 150, Theme.ROW_LARGE, "Удалить версию", Ui.NORMAL, selected >= 0))
            askDelete(ui);

        int pressed = buttons(ui, x + 340, by, w - 340, "Закрыть", "Восстановить");
        if (pressed == 1) askRestore(ui);
        else if (pressed == 0) close();
    }

    private void drawList(Ui ui, float x, float y, float w, float h) {
        Object id = this + "list";
        float content = versions.size() * ROW;
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        if (versions.isEmpty()) {
            ui.textIn("копий пока нет", x, y, w, h, Theme.TEXT_DISABLED, Ui.CENTER);
            return;
        }
        float innerW = w - 2;
        ui.beginScroll(id, x + 1, y + 1, innerW, h - 2, innerW, content);
        for (int i = 0; i < versions.size(); i++) {
            Backups.Version v = versions.get(i);
            float iy = i * ROW;
            boolean hovered = ui.over(0, iy, innerW, ROW);
            if (i == selected) ui.rect(0, iy, innerW, ROW, Theme.ACCENT_SOFT);
            else if (hovered) ui.rect(0, iy, innerW, ROW, Theme.HOVER);
            float tw = ui.textWidth(v.humanTime());
            ui.text(v.humanTime(), 8, iy + (ROW - ui.lineHeight()) / 2f,
                    i == selected ? Theme.TEXT : Theme.TEXT_MUTED);
            ui.text(v.reason.ru, 8 + tw + 10, iy + (ROW - ui.lineHeight()) / 2f,
                    Theme.TEXT_DISABLED, Theme.FONT_SMALL, false);
            if (hovered) {
                ui.cursor(GlWindow.CURSOR_HAND);
                if (ui.in().leftPressed()) selected = i;
            }
        }
        ui.endScroll(id, x + 1, y + 1, innerW, h - 2, innerW, content);
    }

    private void drawDetails(Ui ui, float x, float y, float w, float h) {
        if (selected != detailsFor) {
            details = buildDetails();
            detailsFor = selected;
            ui.setScrollY(this + "details", 0);
        }
        Object id = this + "details";
        List<String> lines = ui.wrap(details, w - 24, Theme.FONT_SMALL, false);
        float lineH = ui.font(Theme.FONT_SMALL, false).height();
        float content = lines.size() * lineH + 16;

        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        float[] scroll = ui.beginScroll(id, x + 1, y + 1, w - 2, h - 2, w - 2, content);
        float ly = 8;
        for (String l : lines) {
            if (ly > scroll[1] - lineH && ly < scroll[1] + h)
                ui.text(l, 10, ly, Theme.TEXT, Theme.FONT_SMALL, false);
            ly += lineH;
        }
        ui.endScroll(id, x + 1, y + 1, w - 2, h - 2, w - 2, content);
    }

    private String buildDetails() {
        if (selected < 0 || selected >= versions.size())
            return "Копий пока нет.\n\nОни появятся при сохранении вкладки, а также перед "
                    + "рискованными действиями — правкой помощником, раскладкой сеткой, "
                    + "переключением на другую вкладку.";
        Backups.Version v = versions.get(selected);
        StringBuilder sb = new StringBuilder();
        sb.append("Время:   ").append(v.humanTime()).append('\n');
        sb.append("Причина: ").append(v.reason.ru).append('\n');
        sb.append("Файл:    ").append(v.file.getName()).append('\n');
        sb.append("Размер:  ").append(v.size).append(" байт\n\n");
        try {
            sb.append(diff(Backups.read(v), current));
        } catch (Exception e) {
            sb.append("Не удалось прочитать копию: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** Чем эта версия отличается от того, что открыто сейчас. */
    private static String diff(QuestTab old, QuestTab now) {
        StringBuilder sb = new StringBuilder("--- Отличия версии от текущего состояния ---\n");
        if (now == null) return "Текущая вкладка недоступна.";
        if (!eq(old.tabID, now.tabID)) sb.append("tabID: ").append(old.tabID)
                .append(" → ").append(now.tabID).append('\n');
        if (old.sortIndex != now.sortIndex) sb.append("sortIndex: ").append(old.sortIndex)
                .append(" → ").append(now.sortIndex).append('\n');

        Set<String> all = new LinkedHashSet<>(old.quests.keySet());
        all.addAll(now.quests.keySet());
        List<String> onlyOld = new ArrayList<>();
        List<String> onlyNow = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String id : all) {
            Quest a = old.quests.get(id);
            Quest b = now.quests.get(id);
            if (b == null) onlyOld.add(id);
            else if (a == null) onlyNow.add(id);
            else if (!a.toJson().toString().equals(b.toJson().toString())) changed.add(id);
        }
        line(sb, "Было в версии, сейчас нет", onlyOld);
        line(sb, "Появилось после версии", onlyNow);
        line(sb, "Изменилось", changed);
        if (onlyOld.isEmpty() && onlyNow.isEmpty() && changed.isEmpty())
            sb.append("Квесты совпадают с текущими.\n");
        sb.append("\nКвестов в версии: ").append(old.quests.size())
                .append(", сейчас: ").append(now.quests.size()).append('\n');
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, List<String> ids) {
        if (ids.isEmpty()) return;
        sb.append(label).append(" (").append(ids.size()).append("): ")
                .append(String.join(", ", ids)).append('\n');
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void askRestore(Ui ui) {
        if (selected < 0 || openDialog == null) return;
        Backups.Version v = versions.get(selected);
        openDialog.accept(Dialogs.confirm("Восстановление",
                "Вернуть вкладку к версии от " + v.humanTime() + "?\n"
                        + "Текущее состояние сначала сохранится отдельной копией.",
                new String[]{"Отмена", "Восстановить"}, answer -> {
                    if (answer != 1) return;
                    try {
                        onRestore.accept(Backups.read(v));
                        close();
                    } catch (Exception e) {
                        openDialog.accept(Dialogs.message("Ошибка",
                                "Не удалось прочитать копию:\n" + e.getMessage()));
                    }
                }));
    }

    private void askDelete(Ui ui) {
        if (selected < 0 || openDialog == null) return;
        Backups.Version v = versions.get(selected);
        openDialog.accept(Dialogs.confirm("Удаление версии",
                "Удалить копию от " + v.humanTime() + "?",
                new String[]{"Отмена", "Удалить"}, answer -> {
                    if (answer != 1) return;
                    //noinspection ResultOfMethodCallIgnored
                    v.file.delete();
                    reload();
                }));
    }

    private void openFolder() {
        File dir = Backups.versionsDir(tabFile);
        try {
            if (dir != null && dir.isDirectory()) java.awt.Desktop.getDesktop().open(dir);
        } catch (Exception ignored) {
            // без рабочего стола (или на сервере без графики) просто ничего не делаем
        }
    }
}
