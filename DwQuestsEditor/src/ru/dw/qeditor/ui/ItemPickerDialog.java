package ru.dw.qeditor.ui;

import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.util.ForgeName;

import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;

/**
 * Выбор предмета: поиск по русскому имени, id или числовому id, фильтр по моду,
 * список с ленивой подгрузкой иконок и ручной ввод строки с метой/количеством/NBT.
 */
public class ItemPickerDialog extends JDialog {

    private final JTextField search = new JTextField();
    private final JComboBox<String> modFilter = new JComboBox<>();
    private final JTextField manual = new JTextField();
    private final JCheckBox keepExtras = new JCheckBox("Сохранить количество и NBT", true);
    private final JLabel preview = new JLabel();
    private final JList<ItemRegistry.Entry> list;
    private final Model model = new Model();
    private final Timer searchTimer;

    private final String original;
    private String result;

    private ItemPickerDialog(Window owner, String current) {
        super(owner, "Выбор предмета", ModalityType.APPLICATION_MODAL);
        this.original = current == null ? "" : current;

        list = new JList<>(model);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new Renderer());
        list.setFixedCellHeight(26);
        list.setVisibleRowCount(16);

        searchTimer = new Timer(180, e -> refresh());
        searchTimer.setRepeats(false);

        modFilter.addItem("(все моды)");
        for (String m : ItemRegistry.get().mods()) modFilter.addItem(m);
        modFilter.addActionListener(e -> refresh());

        search.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void go() { searchTimer.restart(); }

            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });
        search.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_DOWN && model.getSize() > 0) {
                    list.requestFocusInWindow();
                    list.setSelectedIndex(0);
                } else if (e.getKeyCode() == KeyEvent.VK_ENTER) {
                    if (model.getSize() > 0) { list.setSelectedIndex(0); accept(); }
                }
            }
        });

        list.addListSelectionListener(e -> {
            ItemRegistry.Entry sel = list.getSelectedValue();
            if (sel != null) Ui.quietSet(manual, buildFrom(sel.key));
            updatePreview();
        });
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) accept();
            }
        });

        manual.setText(original);
        manual.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void go() { SwingUtilities.invokeLater(ItemPickerDialog.this::updatePreview); }

            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });

        JPanel top = new JPanel(new BorderLayout(4, 4));
        top.setBorder(BorderFactory.createEmptyBorder(6, 6, 2, 6));
        top.add(new JLabel("Поиск:"), BorderLayout.WEST);
        top.add(search, BorderLayout.CENTER);
        top.add(modFilter, BorderLayout.EAST);

        JPanel bottom = new JPanel(new BorderLayout(4, 4));
        bottom.setBorder(BorderFactory.createEmptyBorder(2, 6, 6, 6));
        JPanel manualRow = new JPanel(new BorderLayout(4, 0));
        manualRow.add(new JLabel("Строка:"), BorderLayout.WEST);
        manualRow.add(manual, BorderLayout.CENTER);
        manualRow.add(preview, BorderLayout.EAST);
        bottom.add(manualRow, BorderLayout.NORTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        buttons.add(keepExtras);
        JButton ok = Ui.btn("Выбрать", this::accept);
        buttons.add(ok);
        buttons.add(Ui.btn("Отмена", () -> { result = null; dispose(); }));
        bottom.add(buttons, BorderLayout.SOUTH);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(new JScrollPane(list), BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);

        ForgeName fn = ForgeName.parse(original);
        if (!fn.name.isEmpty()) search.setText(fn.name);
        refresh();
        updatePreview();

        setSize(640, 520);
        setLocationRelativeTo(owner);
    }

    /** @return новая строка предмета либо null, если отменено. */
    public static String choose(Component parent, String current) {
        Window w = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        ItemPickerDialog d = new ItemPickerDialog(w, current);
        d.setVisible(true);
        return d.result;
    }

    private void refresh() {
        String mod = modFilter.getSelectedIndex() <= 0 ? null : (String) modFilter.getSelectedItem();
        model.set(ItemRegistry.get().search(search.getText(), mod, 500));
    }

    /** Подставить новый id, сохранив количество и NBT исходной строки. */
    private String buildFrom(String key) {
        if (!keepExtras.isSelected()) return key;
        ForgeName fn = ForgeName.parse(manual.getText().isEmpty() ? original : manual.getText());
        return fn.withId(key);
    }

    private void updatePreview() {
        String s = manual.getText().trim();
        preview.setIcon(Ui.itemIcon(s, 24));
        preview.setToolTipText(ItemRegistry.get().displayName(s));
    }

    private void accept() {
        String s = manual.getText().trim();
        if (s.isEmpty()) {
            ItemRegistry.Entry sel = list.getSelectedValue();
            if (sel == null) return;
            s = sel.key;
        }
        result = s;
        dispose();
    }

    private static final class Model extends AbstractListModel<ItemRegistry.Entry> {
        private List<ItemRegistry.Entry> items = new ArrayList<>();

        void set(List<ItemRegistry.Entry> items) {
            int old = this.items.size();
            this.items = items;
            if (old > 0) fireIntervalRemoved(this, 0, old - 1);
            if (!items.isEmpty()) fireIntervalAdded(this, 0, items.size() - 1);
        }

        @Override public int getSize() { return items.size(); }

        @Override public ItemRegistry.Entry getElementAt(int index) { return items.get(index); }
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel l = (JLabel) super.getListCellRendererComponent(list, value, index, selected, focus);
            if (value instanceof ItemRegistry.Entry) {
                ItemRegistry.Entry e = (ItemRegistry.Entry) value;
                l.setText("<html>" + escape(e.name) + "  <font color=gray>" + escape(e.key) + "</font></html>");
                l.setIcon(Ui.itemIcon(e.key, 20));
                l.setIconTextGap(6);
                l.setPreferredSize(new Dimension(l.getPreferredSize().width, 26));
            }
            return l;
        }

        private static String escape(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
