package ru.dw.qeditor.app;

import java.util.ArrayDeque;
import java.util.Deque;

/** Undo/redo на снимках JSON вкладки. */
public class History {

    private static final int LIMIT = 200;

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private Snapshot current;

    public static final class Snapshot {
        public final String json;
        public final String label;

        Snapshot(String json, String label) {
            this.json = json;
            this.label = label;
        }
    }

    public void reset(String json) {
        undo.clear();
        redo.clear();
        current = new Snapshot(json, "Открытие");
    }

    /** Запомнить состояние ПЕРЕД изменением. */
    public void push(String jsonBefore, String label) {
        if (current != null && current.json.equals(jsonBefore)) {
            undo.push(current);
        } else {
            undo.push(new Snapshot(jsonBefore, label));
        }
        while (undo.size() > LIMIT) undo.removeLast();
        redo.clear();
    }

    /** Зафиксировать состояние ПОСЛЕ изменения. */
    public void commit(String jsonAfter, String label) {
        current = new Snapshot(jsonAfter, label);
    }

    public boolean canUndo() { return !undo.isEmpty(); }

    public boolean canRedo() { return !redo.isEmpty(); }

    public String undoLabel() { return undo.isEmpty() ? null : undo.peek().label; }

    public String redoLabel() { return redo.isEmpty() ? null : redo.peek().label; }

    public String undo() {
        if (undo.isEmpty()) return null;
        Snapshot s = undo.pop();
        if (current != null) redo.push(current);
        current = s;
        return s.json;
    }

    public String redo() {
        if (redo.isEmpty()) return null;
        Snapshot s = redo.pop();
        if (current != null) undo.push(current);
        current = s;
        return s.json;
    }
}
