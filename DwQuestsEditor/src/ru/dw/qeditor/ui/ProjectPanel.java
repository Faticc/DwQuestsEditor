package ru.dw.qeditor.ui;

import ru.dw.qeditor.io.Workspace;
import ru.dw.qeditor.ui.theme.Theme;

import javax.swing.AbstractListModel;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ListSelectionModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.util.function.Consumer;

/**
 * Список вкладок открытой папки. Переключение между вкладками одним щелчком —
 * без диалога «открыть файл».
 */
public class ProjectPanel extends JPanel {

    private final Workspace workspace;
    private final Model model = new Model();
    private final JList<Workspace.Entry> list = new JList<>(model);
    private final JLabel header = Theme.sectionTitle("Папка не открыта");
    private final JLabel info = Theme.caption(" ");

    private Consumer<Workspace.Entry> onOpen;
    private Runnable onNewTab;
    private Runnable onChooseFolder;
    private File currentFile;

    public ProjectPanel(Workspace workspace) {
        this.workspace = workspace;
        setLayout(new BorderLayout(0, 6));
        setOpaque(false);
        setBorder(Theme.padding(12, 12, 12, 8));
        setPreferredSize(new Dimension(240, 400));

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(34);
        list.setCellRenderer(new Renderer());
        list.setOpaque(false);
        list.setBackground(Theme.BG);
        list.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Workspace.Entry sel = list.getSelectedValue();
                if (sel != null && onOpen != null) onOpen.accept(sel);
            }
        });

        JScrollPane scroll = new JScrollPane(list);
        Theme.styleScroll(scroll);

        JPanel top = new JPanel(new BorderLayout(0, 4));
        top.setOpaque(false);
        top.add(header, BorderLayout.NORTH);
        top.add(info, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new ru.dw.qeditor.ui.theme.WrapLayout(FlowLayout.LEFT, 4, 4));
        buttons.setOpaque(false);
        buttons.add(Ui.smallBtn("Папка…", "Открыть папку с вкладками", () -> {
            if (onChooseFolder != null) onChooseFolder.run();
        }));
        buttons.add(Ui.smallBtn("+ Вкладка", "Создать вкладку в этой папке", () -> {
            if (onNewTab != null) onNewTab.run();
        }));
        buttons.add(Ui.ghostBtn("Обновить", "Перечитать список", this::refresh));

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);
    }

    public void setOnOpen(Consumer<Workspace.Entry> onOpen) { this.onOpen = onOpen; }

    public void setOnNewTab(Runnable onNewTab) { this.onNewTab = onNewTab; }

    public void setOnChooseFolder(Runnable onChooseFolder) { this.onChooseFolder = onChooseFolder; }

    /** Подсветить вкладку, открытую сейчас. */
    public void setCurrentFile(File file) {
        this.currentFile = file;
        Workspace.Entry e = workspace.find(file);
        if (e != null) list.setSelectedValue(e, true);
        else list.clearSelection();
        repaint();
    }

    public void refresh() {
        workspace.rescan();
        model.changed();
        if (workspace.isOpen()) {
            header.setText(workspace.name().toUpperCase());
            header.setToolTipText(workspace.dir().getAbsolutePath());
            info.setText("вкладок: " + workspace.entries().size() + ", квестов: " + workspace.totalQuests());
        } else {
            header.setText("ПАПКА НЕ ОТКРЫТА");
            header.setToolTipText(null);
            info.setText("Файл → Открыть папку…");
        }
        setCurrentFile(currentFile);
    }

    private final class Model extends AbstractListModel<Workspace.Entry> {
        private int lastSize;

        void changed() {
            int old = lastSize;
            lastSize = getSize();
            if (old > 0) fireIntervalRemoved(this, 0, old - 1);
            if (lastSize > 0) fireIntervalAdded(this, 0, lastSize - 1);
        }

        @Override public int getSize() { return workspace.entries().size(); }

        @Override public Workspace.Entry getElementAt(int index) { return workspace.entries().get(index); }
    }

    private final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean selected, boolean focus) {
            JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, selected, focus);
            if (!(value instanceof Workspace.Entry)) return c;
            Workspace.Entry e = (Workspace.Entry) value;

            boolean current = currentFile != null && e.file.getAbsolutePath().equals(currentFile.getAbsolutePath());
            String sub = e.error != null
                    ? "<font color='#D9635F'>не читается</font>"
                    : "<font color='#8A99A8'>квестов: " + e.questCount + "</font>";
            c.setText("<html><b>" + escape(e.title()) + "</b>" + (current ? " •" : "")
                    + "<br>" + sub + "</html>");
            c.setIcon(e.iconItem == null || e.iconItem.isEmpty()
                    ? null : Ui.itemIcon(e.iconItem, 22));
            c.setIconTextGap(8);
            c.setBorder(Theme.padding(2, 8, 2, 6));
            c.setToolTipText(e.file.getAbsolutePath()
                    + (e.error == null ? "" : "\n" + e.error));
            if (!selected) c.setBackground(current ? Theme.SURFACE_2 : Theme.BG);
            c.setOpaque(true);
            return c;
        }

        private String escape(String s) {
            return s == null ? "" : s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
