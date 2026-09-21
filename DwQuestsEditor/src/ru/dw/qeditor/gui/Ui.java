package ru.dw.qeditor.gui;

import ru.dw.qeditor.gl.Atlas;
import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gl.Input;
import ru.dw.qeditor.gl.Textures;
import ru.dw.qeditor.gl.UiFont;
import ru.dw.qeditor.render.GuiPainter;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Интерфейс редактора в режиме немедленного вывода.
 *
 * <p>Виджетов как объектов здесь нет: каждый кадр экран описывается заново
 * вызовами вида {@code if (ui.button(x, y, w, h, "Сохранить"))}. Состояние,
 * которое нельзя вывести из модели, — курсор в поле ввода, прокрутка списка,
 * раскрытое меню — живёт в этом классе по ключу виджета.
 *
 * <p>Так весь интерфейс оказывается в тех же четырёхугольниках, что и карта
 * квестов: один поток вершин, новый вызов отрисовки — только на смену текстуры.
 * Swing с его иерархией компонентов, менеджерами раскладки и отдельным потоком
 * событий больше не нужен.
 *
 * <p>Координаты — логические точки: размер экранной точки учитывает системный
 * масштаб, поэтому на экране 150 % интерфейс не мельчает. Внутри прокручиваемой
 * области начало координат съезжает вместе с содержимым, и мышь считается там
 * же, — за этим следит {@code originX/originY}.
 */
public final class Ui {

    public static final int NORMAL = 0;
    public static final int PRIMARY = 1;
    public static final int DANGER = 2;
    public static final int GHOST = 3;

    public static final int LEFT = 0;
    public static final int CENTER = 1;
    public static final int RIGHT = 2;

    private static final Object NO_FOCUS = new Object();

    private final GlWindow window;
    private final Batch batch;

    private GuiPainter painter;
    private Input in;
    private float scale = 1f;
    private float width;
    private float height;
    private double time;

    /** Ввод перехвачен верхним слоем (меню, диалог) — нижние виджеты молчат. */
    private boolean blocked;
    private boolean keyboardCaptured;
    private Object focus;
    private Object nextFocus;
    /** Кто сейчас «держит» мышь: ползунок, разделитель, выделение текста. */
    private Object active;

    private final Map<Object, Object> state = new HashMap<>();
    private final Deque<float[]> stack = new ArrayDeque<>();
    /** Область отсечения — в координатах окна. */
    private float clipX, clipY, clipW, clipH;
    /** Сдвиг текущей системы координат относительно окна. */
    private float originX, originY;

    private String tooltip;
    private float tooltipX, tooltipY;
    private int cursor = GlWindow.CURSOR_ARROW;

    public Ui(GlWindow window, Batch batch) {
        this.window = window;
        this.batch = batch;
    }

    // ------------------------------------------------------------------ кадр

    public void begin() {
        in = window.input();
        scale = Math.max(1f, window.contentScale());
        width = window.width() / scale;
        height = window.height() / scale;
        time = glfwGetTime();

        painter = GuiPainter.of(batch, scale, window.width(), window.height());
        stack.clear();
        clipX = 0;
        clipY = 0;
        clipW = width;
        clipH = height;
        originX = 0;
        originY = 0;
        blocked = false;
        keyboardCaptured = false;
        tooltip = null;
        cursor = GlWindow.CURSOR_ARROW;

        if (nextFocus != null) {
            focus = nextFocus == NO_FOCUS ? null : nextFocus;
            nextFocus = null;
        }
        // щелчок мимо любого поля снимает фокус; поле вернёт его себе само
        if (in.leftPressed()) nextFocus = NO_FOCUS;
        if (!in.leftDown()) active = null;
    }

    public void end() {
        if (tooltip != null) drawTooltip();
        window.cursor(cursor);
        painter.dispose();
        UiFont.refreshAll();
        Textures.refresh();
    }

    public GuiPainter painter() { return painter; }

    public Batch batch() { return batch; }

    public GlWindow window() { return window; }

