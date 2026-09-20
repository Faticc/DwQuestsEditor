package ru.dw.qeditor.ui;

import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.NbtLite;
import ru.dw.qeditor.util.ForgeName;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;

/**
 * Редактор предмета с произвольным NBT — в том числе награды-скрипта
 * {@code customnpcs:scripted_item}.
 *
 * <p>Схема NBT у скриптовых предметов зависит от сборки сервера, поэтому здесь
 * два уровня: удобные поля для имени и лора (display.Name / display.Lore) и
 * полный текст NBT, который можно править руками с проверкой синтаксиса.
 */
public class NbtItemDialog extends JDialog {

    private final JTextField id = new JTextField();
    private final JSpinner count = new JSpinner(new SpinnerNumberModel(1, 1, 9999, 1));
    private final JTextField name = new JTextField();
    private final JTextArea lore = new JTextArea(3, 30);
    private final JTextArea nbt = new JTextArea(10, 40);
    private final JLabel status = new JLabel(" ");
    private final JLabel preview = new JLabel();

    private String result;
    private boolean syncing;

    private NbtItemDialog(Window owner, String title, String reward, String defaultId) {
        super(owner, title, ModalityType.APPLICATION_MODAL);

        ForgeName fn = ForgeName.parse(reward == null || reward.isEmpty() ? defaultId : reward);
        id.setText(fn.id);
        count.setValue(fn.hasCount ? Math.max(1, fn.count) : 1);
        nbt.setText(fn.nbt == null ? "" : fn.nbt);
        nbt.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        nbt.setLineWrap(true);
        lore.setLineWrap(true);

        readFieldsFromNbt();

        name.getDocument().addDocumentListener(simple(this::writeFieldsToNbt));
        lore.getDocument().addDocumentListener(simple(this::writeFieldsToNbt));
        nbt.getDocument().addDocumentListener(simple(() -> {
            readFieldsFromNbt();
            updatePreview();
        }));
        id.getDocument().addDocumentListener(simple(this::updatePreview));

        JPanel head = Ui.form();
        JPanel idRow = new JPanel(new BorderLayout(4, 0));
        idRow.add(id, BorderLayout.CENTER);
        idRow.add(Ui.smallBtn("…", "Выбрать предмет", () -> {
            String s = ItemPickerDialog.choose(this, id.getText());
            if (s != null) id.setText(ForgeName.parse(s).id);
        }), BorderLayout.EAST);
        Ui.row(head, "Предмет:", idRow);
        Ui.row(head, "Количество:", count);
        Ui.row(head, "Имя (display.Name):", name);
        Ui.row(head, "Вид:", preview);

        JScrollPane loreScroll = new JScrollPane(lore);
        loreScroll.setBorder(BorderFactory.createTitledBorder("Лор (display.Lore) — по строке на строку"));
        loreScroll.setPreferredSize(new Dimension(460, 70));

        JScrollPane nbtScroll = new JScrollPane(nbt);
        nbtScroll.setBorder(BorderFactory.createTitledBorder("NBT целиком"));
        nbtScroll.setPreferredSize(new Dimension(460, 200));

        JPanel center = new JPanel(new BorderLayout(0, 6));
        center.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        center.add(head, BorderLayout.NORTH);
        center.add(loreScroll, BorderLayout.CENTER);
        center.add(nbtScroll, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 4));
        buttons.add(status);
        buttons.add(Ui.smallBtn("Проверить NBT", "Разобрать и переписать аккуратно", this::normalize));
        JButton ok = Ui.btn("Готово", this::accept);
        buttons.add(ok);
        buttons.add(Ui.btn("Отмена", () -> { result = null; dispose(); }));

        setLayout(new BorderLayout());
        add(center, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(ok);
        updatePreview();
        pack();
        setLocationRelativeTo(owner);
    }

    /** Редактор произвольного предмета с NBT. */
    public static String edit(Component parent, String reward) {
        return open(parent, "Предмет с NBT", reward, "minecraft:diamond");
    }

