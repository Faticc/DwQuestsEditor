package ru.dw.qeditor.ui;

import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.ui.theme.SegmentedTabs;
import ru.dw.qeditor.ui.theme.Theme;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JTextField;
import java.awt.BorderLayout;
import java.awt.Dimension;

/**
 * Правая колонка редактора: свойства вкладки и выбранного квеста, описание,
 * задачи, награды и зависимости.
 */
public class SidebarPanel extends JPanel implements EditorModel.Listener {

    private final EditorModel model;

    // вкладка
    private final JTextField tabID;
    private final JButton tabIcon;
    private final JSpinner tabSort;
    private final JLabel tabInfo = new JLabel();

    // квест
    private final JTextField questID;
    private final JTextField questName;
    private final JButton questIcon;
    private final JSpinner questX;
    private final JSpinner questY;
    private final JComboBox<Defs.Rarity> rarity;
    private final JComboBox<Defs.IconSize> iconSize;
    private final JComboBox<Defs.NodeType> nodeType;
    private final JComboBox<Defs.TasksType> tasksType;
    private final JTextField broadcast;
    private final JLabel questHint = new JLabel(" ");

    private final DescriptionPanel description;
    private final TasksPanel tasks;
    private final RewardsPanel rewards;
    private final ParentsPanel parents;
    private final BroadcastPanel broadcastPanel;

    private boolean refreshing;

    public SidebarPanel(EditorModel model) {
        this.model = model;
        model.addListener(this);

        // ---------- вкладка
        tabID = Ui.text("", v -> onTabIDChanged(v));
        tabIcon = Ui.itemButton("minecraft:book", 24, this::pickTabIcon);
        tabSort = Ui.spinner(0, -999, 999, 1, v -> {
            if (refreshing) return;
            model.edit("sortIndex вкладки", () -> model.tab().sortIndex = (int) (double) v);
        });

        JPanel tabForm = Ui.form();
        Ui.row(tabForm, "tabID", tabID);
        Ui.row(tabForm, "Иконка", Ui.left(tabIcon));
        Ui.row(tabForm, "sortIndex", tabSort);
        Ui.rowFull(tabForm, tabInfo);

        // ---------- квест
        questID = Ui.text("", this::onQuestIDChanged);
        questName = Ui.text("", v -> {
            Quest q = model.selectedQuest();
            if (refreshing || q == null || v.equals(nz(q.displayName))) return;
            model.edit("Название квеста", () -> q.displayName = v);
        });
        questIcon = Ui.itemButton("minecraft:bedrock", 24, this::pickQuestIcon);
        questX = Ui.spinner(0, -9999, 9999, 1, v -> setPos(v, null));
        questY = Ui.spinner(0, -9999, 9999, 1, v -> setPos(null, v));
        rarity = Ui.combo(Defs.Rarity.values(), Defs.Rarity.UNCOMMON, v -> apply("Редкость", q -> q.rarity = v));
        iconSize = Ui.combo(Defs.IconSize.values(), Defs.IconSize.DEFAULT, v -> apply("Размер иконки", q -> q.iconSize = v));
        nodeType = Ui.combo(Defs.NodeType.values(), Defs.NodeType.NORMAL, v -> apply("Тип узла", q -> q.nodeType = v));
        tasksType = Ui.combo(Defs.TasksType.values(), Defs.TasksType.DEFAULT, v -> apply("Режим задач", q -> q.tasksType = v));
        broadcast = Ui.text("", v -> { });
        broadcast.setVisible(false);

        JPanel questForm = Ui.form();
        Ui.row(questForm, "questID", questID);
        Ui.row(questForm, "Название", questName);
        Ui.row(questForm, "Иконка и место", Ui.horizontal(questIcon,
                Theme.caption("X"), questX, Theme.caption("Y"), questY));
        Ui.row(questForm, "Редкость", rarity);
        Ui.row(questForm, "Размер узла", Ui.horizontal(iconSize, nodeType));
        Ui.row(questForm, "Режим задач", tasksType);
        Ui.rowFull(questForm, questHint);
        questHint.setFont(Theme.font(Theme.FONT_SIZE_SMALL));
        questHint.setForeground(Theme.TEXT_MUTED);

        description = new DescriptionPanel(model);
        tasks = new TasksPanel(model);
        rewards = new RewardsPanel(model);
        parents = new ParentsPanel(model);
        broadcastPanel = new BroadcastPanel(model);

        // ---------- сборка
        JPanel head = Ui.vertical();
        head.add(Ui.titled("Вкладка", tabForm));
        head.add(javax.swing.Box.createVerticalStrut(10));
        head.add(Ui.titled("Квест", questForm));

        head.setBorder(Theme.padding(12, 12, 4, 12));
        JScrollPane headScroll = new JScrollPane(head);
        Theme.styleScroll(headScroll);

        SegmentedTabs details = new SegmentedTabs().compact();
        details.addTab("Описание", description);
        details.addTab("Задачи", tasks);
        details.addTab("Награды", rewards);
        details.addTab("Связи", parents);
        details.addTab("Оповещение", broadcastPanel);

        setLayout(new BorderLayout());
        setBackground(Theme.BG);
        setBorder(Theme.leftLine());
        javax.swing.JSplitPane vertical =
                new javax.swing.JSplitPane(javax.swing.JSplitPane.VERTICAL_SPLIT, headScroll, details);
        vertical.setResizeWeight(0);
        javax.swing.SwingUtilities.invokeLater(() -> {
            int pref = head.getPreferredSize().height + 16;
            int limit = Math.max(260, (int) (getHeight() * 0.58));
            vertical.setDividerLocation(Math.min(pref, limit));
        });
        vertical.setBorder(null);
        vertical.setOpaque(false);
        vertical.setContinuousLayout(true);
        add(vertical, BorderLayout.CENTER);
        setPreferredSize(new Dimension(410, 800));

        refresh();
    }

