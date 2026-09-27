package ru.dw.qeditor.app;

import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.io.Workspace;


import java.io.File;
import java.util.List;
import java.util.function.Consumer;

/**
 * Список вкладок открытой папки. Переключение между вкладками одним щелчком —
 * без диалога «открыть файл».
 *
 * <p>Порт {@code ui/ProjectPanel}: вместо {@code JList} с рисователем ячеек —
 * обычный цикл по записям внутри прокручиваемой области.
 */
public final class ProjectView {

    private static final float ROW = 34;

    private final Workspace workspace;
    private final Object scrollId = new Object();

    private Consumer<Workspace.Entry> onOpen;
    private Runnable onNewTab;
    private Runnable onChooseFolder;
    private File currentFile;

    public ProjectView(Workspace workspace) {
        this.workspace = workspace;
    }

    public void setOnOpen(Consumer<Workspace.Entry> onOpen) { this.onOpen = onOpen; }

    public void setOnNewTab(Runnable onNewTab) { this.onNewTab = onNewTab; }

    public void setOnChooseFolder(Runnable onChooseFolder) { this.onChooseFolder = onChooseFolder; }

    /** Подсветить вкладку, открытую сейчас. */
    public void setCurrentFile(File file) { this.currentFile = file; }

    public void refresh() { workspace.rescan(); }

    public void draw(Ui ui, float x, float y, float w, float h) {
        float pad = 10;
        float cx = x + pad;
        float cw = w - pad * 2;
        float cy = y + pad;

        String title = workspace.isOpen() ? workspace.name().toUpperCase() : "ПАПКА НЕ ОТКРЫТА";
        Widgets.section(ui, title, cx, cy, cw);
        if (workspace.isOpen() && ui.over(cx, cy, cw, 14)) ui.tooltip(workspace.dir().getAbsolutePath());
        cy += 18;

        String info = workspace.isOpen()
                ? "вкладок: " + workspace.entries().size() + ", квестов: " + workspace.totalQuests()
                : "Файл → Открыть папку…";
        Widgets.caption(ui, info, cx, cy);
        cy += 20;

        // кнопки внизу колонки: список тянется на всё, что осталось
        float buttonsH = Theme.ROW + pad;
        float listH = Math.max(40, y + h - buttonsH - cy - pad);
        drawList(ui, cx, cy, cw, listH);

        float by = y + h - buttonsH;
        float bw = (cw - 8) / 2f;
        if (ui.button(cx, by, bw, Theme.ROW, "Папка…") && onChooseFolder != null) onChooseFolder.run();
        if (ui.over(cx, by, bw, Theme.ROW)) ui.tooltip("Открыть папку с вкладками");
        if (ui.button(cx + bw + 8, by, bw, Theme.ROW, "+ Вкладка", Ui.PRIMARY, true) && onNewTab != null)
            onNewTab.run();
        if (ui.over(cx + bw + 8, by, bw, Theme.ROW)) ui.tooltip("Создать вкладку в этой папке");
    }

    private void drawList(Ui ui, float x, float y, float w, float h) {
        List<Workspace.Entry> entries = workspace.entries();
        float content = entries.size() * ROW;
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE, Theme.BORDER, 1);
        if (entries.isEmpty()) {
            ui.textIn(workspace.isOpen() ? "в папке нет вкладок" : "папка не открыта",
                    x, y, w, h, Theme.TEXT_DISABLED, Ui.CENTER, Theme.FONT_SMALL, false);
            return;
        }

        float innerW = w - 2;
        ui.beginScroll(scrollId, x + 1, y + 1, innerW, h - 2, innerW, content);

        for (int i = 0; i < entries.size(); i++) {
            Workspace.Entry e = entries.get(i);
            float iy = i * ROW;
            boolean current = currentFile != null
                    && e.file.getAbsolutePath().equals(currentFile.getAbsolutePath());
            boolean hovered = ui.over(0, iy, innerW, ROW);

            if (current) ui.rect(0, iy, innerW, ROW, Theme.ACCENT_SOFT);
            else if (hovered) ui.rect(0, iy, innerW, ROW, Theme.HOVER);
            if (current) ui.rect(0, iy, 2, ROW, Theme.ACCENT);

            float tx = 8;
            if (e.iconItem != null && !e.iconItem.isEmpty()) {
                Icons.item(ui, e.iconItem, tx, iy + (ROW - 22) / 2f, 22);
                tx += 26;
            }
            ui.text(ui.ellipsize(e.title(), innerW - tx - 10, Theme.FONT, true),
                    tx, iy + 4, current || hovered ? Theme.TEXT : Theme.TEXT_MUTED, Theme.FONT, true);
            ui.text(e.error != null ? "не читается" : "квестов: " + e.questCount,
                    tx, iy + 18, e.error != null ? Theme.DANGER : Theme.TEXT_DISABLED,
                    Theme.FONT_SMALL, false);

            if (hovered) {
                ui.cursor(GlWindow.CURSOR_HAND);
                ui.tooltip(e.file.getAbsolutePath() + (e.error == null ? "" : "\n" + e.error));
                if (ui.in().leftPressed() && onOpen != null) onOpen.accept(e);
            }
        }
        ui.endScroll(scrollId, x + 1, y + 1, innerW, h - 2, innerW, content);
    }
}
