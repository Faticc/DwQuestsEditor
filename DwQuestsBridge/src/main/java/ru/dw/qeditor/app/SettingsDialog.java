package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;

/**
 * Настройки: масштаб интерфейса и сколько держать версий вкладки.
 *
 * <p>Путей к ресурсам больше нет — редактор живёт в игре, и текстуры, шрифт и
 * иконки предметов отдаёт она.
 */
public final class SettingsDialog extends Dialog {

    private final Runnable onSaved;

    private int uiScale;
    private int backupKeep;
    private int autoMinutes;
    private boolean backupOnSave;
    private boolean reviewBeforeSave;

    private SettingsDialog(Runnable onSaved) {
        super("Настройки");
        this.onSaved = onSaved;
        this.width = 620;
        this.height = 330;

        Config c = Config.get();
        uiScale = c.uiScale;
        backupKeep = c.backupKeep;
        autoMinutes = c.autoBackupMinutes;
        backupOnSave = c.backupOnSave;
        reviewBeforeSave = c.reviewBeforeSave;
    }

    public static Dialog open(Runnable onSaved) { return new SettingsDialog(onSaved); }

    @Override
    protected void content(Ui ui, float x, float y, float w, float h) {
        float row = Theme.ROW_LARGE;
        float labelW = 260;
        float cy = y;

        label(ui, "Масштаб интерфейса, %", x, cy, labelW, row);
        uiScale = ui.intField(this + "scale", x + labelW, cy, 100, row, uiScale, 0, 300);
        if (ui.over(x, cy, labelW, row))
            ui.tooltip("0 — как масштаб экрана в Windows; 100 — один пиксель на точку");
        cy += row + 6;

        label(ui, "Хранить версий на вкладку", x, cy, labelW, row);
        backupKeep = ui.intField(this + "keep", x + labelW, cy, 100, row, backupKeep, 0, 500);
        cy += row + 6;

        label(ui, "Автокопия каждые, минут", x, cy, labelW, row);
        autoMinutes = ui.intField(this + "auto", x + labelW, cy, 100, row, autoMinutes, 0, 120);
        if (ui.over(x, cy, labelW, row)) ui.tooltip("0 — не делать автоматических копий");
        cy += row + 10;

        if (ui.checkbox(x + labelW, cy, "Копия при каждом сохранении", backupOnSave))
            backupOnSave = !backupOnSave;
        cy += 24;
        if (ui.checkbox(x + labelW, cy, "Показывать изменения перед сохранением", reviewBeforeSave))
            reviewBeforeSave = !reviewBeforeSave;

        int pressed = buttons(ui, x, y + h - row, w, "Отмена", "Сохранить");
        if (pressed == 1) save();
        else if (pressed == 0) close();
    }

    private void label(Ui ui, String text, float x, float y, float w, float h) {
        ui.textIn(text, x, y, w - 8, h, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
    }

    private void save() {
        Config c = Config.get();
        // меньше половины интерфейс не читается — такое значение считаем «авто»
        c.uiScale = uiScale < 50 ? 0 : uiScale;
        c.backupKeep = backupKeep;
        c.autoBackupMinutes = autoMinutes;
        c.backupOnSave = backupOnSave;
        c.reviewBeforeSave = reviewBeforeSave;
        c.save();
        if (onSaved != null) onSaved.run();
        close();
    }
}
