package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.Enchants;
import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.NbtLite;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.util.ForgeName;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Редактор предмета с произвольным NBT — в том числе награды-скрипта
 * {@code customnpcs:scripted_item}.
 *
 * <p>Схема NBT у скриптовых предметов зависит от сборки сервера, поэтому здесь
 * два уровня: удобные поля (имя, лор, зачарования, неразрушимость, мета и
 * количество) и полный текст NBT, который можно править руками с проверкой
 * синтаксиса. Правку подхватывает та сторона, где её сделали.
 *
 * <p>Зачарования в NBT — это голые номера ({@code ench:[0:{id:16s,lvl:5s}]}),
 * поэтому рядом с номером показывается имя из {@link Enchants}; для модовых
 * зачарований номер вписывается руками.
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
    private boolean unbreakable;
    /** Зачарования: пары {номер, уровень}. */
    private final List<int[]> ench = new ArrayList<>();

    /** Прошлые значения полей: по ним видно, какую сторону перенесли. */
    private String lastName = "";
    private String lastLore = "";
    private String lastNbt;
    private String lastExtras = "";

    private NbtItemDialog(String title, String reward, String defaultId,
                          Consumer<String> onOk, Consumer<Dialog> openDialog) {
        super(title);
        this.onOk = onOk;
        this.openDialog = openDialog;
        this.width = 700;
        this.height = 700;

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
        if (ui.over(x + labelW + idW + pickW + 12, cy, countW, row)) ui.tooltip("Количество");
        cy += row + 6;

        // ---- мета (она же прочность) и неразрушимость
        ui.textIn("Мета / прочность", x, cy, labelW, row, Theme.TEXT_MUTED, Ui.LEFT);
        int meta = metaOf(id);
        int newMeta = ui.intField(this + "meta", x + labelW, cy, 90, row, meta, 0, 32767);
        if (newMeta != meta) id = withMeta(id, newMeta);
        if (ui.over(x + labelW, cy, 90, row))
            ui.tooltip("В 1.7.10 прочность и вариант предмета — это одно и то же:\n"
                    + "число после второго двоеточия в id");
        if (ui.checkbox(x + labelW + 104, cy + (row - 17) / 2f, "не ломается (Unbreakable)", unbreakable))
            unbreakable = !unbreakable;
        cy += row + 6;

        // ---- вид
        ui.textIn("Вид", x, cy, labelW, row, Theme.TEXT_MUTED, Ui.LEFT);
        String built = build();
        Icons.item(ui, built, x + labelW, cy + (row - 24) / 2f, 24);
        ui.textIn(ItemLook.name(built), x + labelW + 30, cy, w - labelW - 30, row, Theme.TEXT, Ui.LEFT);
        cy += row + 6;

        // ---- имя
        ui.textIn("Имя (display.Name)", x, cy, labelW, row, Theme.TEXT_MUTED, Ui.LEFT);
        name = ui.field(this + "name", x + labelW, cy, w - labelW, row, name, "§6Особый предмет");
        cy += row + 10;

        cy += drawEnchants(ui, x, cy, w);

        // ---- лор и NBT делят остаток по высоте
        float rest = y + h - cy - Theme.ROW_LARGE - 32;
        float loreH = Math.max(46, rest * 0.3f);
        float nbtH = Math.max(70, rest - loreH - 26);

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

    /** Таблица зачарований; возвращает занятую высоту. */
    private float drawEnchants(Ui ui, float x, float y, float w) {
        float row = 24;
        Widgets.caption(ui, book() ? "Зачарования книги (StoredEnchantments)" : "Зачарования (ench)", x, y);
        if (ui.button(x + w - 100, y - 4, 100, row, "Добавить", Ui.NORMAL, ench.size() < 12))
            ench.add(new int[]{0, 1});

        float cy = y + 18;
        String[] names = enchNames();
        for (int i = 0; i < ench.size(); i++) {
            int[] e = ench.get(i);
            float bx = x;

            int picked = Widgets.dropdown(ui, popup(), this + "ench" + i, bx, cy, 220, row,
                    names, indexOfEnch(e[0]));
            if (picked >= 0 && picked < names.length) {
                int newID = Enchants.idOf(names[picked]);
                if (newID >= 0) e[0] = newID;
            }
            bx += 226;

            ui.textIn("№", bx, cy, 16, row, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
            e[0] = ui.intField(this + "enchid" + i, bx + 16, cy, 66, row, e[0], 0, 32767);
            bx += 86;

            ui.textIn("ур.", bx, cy, 24, row, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
            e[1] = ui.intField(this + "enchlvl" + i, bx + 24, cy, 60, row, e[1], 1, 255);
            bx += 88;

            if (e[1] > Enchants.maxLevel(e[0]))
                ui.textIn("выше обычного предела", bx, cy, w - (bx - x) - 34, row,
                        Theme.TEXT_DISABLED, Ui.LEFT, Theme.FONT_SMALL, false);

            if (ui.button(x + w - 30, cy, 30, row, "✕", Ui.DANGER, true)) {
                ench.remove(i);
                ui.forgetState(this + "enchid" + i);
                ui.forgetState(this + "enchlvl" + i);
                break;
            }
            cy += row + 4;
        }
        return cy - y + 6;
    }

    private String[] enchNames() {
        List<Enchants.Ench> all = Enchants.all();
        String[] out = new String[all.size()];
        for (int i = 0; i < all.size(); i++) out[i] = all.get(i).ru() + "  (" + all.get(i).id() + ")";
        return out;
    }

    private int indexOfEnch(int id) {
        List<Enchants.Ench> all = Enchants.all();
        for (int i = 0; i < all.size(); i++) if (all.get(i).id() == id) return i;
        return -1;
    }

    // ------------------------------------------------------------------ синхронизация

    /** Перенести правку в ту сторону, где её сделали. */
    private void sync() {
        boolean nbtChanged = !nbt.equals(lastNbt);
        boolean fieldsChanged = !name.equals(lastName) || !lore.equals(lastLore)
                || !extras().equals(lastExtras);
        if (nbtChanged) {
            lastNbt = nbt;
            readFieldsFromNbt();
        } else if (fieldsChanged) {
            lastName = name;
            lastLore = lore;
            lastExtras = extras();
            writeFieldsToNbt();
        }
    }

    /** Слепок «прочих» полей — по нему видно, что их правили. */
    private String extras() {
        StringBuilder sb = new StringBuilder(unbreakable ? "U" : "-");
        for (int[] e : ench) sb.append(';').append(e[0]).append(':').append(e[1]);
        return sb.toString();
    }

    private boolean book() {
        return id != null && id.startsWith("minecraft:enchanted_book");
    }

    private String enchKey() {
        return book() ? "StoredEnchantments" : "ench";
    }

    private void readFieldsFromNbt() {
        String text = nbt.trim();
        if (text.isEmpty()) {
            name = "";
            lore = "";
            ench.clear();
            unbreakable = false;
            lastName = "";
            lastLore = "";
            lastExtras = extras();
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

        unbreakable = c.i("Unbreakable", 0) != 0;
        ench.clear();
        NbtLite.ListTag list = c.list(enchKey());
        if (list == null) list = c.list(book() ? "ench" : "StoredEnchantments");
        if (list != null) {
            for (Object o : list) {
                if (!(o instanceof NbtLite.Compound)) continue;
                NbtLite.Compound e = (NbtLite.Compound) o;
                ench.add(new int[]{e.i("id", 0), Math.max(1, e.i("lvl", 1))});
            }
        }

        lastName = name;
        lastLore = lore;
        lastExtras = extras();
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

        if (unbreakable) c.put("Unbreakable", NbtLite.Num.of(1, 'b'));
        else c.remove("Unbreakable");

        // зачарования лежат в своём списке; у книги — в другом, и лишний
        // из двух убираем, чтобы не осталось следов от прошлого предмета
        c.remove("ench");
        c.remove("StoredEnchantments");
        if (!ench.isEmpty()) {
            NbtLite.ListTag list = new NbtLite.ListTag();
            list.indexed = true;
            for (int[] e : ench) {
                NbtLite.Compound one = new NbtLite.Compound();
                one.put("id", NbtLite.Num.of(e[0], 's'));
                one.put("lvl", NbtLite.Num.of(e[1], 's'));
                list.add(one);
            }
            c.put(enchKey(), list);
        }

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

    /** Мета из «mod:name:meta»; 0, если её нет. */
    private static int metaOf(String id) {
        ForgeName fn = ForgeName.parse(id == null ? "" : id.trim());
        return fn.meta < 0 ? 0 : fn.meta;
    }

    private static String withMeta(String id, int meta) {
        ForgeName fn = ForgeName.parse(id == null ? "" : id.trim());
        String base = fn.mod + ":" + fn.name;
        return meta == 0 ? base : base + ":" + meta;
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
