package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.NbtLite;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.util.ForgeName;

import java.util.function.Consumer;

/**
 * Редактор предмета с произвольным NBT — в том числе награды-скрипта
 * {@code customnpcs:scripted_item}.
 *
 * <p>Схема NBT у скриптовых предметов зависит от сборки сервера, поэтому здесь
 * два уровня: удобные поля для имени и лора (display.Name / display.Lore) и
 * полный текст NBT, который можно править руками с проверкой синтаксиса.
 *
 * <p>В отличие от версии на Swing, поля и текст NBT сверяются не по событиям
 * документа, а раз в кадр: что изменилось — то и переносится в другую сторону.
 */
public final class NbtItemDialog extends Dialog {

    private final Consumer<String> onOk;
    /** Куда открыть выбор предмета — его показывает окно поверх этого. */
    private final Consumer<Dialog> openDialog;

    private String id;
    private int count;
    private String name = "";
    private String lore = "";
    private String nbt;
    private String status = " ";

    /** Прошлые значения полей: по ним видно, какую сторону перенесли. */
    private String lastName = "";
    private String lastLore = "";
    private String lastNbt;

    private NbtItemDialog(String title, String reward, String defaultId,
                          Consumer<String> onOk, Consumer<Dialog> openDialog) {
        super(title);
        this.onOk = onOk;
        this.openDialog = openDialog;
        this.width = 640;
        this.height = 620;

        ForgeName fn = ForgeName.parse(reward == null || reward.isEmpty() ? defaultId : reward);
        this.id = fn.id;
        this.count = fn.hasCount ? Math.max(1, fn.count) : 1;
        this.nbt = fn.nbt == null ? "" : fn.nbt;
        this.lastNbt = nbt;
        readFieldsFromNbt();
    }

    /** Редактор произвольного предмета с NBT. */
    public static Dialog edit(String reward, Consumer<String> onOk, Consumer<Dialog> openDialog) {
        return new NbtItemDialog("Предмет с NBT", reward, "minecraft:diamond", onOk, openDialog);
    }

    /** Редактор награды-скрипта CustomNPCs. */
    public static Dialog editScript(String reward, Consumer<String> onOk, Consumer<Dialog> openDialog) {
        return new NbtItemDialog("Награда-скрипт (customnpcs:scripted_item)",
                reward, "customnpcs:scripted_item", onOk, openDialog);
    }