    public Input in() { return in; }

    public float scale() { return scale; }

    public float width() { return width; }

    public float height() { return height; }

    public double time() { return time; }

    /** Курсор в текущей системе координат. */
    public float mx() { return in.mouseX / scale - originX; }

    public float my() { return in.mouseY / scale - originY; }

    /**
     * Точка текущей системы координат — в координатах окна.
     *
     * <p>Нужна тем, кто отдаёт координаты верхнему слою: {@link Popup} рисуется
     * с чистым началом координат, а раскрывают его виджеты изнутри
     * прокручиваемых областей, где начало уже съехало.
     */
    public float toScreenX(float x) { return x + originX; }

    public float toScreenY(float y) { return y + originY; }

    /** Курсор в координатах окна. */
    public float screenMx() { return in.mouseX / scale; }

    public float screenMy() { return in.mouseY / scale; }

    /** Нажата ли комбинация; при наборе текста простые клавиши не в счёт. */
    public boolean shortcut(int key, boolean ctrl) {
        if (keyboardCaptured && !ctrl) return false;
        for (Input.KeyEvent e : in.keys())
            if (e.key() == key && e.action() == GLFW_PRESS && e.ctrl() == ctrl) return true;
        return false;
    }

    public boolean keyPressed(int key) {
        for (Input.KeyEvent e : in.keys())
            if (e.key() == key && (e.action() == GLFW_PRESS || e.action() == GLFW_REPEAT)) return true;
        return false;
    }

    public boolean keyboardCaptured() { return keyboardCaptured; }

    public void captureKeyboard() { keyboardCaptured = true; }

    /**
     * Ключи виджетов чаще всего собираются строкой прямо в кадре
     * ({@code this + "name"}), поэтому сравнивать их можно только по значению:
     * один и тот же ключ в разных кадрах — разные объекты.
     */
    public boolean focused(Object id) { return java.util.Objects.equals(focus, id); }

    /** Хоть какое-то поле в фокусе — значит, клавиши забирает оно, а не карта. */
    public boolean anyFocus() { return focus != null; }

    public void focus(Object id) { nextFocus = id; }

    public void clearFocus() { nextFocus = NO_FOCUS; }

    public void cursor(int c) { cursor = c; }

    // ------------------------------------------------------------------ слои и отсечение

    /**
     * Верхний слой (меню, диалог) рисуется последним, но ввод должен доставаться
     * ему, а не тому, что под ним. Поэтому нижние виджеты в этом кадре уже
     * отказались от событий флагом blocked, а здесь он временно снимается.
     */
    public void beginOverlay() {
        stack.push(new float[]{clipX, clipY, clipW, clipH, originX, originY, blocked ? 1 : 0});
        clipX = 0;
        clipY = 0;
        clipW = width;
        clipH = height;
        originX = 0;
        originY = 0;
        blocked = false;
        painter.push();
        painter.clipReset(0, 0, width, height);
    }

    public void endOverlay() {
        painter.pop();
        restore();
    }

    /** Пометить, что дальше ввод забирает верхний слой. */
    public void blockInput() { blocked = true; }

    public boolean inputBlocked() { return blocked; }

    /** Координаты — в текущей системе, отсечение хранится в координатах окна. */
    public void pushClip(float x, float y, float w, float h) {
        stack.push(new float[]{clipX, clipY, clipW, clipH, originX, originY, blocked ? 1 : 0});
        float nx = Math.max(clipX, x + originX);
        float ny = Math.max(clipY, y + originY);
        float nx2 = Math.min(clipX + clipW, x + originX + w);
        float ny2 = Math.min(clipY + clipH, y + originY + h);
        clipX = nx;
        clipY = ny;
        clipW = Math.max(0, nx2 - nx);
        clipH = Math.max(0, ny2 - ny);
        painter.clipReset(clipX - originX, clipY - originY, clipW, clipH);
    }

    public void popClip() { restore(); }

