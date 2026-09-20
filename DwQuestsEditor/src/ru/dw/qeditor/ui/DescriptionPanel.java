package ru.dw.qeditor.ui;

import ru.dw.qeditor.model.Quest;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Insets;

/**
 * Редактор описания квеста: текст, кнопки §-форматирования, палитра 16 цветов
 * Minecraft и живой предпросмотр игровым шрифтом.
 */
public class DescriptionPanel extends JPanel {

    /** Цвета §0..§f из FontRenderer 1.7.10. */
    private static final int[] COLORS = {
            0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA,
            0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF
    };
    private static final String[] COLOR_NAMES = {
            "Чёрный", "Тёмно-синий", "Тёмно-зелёный", "Бирюзовый", "Тёмно-красный", "Фиолетовый",
            "Золотой", "Серый", "Тёмно-серый", "Синий", "Зелёный", "Голубой",
            "Красный", "Розовый", "Жёлтый", "Белый"
    };

    private final EditorModel model;
    private final JTextArea area = new JTextArea(6, 20);
    private final McTextPreview preview = new McTextPreview();
    private boolean quiet;

    public DescriptionPanel(EditorModel model) {
        this.model = model;
        setLayout(new BorderLayout(0, 6));
        setOpaque(false);
        setBorder(ru.dw.qeditor.ui.theme.Theme.padding(0, 12, 12, 12));

        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(new java.awt.Font(java.awt.Font.MONOSPACED, java.awt.Font.PLAIN, 12));
        area.getDocument().addDocumentListener(new DocumentListener() {
            private void go() {
                if (quiet) return;
                SwingUtilities.invokeLater(DescriptionPanel.this::commit);
            }

            @Override public void insertUpdate(DocumentEvent e) { go(); }

            @Override public void removeUpdate(DocumentEvent e) { go(); }

            @Override public void changedUpdate(DocumentEvent e) { go(); }
        });

        JPanel toolbar = new JPanel(new ru.dw.qeditor.ui.theme.WrapLayout(FlowLayout.LEFT, 4, 4));
        toolbar.setOpaque(false);
        toolbar.add(fmtButton("Ж", "§l — жирный", "§l"));
        toolbar.add(fmtButton("К", "§o — курсив", "§o"));
        toolbar.add(fmtButton("П", "§n — подчёркнутый", "§n"));
        toolbar.add(fmtButton("З", "§m — зачёркнутый", "§m"));
        toolbar.add(fmtButton("?", "§k — обфускация", "§k"));
        toolbar.add(fmtButton("R", "§r — сброс форматирования", "§r"));
        toolbar.add(Ui.smallBtn("<BR>", "Новый абзац", () -> insert("<BR>")));
        toolbar.add(Ui.smallBtn("<RI>", "Отступ строки", () -> insert("<RI>")));
        toolbar.add(Ui.smallBtn("IMG", "Вставить картинку из ресурсов мода", this::insertImage));
        toolbar.add(Ui.ghostBtn("1× / 2×", "Масштаб предпросмотра", () -> {
            preview.toggleScale();
            preview.revalidate();
            preview.repaint();
        }));

        JPanel palette = new JPanel(new FlowLayout(FlowLayout.LEFT, 3, 2));
        palette.setOpaque(false);
        for (int i = 0; i < COLORS.length; i++) {
            final char code = "0123456789abcdef".charAt(i);
            JButton b = new JButton();
            b.setBackground(new Color(COLORS[i]));
            b.setOpaque(true);
            b.setBorderPainted(true);
            b.setFocusable(false);
            b.setPreferredSize(new Dimension(20, 20));
            b.setBorder(javax.swing.BorderFactory.createLineBorder(
                    ru.dw.qeditor.ui.theme.Theme.BORDER));
            b.setMargin(new Insets(0, 0, 0, 0));
            b.setToolTipText("§" + code + " — " + COLOR_NAMES[i]);
            b.addActionListener(e -> insert("§" + code));
            palette.add(b);
        }

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.add(toolbar, BorderLayout.NORTH);
        top.add(palette, BorderLayout.SOUTH);

        JScrollPane areaScroll = new JScrollPane(area);
        areaScroll.setPreferredSize(new Dimension(320, 120));
        ru.dw.qeditor.ui.theme.Theme.styleScroll(areaScroll);
        areaScroll.setBorder(javax.swing.BorderFactory.createLineBorder(
                ru.dw.qeditor.ui.theme.Theme.BORDER));

        JPanel previewWrap = new JPanel(new BorderLayout(0, 4));
        previewWrap.setOpaque(false);
        previewWrap.add(ru.dw.qeditor.ui.theme.Theme.sectionTitle("Как это выглядит в игре"), BorderLayout.NORTH);
        JScrollPane previewScroll = new JScrollPane(preview);
        previewScroll.setPreferredSize(new Dimension(320, 160));
        ru.dw.qeditor.ui.theme.Theme.styleScroll(previewScroll);
        previewScroll.setBorder(javax.swing.BorderFactory.createLineBorder(
                ru.dw.qeditor.ui.theme.Theme.BORDER));
        previewWrap.add(previewScroll, BorderLayout.CENTER);
        previewWrap.setBorder(ru.dw.qeditor.ui.theme.Theme.padding(8, 0, 0, 0));

        add(top, BorderLayout.NORTH);
        add(areaScroll, BorderLayout.CENTER);
        add(previewWrap, BorderLayout.SOUTH);
    }

    private JButton fmtButton(String text, String tooltip, String code) {
        return Ui.smallBtn(text, tooltip, () -> insert(code));
    }

    private void insert(String code) {
        int start = area.getSelectionStart();
        int end = area.getSelectionEnd();
        if (start != end && !code.equals("<BR>") && !code.equals("<RI>")) {
            String selected = area.getSelectedText();
            area.replaceRange(code + selected + "§r", start, end);
            area.setCaretPosition(start + code.length() + selected.length() + 2);
        } else {
            area.insert(code, start);
            area.setCaretPosition(start + code.length());
        }
        area.requestFocusInWindow();
    }

    private void insertImage() {
        String s = JOptionPane.showInputDialog(this,
                "Формат: mod:textures/путь.png:u:v:ширина:высота[:масштаб]",
                "dwquests:textures/gui/backgrounds/Botania.png:0:0:256:128:0.5");
        if (s == null || s.trim().isEmpty()) return;
        insert("<IMG>" + s.trim() + "</IMG>");
    }

    private void commit() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        String text = area.getText();
        if (text.equals(q.description)) return;
        model.edit("Описание квеста", () -> q.description = text);
        preview.showQuest(q);
    }

    /** Обновить из модели. */
    public void refresh() {
        Quest q = model.selectedQuest();
        quiet = true;
        try {
            area.setText(q == null ? "" : (q.description == null ? "" : q.description));
            area.setCaretPosition(0);
        } finally {
            quiet = false;
        }
        area.setEnabled(q != null);
        preview.showQuest(q);
    }
}
