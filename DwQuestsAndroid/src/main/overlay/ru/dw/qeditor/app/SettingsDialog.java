package ru.dw.qeditor.app;

import ru.dw.qeditor.android.Platform;
import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;

/**
 * Настройки на телефоне: размер интерфейса, версии вкладок, доступ к памяти.
 *
 * <p>Android-вариант общего {@code app/SettingsDialog}: масштаб здесь — в
 * процентах от системного размера, а не от пикселя, потому что точек на
 * дюйм у телефона в разы больше, чем у монитора.
 */
public final class SettingsDialog extends Dialog {

    private final Runnable onSaved;

    private int uiScale;
    private int backupKeep;
    private int autoMinutes;
    private boolean backupOnSave;
    private boolean reviewBeforeSave;
    private boolean watchFile;

    private SettingsDialog(Runnable onSaved) {
        super("Настройки");
        this.onSaved = onSaved;
        this.width = 620;
        this.height = 420;

        Config c = Config.get();
        uiScale = c.uiScale;
        backupKeep = c.backupKeep;
        autoMinutes = c.autoBackupMinutes;
        backupOnSave = c.backupOnSave;
        reviewBeforeSave = c.reviewBeforeSave;
        watchFile = c.watchFile;
    }

    public static Dialog open(Runnable onSaved) { return new SettingsDialog(onSaved); }

    @Override
    protected void content(Ui ui, float x, float y, float w, float h) {
        float row = Theme.ROW_LARGE;
        float labelW = Math.min(260, w - 130);
        float cy = y;

        label(ui, "Размер интерфейса, %", x, cy, labelW, row);
        uiScale = ui.intField(this + "scale", x + labelW, cy, 110, row, uiScale, 0, 250);
        cy += row + 2;
        ui.textIn("0 — подобрать по экрану; 100 — системный размер", x, cy, w, 18,
                Theme.TEXT_DISABLED, Ui.LEFT, Theme.FONT_SMALL, false);
        cy += 24;

        label(ui, "Хранить версий на вкладку", x, cy, labelW, row);
        backupKeep = ui.intField(this + "keep", x + labelW, cy, 110, row, backupKeep, 0, 500);
        cy += row + 6;

        label(ui, "Автокопия каждые, минут", x, cy, labelW, row);
        autoMinutes = ui.intField(this + "auto", x + labelW, cy, 110, row, autoMinutes, 0, 120);
        cy += row + 10;

        if (ui.checkbox(x, cy, "Копия при каждом сохранении", backupOnSave)) backupOnSave = !backupOnSave;
        cy += 28;
        if (ui.checkbox(x, cy, "Показывать изменения перед сохранением", reviewBeforeSave))
            reviewBeforeSave = !reviewBeforeSave;
        cy += 28;
        if (ui.checkbox(x, cy, "Перечитывать файл, изменённый другим приложением", watchFile))
            watchFile = !watchFile;
        cy += 34;

        Platform p = Platform.get();
        boolean access = p.hasStorageAccess();
        ui.textIn(access ? "Доступ к памяти телефона: есть" : "Доступ к памяти телефона: только свой каталог",
                x, cy, w - 150, row, access ? Theme.SUCCESS : Theme.WARNING, Ui.LEFT, Theme.FONT_SMALL, false);
        if (!access && ui.button(x + w - 140, cy, 140, row, "Дать доступ", Ui.NORMAL, true))
            p.requestStorageAccess();

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
        c.watchFile = watchFile;
        c.save();
        if (onSaved != null) onSaved.run();
        close();
    }
}
