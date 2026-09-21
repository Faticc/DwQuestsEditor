package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.Gifts;
import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.assets.NbtLite;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.render.GuiPainter;
import ru.dw.qeditor.util.ForgeName;

import java.awt.image.BufferedImage;
import java.util.function.Consumer;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_KP_ENTER;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_RIGHT;

/**
 * Редактор гифта в том же виде, что и в игре (GuiContainerGift + IconSelector):
 * карусель текстур и слоёв, поля имени и лора, переключатель типа выдачи,
 * диапазон случайной выдачи и сетка предметов 3×9.
 *
 * <p>Результат — строка награды {@code DwQuests:Gift:<мета>{<NBT>}} с тем же
 * набором тегов, что пишет мод (Layers, DropType, display.Name, display.Lore,
 * MinRandom/MaxRandom, Items со Slot).
 *
 * <p>Инвентарь игрока из игрового окна не воспроизводится — в редакторе его
 * нет; вместо него внизу подсказка. Слоты заполняются выбором предмета, а не
 * перетаскиванием.
 */
public final class GiftDialog extends Dialog {

    /** Размеры окна мода. */
    private static final int GUI_W = 256;
    private static final int GUI_H = 190;
    private static final int SLOT = 18;
    private static final int GIFT_SLOTS = 27;

    private static final int FIELD_BACK = -871296751;
    private static final int FIELD_HOVER = -867941308;

    private final Consumer<String> onOk;
    private final Consumer<Dialog> openDialog;

    /** Сколько логических точек на пиксель окна мода. */
    private int scale = 2;

    private Defs.GiftTexture texture = Defs.GiftTexture.Present;
    private int[] layers = new int[]{0, 0};
    private final String[] slots = new String[GIFT_SLOTS];

    private final Field nameField = new Field(120, 8, 129, 18, 64, false);
    private final Field loreField = new Field(120, 30, 129, 18, 512, false);
    private final Field randomField = new Field(209, 52, 40, 18, 5, true);
    private Defs.DropType dropType = Defs.DropType.DEFAULT;
    private Field focused;

    private boolean resetPrefix = true;
    /** Курсор в координатах окна мода — им подсвечиваются кнопки и поля. */
    private float mouseX = -100, mouseY = -100;

    private GiftDialog(String reward, Consumer<String> onOk, Consumer<Dialog> openDialog) {
        super("Гифт (DwQuests:Gift)");
        this.onOk = onOk;
        this.openDialog = openDialog;
        nameField.text = "Базовый Гифт";
        load(reward);
        resize();
    }

    /** Открыть редактор гифта; готовая строка приходит в {@code onOk}. */
    public static Dialog edit(String reward, Consumer<String> onOk, Consumer<Dialog> openDialog) {
        return new GiftDialog(reward, onOk, openDialog);
    }

    private void resize() {
        this.width = GUI_W * scale + 32;
        this.height = GUI_H * scale + 40 + 24 + Theme.ROW_LARGE * 2 + 24;
    }

    // ------------------------------------------------------------------ кадр

