package ru.dw.qeditor.app;

import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Состояние редактора: открытая вкладка, файл, выделение, история.
 * Любое изменение модели проходит через {@link #edit(String, Runnable)} —
 * так автоматически получается undo/redo и пометка «изменено».
 */
public class EditorModel {

    public interface Listener {
        /** what: "tab" — сменилась вкладка целиком, "data" — правка, "selection", "file". */
        void modelChanged(String what);
    }

    private final List<Listener> listeners = new ArrayList<>();
    private final History history = new History();

    private QuestTab tab = QuestTab.empty("NewTab");
    private File file;
    private boolean dirty;
    /** Время последней записи/чтения файла — чтобы замечать правки извне. */
    private long fileStamp;

    private final Set<String> selection = new LinkedHashSet<>();
    private boolean suppressEvents;

    public EditorModel() {
        history.reset(tab.toJsonString());
    }

    // ------------------------------------------------------------------ подписка

    public void addListener(Listener l) { listeners.add(l); }

    public void fire(String what) {
        if (suppressEvents) return;
        for (Listener l : new ArrayList<>(listeners)) l.modelChanged(what);
    }

    // ------------------------------------------------------------------ документ

    public QuestTab tab() { return tab; }

    public File file() { return file; }

    public boolean isDirty() { return dirty; }

    public History history() { return history; }

    public long fileStamp() { return fileStamp; }

    public void markSaved(File f) {
        this.file = f;
        this.dirty = false;
        this.fileStamp = f == null ? 0 : f.lastModified();
        fire("file");
    }

    /** Полная замена документа (открытие файла, вставка из буфера, новый). */
    public void setTab(QuestTab newTab, File f, boolean dirty) {
        this.tab = newTab;
        this.file = f;
        this.dirty = dirty;
        this.fileStamp = f == null ? 0 : f.lastModified();
        selection.clear();
        history.reset(newTab.toJsonString());
        fire("tab");
    }

    /**
     * Выполнить правку с записью в историю.
     *
     * @param label короткое название действия для меню «Отменить»
     */
    public void edit(String label, Runnable change) {
        String before = tab.toJsonString();
        change.run();
        String after = tab.toJsonString();
        if (before.equals(after)) return;
        history.push(before, label);
        history.commit(after, label);
        dirty = true;
        fire("data");
    }

    /** Правка без записи в историю (например, промежуточный кадр перетаскивания). */
    public void touch() {
        dirty = true;
        fire("data");
    }

    /** Зафиксировать в истории результат серии touch() (конец перетаскивания). */
    public void commitDrag(String beforeJson, String label) {
        String after = tab.toJsonString();
        if (beforeJson.equals(after)) return;
        history.push(beforeJson, label);
        history.commit(after, label);
        dirty = true;
        fire("data");
    }

    public boolean undo() {
        String json = history.undo();
        if (json == null) return false;
        applyJson(json);
        return true;
    }

    public boolean redo() {
        String json = history.redo();
        if (json == null) return false;
        applyJson(json);
        return true;
    }

    private void applyJson(String json) {
        QuestTab restored = QuestTab.parse(json);
        this.tab = restored;
        selection.removeIf(id -> !restored.quests.containsKey(id));
        dirty = true;
        fire("tab");
    }

    // ------------------------------------------------------------------ выделение

    public Set<String> selection() { return selection; }

    public String single() { return selection.size() == 1 ? selection.iterator().next() : null; }

    public Quest selectedQuest() {
        String id = single();
        return id == null ? null : tab.quests.get(id);
    }

    public void select(String questID) {
        selection.clear();
        if (questID != null) selection.add(questID);
        fire("selection");
    }

    public void selectAll() {
        selection.clear();
        selection.addAll(tab.quests.keySet());
        fire("selection");
    }

    public void toggleSelect(String questID) {
        if (questID == null) return;
        if (!selection.remove(questID)) selection.add(questID);
        fire("selection");
    }

    public void addSelect(String questID) {
        if (questID != null && selection.add(questID)) fire("selection");
    }

    public void clearSelection() {
        if (selection.isEmpty()) return;
        selection.clear();
        fire("selection");
    }

    public void setSuppressEvents(boolean v) { suppressEvents = v; }

    public String title() {
        StringBuilder sb = new StringBuilder("DwQuests Editor — ");
        sb.append(tab.tabID == null ? "?" : tab.tabID);
        if (file != null) sb.append("  (").append(file.getName()).append(')');
        if (dirty) sb.append(" *");
        return sb.toString();
    }
}