    private void restore() {
        float[] c = stack.pop();
        clipX = c[0];
        clipY = c[1];
        clipW = c[2];
        clipH = c[3];
        originX = c[4];
        originY = c[5];
        blocked = c[6] != 0;
        painter.clipReset(clipX - originX, clipY - originY, clipW, clipH);
    }

    // ------------------------------------------------------------------ ввод

    /** Курсор внутри прямоугольника, он не перекрыт и не обрезан. */
    public boolean over(float x, float y, float w, float h) {
        if (blocked || !in.mouseInside) return false;
        float px = mx();
        float py = my();
        if (px < x || py < y || px >= x + w || py >= y + h) return false;
        float sx = px + originX;
        float sy = py + originY;
        return sx >= clipX && sy >= clipY && sx < clipX + clipW && sy < clipY + clipH;
    }

    public boolean clicked(float x, float y, float w, float h) {
        return over(x, y, w, h) && in.leftPressed();
    }

    public boolean rightClicked(float x, float y, float w, float h) {
        return over(x, y, w, h) && in.rightPressed();
    }

    public boolean doubleClicked(float x, float y, float w, float h) {
        return over(x, y, w, h) && in.doubleClick();
    }

    public Object active() { return active; }

    public void setActive(Object id) { active = id; }

    public boolean isActive(Object id) { return active != null && active.equals(id); }

    // ------------------------------------------------------------------ текст

    public UiFont font() { return UiFont.get(Theme.FONT, false, scale); }

    public UiFont font(int size, boolean bold) { return UiFont.get(size, bold, scale); }

    public float textWidth(String s) { return font().width(s); }

    public float textWidth(String s, int size, boolean bold) { return font(size, bold).width(s); }

    public float lineHeight() { return font().height(); }

    public float text(String s, float x, float y, int color) {
        return text(s, x, y, color, Theme.FONT, false);
    }

    /** Строка; y — верх строки. Возвращает конечный X. */
    public float text(String s, float x, float y, int color, int size, boolean bold) {
        if (s == null || s.isEmpty()) return x;
        UiFont f = font(size, bold);
        float pen = x;
        float top = y + f.ascent();
        for (int i = 0; i < s.length(); i++) {
            UiFont.Glyph g = f.glyphOf(s.charAt(i));
            Atlas.Region r = g.region();
            if (r != null && r.valid())
                painter.regionMask(r, pen + g.left(), top - g.top(),
                        r.width() / scale, r.height() / scale, color);
            pen += g.advance();
        }
        return pen;
    }

    /** Строка в прямоугольнике с выравниванием и обрезкой многоточием. */
    public void textIn(String s, float x, float y, float w, float h, int color, int align) {
        textIn(s, x, y, w, h, color, align, Theme.FONT, false);
    }

    public void textIn(String s, float x, float y, float w, float h, int color, int align,
                       int size, boolean bold) {
        if (s == null || s.isEmpty()) return;
        UiFont f = font(size, bold);
        String shown = ellipsize(s, w, size, bold);
        float tw = f.width(shown);
        float tx = switch (align) {
            case CENTER -> x + (w - tw) / 2f;
            case RIGHT -> x + w - tw;
            default -> x;
        };
        text(shown, tx, y + (h - f.height()) / 2f, color, size, bold);
    }

    public String ellipsize(String s, float maxWidth, int size, boolean bold) {
        UiFont f = font(size, bold);
        if (f.width(s) <= maxWidth) return s;
        float dots = f.width("…");
        int n = f.fit(s, Math.max(0, maxWidth - dots));
        return s.substring(0, Math.max(0, n)) + "…";
    }

