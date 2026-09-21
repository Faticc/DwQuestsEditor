package ru.dw.qeditor.gui;

import ru.dw.qeditor.gl.Input;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
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

    /**
     * Своя отмена у каждого поля: отмена модели тут не годится — поле в фокусе
     * показывает свой текст, а не модель, и следующей же буквой вернуло бы
     * отменённое обратно.
     */
    private record Snap(String text, int caret, int anchor) { }
    private static final int UNDO_LIMIT = 200;
    private static final int EDIT_OTHER = 0, EDIT_TYPE = 1, EDIT_ERASE = 2;
    private final Deque<Snap> undo = new ArrayDeque<>();
    private final Deque<Snap> redo = new ArrayDeque<>();
    /** Какой была последняя правка: подряд набранные буквы отменяются разом. */
    private int lastEdit;

    public boolean hasSelection() { return caret != anchor; }

    public int selStart() { return Math.min(caret, anchor); }

    public int selEnd() { return Math.max(caret, anchor); }

    public String selectedText() {
        return hasSelection() ? text.substring(selStart(), selEnd()) : "";
    }

    public void set(String s) {
        s = s == null ? "" : s;
        // текст сменили снаружи (другой квест, отмена в модели) — старая
        // история к нему уже не относится
        if (!s.equals(text)) {
            undo.clear();
            redo.clear();
            lastEdit = EDIT_OTHER;
        }
        text = s;
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
        lastEdit = EDIT_OTHER;
    }

    /** Запомнить текст перед правкой; серия однотипных правок — одна запись. */
    private void remember(int kind) {
        if (kind != EDIT_OTHER && kind == lastEdit && !undo.isEmpty()) return;
        undo.push(new Snap(text, caret, anchor));
        if (undo.size() > UNDO_LIMIT) undo.removeLast();
        redo.clear();
        lastEdit = kind;
    }

    private boolean restore(Deque<Snap> from, Deque<Snap> to) {
        if (from.isEmpty()) return false;
        to.push(new Snap(text, caret, anchor));
        Snap s = from.pop();
        text = s.text();
        caret = Math.min(s.caret(), text.length());
        anchor = Math.min(s.anchor(), text.length());
        lastEdit = EDIT_OTHER;
        blinkFrom = glfwGetTime();
        return true;
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
                case GLFW_KEY_BACKSPACE -> { remember(EDIT_ERASE); backspace(ctrl); changed = true; }
                case GLFW_KEY_DELETE -> { remember(EDIT_ERASE); delete(ctrl); changed = true; }
                case GLFW_KEY_Z -> {
                    if (ctrl) changed |= shift ? restore(redo, undo) : restore(undo, redo);
                }
                case GLFW_KEY_Y -> { if (ctrl) changed |= restore(redo, undo); }
                case GLFW_KEY_A -> { if (ctrl) selectAll(); }
                case GLFW_KEY_C -> { if (ctrl && hasSelection()) toClipboard.put(selectedText()); }
                case GLFW_KEY_X -> {
                    if (ctrl && hasSelection()) {
                        toClipboard.put(selectedText());
                        remember(EDIT_OTHER);
                        deleteSelection();
                        changed = true;
                    }
                }
                case GLFW_KEY_V -> {
                    if (ctrl && clipboard != null && !clipboard.isEmpty()) {
                        remember(EDIT_OTHER);
                        insert(multiline ? clipboard.replace("\r\n", "\n").replace('\r', '\n')
                                : clipboard.replace("\r", " ").replace("\n", " "));
                        changed = true;
                    }
                }
                case GLFW_KEY_ENTER, GLFW_KEY_KP_ENTER -> {
                    if (multiline && !ctrl) {
                        remember(EDIT_OTHER);
                        insert("\n");
                        changed = true;
                    }
                }
                case GLFW_KEY_TAB -> {
                    if (multiline) {
                        remember(EDIT_TYPE);
                        insert("    ");
                        changed = true;
                    }
                }
                default -> { }
            }
        }
        String typed = in.text();
        if (!typed.isEmpty()) {
            remember(EDIT_TYPE);
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
