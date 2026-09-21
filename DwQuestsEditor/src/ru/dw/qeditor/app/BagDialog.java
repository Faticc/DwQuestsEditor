package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.Gifts;
import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.PlasticBag;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.util.ForgeName;

import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * Пластиковый пакет MFR: пять слотов, настроек у пакета нет. Слоты правятся так
 * же, как в гифте: ЛКМ — выбрать предмет, гифт в слоте открывается своим окном,
 * Shift+ЛКМ кладёт новый гифт, ПКМ убирает, колесо меняет число.
 */
public final class BagDialog extends Dialog {

    private static final float CELL = 64;
    private static final float GAP = 12;
    private static final int MAX_STACK = 64;

    private final String base;
    private final String[] slots;
    private final Consumer<String> onOk;
    private final Consumer<Dialog> openDialog;

    private BagDialog(String reward, Consumer<String> onOk, Consumer<Dialog> openDialog) {
        super("Пластиковый пакет (MineFactoryReloaded)");
        this.base = reward == null || reward.isEmpty() ? PlasticBag.ID : reward;
        this.slots = PlasticBag.slots(base);
        this.onOk = onOk;
        this.openDialog = openDialog;
        this.width = PlasticBag.SLOTS * (CELL + GAP) - GAP + 32 + 40;
        this.height = 40 + 12 + 30 + CELL + 30 + 20 + 16 + Theme.ROW_LARGE + 24;
    }

    /** Открыть редактор пакета; готовая строка приходит в {@code onOk}. */
    public static Dialog edit(String reward, Consumer<String> onOk, Consumer<Dialog> openDialog) {
        return new BagDialog(reward, onOk, openDialog);
    }

    @Override
    protected void content(Ui ui, float x, float y, float w, float h) {
        ui.textIn("Что лежит в пакете — игрок достанет всё сразу", x, y, w, 20,
                Theme.TEXT_MUTED, Ui.LEFT);

        float rowW = PlasticBag.SLOTS * (CELL + GAP) - GAP;
        float sx0 = x + Math.round((w - rowW) / 2);
        float sy = y + 30;
        for (int i = 0; i < PlasticBag.SLOTS; i++) {
            float sx = sx0 + i * (CELL + GAP);
            boolean hovered = ui.over(sx, sy, CELL, CELL);
            ui.roundRectBorder(sx, sy, CELL, CELL, Theme.RADIUS_SMALL,
                    hovered ? Theme.HOVER : Theme.SURFACE_2, hovered ? Theme.ACCENT : Theme.BORDER, 1);
            String item = slots[i];
            if (item != null) {
                Icons.item(ui, item, sx + 12, sy + 8, 40);
                int count = ItemLook.count(item);
                if (count > 1)
                    ui.textIn(String.valueOf(count), sx, sy + CELL - 20, CELL - 6, 18, Theme.TEXT, Ui.RIGHT);
                ui.textIn(ItemLook.name(item), sx - GAP / 2, sy + CELL + 4, CELL + GAP, 18,
                        Theme.TEXT_MUTED, Ui.CENTER, Theme.FONT_SMALL, false);
            } else {
                ui.textIn("пусто", sx, sy, CELL, CELL, Theme.TEXT_DISABLED, Ui.CENTER, Theme.FONT_SMALL, false);
            }
            if (hovered) {
                ui.cursor(GlWindow.CURSOR_HAND);
                ui.tooltip(item == null ? "Слот " + (i + 1) + ": пусто" : ItemLook.name(item) + " × "
                        + ItemLook.count(item) + "\n" + item);
                input(ui, i);
            }
        }

        ui.textIn("ЛКМ — предмет, Shift+ЛКМ — гифт, ПКМ — убрать, колесо — число",
                x, sy + CELL + 30, w, 20, Theme.TEXT_DISABLED, Ui.CENTER, Theme.FONT_SMALL, false);

        int pressed = buttons(ui, x, y + h - Theme.ROW_LARGE, w, "Отмена", "Готово");
        boolean enter = ui.keyPressed(GLFW_KEY_ENTER) || ui.keyPressed(GLFW_KEY_KP_ENTER);
        if (pressed == 1 || enter) {
            onOk.accept(PlasticBag.build(base, slots));
            close();
        } else if (pressed == 0) {
            close();
        }
    }

    private void input(Ui ui, int i) {
        if (ui.in().scrollY != 0 && slots[i] != null) {
            ForgeName fn = ForgeName.parse(slots[i]);
            int dir = ui.in().scrollY > 0 ? 1 : -1;
            int count = Math.max(1, Math.min(MAX_STACK, (fn.hasCount ? fn.count : 1) + dir));
            slots[i] = fn.withCount(count);
            ui.in().consumeScroll();
        }
        if (ui.in().pressed(GLFW_MOUSE_BUTTON_RIGHT)) {
            slots[i] = null;
            return;
        }
        if (!ui.in().leftPressed() || openDialog == null) return;
        String current = slots[i];
        // гифт в пакете правится своим окном — иначе до его содержимого не добраться
        if (Gifts.isGift(current) || ui.in().shift()) {
            String gift = Gifts.isGift(current) ? current : "DwQuests:Gift:0";
            openDialog.accept(GiftDialog.edit(gift, s -> slots[i] = s, openDialog));
        } else if (PlasticBag.isBag(current)) {
            openDialog.accept(BagDialog.edit(current, s -> slots[i] = s, openDialog));
        } else {
            openDialog.accept(ItemPicker.open(current == null ? "minecraft:diamond" : current,
                    s -> slots[i] = capped(s)));
        }
    }

    /** В слоте пакета одна стопка — больше 64 не кладём. */
    private static String capped(String item) {
        if (item == null) return null;
        ForgeName fn = ForgeName.parse(item);
        return fn.hasCount && fn.count > MAX_STACK ? fn.withCount(MAX_STACK) : item;
    }
}