    /** Разбить текст на строки по ширине — для многострочных полей и подсказок. */
    public List<String> wrap(String s, float maxWidth, int size, boolean bold) {
        UiFont f = font(size, bold);
        List<String> out = new ArrayList<>();
        for (String paragraph : s.split("\n", -1)) {
            if (paragraph.isEmpty()) {
                out.add("");
                continue;
            }
            int start = 0;
            while (start < paragraph.length()) {
                String rest = paragraph.substring(start);
                int fit = f.fit(rest, maxWidth);
                if (fit >= rest.length()) {
                    out.add(rest);
                    break;
                }
                int brk = paragraph.lastIndexOf(' ', start + fit);
                if (brk <= start) brk = start + Math.max(1, fit);
                out.add(paragraph.substring(start, brk));
                start = brk;
                while (start < paragraph.length() && paragraph.charAt(start) == ' ') start++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ фигуры

    public void rect(float x, float y, float w, float h, int color) {
        painter.rect(x, y, w, h, color);
    }

    public void roundRect(float x, float y, float w, float h, float radius, int fill) {
        painter.roundRect(x, y, w, h, radius, fill, 0, 0);
    }

    public void roundRectBorder(float x, float y, float w, float h, float radius,
                                int fill, int border, float thickness) {
        painter.roundRect(x, y, w, h, radius, fill, border, thickness);
    }

    public void line(float x1, float y1, float x2, float y2, float thickness, int color) {
        painter.segment(x1, y1, x2, y2, thickness, color);
    }

    public void border(float x, float y, float w, float h, int color) {
        float t = 1f / scale;
        rect(x, y, w, t, color);
        rect(x, y + h - t, w, t, color);
        rect(x, y, t, h, color);
        rect(x + w - t, y, t, h, color);
    }

    public void hSeparator(float x, float y, float w) {
        rect(x, y, w, 1f / scale, Theme.BORDER);
    }

    public void vSeparator(float x, float y, float h) {
        rect(x, y, 1f / scale, h, Theme.BORDER);
    }

    public void panel(float x, float y, float w, float h) {
        roundRectBorder(x, y, w, h, Theme.RADIUS, Theme.SURFACE, Theme.BORDER, 1);
    }

    /** Треугольник-стрелка: два отрезка дешевле отдельной текстуры. */
    public void triangle(float cx, float cy, float size, boolean up, int color) {
        float h = size * 0.5f;
        float dy = up ? -h : h;
        line(cx - h, cy - dy / 2, cx, cy + dy / 2, 1.4f, color);
        line(cx + h, cy - dy / 2, cx, cy + dy / 2, 1.4f, color);
    }

    // ------------------------------------------------------------------ кнопки

    public boolean button(float x, float y, float w, float h, String label) {
        return button(x, y, w, h, label, NORMAL, true);
    }

    public boolean button(float x, float y, float w, float h, String label, int kind, boolean enabled) {
        boolean hovered = enabled && over(x, y, w, h);
        boolean held = hovered && in.leftDown();
        boolean pressed = hovered && in.leftPressed();

        int fill;
        int textColor;
        int borderColor = 0;
        switch (kind) {
            case PRIMARY -> {
                fill = held ? Theme.ACCENT_PRESSED : hovered ? Theme.ACCENT_HOVER : Theme.ACCENT;
                textColor = 0xFF0C1116;
            }
            case DANGER -> {
                fill = held ? Theme.shade(Theme.DANGER, 0.8f)
                        : hovered ? Theme.shade(Theme.DANGER, 1.1f) : Theme.DANGER;
                textColor = 0xFF1A0F0F;
            }
            case GHOST -> {
                fill = held ? Theme.PRESSED : hovered ? Theme.HOVER : 0;
                textColor = Theme.TEXT_MUTED;
            }
            default -> {
                fill = held ? Theme.PRESSED : hovered ? Theme.HOVER : Theme.SURFACE_2;
                borderColor = Theme.BORDER;
                textColor = Theme.TEXT;
            }
        }
        if (!enabled) {
            fill = kind == GHOST ? 0 : Theme.SURFACE_2;
            borderColor = kind == GHOST ? 0 : Theme.BORDER;
            textColor = Theme.TEXT_DISABLED;
        }
        if ((fill >>> 24) != 0 || borderColor != 0)
            roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, fill, borderColor, borderColor == 0 ? 0 : 1);
        textIn(label, x + 8, y, w - 16, h, textColor, CENTER);
        if (hovered) cursor(GlWindow.CURSOR_HAND);
        return pressed;
    }

    /** Кнопка-переключатель: акцентная, когда включена. */
    public boolean toggleButton(float x, float y, float w, float h, String label, boolean on) {
        boolean hovered = over(x, y, w, h);
        int fill = hovered ? Theme.HOVER : on ? Theme.ACCENT_SOFT : Theme.SURFACE_2;
        roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, fill, on ? Theme.ACCENT : Theme.BORDER, 1);
        textIn(label, x + 6, y, w - 12, h, on ? Theme.ACCENT : Theme.TEXT_MUTED, CENTER,
                Theme.FONT_SMALL, false);
        if (hovered) cursor(GlWindow.CURSOR_HAND);
        return hovered && in.leftPressed();
    }

    public float checkboxWidth(String label) { return 15 + 6 + textWidth(label); }

    public boolean checkbox(float x, float y, String label, boolean value) {
        float box = 15;
        float w = checkboxWidth(label);
        boolean hovered = over(x, y, w, box + 2);
        roundRectBorder(x, y + 1, box, box, 4, value ? Theme.ACCENT : Theme.SURFACE_2,
                value ? Theme.ACCENT : Theme.BORDER_STRONG, 1);
        if (value) {
            line(x + 4, y + 8.5f, x + 6.5f, y + 11.5f, 2, 0xFF0C1116);
            line(x + 6.5f, y + 11.5f, x + 11.5f, y + 5f, 2, 0xFF0C1116);
        }
        text(label, x + box + 6, y + 1 + (box - lineHeight()) / 2f,
                hovered ? Theme.TEXT : Theme.TEXT_MUTED);
        if (hovered) cursor(GlWindow.CURSOR_HAND);
        return hovered && in.leftPressed();
    }

    // ------------------------------------------------------------------ поля ввода

    private TextBuf buf(Object id, String value) {
        TextBuf b = (TextBuf) state.get(id);
        if (b == null) {
            b = new TextBuf();
            b.set(value);
            state.put(id, b);
        }
        // пока поле не в фокусе, оно просто показывает значение модели
        if (!focused(id)) b.set(value);
        return b;
    }

    /** Многоразовый ключ: поле, привязанное к объекту и имени свойства. */
    public static Object id(Object owner, String name) { return owner + " " + name; }

    /**
     * Однострочное поле ввода.
     *
     * @return значение поля (новое, если в него печатали)
     */
    public String field(Object id, float x, float y, float w, float h, String value, String placeholder) {
        TextBuf b = buf(id, value == null ? "" : value);
        boolean hovered = over(x, y, w, h);
        boolean focused = focused(id);

        if (hovered) {
            cursor(GlWindow.CURSOR_TEXT);
            if (in.leftPressed()) {
                focus = id;
                nextFocus = id;
                active = id;
                b.moveTo(caretAt(b, x + 6 - b.scrollX), in.shift());
                if (in.doubleClick()) {
                    b.anchor = b.wordLeft(b.caret);
                    b.caret = b.wordRight(b.caret);
                }
                focused = true;
            }
        }
        if (isActive(id) && in.leftDown() && !in.leftPressed())
            b.moveTo(caretAt(b, x + 6 - b.scrollX), true);

        boolean changed = false;
        if (focused) {
            captureKeyboard();
            changed = b.handleKeys(in, false, window.clipboard(), window::setClipboard);
            if (keyPressed(GLFW_KEY_ENTER) || keyPressed(GLFW_KEY_ESCAPE)
                    || keyPressed(GLFW_KEY_KP_ENTER) || keyPressed(GLFW_KEY_TAB)) nextFocus = NO_FOCUS;
        }

        roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2,
                focused ? Theme.ACCENT : hovered ? Theme.BORDER_STRONG : Theme.BORDER, 1);

        UiFont f = font();
        float textX = x + 6;
        float textY = y + (h - f.height()) / 2f;
        float caretX = f.offsetOf(b.text, b.caret);
        float visible = w - 12;
        // курсор всегда должен оставаться в поле зрения
        if (caretX - b.scrollX > visible) b.scrollX = caretX - visible;
        if (caretX - b.scrollX < 0) b.scrollX = caretX;
        if (f.width(b.text) - b.scrollX < visible) b.scrollX = Math.max(0, f.width(b.text) - visible);

        pushClip(x + 4, y, w - 8, h);
        if (b.text.isEmpty() && !focused && placeholder != null) {
            text(placeholder, textX, textY, Theme.TEXT_DISABLED);
        } else {
            if (b.hasSelection() && focused) {
                float sx = f.offsetOf(b.text, b.selStart()) - b.scrollX;
                float ex = f.offsetOf(b.text, b.selEnd()) - b.scrollX;
                rect(textX + sx, y + 3, ex - sx, h - 6, Theme.SELECTION);
            }
            text(b.text, textX - b.scrollX, textY, Theme.TEXT);
        }
        if (focused && blink(b)) rect(textX + caretX - b.scrollX, y + 4, 1, h - 8, Theme.TEXT);
        popClip();

        return changed ? b.text : value;
    }

