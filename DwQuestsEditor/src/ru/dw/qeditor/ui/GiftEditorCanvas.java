package ru.dw.qeditor.ui;

import ru.dw.qeditor.assets.Gifts;
import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.render.GuiPainter;

import javax.swing.JComponent;
import javax.swing.Timer;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * Редактор гифта в том же виде, что и в игре (GuiContainerGift + IconSelector):
 * карусель текстур и слоёв, поля имени и лора, переключатель типа выдачи,
 * диапазон случайной выдачи и сетка предметов 3×9.
 *
 * <p>Инвентарь игрока из игрового окна не воспроизводится — в редакторе его
 * нет; вместо него внизу подсказка.
 */
public class GiftEditorCanvas extends JComponent {

    /** Размеры окна мода. */
    private static final int GUI_W = 256;
    private static final int GUI_H = 190;
    private static final int SLOT = 18;
    private static final int GIFT_SLOTS = 27;

    private static final int FIELD_BACK = -871296751;
    private static final int FIELD_HOVER = -867941308;

    private int scale = 2;

    private Defs.GiftTexture texture = Defs.GiftTexture.Present;
    private int[] layers = new int[]{0, 0};
    private final String[] slots = new String[GIFT_SLOTS];

    private final Field nameField = new Field(120, 8, 129, 18, 64, false);
    private final Field loreField = new Field(120, 30, 129, 18, 512, false);
    private final Field randomField = new Field(209, 52, 40, 18, 5, true);
    private Defs.DropType dropType = Defs.DropType.DEFAULT;

    private Field focused;
    private int caretTick;
    private float mouseX = -100, mouseY = -100;
    private final Timer caretTimer;

    private Runnable onChange;

    public GiftEditorCanvas() {
        setFocusable(true);
        nameField.text = "Базовый Гифт";
        caretTimer = new Timer(100, e -> {
            caretTick++;
            if (focused != null) repaint();
        });
        caretTimer.start();

        MouseAdapter ma = new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) { mouseX = vx(e); mouseY = vy(e); repaint(); }

            @Override public void mousePressed(MouseEvent e) { onPress(e); }

