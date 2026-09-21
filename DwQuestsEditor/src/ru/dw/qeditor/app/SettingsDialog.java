package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;

import java.io.File;

/**
 * Настройки: чем запускать Claude CLI, сколько держать версий вкладки и — если
 * нужно — где взять свои ресурсы вместо встроенных.
 *
 * <p>Пути можно и вписать руками, и выбрать проводником — кнопкой «…» рядом
 * с полем (её показывает tinyfd, настоящий системный диалог).
 */
public final class SettingsDialog extends Dialog {

    private static final String[] MODELS = {"sonnet", "opus", "fable", "haiku"};

    private final Runnable onSaved;

    private String iconsDir;
    private String gameDir;
    private String claudeExe;
    private int modelIndex;
    private int backupKeep;
    private int autoMinutes;
    private boolean backupOnSave;
    private boolean reviewBeforeSave;

    private SettingsDialog(Runnable onSaved) {
        super("Настройки");
        this.onSaved = onSaved;
        this.width = 760;
        this.height = 450;

        Config c = Config.get();
        iconsDir = c.iconsDir;
        gameDir = c.gameDir;
        claudeExe = c.claudeExe;
        backupKeep = c.backupKeep;
        autoMinutes = c.autoBackupMinutes;
        backupOnSave = c.backupOnSave;
        reviewBeforeSave = c.reviewBeforeSave;
        for (int i = 0; i < MODELS.length; i++)
            if (MODELS[i].equals(c.claudeModel)) modelIndex = i;
    }

    public static Dialog open(Runnable onSaved) { return new SettingsDialog(onSaved); }

    @Override
    protected void content(Ui ui, float x, float y, float w, float h) {
        float row = Theme.ROW_LARGE;
        float labelW = 230;
        float cy = y;

        iconsDir = pathRow(ui, "Папка иконок (img, json)", x, cy, w, labelW, row, iconsDir, true, null);
        if (ui.over(x, cy, labelW, row))
            ui.tooltip("Пусто — иконки берутся из самой программы");
        cy += row + 6;
        gameDir = pathRow(ui, "Папка ресурсов мода (assets)", x, cy, w, labelW, row, gameDir, true, null);
        if (ui.over(x, cy, labelW, row))
            ui.tooltip("Внутри assets/dwquests, assets/dwcore, assets/minecraft.\n"
                    + "Пусто — ресурсы берутся из самой программы");
        cy += row + 6;
        claudeExe = pathRow(ui, "Claude CLI", x, cy, w, labelW, row, claudeExe, false, "*.*");
        cy += row + 12;

        label(ui, "Модель по умолчанию", x, cy, labelW, row);
        modelIndex = Widgets.dropdown(ui, popup(), this + "model", x + labelW, cy, 160, row,
                MODELS, modelIndex);
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

    /** Поле пути с кнопкой выбора; возвращает новое значение. */
    private String pathRow(Ui ui, String caption, float x, float y, float w, float labelW, float row,
                           String value, boolean folder, String pattern) {
        label(ui, caption, x, y, labelW, row);
        float bw = 34;
        String v = ui.field(this + caption, x + labelW, y, w - labelW - bw - 6, row, value, "не задано");
        if (ui.button(x + w - bw, y, bw, row, "…")) {
            File start = value == null || value.isEmpty() ? null : new File(value);
            File picked = folder
                    ? FileDialogs.folder(caption, start)
                    : FileDialogs.openAny(caption, start, pattern, caption);
            if (picked != null) {
                v = picked.getAbsolutePath();
                ui.forgetState(this + caption);
            }
        }
        return v;
    }

    private void save() {
        Config c = Config.get();
        c.iconsDir = iconsDir.trim();
        c.gameDir = gameDir.trim();
        c.claudeExe = claudeExe.trim();
        c.claudeModel = MODELS[modelIndex];
        c.backupKeep = backupKeep;
        c.autoBackupMinutes = autoMinutes;
        c.backupOnSave = backupOnSave;
        c.reviewBeforeSave = reviewBeforeSave;
        c.save();
        if (onSaved != null) onSaved.run();
        close();
    }
}