    private boolean blink(TextBuf b) {
        return ((time - b.blinkFrom) % 1.0) < 0.55;
    }

    private int caretAt(TextBuf b, float textLeft) {
        return font().indexAt(b.text, mx() - textLeft);
    }

    /** Целое число со стрелками. */
    public int intField(Object id, float x, float y, float w, float h, int value, int min, int max) {
        float bw = 16;
        String s = field(id, x, y, w - bw, h, String.valueOf(value), null);
        int v = value;
        try {
            v = Integer.parseInt(s.trim());
        } catch (NumberFormatException ignored) { }
        v = Math.max(min, Math.min(max, v));
        if (spinner(x + w - bw, y, bw, h)) {
            v = Math.max(min, Math.min(max, v + (my() < y + h / 2 ? 1 : -1)));
            state.remove(id);
        }
        return v;
    }

    /** Дробное число со стрелками. */
    public float floatField(Object id, float x, float y, float w, float h, float value, float step) {
        float bw = 16;
        String s = field(id, x, y, w - bw, h, num(value), null);
        float v = value;
        try {
            v = Float.parseFloat(s.trim().replace(',', '.'));
        } catch (NumberFormatException ignored) { }
        if (spinner(x + w - bw, y, bw, h)) {
            v += (my() < y + h / 2 ? 1 : -1) * step;
            state.remove(id);
        }
        return v;
    }

