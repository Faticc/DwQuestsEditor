package ru.dw.qeditor.gui;

import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gl.UiFont;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Составные виджеты: многострочное поле, список, выпадающий список, вкладки,
 * разделитель панелей. Всё то, за чем раньше ходили в Swing.
 *
 * <p>Методы статические и берут {@link Ui} первым аргументом — состояние всё
 * равно хранится в нём, а отдельные объекты виджетов тут только мешали бы.
 */
public final class Widgets {

    private Widgets() { }

    // ------------------------------------------------------------------ вкладки

    /** Сегментированный переключатель вкладок. */
    public static int tabs(Ui ui, float x, float y, float h, String[] titles, int selected) {
        float px = x;
        for (int i = 0; i < titles.length; i++) {
            float w = ui.textWidth(titles[i]) + 28;
            boolean active = i == selected;
            boolean hovered = ui.over(px, y, w, h);
            if (active) ui.roundRect(px, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2);
            else if (hovered) ui.roundRect(px, y, w, h, Theme.RADIUS_SMALL, Theme.HOVER);
            ui.textIn(titles[i], px, y, w, h, active ? Theme.TEXT : Theme.TEXT_MUTED, Ui.CENTER);
            if (active) ui.rect(px + 10, y + h - 2, w - 20, 2, Theme.ACCENT);
            if (hovered) {
                ui.cursor(GlWindow.CURSOR_HAND);
                if (ui.in().leftPressed()) selected = i;
            }
            px += w + 2;
        }
        return selected;
    }

    // ------------------------------------------------------------------ разделитель

    /**
     * Вертикальный разделитель панелей: тянется мышью, возвращает новую
     * координату.
     */
    public static float splitterV(Ui ui, Object id, float x, float y, float h,
                                  float value, float min, float max) {
        float w = 6;
        boolean hovered = ui.over(x, y, w, h);
        if (hovered) ui.cursor(GlWindow.CURSOR_RESIZE_H);
        if (hovered && ui.in().leftPressed()) ui.setActive(id);
        if (ui.isActive(id)) {
            ui.cursor(GlWindow.CURSOR_RESIZE_H);
            value = Math.max(min, Math.min(max, ui.screenMx() - w / 2));
        }
        ui.rect(x + w / 2 - 0.5f, y, 1, h, hovered || ui.isActive(id) ? Theme.ACCENT : Theme.BORDER);
        return value;
    }

    public static float splitterH(Ui ui, Object id, float x, float y, float w,
                                  float value, float min, float max) {
        float h = 6;
        boolean hovered = ui.over(x, y, w, h);
        if (hovered) ui.cursor(GlWindow.CURSOR_RESIZE_V);
        if (hovered && ui.in().leftPressed()) ui.setActive(id);
        if (ui.isActive(id)) {
            ui.cursor(GlWindow.CURSOR_RESIZE_V);
            value = Math.max(min, Math.min(max, ui.screenMy() - h / 2));
        }
        ui.rect(x, y + h / 2 - 0.5f, w, 1, hovered || ui.isActive(id) ? Theme.ACCENT : Theme.BORDER);
        return value;
    }

    // ------------------------------------------------------------------ подписи

    /** Подпись над полем. */
    public static void caption(Ui ui, String text, float x, float y) {
        ui.text(text, x, y, Theme.TEXT_MUTED, Theme.FONT_SMALL, false);
    }

    /** Заголовок раздела с линией. */
    public static void section(Ui ui, String text, float x, float y, float w) {
        ui.text(text, x, y, Theme.TEXT_MUTED, Theme.FONT_SMALL, true);
        float tw = ui.textWidth(text, Theme.FONT_SMALL, true);
        ui.hSeparator(x + tw + 8, y + ui.font(Theme.FONT_SMALL, true).height() / 2, w - tw - 8);
    }

    // ------------------------------------------------------------------ выпадающий список

