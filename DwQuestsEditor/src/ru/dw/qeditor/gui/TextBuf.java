package ru.dw.qeditor.gui;

import ru.dw.qeditor.gl.Input;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Состояние редактируемого текста: сам текст, курсор, выделение, прокрутка.
 *
 * <p>Раньше всё это давал {@code JTextField} / {@code JTextArea}. Здесь то же
 * самое, но своими руками: перемещение по словам, выделение с Shift, работа с
 * буфером обмена и мягкий перенос строк для многострочного поля.
 */
public final class TextBuf {

    public String text = "";
    /** Позиция курсора в символах. */
    public int caret;
    /** Другой конец выделения; равен caret, когда ничего не выделено. */
    public int anchor;
    public float scrollX;
    public float scrollY;
    /** Когда курсор последний раз двигали — по этому мигает полоска. */
    public double blinkFrom;

    /** Границы строк после мягкого переноса: начало каждой строки в тексте. */
    public final List<int[]> lines = new ArrayList<>();

    public boolean hasSelection() { return caret != anchor; }

    public int selStart() { return Math.min(caret, anchor); }

    public int selEnd() { return Math.max(caret, anchor); }

    public String selectedText() {
        return hasSelection() ? text.substring(selStart(), selEnd()) : "";
    }

    public void set(String s) {
        text = s == null ? "" : s;
        caret = Math.min(caret, text.length());
        anchor = Math.min(anchor, text.length());
    }

    public void selectAll() {
        anchor = 0;
        caret = text.length();
    }

    public void moveTo(int pos, boolean select) {
        caret = clamp(pos);
        if (!select) anchor = caret;
        blinkFrom = glfwGetTime();
    }

    private int clamp(int pos) { return Math.max(0, Math.min(text.length(), pos)); }

    public void insert(String s) {
        deleteSelection();
        text = text.substring(0, caret) + s + text.substring(caret);
        caret += s.length();
        anchor = caret;
        blinkFrom = glfwGetTime();
    }

    public boolean deleteSelection() {
        if (!hasSelection()) return false;
        int a = selStart();
        int b = selEnd();
        text = text.substring(0, a) + text.substring(b);
        caret = anchor = a;
        return true;
    }

    public void backspace(boolean word) {
        if (deleteSelection()) return;
        if (caret == 0) return;
        int from = word ? wordLeft(caret) : caret - 1;
        text = text.substring(0, from) + text.substring(caret);
        caret = anchor = from;
    }

    public void delete(boolean word) {
        if (deleteSelection()) return;
        if (caret >= text.length()) return;
        int to = word ? wordRight(caret) : caret + 1;
        text = text.substring(0, caret) + text.substring(to);
        anchor = caret;
    }

    public int wordLeft(int from) {
        int i = from;
        while (i > 0 && Character.isWhitespace(text.charAt(i - 1))) i--;
        while (i > 0 && !Character.isWhitespace(text.charAt(i - 1))) i--;
        return i;
    }

    public int wordRight(int from) {
        int i = from;
        int n = text.length();
        while (i < n && !Character.isWhitespace(text.charAt(i))) i++;
        while (i < n && Character.isWhitespace(text.charAt(i))) i++;
        return i;
    }

    /** Начало строки (по фактическим переводам строки, не по мягкому переносу). */
    public int lineStart(int pos) {
        int i = text.lastIndexOf('\n', Math.max(0, pos - 1));
        return i < 0 ? 0 : i + 1;
    }

    public int lineEnd(int pos) {
        int i = text.indexOf('\n', pos);
        return i < 0 ? text.length() : i;
    }

    /**
     * Общая для поля и области обработка клавиш: перемещение, удаление, буфер
     * обмена. Возвращает true, если текст изменился.
     */
    public boolean handleKeys(Input in, boolean multiline, String clipboard, ClipboardSink toClipboard) {
        boolean changed = false;
        for (Input.KeyEvent e : new ArrayList<>(in.keys())) {
            if (e.action() == GLFW_RELEASE) continue;
            boolean shift = e.shift();
            boolean ctrl = e.ctrl();
            switch (e.key()) {
                case GLFW_KEY_LEFT -> moveTo(ctrl ? wordLeft(caret) : caret - 1, shift);
                case GLFW_KEY_RIGHT -> moveTo(ctrl ? wordRight(caret) : caret + 1, shift);
                case GLFW_KEY_HOME -> moveTo(ctrl ? 0 : lineStart(caret), shift);
                case GLFW_KEY_END -> moveTo(ctrl ? text.length() : lineEnd(caret), shift);
                case GLFW_KEY_BACKSPACE -> { backspace(ctrl); changed = true; }
                case GLFW_KEY_DELETE -> { delete(ctrl); changed = true; }
                case GLFW_KEY_A -> { if (ctrl) selectAll(); }
                case GLFW_KEY_C -> { if (ctrl && hasSelection()) toClipboard.put(selectedText()); }
                case GLFW_KEY_X -> {
                    if (ctrl && hasSelection()) {
                        toClipboard.put(selectedText());
                        deleteSelection();
                        changed = true;
                    }
                }
                case GLFW_KEY_V -> {
                    if (ctrl && clipboard != null && !clipboard.isEmpty()) {
                        insert(multiline ? clipboard.replace("\r\n", "\n").replace('\r', '\n')
                                : clipboard.replace("\r", " ").replace("\n", " "));
                        changed = true;
                    }
                }
                case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                    if (multiline && !ctrl) {
                        insert("\n");
                        changed = true;
                    }
                }
                case GLFW_KEY_TAB -> {
                    if (multiline) {
                        insert("    ");
                        changed = true;
                    }
                }
                default -> { }
            }
        }
        String typed = in.text();
        if (!typed.isEmpty()) {
            insert(typed);
            changed = true;
        }
        return changed;
    }

    /** Куда класть скопированное — окно знает про системный буфер обмена. */
    public interface ClipboardSink {
        void put(String s);
    }
}