    /** Редактор награды-скрипта CustomNPCs. */
    public static String editScript(Component parent, String reward) {
        return open(parent, "Награда-скрипт (customnpcs:scripted_item)",
                reward, "customnpcs:scripted_item");
    }

    private static String open(Component parent, String title, String reward, String defaultId) {
        Window w = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        NbtItemDialog d = new NbtItemDialog(w, title, reward, defaultId);
        d.setVisible(true);
        return d.result;
    }

    // ------------------------------------------------------------------ синхронизация

    private void readFieldsFromNbt() {
        if (syncing) return;
        syncing = true;
        try {
            String text = nbt.getText().trim();
            if (text.isEmpty()) {
                name.setText("");
                lore.setText("");
                status.setText(" ");
                return;
            }
            NbtLite.Compound c = NbtLite.parse(text);
            if (c == null) {
                status.setText("NBT не разобрался");
                return;
            }
            status.setText(" ");
            NbtLite.Compound display = c.comp("display");
            name.setText(display == null ? "" : nz(display.string("Name")));
            Object loreTag = display == null ? null : display.get("Lore");
            if (loreTag instanceof NbtLite.ListTag) {
                StringBuilder sb = new StringBuilder();
                for (Object o : (NbtLite.ListTag) loreTag) {
                    if (sb.length() > 0) sb.append('\n');
                    sb.append(String.valueOf(o));
                }
                lore.setText(sb.toString());
            } else {
                lore.setText(loreTag == null ? "" : String.valueOf(loreTag));
            }
        } finally {
            syncing = false;
        }
    }

    private void writeFieldsToNbt() {
        if (syncing) return;
        syncing = true;
        try {
            String text = nbt.getText().trim();
            NbtLite.Compound c = text.isEmpty() ? new NbtLite.Compound() : NbtLite.parse(text);
            if (c == null) {
                status.setText("NBT не разобрался — поля выше не применены");
                return;
            }
            NbtLite.Compound display = c.comp("display");
            if (display == null) {
                display = new NbtLite.Compound();
                c.put("display", display);
            }
            String n = name.getText();
            if (n.isEmpty()) display.remove("Name");
            else display.put("Name", n);

            String l = lore.getText();
            if (l.isEmpty()) {
                display.remove("Lore");
            } else if (l.contains("\n")) {
                NbtLite.ListTag list = new NbtLite.ListTag();
                for (String line : l.split("\n")) list.add(line);
                display.put("Lore", list);
            } else {
                display.put("Lore", l);
            }
            if (display.isEmpty()) c.remove("display");

            nbt.setText(c.isEmpty() ? "" : NbtLite.write(c));
            status.setText(" ");
            updatePreview();
        } finally {
            syncing = false;
        }
    }

    private void normalize() {
        String text = nbt.getText().trim();
        if (text.isEmpty()) {
            status.setText("NBT пуст — это допустимо");
            return;
        }
        NbtLite.Compound c = NbtLite.parse(text);
        if (c == null) {
            status.setText("Ошибка: NBT не разобрался");
            return;
        }
        syncing = true;
        try {
            nbt.setText(NbtLite.write(c));
        } finally {
            syncing = false;
        }
        readFieldsFromNbt();
        status.setText("NBT в порядке");
        updatePreview();
    }

    private String build() {
        String nbtText = nbt.getText().trim();
        return ForgeName.build(id.getText().trim(),
                ((Number) count.getValue()).intValue(),
                nbtText.isEmpty() ? null : nbtText);
    }

    private void updatePreview() {
        String s = build();
        preview.setIcon(Ui.itemIcon(s, 24));
        preview.setText("  " + ItemLook.name(s));
    }

    private void accept() {
        String nbtText = nbt.getText().trim();
        if (!nbtText.isEmpty() && NbtLite.parse(nbtText) == null) {
            status.setText("Ошибка: NBT не разобрался, исправь перед сохранением");
            return;
        }
        result = build();
        dispose();
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static javax.swing.event.DocumentListener simple(Runnable action) {
        return new javax.swing.event.DocumentListener() {
            private void go() { SwingUtilities.invokeLater(action); }

            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        };
    }
}
