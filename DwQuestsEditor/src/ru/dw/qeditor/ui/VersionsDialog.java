package ru.dw.qeditor.ui;

import ru.dw.qeditor.io.Backups;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.ui.theme.Theme;

import javax.swing.AbstractListModel;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Window;
import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * История версий вкладки: список копий, сводка отличий от текущего состояния и
 * восстановление. Перед самим восстановлением делается ещё одна копия, так что
 * откат тоже обратим.
 */
public class VersionsDialog extends JDialog {

    private final File tabFile;
    private final QuestTab current;
    private final Model model = new Model();
    private final JList<Backups.Version> list = new JList<>(model);
    private final JTextArea details = new JTextArea();
    private final JLabel summary = Theme.caption(" ");

    private QuestTab restored;

    private VersionsDialog(Window owner, File tabFile, QuestTab current) {
        super(owner, "История версий", ModalityType.APPLICATION_MODAL);
        this.tabFile = tabFile;
        this.current = current;

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setFixedCellHeight(30);
        list.setCellRenderer(new Renderer());
        list.addListSelectionListener(e -> showDetails());

        details.setEditable(false);
        details.setFont(Theme.mono(12));

        JScrollPane listScroll = new JScrollPane(list);
        Theme.styleScroll(listScroll);
        listScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));
        listScroll.setPreferredSize(new Dimension(330, 420));

        JScrollPane detailsScroll = new JScrollPane(details);
        Theme.styleScroll(detailsScroll);
        detailsScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, listScroll, detailsScroll);
        split.setResizeWeight(0.42);
        split.setBorder(null);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(Theme.padding(10, 12, 6, 12));
        JLabel title = new JLabel(tabFile == null
                ? "Вкладка ещё не сохранена в файл — копии лежат в каталоге черновиков"
                : tabFile.getName());
        title.setForeground(Theme.TEXT);
        top.add(title, BorderLayout.NORTH);
        top.add(summary, BorderLayout.SOUTH);

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 8));
        buttons.setOpaque(false);
        buttons.add(Ui.smallBtn("Открыть папку версий", "Показать файлы копий", this::openFolder));
        buttons.add(Ui.smallBtn("Удалить версию", "Убрать выбранную копию", this::deleteSelected));
        JButton restore = Ui.primary("Восстановить", this::restoreSelected);
        buttons.add(restore);
        buttons.add(Ui.btn("Закрыть", this::dispose));

        JPanel content = new JPanel(new BorderLayout());
        content.setBorder(Theme.padding(0, 12, 0, 12));
        content.setOpaque(false);
        content.add(split, BorderLayout.CENTER);

        setLayout(new BorderLayout());
        add(top, BorderLayout.NORTH);
        add(content, BorderLayout.CENTER);
        add(buttons, BorderLayout.SOUTH);

        refresh();
        setSize(940, 560);
        setLocationRelativeTo(owner);
    }

    /**
     * @return восстановленная вкладка либо null, если ничего не восстанавливали
     */
    public static QuestTab open(Component parent, File tabFile, QuestTab current) {
        Window w = parent == null ? null : SwingUtilities.getWindowAncestor(parent);
        VersionsDialog d = new VersionsDialog(w, tabFile, current);
        d.setVisible(true);
        return d.restored;
    }

    private void refresh() {
        model.reload();
        summary.setText(Backups.summary(tabFile));
        if (model.getSize() > 0) list.setSelectedIndex(0);
        else details.setText("Копий пока нет.\n\nОни появятся при сохранении вкладки, а также\n"
                + "перед рискованными действиями — правкой помощником,\nраскладкой сеткой, "
                + "переключением на другую вкладку.");
    }

    private void showDetails() {
        Backups.Version v = list.getSelectedValue();
        if (v == null) {
            details.setText("");
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Время:   ").append(v.humanTime()).append('\n');
        sb.append("Причина: ").append(v.reason.ru).append('\n');
        sb.append("Файл:    ").append(v.file.getName()).append('\n');
        sb.append("Размер:  ").append(v.size).append(" байт\n\n");
        try {
            QuestTab old = Backups.read(v);
            sb.append(diff(old, current));
        } catch (Exception e) {
            sb.append("Не удалось прочитать копию: ").append(e.getMessage());
        }
        details.setText(sb.toString());
        details.setCaretPosition(0);
    }

    /** Чем эта версия отличается от того, что открыто сейчас. */
    private static String diff(QuestTab old, QuestTab now) {
        StringBuilder sb = new StringBuilder("--- Отличия версии от текущего состояния ---\n");
        if (now == null) return "Текущая вкладка недоступна.";
        if (!eq(old.tabID, now.tabID)) sb.append("tabID: ").append(old.tabID)
                .append(" → ").append(now.tabID).append('\n');
        if (old.sortIndex != now.sortIndex) sb.append("sortIndex: ").append(old.sortIndex)
                .append(" → ").append(now.sortIndex).append('\n');

        Set<String> all = new LinkedHashSet<>(old.quests.keySet());
        all.addAll(now.quests.keySet());
        List<String> onlyOld = new ArrayList<>();
        List<String> onlyNow = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String id : all) {
            Quest a = old.quests.get(id);
            Quest b = now.quests.get(id);
            if (b == null) onlyOld.add(id);
            else if (a == null) onlyNow.add(id);
            else if (!a.toJson().toString().equals(b.toJson().toString())) changed.add(id);
        }
        line(sb, "Было в версии, сейчас нет", onlyOld);
        line(sb, "Появилось после версии", onlyNow);
        line(sb, "Изменилось", changed);
        if (onlyOld.isEmpty() && onlyNow.isEmpty() && changed.isEmpty())
            sb.append("Квесты совпадают с текущими.\n");
        sb.append("\nКвестов в версии: ").append(old.quests.size())
                .append(", сейчас: ").append(now.quests.size()).append('\n');
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, List<String> ids) {
        if (ids.isEmpty()) return;
        sb.append(label).append(" (").append(ids.size()).append("): ")
                .append(String.join(", ", ids)).append('\n');
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private void restoreSelected() {
        Backups.Version v = list.getSelectedValue();
        if (v == null) return;
        int answer = javax.swing.JOptionPane.showConfirmDialog(this,
                "Вернуть вкладку к версии от " + v.humanTime() + "?\n"
                        + "Текущее состояние сначала сохранится отдельной копией.",
                "Восстановление", javax.swing.JOptionPane.YES_NO_OPTION);
        if (answer != javax.swing.JOptionPane.YES_OPTION) return;
        try {
            restored = Backups.read(v);
            dispose();
        } catch (Exception e) {
            javax.swing.JOptionPane.showMessageDialog(this,
                    "Не удалось прочитать копию:\n" + e.getMessage(),
                    "Ошибка", javax.swing.JOptionPane.ERROR_MESSAGE);
        }
    }

    private void deleteSelected() {
        Backups.Version v = list.getSelectedValue();
        if (v == null) return;
        if (javax.swing.JOptionPane.showConfirmDialog(this,
                "Удалить копию от " + v.humanTime() + "?", "Удаление версии",
                javax.swing.JOptionPane.YES_NO_OPTION) != javax.swing.JOptionPane.YES_OPTION) return;
        //noinspection ResultOfMethodCallIgnored
        v.file.delete();
        refresh();
    }

    private void openFolder() {
        File dir = Backups.versionsDir(tabFile);
        try {
            if (dir != null && dir.isDirectory()) java.awt.Desktop.getDesktop().open(dir);
        } catch (Exception ignored) { }
    }

    private final class Model extends AbstractListModel<Backups.Version> {
        private List<Backups.Version> items = new ArrayList<>();

        void reload() {
            int old = items.size();
            items = Backups.list(tabFile);
            if (old > 0) fireIntervalRemoved(this, 0, old - 1);
            if (!items.isEmpty()) fireIntervalAdded(this, 0, items.size() - 1);
        }

        @Override public int getSize() { return items.size(); }

        @Override public Backups.Version getElementAt(int index) { return items.get(index); }
    }

    private static final class Renderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int index,
                                                      boolean sel, boolean focus) {
            JLabel c = (JLabel) super.getListCellRendererComponent(l, value, index, sel, focus);
            if (value instanceof Backups.Version) {
                Backups.Version v = (Backups.Version) value;
                c.setText("<html>" + v.humanTime()
                        + "  <font color='#8A99A8'>" + v.reason.ru + "</font></html>");
                c.setBorder(Theme.padding(2, 8, 2, 6));
            }
            return c;
        }
    }
}
