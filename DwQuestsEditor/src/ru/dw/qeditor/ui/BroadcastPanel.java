package ru.dw.qeditor.ui;

import ru.dw.qeditor.model.Quest;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * Оповещение в чат при выполнении квеста (поле {@code broadcast}).
 *
 * <p>Мод (QuestActionProcessor.broadcastCompleted) отправляет сообщение всем
 * игрокам один раз — в момент, когда квест становится выполненным, — подставляя
 * {@code %nick%}, {@code %player%}, {@code %quest%}, {@code %tab%} и переводя
 * {@code &}-коды в §.
 */
public class BroadcastPanel extends JPanel {

    private static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };

    private final EditorModel model;
    private final JTextArea area = new JTextArea(3, 30);
    private final McTextPreview preview = new McTextPreview();
    private final JLabel hint = new JLabel(" ");
    private boolean quiet;

    public BroadcastPanel(EditorModel model) {
        this.model = model;
        setLayout(new BorderLayout(0, 4));

        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void go() {
                if (quiet) return;
                SwingUtilities.invokeLater(BroadcastPanel.this::commit);
            }

            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { go(); }

            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { go(); }
        });

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        tools.add(Ui.smallBtn("%nick%", "Ник игрока", () -> insert("%nick%")));
        tools.add(Ui.smallBtn("%player%", "Ник игрока (синоним)", () -> insert("%player%")));
        tools.add(Ui.smallBtn("%quest%", "Название квеста", () -> insert("%quest%")));
        tools.add(Ui.smallBtn("%tab%", "Название вкладки", () -> insert("%tab%")));
        tools.add(Ui.smallBtn("Ж", "&l — жирный", () -> insert("&l")));
        tools.add(Ui.smallBtn("R", "&r — сброс", () -> insert("&r")));
        tools.add(Ui.smallBtn("Шаблон", "Подставить типовое оповещение", this::template));

        JPanel palette = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 2));
        for (int i = 0; i < COLORS.length; i++) {
            final char code = "0123456789abcdef".charAt(i);
            JButton b = new JButton();
            b.setBackground(new Color(COLORS[i]));
            b.setOpaque(true);
            b.setFocusable(false);
            b.setPreferredSize(new Dimension(18, 18));
            b.setMargin(new Insets(0, 0, 0, 0));
            b.setToolTipText("&" + code);
            b.addActionListener(e -> insert("&" + code));
            palette.add(b);
        }

        JPanel top = new JPanel(new BorderLayout());
        top.add(tools, BorderLayout.NORTH);
        top.add(palette, BorderLayout.SOUTH);

        JScrollPane areaScroll = new JScrollPane(area);
        areaScroll.setPreferredSize(new Dimension(320, 70));
        areaScroll.setBorder(BorderFactory.createTitledBorder("Текст оповещения"));

        preview.setLabelName("Так увидят в чате", 0xFFFFAA00);
        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setPreferredSize(new Dimension(320, 110));

        JPanel south = new JPanel(new BorderLayout());
        south.add(previewScroll, BorderLayout.CENTER);
        south.add(hint, BorderLayout.SOUTH);
        hint.setFont(hint.getFont().deriveFont(java.awt.Font.PLAIN, 11f));
        hint.setText("Отправляется всем игрокам один раз, когда квест становится выполненным.");

        add(top, BorderLayout.NORTH);
        add(areaScroll, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
    }

    private void template() {
        area.setText("&6[Квесты] &eИгрок &b%nick% &eвыполнил квест &a%quest% &eво вкладке &a%tab%&e!");
    }

    private void insert(String s) {
        int pos = area.getCaretPosition();
        area.insert(s, pos);
        area.setCaretPosition(pos + s.length());
        area.requestFocusInWindow();
    }

    private void commit() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        String text = area.getText();
        String value = text.isEmpty() ? null : text;
        if (eq(value, q.broadcast)) return;
        model.edit("Оповещение квеста", () -> q.broadcast = value);
        updatePreview(q);
    }

    /** Показать так, как это увидит игрок: плейсхолдеры подставлены, & → §. */
    private void updatePreview(Quest q) {
        String text = area.getText();
        if (text.isEmpty()) {
            preview.setText("§7(оповещения нет — квест выполняется молча)");
            hint.setText("Отправляется всем игрокам один раз, когда квест становится выполненным.");
            return;
        }
        String resolved = text
                .replace("%nick%", "Steve")
                .replace("%player%", "Steve")
                .replace("%quest%", q == null ? "Квест" : q.displayNameOrID())
                .replace("%tab%", model.tab().tabID == null ? "Вкладка" : model.tab().tabID)
                .replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
        preview.setText(resolved);
        hint.setText("Ник в примере — Steve. Мод сам переводит &-коды в §.");
    }

    public void refresh() {
        Quest q = model.selectedQuest();
        quiet = true;
        try {
            area.setText(q == null || q.broadcast == null ? "" : q.broadcast);
        } finally {
            quiet = false;
        }
        area.setEnabled(q != null);
        updatePreview(q);
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }
}