    @Override
    protected void content(Ui ui, float x, float y, float w, float h) {
        float row = Theme.ROW_LARGE;
        float labelW = 150;
        float cy = y;

        // ---- предмет и количество
        ui.textIn("Предмет", x, cy, labelW, row, Theme.TEXT_MUTED, Ui.LEFT);
        float pickW = 34;
        float countW = 70;
        float idW = w - labelW - pickW - countW - 16;
        id = ui.field(this + "id", x + labelW, cy, idW, row, id, "mod:item");
        if (ui.button(x + labelW + idW + 4, cy, pickW, row, "…") && openDialog != null)
            openDialog.accept(ItemPicker.open(id, s -> id = ForgeName.parse(s).id));
        count = ui.intField(this + "count", x + labelW + idW + pickW + 12, cy, countW, row, count, 1, 9999);
        cy += row + 8;

        // ---- вид
        ui.textIn("Вид", x, cy, labelW, row, Theme.TEXT_MUTED, Ui.LEFT);
        String built = build();
        Icons.item(ui, built, x + labelW, cy + (row - 24) / 2f, 24);
        ui.textIn(ItemLook.name(built), x + labelW + 30, cy, w - labelW - 30, row, Theme.TEXT, Ui.LEFT);
        cy += row + 8;

        // ---- имя
        ui.textIn("Имя (display.Name)", x, cy, labelW, row, Theme.TEXT_MUTED, Ui.LEFT);
        name = ui.field(this + "name", x + labelW, cy, w - labelW, row, name, "§6Особый предмет");
        cy += row + 10;

        // ---- лор и NBT делят остаток по высоте
        float rest = y + h - cy - Theme.ROW_LARGE - 32;
        float loreH = Math.max(50, rest * 0.32f);
        float nbtH = Math.max(80, rest - loreH - 26);

        Widgets.caption(ui, "Лор (display.Lore) — по строке на строку", x, cy);
        lore = Widgets.textArea(ui, this + "lore", x, cy + 14, w, loreH, lore, null);
        cy += loreH + 20;

        Widgets.caption(ui, "NBT целиком", x, cy);
        nbt = Widgets.textArea(ui, this + "nbt", x, cy + 14, w, nbtH, nbt, "{display:{Name:\"…\"}}");
        cy += nbtH + 20;

        sync();

        // ---- строка состояния и кнопки
        ui.textIn(status, x, y + h - Theme.ROW_LARGE, w - 340, Theme.ROW_LARGE,
                status.startsWith("Ошибка") || status.contains("не разобрал")
                        ? Theme.DANGER : Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        float bx = x + w - 340;
        if (ui.button(bx, y + h - Theme.ROW_LARGE, 120, Theme.ROW_LARGE, "Проверить NBT")) normalize();
        int pressed = buttons(ui, x + 126, y + h - Theme.ROW_LARGE, w - 126, "Отмена", "Готово");
        if (pressed == 1) accept();
        else if (pressed == 0) close();
    }

    // ------------------------------------------------------------------ синхронизация

    /** Перенести правку в ту сторону, где её сделали. */
    private void sync() {
        boolean nbtChanged = !nbt.equals(lastNbt);
        boolean fieldsChanged = !name.equals(lastName) || !lore.equals(lastLore);
        if (nbtChanged) {
            lastNbt = nbt;
            readFieldsFromNbt();
        } else if (fieldsChanged) {
            lastName = name;
            lastLore = lore;
            writeFieldsToNbt();
        }
    }

    private void readFieldsFromNbt() {
        String text = nbt.trim();
        if (text.isEmpty()) {
            name = "";
            lore = "";
            lastName = "";
            lastLore = "";
            status = " ";
            return;
        }
        NbtLite.Compound c = NbtLite.parse(text);
        if (c == null) {
            status = "NBT не разобрался";
            return;
        }
        status = " ";
        NbtLite.Compound display = c.comp("display");
        name = display == null ? "" : nz(display.string("Name"));
        Object loreTag = display == null ? null : display.get("Lore");
        if (loreTag instanceof NbtLite.ListTag) {
            StringBuilder sb = new StringBuilder();
            for (Object o : (NbtLite.ListTag) loreTag) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(String.valueOf(o));
            }
            lore = sb.toString();
        } else {
            lore = loreTag == null ? "" : String.valueOf(loreTag);
        }
        lastName = name;
        lastLore = lore;
    }

    private void writeFieldsToNbt() {
        String text = nbt.trim();
        NbtLite.Compound c = text.isEmpty() ? new NbtLite.Compound() : NbtLite.parse(text);
        if (c == null) {
            status = "NBT не разобрался — поля выше не применены";
            return;
        }
        NbtLite.Compound display = c.comp("display");
        if (display == null) {
            display = new NbtLite.Compound();
            c.put("display", display);
        }
        if (name.isEmpty()) display.remove("Name");
        else display.put("Name", name);

        if (lore.isEmpty()) {
            display.remove("Lore");
        } else if (lore.contains("\n")) {
            NbtLite.ListTag list = new NbtLite.ListTag();
            for (String line : lore.split("\n")) list.add(line);
            display.put("Lore", list);
        } else {
            display.put("Lore", lore);
        }
        if (display.isEmpty()) c.remove("display");

        nbt = c.isEmpty() ? "" : NbtLite.write(c);
        lastNbt = nbt;
        status = " ";
    }

    private void normalize() {
        String text = nbt.trim();
        if (text.isEmpty()) {
            status = "NBT пуст — это допустимо";
            return;
        }
        NbtLite.Compound c = NbtLite.parse(text);
        if (c == null) {
            status = "Ошибка: NBT не разобрался";
            return;
        }
        nbt = NbtLite.write(c);
        lastNbt = nbt;
        readFieldsFromNbt();
        status = "NBT в порядке";
    }

    private String build() {
        String nbtText = nbt.trim();
        return ForgeName.build(id.trim(), count, nbtText.isEmpty() ? null : nbtText);
    }

    private void accept() {
        String nbtText = nbt.trim();
        if (!nbtText.isEmpty() && NbtLite.parse(nbtText) == null) {
            status = "Ошибка: NBT не разобрался, исправь перед сохранением";
            return;
        }
        onOk.accept(build());
        close();
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