    /**
     * Выпадающий список. Раскрытое меню рисует {@link Popup} в самом конце
     * кадра, поэтому выбор приходит следующим кадром — на глаз незаметно.
     */
    public static int dropdown(Ui ui, Popup popup, Object id, float x, float y, float w, float h,
                               String[] items, int selected) {
        int picked = popup.takePick(id);
        if (picked >= 0) selected = picked;

        boolean hovered = ui.over(x, y, w, h);
        boolean open = popup.isOpen(id);
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, hovered ? Theme.HOVER : Theme.SURFACE_2,
                open ? Theme.ACCENT : hovered ? Theme.BORDER_STRONG : Theme.BORDER, 1);
        String label = selected >= 0 && selected < items.length ? items[selected] : "";
        ui.textIn(label, x + 8, y, w - 28, h, Theme.TEXT, Ui.LEFT);
        ui.triangle(x + w - 12, y + h / 2, 8, false, Theme.TEXT_MUTED);
        if (hovered) {
            ui.cursor(GlWindow.CURSOR_HAND);
            if (ui.in().leftPressed()) {
                List<Popup.Item> entries = new ArrayList<>();
                for (int i = 0; i < items.length; i++)
                    entries.add(i == selected ? Popup.Item.check(items[i], true) : Popup.Item.of(items[i]));
                // список рисуется верхним слоем с чистым началом координат,
                // а поле может стоять внутри прокручиваемой области
                popup.open(id, ui.toScreenX(x), ui.toScreenY(y + h + 2), ui.toScreenY(y - 2),
                        entries, w);
            }
        }
        return selected;
    }

    // ------------------------------------------------------------------ список

    /** Простой список строк с выделением и прокруткой. */
    public static int listBox(Ui ui, Object id, float x, float y, float w, float h,
                              List<String> items, int selected) {
        float rowH = Theme.ROW;
        float content = items.size() * rowH;
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        ui.beginScroll(id, x + 1, y + 1, w - 2, h - 2, w - 2, content);
        for (int i = 0; i < items.size(); i++) {
            float iy = i * rowH;
            boolean hovered = ui.over(0, iy, w - 2, rowH);
            if (i == selected) ui.rect(0, iy, w - 2, rowH, Theme.ACCENT_SOFT);
            else if (hovered) ui.rect(0, iy, w - 2, rowH, Theme.HOVER);
            ui.textIn(items.get(i), 8, iy, w - 18, rowH,
                    i == selected ? Theme.TEXT : Theme.TEXT_MUTED, Ui.LEFT);
            if (hovered && ui.in().leftPressed()) selected = i;
        }
        ui.endScroll(id, x + 1, y + 1, w - 2, h - 2, w - 2, content);
        return selected;
    }

    // ------------------------------------------------------------------ многострочный текст

    /** Строка после мягкого переноса: начало и конец в исходном тексте. */
    private record Line(int start, int end) { }

    private static List<Line> layout(String text, UiFont f, float width) {
        List<Line> lines = new ArrayList<>();
        int pos = 0;
        int n = text.length();
        while (true) {
            int nl = text.indexOf('\n', pos);
            int paraEnd = nl < 0 ? n : nl;
            int start = pos;
            while (true) {
                String rest = text.substring(start, paraEnd);
                int fit = f.fit(rest, width);
                if (fit >= rest.length()) {
                    lines.add(new Line(start, paraEnd));
                    break;
                }
                int brk = text.lastIndexOf(' ', start + fit);
                if (brk <= start) brk = start + Math.max(1, fit);
                else brk = brk + 1;
                lines.add(new Line(start, brk));
                start = brk;
            }
            if (nl < 0) break;
            pos = nl + 1;
            if (pos > n) break;
        }
        return lines;
    }

    private static int lineOf(List<Line> lines, int caret) {
        for (int i = 0; i < lines.size(); i++)
            if (caret >= lines.get(i).start() && caret <= lines.get(i).end()) return i;
        return Math.max(0, lines.size() - 1);
    }

    /**
     * Многострочное поле с мягким переносом строк.
     *
     * @return текст (новый, если его правили)
     */
    public static String textArea(Ui ui, Object id, float x, float y, float w, float h,
                                  String value, String placeholder) {
        TextBuf b = ui.state(id, TextBuf::new);
        boolean focused = ui.focused(id);
        if (!focused) b.set(value == null ? "" : value);

        UiFont f = ui.font();
        float pad = 8;
        float textW = w - pad * 2 - Theme.SCROLLBAR;
        List<Line> lines = layout(b.text, f, textW);
        float lineH = f.height();
        float content = Math.max(h - 2, lines.size() * lineH + pad * 2);

        boolean hovered = ui.over(x, y, w, h);
        if (hovered) ui.cursor(GlWindow.CURSOR_TEXT);
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2,
                focused ? Theme.ACCENT : hovered ? Theme.BORDER_STRONG : Theme.BORDER, 1);

        boolean changed = false;
        if (focused) {
            ui.captureKeyboard();
            int line = lineOf(lines, b.caret);
            // вверх-вниз работают по строкам после переноса, а не по абзацам
            if (ui.keyPressed(GLFW_KEY_UP) || ui.keyPressed(GLFW_KEY_DOWN)) {
                boolean up = ui.keyPressed(GLFW_KEY_UP);
                int target = Math.max(0, Math.min(lines.size() - 1, line + (up ? -1 : 1)));
                float col = f.width(b.text.substring(lines.get(line).start(), b.caret));
                Line t = lines.get(target);
                String s = b.text.substring(t.start(), t.end());
                b.moveTo(t.start() + f.indexAt(s, col), ui.in().shift());
                ui.in().consumeKey(up ? GLFW_KEY_UP : GLFW_KEY_DOWN);
            }
            changed = b.handleKeys(ui.in(), true, ui.window().clipboard(), ui.window()::setClipboard);
            if (changed) lines = layout(b.text, f, textW);
        }

        float[] scroll = ui.beginScroll(id + " scroll", x + 1, y + 1, w - 2, h - 2, w - 2, content);

        if (hovered && ui.in().leftPressed()) {
            ui.focus(id);
            ui.setActive(id);
            int pos = caretAtPoint(ui, lines, f, b, pad, pad);
            b.moveTo(pos, ui.in().shift());
        }
        if (ui.isActive(id) && ui.in().leftDown() && !ui.in().leftPressed())
            b.moveTo(caretAtPoint(ui, lines, f, b, pad, pad), true);

        if (b.text.isEmpty() && !focused && placeholder != null) {
            ui.text(placeholder, pad, pad, Theme.TEXT_DISABLED);
        }
        for (int i = 0; i < lines.size(); i++) {
            Line l = lines.get(i);
            float ly = pad + i * lineH;
            if (ly - scroll[1] > h || ly - scroll[1] + lineH < -lineH) continue;
            String s = b.text.substring(l.start(), l.end());
            if (focused && b.hasSelection()) {
                int s0 = Math.max(b.selStart(), l.start());
                int s1 = Math.min(b.selEnd(), l.end());
                if (s1 > s0) {
                    float sx = f.width(b.text.substring(l.start(), s0));
                    float ex = f.width(b.text.substring(l.start(), s1));
                    ui.rect(pad + sx, ly, Math.max(2, ex - sx), lineH, Theme.SELECTION);
                }
            }
            ui.text(s, pad, ly, Theme.TEXT);
        }
        if (focused) {
            int line = lineOf(lines, b.caret);
            float cx = pad + f.width(b.text.substring(lines.get(line).start(), b.caret));
            float cy = pad + line * lineH;
            if (((ui.time() - b.blinkFrom) % 1.0) < 0.55) ui.rect(cx, cy, 1, lineH, Theme.TEXT);
            // курсор не должен уезжать за край окна
            if (changed || ui.in().keys().size() > 0) {
                float sy = ui.scrollY(id + " scroll");
                if (cy < sy) ui.setScrollY(id + " scroll", cy);
                else if (cy + lineH > sy + h - 2) ui.setScrollY(id + " scroll", cy + lineH - h + 2);
            }
        }

        ui.endScroll(id + " scroll", x + 1, y + 1, w - 2, h - 2, w - 2, content);
        return changed ? b.text : value;
    }

    private static int caretAtPoint(Ui ui, List<Line> lines, UiFont f, TextBuf b, float padX, float padY) {
        if (lines.isEmpty()) return 0;
        float lineH = f.height();
        int line = (int) Math.floor((ui.my() - padY) / lineH);
        line = Math.max(0, Math.min(lines.size() - 1, line));
        Line l = lines.get(line);
        String s = b.text.substring(l.start(), l.end());
        return l.start() + f.indexAt(s, ui.mx() - padX);
    }

    // ------------------------------------------------------------------ прочее

    /** Полоса состояния внизу окна. */
    public static void statusBar(Ui ui, String text, float y) {
        ui.rect(0, y, ui.width(), Theme.STATUS_BAR, Theme.SURFACE);
        ui.hSeparator(0, y, ui.width());
        ui.textIn(text, 16, y, ui.width() - 32, Theme.STATUS_BAR, Theme.TEXT_MUTED, Ui.LEFT,
                Theme.FONT_SMALL, false);
    }
}
