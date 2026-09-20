package ru.dw.qeditor.ui;

import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;

import javax.swing.DefaultCellEditor;
import javax.swing.JComboBox;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableColumn;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.List;

/** Список зависимостей квеста: родитель, тип зависимости, тип линии. */
public class ParentsPanel extends JPanel {

    private final EditorModel model;
    private final Model tableModel = new Model();
    private final JTable table = new JTable(tableModel);

    public ParentsPanel(EditorModel model) {
        this.model = model;
        setLayout(new BorderLayout(0, 4));

        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setRowHeight(22);
        table.getTableHeader().setReorderingAllowed(false);

        JScrollPane scroll = new JScrollPane(table);
        scroll.setPreferredSize(new Dimension(320, 110));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        buttons.add(Ui.smallBtn("Добавить", "Добавить зависимость", this::add));
        buttons.add(Ui.smallBtn("Удалить", "Удалить выбранную зависимость", this::remove));

        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    private void add() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        List<String> candidates = candidates(q);
        if (candidates.isEmpty()) return;
        model.edit("Добавление зависимости", () -> q.parents.add(new Parent(candidates.get(0))));
        refresh();
    }

    private void remove() {
        Quest q = model.selectedQuest();
        int row = table.getSelectedRow();
        if (q == null || row < 0 || row >= q.parents.size()) return;
        model.edit("Удаление зависимости", () -> q.parents.remove(row));
        refresh();
    }

    private List<String> candidates(Quest self) {
        List<String> out = new ArrayList<>();
        for (String id : model.tab().quests.keySet())
            if (!id.equals(self.questID)) out.add(id);
        return out;
    }

    public void refresh() {
        Quest q = model.selectedQuest();
        tableModel.fireTableDataChanged();
        table.setEnabled(q != null);
        if (q == null) return;

        TableColumn col0 = table.getColumnModel().getColumn(0);
        List<String> cand = candidates(q);
        for (Parent p : q.parents) if (p.questID != null && !cand.contains(p.questID)) cand.add(p.questID);
        col0.setCellEditor(new DefaultCellEditor(new JComboBox<>(cand.toArray(new String[0]))));

        table.getColumnModel().getColumn(1)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(Defs.ParentType.values())));
        table.getColumnModel().getColumn(2)
                .setCellEditor(new DefaultCellEditor(new JComboBox<>(Defs.LineType.values())));
        col0.setPreferredWidth(150);
    }

    private final class Model extends AbstractTableModel {
        private final String[] columns = {"Родитель", "Тип", "Линия"};

        @Override public int getRowCount() {
            Quest q = model.selectedQuest();
            return q == null ? 0 : q.parents.size();
        }

        @Override public int getColumnCount() { return columns.length; }

        @Override public String getColumnName(int c) { return columns[c]; }

        @Override public boolean isCellEditable(int r, int c) { return true; }

        @Override
        public Object getValueAt(int r, int c) {
            Quest q = model.selectedQuest();
            if (q == null || r >= q.parents.size()) return null;
            Parent p = q.parents.get(r);
            switch (c) {
                case 0: return p.questID;
                case 1: return p.parentType;
                case 2: return p.lineType;
                default: return null;
            }
        }

        @Override
        public void setValueAt(Object value, int r, int c) {
            Quest q = model.selectedQuest();
            if (q == null || r >= q.parents.size() || value == null) return;
            Parent p = q.parents.get(r);
            model.edit("Изменение зависимости", () -> {
                switch (c) {
                    case 0: p.questID = String.valueOf(value); break;
                    case 1:
                        p.parentType = (Defs.ParentType) value;
                        if (p.parentType != Defs.ParentType.DEFAULT) p.plainString = false;
                        break;
                    case 2:
                        p.lineType = (Defs.LineType) value;
                        if (p.lineType != Defs.LineType.DEFAULT) p.plainString = false;
                        break;
                    default:
                }
            });
        }
    }
}