    private boolean spinner(float x, float y, float w, float h) {
        boolean hovered = over(x, y, w, h);
        int color = hovered ? Theme.TEXT : Theme.TEXT_MUTED;
        triangle(x + w / 2, y + h * 0.32f, 6, true, color);
        triangle(x + w / 2, y + h * 0.68f, 6, false, color);
        if (hovered) cursor(GlWindow.CURSOR_HAND);
        return hovered && in.leftPressed();
    }

    /** Число без лишнего «.0» — так же, как его пишет редактор в JSON. */
    public static String num(float v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    // ------------------------------------------------------------------ прокрутка

    private float[] scrollState(Object id) {
        return (float[]) state.computeIfAbsent(id, k -> new float[2]);
    }

    /**
     * Начало прокручиваемой области: ставит отсечение и сдвигает начало
     * координат. Содержимое рисуется в своих координатах, от нуля.
     *
     * @return сдвиг {x, y}
     */
    public float[] beginScroll(Object id, float x, float y, float w, float h,
                               float contentWidth, float contentHeight) {
        float[] s = scrollState(id);
        if (over(x, y, w, h) && in.scrollY != 0) {
            // Shift — вбок; вбок же, если прокручивать по вертикали нечего
            // (так листается предпросмотр, который шире панели)
            boolean sideways = in.shift() || (contentHeight <= h && contentWidth > w);
            s[sideways ? 0 : 1] -= in.scrollY * 48;
            in.consumeScroll();
        }
        float maxY = Math.max(0, contentHeight - h);
        float maxX = Math.max(0, contentWidth - w);
        s[0] = Math.max(0, Math.min(maxX, s[0]));
        s[1] = Math.max(0, Math.min(maxY, s[1]));

        pushClip(x, y, w, h);
        painter.push();
        painter.translate(x - s[0], y - s[1]);
        originX += x - s[0];
        originY += y - s[1];
        return s;
    }

    public void endScroll(Object id, float x, float y, float w, float h,
                          float contentWidth, float contentHeight) {
        painter.pop();
        popClip();

        float[] s = scrollState(id);
        if (contentHeight > h) {
            float thumb = Math.max(24, h * h / contentHeight);
            float maxY = contentHeight - h;
            float pos = maxY == 0 ? 0 : s[1] / maxY * (h - thumb);
            float bx = x + w - Theme.SCROLLBAR;
            rect(bx, y, Theme.SCROLLBAR, h, 0x20000000);
            Object thumbId = id + " thumb";
            boolean hovered = over(bx, y + pos, Theme.SCROLLBAR, thumb);
            if (hovered && in.leftPressed()) active = thumbId;
            if (isActive(thumbId) && in.leftDown())
                s[1] = Math.max(0, Math.min(maxY,
                        s[1] + in.mouseDY / scale * maxY / Math.max(1, h - thumb)));
            roundRect(bx + 2, y + pos, Theme.SCROLLBAR - 4, thumb, 3,
                    hovered || isActive(thumbId) ? Theme.BORDER_STRONG : Theme.BORDER);
        }
        if (contentWidth > w) {
            float thumb = Math.max(24, w * w / contentWidth);
            float maxX = contentWidth - w;
            float pos = maxX == 0 ? 0 : s[0] / maxX * (w - thumb);
            float by = y + h - Theme.SCROLLBAR;
            rect(x, by, w, Theme.SCROLLBAR, 0x20000000);
            roundRect(x + pos + 2, by + 2, thumb - 4, Theme.SCROLLBAR - 4, 3, Theme.BORDER);
        }
    }

    /** Сдвинуть прокрутку так, чтобы полоса попала в окно. */
    public void scrollIntoView(Object id, float top, float itemHeight, float viewHeight) {
        float[] s = scrollState(id);
        if (top < s[1]) s[1] = top;
        else if (top + itemHeight > s[1] + viewHeight) s[1] = top + itemHeight - viewHeight;
    }

    public float scrollY(Object id) { return scrollState(id)[1]; }

    public void setScrollY(Object id, float v) { scrollState(id)[1] = v; }

    // ------------------------------------------------------------------ подсказка

    public void tooltip(String text) {
        if (text == null || text.isEmpty()) return;
        tooltip = text;
        tooltipX = screenMx();
        tooltipY = screenMy();
    }

    private void drawTooltip() {
        List<String> lines = wrap(tooltip, 320, Theme.FONT_SMALL, false);
        UiFont f = font(Theme.FONT_SMALL, false);
        float w = 0;
        for (String l : lines) w = Math.max(w, f.width(l));
        float h = lines.size() * f.height() + 10;
        w += 16;
        float x = Math.min(tooltipX + 16, width - w - 4);
        float y = tooltipY + 20;
        if (y + h > height) y = tooltipY - h - 8;

        beginOverlay();
        roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, 0xF01C2430, Theme.BORDER_STRONG, 1);
        float ty = y + 5;
        for (String l : lines) {
            text(l, x + 8, ty, Theme.TEXT, Theme.FONT_SMALL, false);
            ty += f.height();
        }
        endOverlay();
    }

    // ------------------------------------------------------------------ состояние виджетов

    /** Состояние виджета по ключу — для раскрытых списков и тому подобного. */
    @SuppressWarnings("unchecked")
    public <T> T state(Object id, java.util.function.Supplier<T> init) {
        return (T) state.computeIfAbsent(id, k -> init.get());
    }

    public void forgetState(Object id) { state.remove(id); }
}
