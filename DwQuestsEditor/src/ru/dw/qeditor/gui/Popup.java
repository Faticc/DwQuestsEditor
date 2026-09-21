package ru.dw.qeditor.gui;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;

/**
 * Всплывающий список: меню в строке меню, меню по правой кнопке и раскрытый
 * выпадающий список — всё это он.
 *
 * <p>Живёт дольше кадра (открытым его оставляет прошлый кадр), поэтому это
 * обычный объект, а не функция. Рисуется последним и до отрисовки забирает
 * ввод себе: иначе щелчок по пункту меню попал бы ещё и в то, что под меню.
 */
public final class Popup {

    /** Пункт: обычный, с галочкой, недоступный или разделитель. */
    public record Item(String label, String accel, boolean separator, boolean checkbox,
                       boolean checked, boolean enabled) {

        public static Item of(String label) { return new Item(label, null, false, false, false, true); }

        public static Item of(String label, String accel) {
            return new Item(label, accel, false, false, false, true);
        }

        public static Item check(String label, boolean checked) {
            return new Item(label, null, false, true, checked, true);
        }

        public static Item disabled(String label) {
            return new Item(label, null, false, false, false, false);
        }

        public static Item line() { return new Item(null, null, true, false, false, true); }
    }

    private Object owner;
    private final List<Item> items = new ArrayList<>();
    private float x, y;
    private float minWidth;
    /** Куда открывать вверх, если внизу не помещается. */
    private boolean flipUp;
    private int picked = -1;
    private Object pickedOwner;
    /** Кадр, в котором меню открыли: щелчок, открывший его, не должен закрыть. */
    private boolean justOpened;

    public boolean isOpen() { return owner != null; }

    public boolean isOpen(Object who) { return owner != null && owner.equals(who); }

    public Object owner() { return owner; }

    public void open(Object owner, float x, float y, List<Item> items, float minWidth) {
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.minWidth = minWidth;
        this.items.clear();
        this.items.addAll(items);
        this.justOpened = true;
        this.picked = -1;
    }

    public void close() {
        owner = null;
        items.clear();
    }

    /** Забрать выбор, сделанный в этом кадре (и только для своего владельца). */
    public int takePick(Object who) {
        if (picked >= 0 && who.equals(pickedOwner)) {
            int p = picked;
            picked = -1;
            pickedOwner = null;
            return p;
        }
        return -1;
    }

    /** Рисуется последним; возвращает номер выбранного пункта либо −1. */
    public int draw(Ui ui) {
        if (!isOpen()) return -1;

        float rowH = Theme.ROW;
        float sepH = 7;
        float padding = 5;
        float width = minWidth;
        for (Item it : items) {
            if (it.separator()) continue;
            float w = ui.textWidth(it.label()) + 40;
            if (it.accel() != null) w += ui.textWidth(it.accel(), Theme.FONT_SMALL, false) + 24;
            width = Math.max(width, w);
        }
        float height = padding * 2;
        for (Item it : items) height += it.separator() ? sepH : rowH;

        float px = Math.min(x, ui.width() - width - 4);
        float py = flipUp || y + height > ui.height() ? Math.max(4, y - height) : y;

        ui.beginOverlay();
        // тень под меню: мягкого размытия нет, но подложка отделяет его от фона
        ui.roundRect(px + 2, py + 3, width, height, Theme.RADIUS, 0x60000000);
        ui.roundRectBorder(px, py, width, height, Theme.RADIUS, 0xFF1C2430, Theme.BORDER_STRONG, 1);

        int result = -1;
        float iy = py + padding;
        for (int i = 0; i < items.size(); i++) {
            Item it = items.get(i);
            if (it.separator()) {
                ui.hSeparator(px + 8, iy + sepH / 2, width - 16);
                iy += sepH;
                continue;
            }
            boolean hovered = it.enabled() && ui.over(px, iy, width, rowH);
            if (hovered) ui.rect(px + 3, iy, width - 6, rowH, Theme.HOVER);
            int color = it.enabled() ? Theme.TEXT : Theme.TEXT_DISABLED;
            if (it.checkbox() && it.checked()) {
                ui.line(px + 11, iy + rowH / 2, px + 14, iy + rowH / 2 + 3.5f, 2, Theme.ACCENT);
                ui.line(px + 14, iy + rowH / 2 + 3.5f, px + 20, iy + rowH / 2 - 4f, 2, Theme.ACCENT);
            }
            ui.text(it.label(), px + 26, iy + (rowH - ui.lineHeight()) / 2f, color);
            if (it.accel() != null)
                ui.textIn(it.accel(), px, iy, width - 12, rowH, Theme.TEXT_DISABLED, Ui.RIGHT,
                        Theme.FONT_SMALL, false);
            if (hovered && ui.in().leftPressed()) result = i;
            iy += rowH;
        }

        boolean outside = ui.in().leftPressed() && !ui.over(px, py, width, height);
        ui.endOverlay();

        if (result >= 0) {
            picked = result;
            pickedOwner = owner;
            close();
        } else if (!justOpened && (outside || ui.keyPressed(GLFW_KEY_ESCAPE))) {
            close();
        }
        justOpened = false;
        return result;
    }

    /** Меню открыто — нижние слои в этом кадре ввод не получают. */
    public void blockIfOpen(Ui ui) {
        if (isOpen()) ui.blockInput();
    }
}
