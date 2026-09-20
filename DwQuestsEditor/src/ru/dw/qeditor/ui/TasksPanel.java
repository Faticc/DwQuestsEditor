package ru.dw.qeditor.ui;

import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.Task;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Список задач квеста: тип, количество, предмет (или моб), своё описание и
 * зависимости между задачами. Порядок строк — это порядок ключей в JSON,
 * поэтому строки можно перетаскивать.
 */
public class TasksPanel extends JPanel {

    private final EditorModel model;
    private final Model tableModel = new Model();
    private final JTable table = new JTable(tableModel);
    private final JLabel hint = new JLabel(" ");

    private static Task clipboardTask;

    private int dragFrom = -1;

    public TasksPanel(EditorModel model) {
        this.model = model;
        setLayout(new BorderLayout(0, 4));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(24);
        table.getTableHeader().setReorderingAllowed(false);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);

        table.getSelectionModel().addListSelectionListener(e -> updateHint());

        MouseAdapter drag = new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                dragFrom = table.rowAtPoint(e.getPoint());
            }

            @Override
            public void mouseDragged(MouseEvent e) {
                int to = table.rowAtPoint(e.getPoint());
                if (dragFrom < 0 || to < 0 || to == dragFrom) return;
                moveRow(dragFrom, to);
                dragFrom = to;
                table.setRowSelectionInterval(to, to);
            }

            @Override
            public void mouseReleased(MouseEvent e) { dragFrom = -1; }
        };
        table.addMouseListener(drag);
        table.addMouseMotionListener(drag);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(320, 150));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        buttons.add(Ui.smallBtn("Добавить", "Новая задача", this::add));
        buttons.add(Ui.smallBtn("Удалить", "Удалить выбранную", this::remove));
        buttons.add(Ui.smallBtn("↑", "Выше", () -> move(-1)));
        buttons.add(Ui.smallBtn("↓", "Ниже", () -> move(1)));
        buttons.add(Ui.smallBtn("Предмет…", "Выбрать предмет для задачи", this::pickItem));
        buttons.add(Ui.smallBtn("Копир.", "Скопировать задачу", this::copy));
        buttons.add(Ui.smallBtn("Вставить", "Вставить скопированную задачу", this::paste));
        buttons.add(Ui.smallBtn("№", "Перенумеровать задачи подряд", this::renumber));

        hint.setFont(hint.getFont().deriveFont(java.awt.Font.PLAIN, 11f));

        JPanel south = new JPanel(new BorderLayout());
        south.add(buttons, BorderLayout.NORTH);
        south.add(hint, BorderLayout.SOUTH);

        add(scroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    // ------------------------------------------------------------------ действия

    private Task selectedTask() {
        Quest q = model.selectedQuest();
        int row = table.getSelectedRow();
        if (q == null || row < 0) return null;
        List<Integer> ids = new ArrayList<>(q.tasks.keySet());
        return row < ids.size() ? q.tasks.get(ids.get(row)) : null;
    }

    private Integer selectedID() {
        Quest q = model.selectedQuest();
        int row = table.getSelectedRow();
        if (q == null || row < 0) return null;
        List<Integer> ids = new ArrayList<>(q.tasks.keySet());
        return row < ids.size() ? ids.get(row) : null;
    }

    private void add() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        model.edit("Добавление задачи", () -> {
            Task t = new Task();
            t.type = Defs.TaskType.ITEM_DETECT;
            t.forgeName = "minecraft:dirt";
            t.requiredCount = 1;
            q.tasks.put(q.nextTaskID(), t);
        });
        refresh();
        int last = table.getRowCount() - 1;
        if (last >= 0) table.setRowSelectionInterval(last, last);
    }

    private void remove() {
        Quest q = model.selectedQuest();
        Integer id = selectedID();
        if (q == null || id == null) return;
        model.edit("Удаление задачи", () -> {
            q.tasks.remove(id);
            for (Task t : q.tasks.values()) t.parents.removeIf(p -> p.equals(id));
        });
        refresh();
    }

    private void move(int delta) {
        int row = table.getSelectedRow();
        if (row < 0) return;
        int to = row + delta;
        if (to < 0 || to >= table.getRowCount()) return;
        moveRow(row, to);
        table.setRowSelectionInterval(to, to);
    }

    private void moveRow(int from, int to) {
        Quest q = model.selectedQuest();
        if (q == null) return;
        List<Integer> ids = new ArrayList<>(q.tasks.keySet());
        if (from < 0 || to < 0 || from >= ids.size() || to >= ids.size()) return;
        Integer moved = ids.remove(from);
        ids.add(to, moved);
        model.edit("Порядок задач", () -> {
            LinkedHashMap<Integer, Task> copy = new LinkedHashMap<>(q.tasks);
            q.tasks.clear();
            for (Integer id : ids) q.tasks.put(id, copy.get(id));
        });
        tableModel.fireTableDataChanged();
    }

    private void pickItem() {
        Task t = selectedTask();
        if (t == null) return;
        if (t.type.kind != Defs.TaskType.Kind.ITEM) {
            hint.setText("Для этого типа задачи предмет не используется");
            return;
        }
        String picked = ItemPickerDialog.choose(this, t.forgeName);
        if (picked == null) return;
        model.edit("Предмет задачи", () -> t.forgeName = picked);
        refresh();
    }

    private void copy() {
        Task t = selectedTask();
        if (t != null) {
            clipboardTask = t.copy();
            hint.setText("Задача скопирована");
        }
    }

    private void paste() {
        Quest q = model.selectedQuest();
        if (q == null || clipboardTask == null) return;
        model.edit("Вставка задачи", () -> q.tasks.put(q.nextTaskID(), clipboardTask.copy()));
        refresh();
    }

    private void renumber() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        List<Integer> before = new ArrayList<>(q.tasks.keySet());
        model.edit("Перенумерация задач", () -> {
            Map<Integer, Integer> remap = new LinkedHashMap<>();
            for (int i = 0; i < before.size(); i++) remap.put(before.get(i), i);
            q.renumberTasks();
            for (Task t : q.tasks.values()) {
                List<Integer> np = new ArrayList<>();
                for (int p : t.parents) {
                    Integer r = remap.get(p);
                    if (r != null) np.add(r);
                }
                t.parents.clear();
                t.parents.addAll(np);
            }
        });
        refresh();
    }

    private void updateHint() {
        Task t = selectedTask();
        if (t == null) {
            hint.setText(" ");
            return;
        }
        StringBuilder sb = new StringBuilder("В игре: ");
        sb.append(t.description(ru.dw.qeditor.assets.ItemLook.name(t.forgeName)));
        if (!t.type.serverSupported()) sb.append("   ⚠ тип не отслеживается сервером");
        hint.setText(sb.toString());
    }

    public void refresh() {
        tableModel.fireTableStructureChanged();
        table.getColumnModel().getColumn(1)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(Defs.TaskType.values())));
        table.getColumnModel().getColumn(0).setPreferredWidth(28);
        table.getColumnModel().getColumn(1).setPreferredWidth(120);
        table.getColumnModel().getColumn(2).setPreferredWidth(50);
        table.getColumnModel().getColumn(3).setPreferredWidth(170);
        table.getColumnModel().getColumn(3).setCellRenderer(new ItemRenderer());
        table.setEnabled(model.selectedQuest() != null);
        updateHint();
    }

    private final class ItemRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel,
                                                       boolean focus, int row, int col) {
            JLabel l = (JLabel) super.getTableCellRendererComponent(t, value, sel, focus, row, col);
            Quest q = model.selectedQuest();
            if (q != null) {
                List<Integer> ids = new ArrayList<>(q.tasks.keySet());
                if (row < ids.size()) {
                    Task task = q.tasks.get(ids.get(row));
                    if (task.type.kind == Defs.TaskType.Kind.ITEM)
                        l.setIcon(Ui.itemIcon(task.forgeName, 18));
                    else
                        l.setIcon(null);
                }
            }
            return l;
        }
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"№", "Тип", "Кол-во", "Предмет / класс", "Описание", "После задач"};

        private List<Integer> ids() {
            Quest q = model.selectedQuest();
            return q == null ? new ArrayList<>() : new ArrayList<>(q.tasks.keySet());
        }

        @Override public int getRowCount() { return ids().size(); }

        @Override public int getColumnCount() { return columns.length; }

        @Override public String getColumnName(int c) { return columns[c]; }

        @Override public boolean isCellEditable(int r, int c) { return true; }

        @Override
        public Object getValueAt(int r, int c) {
            Quest q = model.selectedQuest();
            List<Integer> ids = ids();
            if (q == null || r >= ids.size()) return null;
            int id = ids.get(r);
            Task t = q.tasks.get(id);
            switch (c) {
                case 0: return id;
                case 1: return t.type;
                case 2: return t.requiredCount;
                case 3: return t.type.kind == Defs.TaskType.Kind.KILL ? nz(t.className) : nz(t.forgeName);
                case 4: return t.type.kind == Defs.TaskType.Kind.KILL ? nz(t.displayName) : nz(t.customDescription);
                case 5: return join(t.parents);
                default: return null;
            }
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            Quest q = model.selectedQuest();
            List<Integer> ids = ids();
            if (q == null || r >= ids.size()) return;
            int id = ids.get(r);
            Task t = q.tasks.get(id);
            String s = value == null ? "" : String.valueOf(value).trim();

            model.edit("Изменение задачи", () -> {
                switch (c) {
                    case 0: {
                        int newID;
                        try { newID = Integer.parseInt(s); } catch (NumberFormatException e) { return; }
                        if (newID == id || q.tasks.containsKey(newID)) return;
                        LinkedHashMap<Integer, Task> copy = new LinkedHashMap<>(q.tasks);
                        q.tasks.clear();
                        for (Map.Entry<Integer, Task> en : copy.entrySet())
                            q.tasks.put(en.getKey() == id ? newID : en.getKey(), en.getValue());
                        for (Task other : q.tasks.values()) {
                            List<Integer> np = new ArrayList<>();
                            for (int p : other.parents) np.add(p == id ? newID : p);
                            other.parents.clear();
                            other.parents.addAll(np);
                        }
                        break;
                    }
                    case 1:
                        if (value instanceof Defs.TaskType) t.type = (Defs.TaskType) value;
                        break;
                    case 2:
                        try { t.requiredCount = Integer.parseInt(s); } catch (NumberFormatException ignored) { }
                        break;
                    case 3:
                        if (t.type.kind == Defs.TaskType.Kind.KILL) t.className = s;
                        else t.forgeName = s;
                        break;
                    case 4:
                        if (t.type.kind == Defs.TaskType.Kind.KILL) t.displayName = s;
                        else t.customDescription = s.isEmpty() ? null : s;
                        break;
                    case 5: {
                        t.parents.clear();
                        for (String part : s.split("[,;\\s]+")) {
                            if (part.trim().isEmpty()) continue;
                            try { t.parents.add(Integer.parseInt(part.trim())); } catch (NumberFormatException ignored) { }
                        }
                        break;
                    }
                    default:
                }
            });
            SwingUtilities.invokeLater(TasksPanel.this::updateHint);
        }

        private String nz(String s) { return s == null ? "" : s; }

        private String join(List<Integer> list) {
            StringBuilder sb = new StringBuilder();
            for (int v : list) {
                if (sb.length() > 0) sb.append(", ");
                sb.append(v);
            }
            return sb.toString();
        }
    }
}
