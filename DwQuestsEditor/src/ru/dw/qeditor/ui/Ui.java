package ru.dw.qeditor.ui;

import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.ui.theme.FlatButton;
import ru.dw.qeditor.ui.theme.RoundedBorder;
import ru.dw.qeditor.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Мелкие помощники для Swing-форм редактора. */
public final class Ui {

    private Ui() { }

    public static final Color ACCENT = Theme.ACCENT;

    // ------------------------------------------------------------------ формы

    public static JPanel form() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setOpaque(false);
        p.putClientProperty("row", 0);
        return p;
    }

    public static void row(JPanel form, String label, Component field) {
        int r = (Integer) form.getClientProperty("row");
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = r;
        c.anchor = GridBagConstraints.WEST;
        c.insets = new Insets(3, 0, 3, 10);
        JLabel l = new JLabel(label);
        l.setForeground(Theme.TEXT_MUTED);
        l.setFont(Theme.font(Theme.FONT_SIZE_SMALL + 1));
        form.add(l, c);

        c = new GridBagConstraints();
        c.gridx = 1;
        c.gridy = r;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        form.add(field, c);
        form.putClientProperty("row", r + 1);
    }

    public static void rowFull(JPanel form, Component field) {
        int r = (Integer) form.getClientProperty("row");
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = 0;
        c.gridy = r;
        c.gridwidth = 2;
        c.weightx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        c.insets = new Insets(3, 0, 3, 0);
        form.add(field, c);
        form.putClientProperty("row", r + 1);
    }

    public static JPanel titled(String title, Component inner) {
        ru.dw.qeditor.ui.theme.Card card = new ru.dw.qeditor.ui.theme.Card(title);
        card.content((JComponent) inner);
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        return card;
    }

    public static JPanel vertical() {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.Y_AXIS));
        return p;
    }

    public static JPanel horizontal(Component... items) {
        JPanel p = new JPanel();
        p.setOpaque(false);
        p.setLayout(new javax.swing.BoxLayout(p, javax.swing.BoxLayout.X_AXIS));
        for (Component c : items) {
            p.add(c);
            p.add(Box.createHorizontalStrut(4));
        }
        return p;
    }

    /** Компоненты в ряд, прижатые влево и не растягивающиеся на всю ширину. */
    public static JPanel left(Component... items) {
        JPanel p = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0));
        p.setOpaque(false);
        for (Component c : items) p.add(c);
        return p;
    }

    // ------------------------------------------------------------------ поля

    public static JButton btn(String text, Runnable action) {
        return FlatButton.normal(text, action);
    }

    public static JButton primary(String text, Runnable action) {
        return FlatButton.primary(text, action);
    }

    public static JButton smallBtn(String text, String tooltip, Runnable action) {
        return FlatButton.normal(text, action).small().tip(tooltip);
    }

    public static JButton ghostBtn(String text, String tooltip, Runnable action) {
        return FlatButton.ghost(text, action).small().tip(tooltip);
    }

    /** Текстовое поле, сообщающее об изменении по мере ввода (без рекурсии). */
    public static JTextField text(String value, Consumer<String> onChange) {
        JTextField f = new JTextField(value == null ? "" : value);
        Theme.styleInput(f);
        f.getDocument().addDocumentListener(new DocumentListener() {
            private void go() {
                if (Boolean.TRUE.equals(f.getClientProperty("quiet"))) return;
                SwingUtilities.invokeLater(() -> onChange.accept(f.getText()));
            }

            @Override public void insertUpdate(DocumentEvent e) { go(); }

            @Override public void removeUpdate(DocumentEvent e) { go(); }

            @Override public void changedUpdate(DocumentEvent e) { go(); }
        });
        return f;
    }

    /** Установить текст, не вызывая обработчик. */
    public static void quietSet(JTextField f, String value) {
        if (f == null) return;
        String v = value == null ? "" : value;
        if (f.getText().equals(v)) return;
        f.putClientProperty("quiet", Boolean.TRUE);
        f.setText(v);
        f.putClientProperty("quiet", Boolean.FALSE);
    }

    public static <T> JComboBox<T> combo(T[] values, T selected, Consumer<T> onChange) {
        JComboBox<T> cb = new JComboBox<>(values);
        cb.setFont(Theme.font(Theme.FONT_SIZE));
        cb.setSelectedItem(selected);
        cb.addActionListener(e -> {
            if (Boolean.TRUE.equals(cb.getClientProperty("quiet"))) return;
            @SuppressWarnings("unchecked") T v = (T) cb.getSelectedItem();
            onChange.accept(v);
        });
        return cb;
    }

    public static <T> void quietSelect(JComboBox<T> cb, T value) {
        if (cb == null) return;
        cb.putClientProperty("quiet", Boolean.TRUE);
        cb.setSelectedItem(value);
        cb.putClientProperty("quiet", Boolean.FALSE);
    }

    public static JSpinner spinner(double value, double min, double max, double step, Consumer<Double> onChange) {
        JSpinner s = new JSpinner(new SpinnerNumberModel(value, min, max, step));
        s.setFont(Theme.font(Theme.FONT_SIZE));
        s.setBorder(new RoundedBorder(Theme.RADIUS_SMALL, Theme.BORDER, 2, 3));
        s.addChangeListener(e -> {
            if (Boolean.TRUE.equals(s.getClientProperty("quiet"))) return;
            onChange.accept(((Number) s.getValue()).doubleValue());
        });
        return s;
    }

    public static void quietSet(JSpinner s, double value) {
        if (s == null) return;
        s.putClientProperty("quiet", Boolean.TRUE);
        s.setValue(value);
        s.putClientProperty("quiet", Boolean.FALSE);
    }

    // ------------------------------------------------------------------ иконки

    private static final Map<String, ImageIcon> ICONS = new HashMap<>();

    /** Иконка предмета для Swing (гифты собираются из слоёв). */
    public static ImageIcon itemIcon(String forgeString, int size) {
        String key = forgeString + "@" + size;
        ImageIcon cached = ICONS.get(key);
        if (cached != null) return cached;
        BufferedImage src = ItemLook.icon(forgeString);
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        if (src != null) g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        ImageIcon icon = new ImageIcon(out);
        if (ICONS.size() > 3000) ICONS.clear();
        ICONS.put(key, icon);
        return icon;
    }

    public static void clearIconCache() { ICONS.clear(); }

    /** Кнопка-квадратик с иконкой предмета, открывающая выбор предмета. */
    public static JButton itemButton(String forgeString, int size, Runnable onClick) {
        JButton b = new FlatButton("", FlatButton.Kind.NORMAL, onClick) {
            @Override
            public Dimension getPreferredSize() {
                return new Dimension(size + 14, size + 12);
            }
        };
        b.setIcon(itemIcon(forgeString, size));
        b.setToolTipText(forgeString);
        return b;
    }

    public static void setItemIcon(JButton b, String forgeString, int size) {
        b.setIcon(itemIcon(forgeString, size));
        b.setToolTipText(forgeString);
    }

    public static void fixHeight(JComponent c, int height) {
        c.setMaximumSize(new Dimension(Integer.MAX_VALUE, height));
        c.setPreferredSize(new Dimension(c.getPreferredSize().width, height));
    }
}