    // ------------------------------------------------------------------ правки

    private interface QuestEdit { void apply(Quest q); }

    private void apply(String label, QuestEdit edit) {
        if (refreshing) return;
        Quest q = model.selectedQuest();
        if (q == null) return;
        model.edit(label, () -> edit.apply(q));
    }

    private void setPos(Double x, Double y) {
        if (refreshing) return;
        Quest q = model.selectedQuest();
        if (q == null) return;
        model.edit("Координаты квеста", () -> {
            if (x != null) q.displayX = (float) (double) x;
            if (y != null) q.displayY = (float) (double) y;
        });
    }

    private void onTabIDChanged(String v) {
        if (refreshing) return;
        QuestTab tab = model.tab();
        if (v.equals(tab.tabID)) return;
        model.edit("tabID", () -> tab.tabID = v);
    }

    private void onQuestIDChanged(String v) {
        if (refreshing) return;
        String old = model.single();
        if (old == null || v.equals(old) || v.trim().isEmpty()) return;
        if (model.tab().quests.containsKey(v)) {
            questHint.setText("Квест с таким id уже есть");
            return;
        }
        model.edit("Переименование квеста", () -> model.tab().renameQuest(old, v));
        model.select(v);
    }

    private void pickTabIcon() {
        String s = ItemPickerDialog.choose(this, model.tab().iconItem);
        if (s == null) return;
        model.edit("Иконка вкладки", () -> model.tab().iconItem = s);
    }

    private void pickQuestIcon() {
        Quest q = model.selectedQuest();
        if (q == null) return;
        String s = ItemPickerDialog.choose(this, q.iconItem);
        if (s == null) return;
        model.edit("Иконка квеста", () -> q.iconItem = s);
    }

    // ------------------------------------------------------------------ обновление

    @Override
    public void modelChanged(String what) {
        refresh();
    }

    public void refresh() {
        if (refreshing) return;
        refreshing = true;
        try {
            QuestTab tab = model.tab();
            Ui.quietSet(tabID, tab.tabID);
            Ui.setItemIcon(tabIcon, tab.iconItem, 24);
            Ui.quietSet(tabSort, tab.sortIndex);
            int taskCount = 0, rewardCount = 0;
            for (Quest q : tab.quests.values()) { taskCount += q.tasks.size(); rewardCount += q.rewards.size(); }
            tabInfo.setText("<html>Квестов: <b>" + tab.quests.size() + "</b>, задач: " + taskCount
                    + ", наград: " + rewardCount + "</html>");

            Quest q = model.selectedQuest();
            boolean one = q != null;
            questID.setEnabled(one);
            questName.setEnabled(one);
            questIcon.setEnabled(one);
            questX.setEnabled(one);
            questY.setEnabled(one);
            rarity.setEnabled(one);
            iconSize.setEnabled(one);
            nodeType.setEnabled(one);
            tasksType.setEnabled(one);
            broadcast.setEnabled(one);

            if (one) {
                Ui.quietSet(questID, q.questID);
                Ui.quietSet(questName, nz(q.displayName));
                Ui.setItemIcon(questIcon, q.iconItem, 24);
                Ui.quietSet(questX, q.displayX);
                Ui.quietSet(questY, q.displayY);
                Ui.quietSelect(rarity, q.rarity);
                Ui.quietSelect(iconSize, q.iconSize);
                Ui.quietSelect(nodeType, q.nodeType);
                Ui.quietSelect(tasksType, q.tasksType);
                Ui.quietSet(broadcast, nz(q.broadcast));
                questHint.setText("В игре покажется: " + q.displayNameOrID()
                        + " [0/" + q.tasksCount() + "]");
            } else {
                Ui.quietSet(questID, "");
                Ui.quietSet(questName, "");
                Ui.quietSet(broadcast, "");
                int n = model.selection().size();
                questHint.setText(n > 1 ? "Выделено квестов: " + n : "Квест не выбран");
            }

            description.refresh();
            tasks.refresh();
            rewards.refresh();
            parents.refresh();
            broadcastPanel.refresh();
        } finally {
            refreshing = false;
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }

    /** Показать сообщение в строке подсказки под свойствами квеста. */
    public void hint(String text) { questHint.setText(text); }

    public static void info(java.awt.Component parent, String message) {
        JOptionPane.showMessageDialog(parent, message, "DwQuests Editor", JOptionPane.INFORMATION_MESSAGE);
    }
}
