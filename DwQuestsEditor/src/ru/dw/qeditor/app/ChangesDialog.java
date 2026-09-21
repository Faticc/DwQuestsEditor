package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.io.TabDiff;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;

/**
 * Несохранённые изменения: чем открытая вкладка отличается от своего файла.
 * Показывается перед сохранением (если включено) и по щелчку на пометке
 * «есть несохранённые правки».
 */
final class ChangesDialog extends Dialog {

    private static final float INDENT = 18;

    private final String fileName;
    private final TabDiff diff;
    private Runnable onSave;
    /** Что сделать, когда окно закрыли без сохранения — например, вернуть вопрос «Сохранить?». */
    private Runnable onClose;

    /** Перенос строк считается при смене ширины, а не каждый кадр: строк бывают тысячи. */
    private final List<Row> rows = new ArrayList<>();
    private float rowsWidth = -1;

    private record Row(String text, int color, boolean bold, float indent, float gapBefore) { }

    ChangesDialog(String fileName, TabDiff diff, Runnable onSave, Runnable onClose) {
        super("Несохранённые изменения");
        this.fileName = fileName;
        this.diff = diff;
        this.onSave = onSave;
        this.onClose = onClose;
        this.width = 900;
        this.height = 640;
    }

    @Override
    public void close() {
        if (isClosed()) return;
        super.close();
        Runnable after = onClose;
        onClose = null;
        if (after != null) after.run();
    }

    @Override
    protected void content(Ui ui, float x, float y, float w, float h) {
        ui.text(fileName, x, y, Theme.TEXT);
        Widgets.caption(ui, diff.summary(), x, y + 18);

        float top = y + 40;
        float bottom = Theme.ROW_LARGE + 10;
        float areaH = h - (top - y) - bottom;
        drawList(ui, x, top, w, areaH);

        float by = y + h - Theme.ROW_LARGE;
        boolean review = Config.get().reviewBeforeSave;
        if (ui.checkbox(x, by + (Theme.ROW_LARGE - 15) / 2, "Показывать перед сохранением", review)) {
            Config.get().reviewBeforeSave = !review;
            Config.get().save();
        }

        String[] labels = onSave == null ? new String[]{"Закрыть"} : new String[]{"Закрыть", "Сохранить"};
        int pressed = buttons(ui, x + 260, by, w - 260, labels);
        boolean enter = ui.keyPressed(GLFW_KEY_ENTER) || ui.keyPressed(GLFW_KEY_KP_ENTER);
        if (onSave != null && (pressed == 1 || enter)) {
            Runnable save = onSave;
            onClose = null;
            close();
            save.run();
        } else if (pressed == 0 || (onSave == null && enter)) {
            close();
        }
    }

    private void drawList(Ui ui, float x, float y, float w, float h) {
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        if (diff.isEmpty()) {
            ui.textIn("Вкладка совпадает с файлом", x, y, w, h, Theme.TEXT_DISABLED, Ui.CENTER);
            return;
        }
        float innerW = w - 2;
        if (rowsWidth != innerW) layout(ui, innerW);

        float lineH = ui.font(Theme.FONT_SMALL, false).height();
        float content = 8;
        for (Row r : rows) content += r.gapBefore + lineH;
        content += 8;

        Object id = this + "list";
        float[] scroll = ui.beginScroll(id, x + 1, y + 1, innerW, h - 2, innerW, content);
        float ly = 8;
        for (Row r : rows) {
            ly += r.gapBefore;
            // за пределами видимого не рисуем: при раскладке сеткой меняется каждый квест
            if (ly > scroll[1] - lineH && ly < scroll[1] + h)
                ui.text(r.text, 10 + r.indent, ly, r.color, Theme.FONT_SMALL, r.bold);
            ly += lineH;
        }
        ui.endScroll(id, x + 1, y + 1, innerW, h - 2, innerW, content);
    }

    private void layout(Ui ui, float innerW) {
        rows.clear();
        rowsWidth = innerW;
        boolean first = true;
        for (TabDiff.Line line : diff.lines) {
            float indent = line.depth() * INDENT;
            boolean head = line.depth() == 0;
            int color = switch (line.kind()) {
                case ADDED -> Theme.SUCCESS;
                case REMOVED -> Theme.DANGER;
                case QUEST -> Theme.ACCENT;
                case NOTE -> Theme.TEXT_MUTED;
                default -> Theme.TEXT;
            };
            List<String> parts = ui.wrap(line.text(), innerW - 24 - indent, Theme.FONT_SMALL, head);
            for (int i = 0; i < parts.size(); i++) {
                // квесты отделяем воздухом, иначе поля соседних сливаются
                float gap = i == 0 && head && !first ? 6 : 0;
                rows.add(new Row(parts.get(i), color, head, indent + (i > 0 ? INDENT : 0), gap));
            }
            first = false;
        }
    }
}
