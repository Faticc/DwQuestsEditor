package ru.dw.qeditor.app;

import java.util.ArrayDeque;
import java.util.Deque;

/** Undo/redo на снимках JSON вкладки. */
public class History {

    private static final int LIMIT = 200;

    private final Deque<Snapshot> undo = new ArrayDeque<>();
    private final Deque<Snapshot> redo = new ArrayDeque<>();
    private Snapshot current;

    /**
     * Набор в поле приходит правкой на каждую букву. Подряд идущие правки с
     * тем же названием склеиваются в один шаг — иначе «Первые шаги» отменялись
     * бы по букве, а двести букв вытесняли бы из истории всё остальное.
     */
    private static final long MERGE_MS = 1500;
    private String mergeLabel;
    private long mergeAt;

    public static final class Snapshot {
        public final String json;
        public final String label;

        Snapshot(String json, String label) {
            this.json = json;
            this.label = label;
        }
    }

    public void reset(String json) {
        mergeLabel = null;
        undo.clear();
        redo.clear();
        current = new Snapshot(json, "Открытие");
    }

    /** Запомнить состояние ПЕРЕД изменением. */
    public void push(String jsonBefore, String label) {
        push(jsonBefore, label, false);
    }

    /** То же, но набор в поле: склеивается с предыдущим таким же шагом. */
    public void push(String jsonBefore, String label, boolean typing) {
        long now = System.currentTimeMillis();
        boolean merge = typing && label.equals(mergeLabel) && now - mergeAt < MERGE_MS
                && !undo.isEmpty() && redo.isEmpty()
                && current != null && current.json.equals(jsonBefore);
        mergeLabel = typing ? label : null;
        mergeAt = now;
        // состояние до начала набора уже лежит в undo — его и оставляем
        if (merge) return;
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
        mergeLabel = null;
        if (undo.isEmpty()) return null;
        Snapshot s = undo.pop();
        if (current != null) redo.push(current);
        current = s;
        return s.json;
    }

    public String redo() {
        mergeLabel = null;
        if (redo.isEmpty()) return null;
        Snapshot s = redo.pop();
        if (current != null) undo.push(current);
        current = s;
        return s.json;
    }
}