    @Override
    protected void content(Ui ui, float x, float y, float w, float h) {
        float gw = GUI_W * scale;
        float gh = GUI_H * scale;
        float gx = x + Math.round((w - gw) / 2);
        float gy = y;

        mouseX = (ui.mx() - gx) / scale;
        mouseY = (ui.my() - gy) / scale;
        boolean inside = ui.over(gx, gy, gw, gh);

        handleInput(ui, inside);

        GuiPainter p = ui.painter();
        ui.pushClip(gx, gy, gw, gh);
        p.push();
        p.translate(gx, gy);
        p.scale(scale / ui.scale());
        drawGui(p, ui);
        p.pop();
        ui.popClip();

        // ---- подвал: предпросмотр, масштаб, кнопки
        float fy = y + gh + 12;
        String reward = buildReward();
        ui.textIn("Предпросмотр:", x, fy, 100, 32, Theme.TEXT_MUTED, Ui.LEFT);
        Icons.item(ui, reward, x + 104, fy + 4, 24);
        if (ui.over(x + 104, fy, 28, 32)) ui.tooltip(nameField.text);

        if (ui.button(x + 140, fy + 3, 30, 26, "−") && scale > 1) {
            scale--;
            resize();
        }
        ui.textIn(scale + "×", x + 172, fy + 3, 30, 26, Theme.TEXT_MUTED, Ui.CENTER, Theme.FONT_SMALL, false);
        if (ui.button(x + 204, fy + 3, 30, 26, "+") && scale < 4) {
            scale++;
            resize();
        }

        fy += 38;
        if (ui.checkbox(x, fy + 6, "Ставить §r перед именем (как в игре)", resetPrefix))
            resetPrefix = !resetPrefix;

        int pressed = buttons(ui, x, y + h - Theme.ROW_LARGE, w, "Отмена", "Готово");
        boolean enter = focused == null && (ui.keyPressed(GLFW_KEY_ENTER) || ui.keyPressed(GLFW_KEY_KP_ENTER));
        if (pressed == 1 || enter) {
            onOk.accept(buildReward());
            close();
        } else if (pressed == 0) {
            close();
        }
    }

    // ------------------------------------------------------------------ отрисовка