            @Override public void mouseWheelMoved(MouseWheelEvent e) { onWheel(e); }
        };
        addMouseListener(ma);
        addMouseMotionListener(ma);
        addMouseWheelListener(ma);

        addKeyListener(new KeyAdapter() {
            @Override public void keyPressed(KeyEvent e) { onKey(e); }

            @Override public void keyTyped(KeyEvent e) { onKeyTyped(e); }
        });
    }

    public void setOnChange(Runnable r) { this.onChange = r; }

    public void setScale(int scale) {
        this.scale = Math.max(1, Math.min(4, scale));
        revalidate();
        repaint();
    }

    public void dispose() { caretTimer.stop(); }

    @Override
    public Dimension getPreferredSize() {
        return new Dimension(GUI_W * scale, GUI_H * scale);
    }

    // ------------------------------------------------------------------ данные

    public Defs.GiftTexture getTexture() { return texture; }

    public int[] getLayers() { return layers.clone(); }

    public String getName() { return nameField.text; }

    public String getLore() { return loreField.text; }

    public Defs.DropType getDropType() { return dropType; }

    public int getMinRandom() { return parseRange()[0]; }

    public int getMaxRandom() { return parseRange()[1]; }

    /** Предметы по слотам; null там, где пусто. */
    public String[] getSlots() { return slots.clone(); }

    public List<String> getItems() {
        List<String> out = new ArrayList<>();
        for (String s : slots) if (s != null) out.add(s);
        return out;
    }

    public void setTexture(Defs.GiftTexture t) {
        texture = t == null ? Defs.GiftTexture.Present : t;
        layers = new int[texture.passCount()];
        changed();
    }

    public void setLayers(int[] v) {
        int[] out = new int[texture.passCount()];
        for (int i = 0; i < out.length; i++) {
            int val = v != null && i < v.length ? v[i] : 0;
            out[i] = Math.max(0, Math.min(texture.passes[i] - 1, val));
        }
        layers = out;
        changed();
    }

    public void setName(String s) { nameField.text = s == null ? "" : s; changed(); }

    public void setLore(String s) { loreField.text = s == null ? "" : s; changed(); }

    public void setDropType(Defs.DropType t) { dropType = t == null ? Defs.DropType.DEFAULT : t; changed(); }

    public void setRandom(int min, int max) {
        randomField.text = min == max ? String.valueOf(max) : min + "-" + max;
        changed();
    }

    public void setSlot(int index, String item) {
        if (index < 0 || index >= GIFT_SLOTS) return;
        slots[index] = item;
        changed();
    }

    /** Заполнить слоты подряд списком предметов. */
    public void setItems(List<String> items) {
        for (int i = 0; i < GIFT_SLOTS; i++) slots[i] = null;
        for (int i = 0; i < items.size() && i < GIFT_SLOTS; i++) slots[i] = items.get(i);
        changed();
    }

    /** Разместить предметы по указанным слотам. */
    public void setSlots(String[] bySlot) {
        for (int i = 0; i < GIFT_SLOTS; i++) slots[i] = i < bySlot.length ? bySlot[i] : null;
        changed();
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

    private void changed() {
        repaint();
        if (onChange != null) onChange.run();
    }

    // ------------------------------------------------------------------ отрисовка

    private BufferedImage buffer;

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h)
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        GuiPainter p = GuiPainter.forImage(buffer, scale);
        try {
            p.rectOpaque(0, 0, w / (float) scale, h / (float) scale, 0xFF0D0D12);
            p.guiBackground(0, 0, GUI_W, GUI_H);

            drawIconSelector(p);

            p.text("Имя: ", 95, 13, -1, true);
            p.text("Лор: ", 95, 35, -1, true);
            p.text("Тип: ", 95, 57, -1, true);

            nameField.draw(p);
            loreField.draw(p);

            int typeWidth = dropType == Defs.DropType.RANDOM ? 85 : 129;
            drawButton(p, 120, 52, typeWidth, 18, dropType.ru);
            if (dropType == Defs.DropType.RANDOM) randomField.draw(p);

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

            p.text("§7ЛКМ — выбрать предмет, ПКМ — очистить, колесо — количество",
                    8, GUI_H - 12, -1, true);
        } finally {
            p.dispose();
        }
        g.drawImage(buffer, 0, 0, null);
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

    private float vx(MouseEvent e) { return e.getX() / (float) scale; }

    private float vy(MouseEvent e) { return e.getY() / (float) scale; }

    private void onPress(MouseEvent e) {
        requestFocusInWindow();
        float x = vx(e), y = vy(e);
        mouseX = x;
        mouseY = y;

        focused = null;
        if (nameField.contains(x, y)) focused = nameField;
        else if (loreField.contains(x, y)) focused = loreField;
        else if (dropType == Defs.DropType.RANDOM && randomField.contains(x, y)) focused = randomField;

        int typeWidth = dropType == Defs.DropType.RANDOM ? 85 : 129;
        if (x >= 120 && x < 120 + typeWidth && y >= 52 && y < 70) {
            Defs.DropType[] types = Defs.DropType.values();
            dropType = types[(dropType.ordinal() + 1) % types.length];
            changed();
            return;
        }

        int slotIndex = slotAt(x, y);
        if (slotIndex >= 0) {
            if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
                slots[slotIndex] = null;
                changed();
            } else {
                String picked = ItemPickerDialog.choose(this,
                        slots[slotIndex] == null ? "minecraft:diamond" : slots[slotIndex]);
                if (picked != null) slots[slotIndex] = picked;
                changed();
            }
            return;
        }
        repaint();
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

    private void onWheel(MouseWheelEvent e) {
        float x = vx(e), y = vy(e);
        int dir = e.getWheelRotation();

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
                changed();
                return;
            }
        }
        // количество в слоте
        int slotIndex = slotAt(x, y);
        if (slotIndex >= 0 && slots[slotIndex] != null) {
            ru.dw.qeditor.util.ForgeName fn = ru.dw.qeditor.util.ForgeName.parse(slots[slotIndex]);
            int count = Math.max(1, (fn.hasCount ? fn.count : 1) - dir);
            slots[slotIndex] = fn.withCount(count);
            changed();
        }
    }

    private void onKey(KeyEvent e) {
        if (focused == null) return;
        if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE && !focused.text.isEmpty()) {
            focused.text = focused.text.substring(0, focused.text.length() - 1);
            changed();
            e.consume();
        } else if (e.getKeyCode() == KeyEvent.VK_ESCAPE || e.getKeyCode() == KeyEvent.VK_ENTER) {
            focused = null;
            repaint();
        }
    }

    private void onKeyTyped(KeyEvent e) {
        if (focused == null) return;
        char c = e.getKeyChar();
        if (c < ' ' || c == 127) return;
        if (focused.numeric && !(Character.isDigit(c) || c == '-')) return;
        if (focused.text.length() >= focused.maxLength) return;
        focused.text += c;
        changed();
        e.consume();
    }

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

        void draw(GuiPainter p) {
            boolean hovered = contains(mouseX, mouseY);
            p.rect(x, y, w, h, hovered ? FIELD_HOVER : FIELD_BACK);
            p.frame(x, y, w, h, 1, -16777216);

            String shown = text.replace('&', '§');
            float tx = x + 4;
            float ty = y + (h - McFont.FONT_HEIGHT + 4) / 2f;
            p.text(shown, tx, ty, -2236963, true);
            if (focused == this && caretTick / 5 % 2 == 0) {
                float cx = tx + McFont.get().width(shown) + 1;
                p.rect(cx, ty - 1, 1, McFont.FONT_HEIGHT + 1, -3092272);
            }
        }
    }
}
