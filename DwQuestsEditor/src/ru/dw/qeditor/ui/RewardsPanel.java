package ru.dw.qeditor.ui;

import ru.dw.qeditor.assets.Gifts;
import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.util.ForgeName;

import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import javax.swing.AbstractListModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;

/**
 * Награды квеста. Строка награды может быть предметом
 * ({@code mod:item:meta=count{nbt}}), командой ({@code command:…}) или
 * гифтом ({@code DwQuests:Gift:meta{…}}) — тип определяется по самой строке.
 */
public class RewardsPanel extends JPanel {

    public enum Kind { ITEM, COMMAND, GIFT, SCRIPT }

    private final EditorModel model;
    private final Model listModel = new Model();
    private final JList<String> list = new JList<>(listModel);

    public RewardsPanel(EditorModel model) {
        this.model = model;
        setLayout(new BorderLayout(0, 4));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(24);
        list.setCellRenderer(new Renderer());
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) editSelected();
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        scroll.setPreferredSize(new Dimension(320, 110));

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        buttons.add(Ui.smallBtn("Предмет…", "Добавить предмет", this::addItem));
        buttons.add(Ui.smallBtn("Гифт…", "Добавить гифт", this::addGift));
        buttons.add(Ui.smallBtn("Команда…", "Добавить команду", this::addCommand));
        buttons.add(Ui.smallBtn("Скрипт…", "Добавить награду-скрипт CustomNPCs", this::addScript));
        buttons.add(Ui.smallBtn("NBT…", "Правка предмета и его NBT вручную", this::editNbt));
        buttons.add(Ui.smallBtn("Изменить…", "Изменить выбранную", this::editSelected));
        buttons.add(Ui.smallBtn("Кол-во…", "Изменить количество", this::editCount));
        buttons.add(Ui.smallBtn("Удалить", "Удалить выбранную", this::remove));
        buttons.add(Ui.smallBtn("↑", "Выше", () -> move(-1)));
        buttons.add(Ui.smallBtn("↓", "Ниже", () -> move(1)));

        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    public static Kind kindOf(String reward) {
        ForgeName fn = ForgeName.parse(reward);
        if (fn.isCommand()) return Kind.COMMAND;
        if (Gifts.isGift(reward)) return Kind.GIFT;
        if (fn.isScript()) return Kind.SCRIPT;
        return Kind.ITEM;
    }

    // ------------------------------------------------------------------ действия

    private void addItem() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        String s = ItemPickerDialog.choose(this, "minecraft:diamond");
        if (s == null) return;
        model.edit("Добавление награды", () -> q.rewards.add(s));
        refresh();
    }

    private void addGift() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        String s = GiftDialog.edit(this, null);
        if (s == null) return;
        model.edit("Добавление гифта", () -> q.rewards.add(s));
        refresh();
    }

    private void addCommand() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        String cmd = JOptionPane.showInputDialog(this,
                "Команда (без ведущего /), подставляется @p:", "give @p minecraft:diamond 1");
        if (cmd == null || cmd.trim().isEmpty()) return;
        model.edit("Добавление команды", () -> q.rewards.add("command:" + cmd.trim()));
        refresh();
    }

    private void addScript() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        String s = NbtItemDialog.editScript(this, "customnpcs:scripted_item");
        if (s == null) return;
        model.edit("Добавление скрипта", () -> q.rewards.add(s));
        refresh();
    }

    private void editNbt() {
        Quest q = model.selectedQuest();
        int i = list.getSelectedIndex();
        if (q == null || i < 0 || i >= q.rewards.size()) return;
        String s = NbtItemDialog.edit(this, q.rewards.get(i));
        if (s == null) return;
        model.edit("Правка NBT награды", () -> q.rewards.set(i, s));
        refresh();
    }

    private void editSelected() {
        Quest q = model.selectedQuest();
        int i = list.getSelectedIndex();
        if (q == null || i < 0 || i >= q.rewards.size()) return;
        String current = q.rewards.get(i);
        String edited;
        switch (kindOf(current)) {
            case GIFT:
                edited = GiftDialog.edit(this, current);
                break;
            case COMMAND: {
                String cmd = JOptionPane.showInputDialog(this, "Команда:",
                        current.substring("command:".length()));
                edited = cmd == null || cmd.trim().isEmpty() ? null : "command:" + cmd.trim();
                break;
            }
            case SCRIPT:
                edited = NbtItemDialog.editScript(this, current);
                break;
            default:
                edited = ForgeName.parse(current).nbt != null
                        ? NbtItemDialog.edit(this, current)
                        : ItemPickerDialog.choose(this, current);
        }
        if (edited == null) return;
        final String value = edited;
        model.edit("Изменение награды", () -> q.rewards.set(i, value));
        refresh();
    }

    private void editCount() {
        Quest q = model.selectedQuest();
        int i = list.getSelectedIndex();
        if (q == null || i < 0 || i >= q.rewards.size()) return;
        String current = q.rewards.get(i);
        ForgeName fn = ForgeName.parse(current);
        if (fn.isCommand()) return;
        String s = JOptionPane.showInputDialog(this, "Количество:", fn.hasCount ? fn.count : 1);
        if (s == null) return;
        int n;
        try { n = Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return; }
        String value = fn.withCount(Math.max(1, n));
        model.edit("Количество награды", () -> q.rewards.set(i, value));
        refresh();
    }

    private void remove() {
        Quest q = model.selectedQuest();
        int i = list.getSelectedIndex();
        if (q == null || i < 0 || i >= q.rewards.size()) return;
        model.edit("Удаление награды", () -> q.rewards.remove(i));
        refresh();
    }

    private void move(int delta) {
        Quest q = model.selectedQuest();
        int i = list.getSelectedIndex();
        if (q == null || i < 0) return;
        int to = i + delta;
        if (to < 0 || to >= q.rewards.size()) return;
        model.edit("Порядок наград", () -> {
            String s = q.rewards.remove(i);
            q.rewards.add(to, s);
        });
        refresh();
        list.setSelectedIndex(to);
    }

    public void refresh() {
        listModel.changed();
        list.setEnabled(model.selectedQuest() != null);
    }

    private final class Model extends AbstractListModel<String> {
        private int lastSize;

        void changed() {
            int old = lastSize;
            lastSize = getSize();
            if (old > 0) fireIntervalRemoved(this, 0, old - 1);
            if (lastSize > 0) fireIntervalAdded(this, 0, lastSize - 1);
        }

        @Override
        public int getSize() {
            Quest q = model.selectedQuest();
            return q == null ? 0 : q.rewards.size();
        }

        @Override
        public String getElementAt(int index) {
            Quest q = model.selectedQuest();
            List<String> r = q == null ? null : q.rewards;
            return r == null || index >= r.size() ? "" : r.get(index);
        }
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean sel, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, sel, focus);
            String s = String.valueOf(value);
            Kind kind = kindOf(s);
            if (kind == Kind.COMMAND) {
                l.setIcon(Ui.itemIcon("minecraft:command_block", 18));
                l.setText("Команда: " + s.substring("command:".length()));
            } else {
                l.setIcon(Ui.itemIcon(s, 18));
                String tag = kind == Kind.GIFT ? " [гифт]" : kind == Kind.SCRIPT ? " [скрипт]" : "";
                l.setText(ItemLook.name(s) + " × " + ItemLook.count(s) + tag);
            }
            l.setToolTipText(s);
            l.setIconTextGap(6);
            return l;
        }
    }
}