    private void drawGui(GuiPainter p, Ui ui) {
        p.rectOpaque(0, 0, GUI_W, GUI_H, 0xFF0D0D12);
        p.guiBackground(0, 0, GUI_W, GUI_H);

        drawIconSelector(p);

        p.text("Имя: ", 95, 13, -1, true);
        p.text("Лор: ", 95, 35, -1, true);
        p.text("Тип: ", 95, 57, -1, true);

        nameField.draw(p, ui);
        loreField.draw(p, ui);

        int typeWidth = dropType == Defs.DropType.RANDOM ? 85 : 129;
        drawButton(p, 120, 52, typeWidth, 18, dropType.ru);
        if (dropType == Defs.DropType.RANDOM) randomField.draw(p, ui);

        // сетка гифта 3×9
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                float sx = 47 + j * SLOT;
                float sy = 115 + i * SLOT;
                p.slot(sx, sy);
                String item = slots[i * 9 + j];
                if (item != null) {
                    p.item(item, sx + 1, sy + 1);
                    int count = ItemLook.count(item);
                    if (count > 1) {
                        String c = String.valueOf(count);
                        p.text(c, sx + 18 - McFont.get().width(c), sy + 10, -1, true);
                    }
                }
            }
        }

        p.text("§7ЛКМ — предмет, Shift+ЛКМ — гифт, ПКМ — убрать, колесо — число",
                8, GUI_H - 12, -1, true);
    }

    /** IconSelector: колонка текстур и по колонке на каждый слой, центр — выбранное. */
    private void drawIconSelector(GuiPainter p) {
        float x = 8, y = 8;
        for (int i = 0; i < 5; i++) {
            if (i == 2) p.slot(x, y + i * SLOT, -3342388, -11141291, -16742400);
            else p.slot(x, y + i * SLOT);
        }
        for (int pass = 0; pass < layers.length; pass++) {
            for (int j = 0; j < 5; j++) {
                float sx = x + 22 + pass * 22;
                if (j == 2) p.slot(sx, y + j * SLOT, -3342388, -11141291, -16742400);
                else p.slot(sx, y + j * SLOT);
            }
        }

        Defs.GiftTexture[] all = Defs.GiftTexture.values();
        for (int i = 0; i < 5; i++) {
            int textureID = texture.ordinal() + i - 2;
            if (textureID < 0 || textureID >= all.length) continue;
            Defs.GiftTexture t = all[textureID];
            int[] ids = new int[t.passCount()];
            if (i == 2) for (int pass = 0; pass < ids.length && pass < layers.length; pass++) ids[pass] = layers[pass];
            BufferedImage icon = Gifts.icon(t, ids);
            if (icon != null) p.imageSmooth(icon, x + 1, y + 1 + i * SLOT, 16, 16);
        }

        for (int pass = 0; pass < layers.length; pass++) {
            for (int i = 0; i < 5; i++) {
                int iconID = layers[pass] + i - 2;
                if (iconID < 0 || iconID >= texture.passes[pass]) continue;
                BufferedImage layer = ModAssets.get().giftLayer(texture.name(), iconID, pass);
                if (layer != null) p.imageSmooth(layer, x + 23 + pass * 22, y + 1 + i * SLOT, 16, 16);
            }
        }
    }

    private void drawButton(GuiPainter p, float x, float y, int w, int h, String text) {
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
        p.gradient(x, y, w, h, -12036765, -14077380, -45);
        if (hovered) p.gradient(x, y, w, h, -8847402, -5701768, -45);
        int light = hovered ? -1 : 1442840575;
        p.hLine(x, y, w, light);
        p.hLine(x, y + h - 1f, w, -2013265920);
        p.vLine(x, y + 1f, h - 2, light);
        p.vLine(x + w - 1f, y + 1f, h - 2, -2013265920);
        float cx = x + (w - McFont.get().width(text)) / 2f;
        float cy = y + (h + 2 - McFont.FONT_HEIGHT) / 2f;
        int v = hovered ? 0 : 255;
        p.text(text, (int) cx, (int) cy, v << 16 | v << 8 | v, false);
    }

    // ------------------------------------------------------------------ ввод

    private void handleInput(Ui ui, boolean inside) {
        float x = mouseX, y = mouseY;

        if (inside && ui.in().scrollY != 0) {
            onWheel(x, y, ui.in().scrollY > 0 ? -1 : 1);
            ui.in().consumeScroll();
        }

        if (inside && (ui.in().leftPressed() || ui.in().pressed(GLFW_MOUSE_BUTTON_RIGHT))) {
            boolean right = !ui.in().leftPressed();
            focused = null;
            if (nameField.contains(x, y)) focused = nameField;
            else if (loreField.contains(x, y)) focused = loreField;
            else if (dropType == Defs.DropType.RANDOM && randomField.contains(x, y)) focused = randomField;

            int typeWidth = dropType == Defs.DropType.RANDOM ? 85 : 129;
            if (x >= 120 && x < 120 + typeWidth && y >= 52 && y < 70) {
                Defs.DropType[] types = Defs.DropType.values();
                dropType = types[(dropType.ordinal() + 1) % types.length];
                return;
            }

            int slotIndex = slotAt(x, y);
            if (slotIndex >= 0) {
                if (right) {
                    slots[slotIndex] = null;
                } else if (openDialog != null) {
                    String current = slots[slotIndex];
                    // гифт внутри гифта правится тем же окном, а не выбором
                    // предмета: иначе до его слоёв и содержимого не добраться
                    if (Gifts.isGift(current) || ui.in().shift()) {
                        String gift = Gifts.isGift(current) ? current : "DwQuests:Gift:0";
                        openDialog.accept(GiftDialog.edit(gift, s -> slots[slotIndex] = s, openDialog));
                    } else {
                        openDialog.accept(ItemPicker.open(
                                current == null ? "minecraft:diamond" : current,
                                s -> slots[slotIndex] = s));
                    }
                }
            }
        }

        if (focused == null) return;
        // поля мода простые: набор с конца и Backspace, как в UITextField
        ui.captureKeyboard();
        for (char c : ui.in().text().toCharArray()) {
            if (c < ' ' || c == 127) continue;
            if (focused.numeric && !(Character.isDigit(c) || c == '-')) continue;
            if (focused.text.length() >= focused.maxLength) break;
            focused.text += c;
        }
        ui.in().consumeText();
        if (ui.in().keyPressed(GLFW_KEY_BACKSPACE) && !focused.text.isEmpty())
            focused.text = focused.text.substring(0, focused.text.length() - 1);
        if (ui.in().keyPressed(GLFW_KEY_ENTER) || ui.in().keyPressed(GLFW_KEY_KP_ENTER)
                || ui.in().keyPressed(GLFW_KEY_ESCAPE)) {
            focused = null;
            // Esc закрыл поле, а не диалог
            ui.in().consumeKey(GLFW_KEY_ESCAPE);
        }
    }

    private int slotAt(float x, float y) {
        for (int i = 0; i < 3; i++) {
            for (int j = 0; j < 9; j++) {
                float sx = 47 + j * SLOT;
                float sy = 115 + i * SLOT;
                if (x >= sx && x < sx + SLOT && y >= sy && y < sy + SLOT) return i * 9 + j;
            }
        }
        return -1;
    }

    private void onWheel(float x, float y, int dir) {
        // колонка текстур
        if (x >= 8 && x < 26 && y >= 8 && y < 98) {
            Defs.GiftTexture[] all = Defs.GiftTexture.values();
            int id = Math.max(0, Math.min(all.length - 1, texture.ordinal() + dir));
            if (all[id] != texture) setTexture(all[id]);
            return;
        }
        // колонки слоёв
        for (int pass = 0; pass < layers.length; pass++) {
            float sx = 8 + 22 + pass * 22;
            if (x >= sx && x < sx + SLOT && y >= 8 && y < 98) {
                int max = texture.passes[pass] - 1;
                layers[pass] = Math.max(0, Math.min(max, layers[pass] + dir));
                return;
            }
        }
        // количество в слоте
        int slotIndex = slotAt(x, y);
        if (slotIndex >= 0 && slots[slotIndex] != null) {
            ForgeName fn = ForgeName.parse(slots[slotIndex]);
            int count = Math.max(1, (fn.hasCount ? fn.count : 1) - dir);
            slots[slotIndex] = fn.withCount(count);
        }
    }

    private void setTexture(Defs.GiftTexture t) {
        texture = t == null ? Defs.GiftTexture.Present : t;
        layers = new int[texture.passCount()];
    }

    // ------------------------------------------------------------------ NBT

    private void load(String reward) {
        ForgeName fn = ForgeName.parse(reward == null || reward.isEmpty() ? "DwQuests:Gift:0" : reward);
        Defs.GiftTexture tex = Defs.GiftTexture.of(fn.meta < 0 ? 0 : fn.meta);
        setTexture(tex);
        int[] v = Gifts.layers(fn.nbt, tex);
        for (int i = 0; i < layers.length; i++)
            layers[i] = Math.max(0, Math.min(tex.passes[i] - 1, v != null && i < v.length ? v[i] : 0));

        NbtLite.Compound nbt = fn.nbt == null ? null : NbtLite.parse(fn.nbt);
        if (nbt == null) {
            nameField.text = "Базовый Гифт";
            loreField.text = "";
            dropType = Defs.DropType.DEFAULT;
            return;
        }

        NbtLite.Compound display = nbt.comp("display");
        nameField.text = display == null ? "Базовый Гифт" : nz(display.string("Name"));
        loreField.text = display == null ? "" : nz(display.string("Lore"));
        dropType = Defs.DropType.of(nbt.string("DropType"));
        int min = nbt.i("MinRandom", 1);
        int max = nbt.i("MaxRandom", 1);
        randomField.text = min == max ? String.valueOf(max) : min + "-" + max;

        NbtLite.ListTag list = nbt.list("Items");
        if (list == null) return;
        int fallback = 0;
        for (Object o : list) {
            if (!(o instanceof NbtLite.Compound)) continue;
            NbtLite.Compound item = (NbtLite.Compound) o;
            String id = item.string("ID");
            if (id == null) continue;
            // NBT предмета мод держит отдельным тегом Tag (ForgeNameUtils
            // .saveStackToNBT), а не внутри строки ID: внутри строки он его не
            // прочтёт и подставит бедрок. Внутри редактора удобнее одна строка
            NbtLite.Compound tag = item.comp("Tag");
            if (tag != null) id = id + NbtLite.write(tag);
            int slot = item.i("Slot", -1);
            if (slot < 0 || slot >= slots.length) slot = fallback;
            while (slot < slots.length && slots[slot] != null) slot++;
            if (slot < slots.length) slots[slot] = id;
            fallback = Math.min(slots.length - 1, fallback + 1);
        }
    }

    private String buildReward() {
        NbtLite.Compound nbt = new NbtLite.Compound();

        NbtLite.ListTag layerTags = new NbtLite.ListTag();
        for (int v : layers) layerTags.add(NbtLite.Num.of(v));
        nbt.put("Layers", layerTags);

        nbt.put("DropType", dropType.name());

        NbtLite.Compound display = new NbtLite.Compound();
        String name = nameField.text;
        if (name == null || name.trim().isEmpty()) name = "Базовый Гифт";
        name = name.replace('&', '§');
        if (resetPrefix && !name.startsWith("§r")) name = "§r" + name;
        display.put("Name", name);
        display.put("Lore", loreField.text == null ? "" : loreField.text.replace('&', '§'));
        nbt.put("display", display);

        if (dropType == Defs.DropType.RANDOM) {
            int[] range = parseRange();
            nbt.put("MinRandom", NbtLite.Num.of(range[0]));
            nbt.put("MaxRandom", NbtLite.Num.of(range[1]));
        }

        NbtLite.ListTag items = new NbtLite.ListTag();
        items.indexed = true;
        for (int i = 0; i < slots.length; i++) {
            if (slots[i] == null) continue;
            ForgeName fn = ForgeName.parse(slots[i]);
            NbtLite.Compound item = new NbtLite.Compound();
            item.put("Slot", NbtLite.Num.of(i, 'b'));
            // ID — только имя и количество, NBT — отдельным тегом Tag: так его
            // пишет и читает сам мод (ForgeNameUtils.saveStackToNBT)
            item.put("ID", fn.id + "=" + (fn.hasCount ? fn.count : 1));
            NbtLite.Compound tag = fn.nbt == null ? null : NbtLite.parse(fn.nbt);
            if (tag != null) item.put("Tag", tag);
            items.add(item);
        }
        nbt.put("Items", items);

        return "DwQuests:Gift:" + texture.ordinal() + NbtLite.write(nbt);
    }

    private int[] parseRange() {
        String s = randomField.text == null ? "" : randomField.text.trim();
        if (s.isEmpty()) return new int[]{1, 1};
        String[] parts = s.split("-");
        try {
            if (parts.length == 1) {
                int v = Integer.parseInt(parts[0].trim());
                return new int[]{v, v};
            }
            if (parts.length == 2)
                return new int[]{Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
        } catch (NumberFormatException ignored) { }
        return new int[]{1, 1};
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Поле ввода в стиле DwCore UITextField. */
    private final class Field {
        final float x, y;
        final int w, h;
        final int maxLength;
        final boolean numeric;
        String text = "";

        Field(float x, float y, int w, int h, int maxLength, boolean numeric) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
            this.maxLength = maxLength;
            this.numeric = numeric;
        }

        boolean contains(float px, float py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }

        void draw(GuiPainter p, Ui ui) {
            boolean hovered = contains(mouseX, mouseY);
            p.rect(x, y, w, h, hovered ? FIELD_HOVER : FIELD_BACK);
            p.frame(x, y, w, h, 1, -16777216);

            String shown = text.replace('&', '§');
            float tx = x + 4;
            float ty = y + (h - McFont.FONT_HEIGHT + 4) / 2f;
            p.text(shown, tx, ty, -2236963, true);
            if (focused == this && (ui.time() % 1.0) < 0.55) {
                float cx = tx + McFont.get().width(shown) + 1;
                p.rect(cx, ty - 1, 1, McFont.FONT_HEIGHT + 1, -3092272);
            }
        }
    }
}
