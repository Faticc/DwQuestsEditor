package ru.dw.qeditor.ui;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.io.Backups;
import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.io.Workspace;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.render.GamePreview;
import ru.dw.qeditor.render.GuiPainter;
import ru.dw.qeditor.ui.theme.Card;
import ru.dw.qeditor.ui.theme.FlatButton;
import ru.dw.qeditor.ui.theme.SegmentedTabs;
import ru.dw.qeditor.ui.theme.Theme;
import ru.dw.qeditor.util.SelfTest;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.JCheckBox;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSpinner;
import javax.swing.JSplitPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Главное окно редактора. */
public class MainFrame extends JFrame implements EditorModel.Listener {

    private final EditorModel model = new EditorModel();
    private final Workspace workspace = new Workspace();
    private final ProjectPanel projectPanel = new ProjectPanel(workspace);
    private final EditorCanvas canvas = new EditorCanvas(model);
    private final PreviewPanel previewPanel = new PreviewPanel();
    private final SidebarPanel sidebar = new SidebarPanel(model);
    private final AssistantPanel assistant = new AssistantPanel(model);
    private final SegmentedTabs tabs = new SegmentedTabs();
    private final JLabel statusBar = new JLabel(" ");

    private JSplitPane projectSplit;
    private JMenuItem undoItem;
    private JMenuItem redoItem;

    private final List<Quest> questClipboard = new ArrayList<>();

    public MainFrame() {
        super("DwQuests Editor");
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);

        model.addListener(this);
        canvas.setCallbacks(new CanvasCallbacks());
        previewPanel.preview().setGuiScale(Config.get().guiScale);
        previewPanel.preview().setOnTabSwitched(t ->
                status("Просмотр: " + t.tabID + ", квестов: " + t.quests.size()
                        + "   •   правится по-прежнему " + model.tab().tabID));
        previewPanel.setOnQuestOpened(() -> {
            String id = previewPanel.preview().getOpenQuestID();
            if (id != null) model.select(id);
        });

        tabs.addTab("Редактор", buildEditorTab());
        tabs.addTab("Просмотр как в игре", previewPanel);
        tabs.addTab("Помощник Claude", assistant);
        tabs.setOnChange(i -> onTabChanged());
        tabs.setBorder(Theme.padding(0, 16, 0, 8));

        projectPanel.setOnOpen(e -> openTabFile(e.file));
        projectPanel.setOnNewTab(this::newTabInFolder);
        projectPanel.setOnChooseFolder(this::openFolder);

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, tabs, sidebar);
        split.setResizeWeight(1);
        split.setDividerLocation(840);
        split.setBorder(null);
        split.setOpaque(false);
        split.setContinuousLayout(true);

        JSplitPane withProject = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, projectPanel, split);
        withProject.setResizeWeight(0);
        withProject.setDividerLocation(230);
        withProject.setBorder(null);
        withProject.setOpaque(false);
        withProject.setContinuousLayout(true);
        this.projectSplit = withProject;

        statusBar.setBorder(Theme.padding(6, 16, 6, 16));
        statusBar.setForeground(Theme.TEXT_MUTED);
        statusBar.setFont(Theme.font(Theme.FONT_SIZE_SMALL + 1));
        JPanel statusWrap = new JPanel(new BorderLayout());
        statusWrap.setBackground(Theme.SURFACE);
        statusWrap.setBorder(Theme.topLine());
        statusWrap.add(statusBar, BorderLayout.CENTER);

        getContentPane().setBackground(Theme.BG);
        setLayout(new BorderLayout());
        add(withProject, BorderLayout.CENTER);
        add(statusWrap, BorderLayout.SOUTH);

        JMenuBar menuBar = buildMenu();
        menuBar.setBackground(Theme.SURFACE);
        menuBar.setBorder(Theme.bottomLine());
        setJMenuBar(menuBar);

        addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { exit(); }
        });

        setSize(1500, 950);
        setLocationRelativeTo(null);

        startWatchTimer();
        startAutosaveTimer();
        updateTitle();
        checkAssets();
        restoreLastFolder();
    }

    // ------------------------------------------------------------------ раскладка

    private JPanel buildEditorTab() {
        JPanel p = new JPanel(new BorderLayout());
        p.setOpaque(false);
        p.add(buildToolbar(), BorderLayout.NORTH);

        JPanel canvasWrap = new JPanel(new BorderLayout());
        canvasWrap.setBackground(Theme.SURFACE);
        canvasWrap.setBorder(javax.swing.BorderFactory.createLineBorder(Theme.BORDER));
        canvasWrap.add(canvas, BorderLayout.CENTER);
        p.add(canvasWrap, BorderLayout.CENTER);
        p.setBorder(Theme.padding(0, 0, 12, 0));
        return p;
    }

    private JPanel toolGroup(java.awt.Component... items) {
        JPanel g = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        g.setOpaque(false);
        for (java.awt.Component c : items) g.add(c);
        return g;
    }

    private JPanel separator() {
        JPanel sep = new JPanel();
        sep.setPreferredSize(new Dimension(1, 22));
        sep.setBackground(Theme.BORDER);
        return sep;
    }

    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new ru.dw.qeditor.ui.theme.WrapLayout(FlowLayout.LEFT, 8, 6));
        bar.setOpaque(false);
        bar.setBorder(Theme.padding(2, 0, 8, 0));

        bar.add(FlatButton.primary("Добавить квест", () -> addQuest(0, 0)).small());
        bar.add(FlatButton.normal("Дублировать", this::duplicateSelected).small());
        bar.add(FlatButton.danger("Удалить", this::deleteSelected).small());
        bar.add(separator());

        bar.add(Theme.caption("сетка"));
        JSpinner grid = new JSpinner(new SpinnerNumberModel(1, 1, 16, 1));
        grid.setPreferredSize(new Dimension(54, 26));
        grid.addChangeListener(e -> canvas.setGridSize(((Number) grid.getValue()).intValue()));
        bar.add(grid);

        JSpinner offX = new JSpinner(new SpinnerNumberModel(0.0, -8.0, 8.0, 0.5));
        offX.setPreferredSize(new Dimension(60, 26));
        JSpinner offY = new JSpinner(new SpinnerNumberModel(0.0, -8.0, 8.0, 0.5));
        offY.setPreferredSize(new Dimension(60, 26));
        Runnable applyOffset = () -> canvas.setGridOffset(
                ((Number) offX.getValue()).floatValue(), ((Number) offY.getValue()).floatValue());
        offX.addChangeListener(e -> applyOffset.run());
        offY.addChangeListener(e -> applyOffset.run());
        bar.add(Theme.caption("сдвиг"));
        bar.add(offX);
        bar.add(offY);

        bar.add(toggle("сетка", true, canvas::setShowGrid));
        bar.add(toggle("привязка", true, canvas::setSnap));
        bar.add(toggle("имена", true, canvas::setShowNames));
        bar.add(toggle("связи", true, canvas::setShowLinks));
        bar.add(toggle("скрытые", false, canvas::setShowHiddenLinks));
        bar.add(separator());

        bar.add(FlatButton.ghost("Вписать", canvas::fitToContent).small());
        bar.add(FlatButton.ghost("Разложить сеткой", this::autoLayout).small());
        bar.add(separator());

        bar.add(Theme.caption("прогресс"));
        JComboBox<GamePreview.Mode> mode = new JComboBox<>(GamePreview.Mode.values());
        mode.setFont(Theme.font(Theme.FONT_SIZE_SMALL + 1));
        mode.addActionListener(e -> {
            previewPanel.preview().setMode((GamePreview.Mode) mode.getSelectedItem());
            previewPanel.repaint();
        });
        bar.add(mode);

        bar.add(Theme.caption("guiScale"));
        JSpinner scale = new JSpinner(new SpinnerNumberModel(Config.get().guiScale, 1, 4, 1));
        scale.setPreferredSize(new Dimension(50, 26));
        scale.addChangeListener(e -> {
            int v = ((Number) scale.getValue()).intValue();
            Config.get().guiScale = v;
            previewPanel.preview().setGuiScale(v);
            previewPanel.repaint();
        });
        bar.add(scale);

        return bar;
    }

    /** Переключатель без «квадратика» Swing — плоская кнопка с состоянием. */
    private JComponent toggle(String text, boolean initial, java.util.function.Consumer<Boolean> onChange) {
        final boolean[] state = {initial};
        FlatButton b = new FlatButton(text, FlatButton.Kind.NORMAL, null).small();
        Runnable paint = () -> b.fg(state[0] ? Theme.ACCENT : Theme.TEXT_MUTED);
        b.addActionListener(e -> {
            state[0] = !state[0];
            onChange.accept(state[0]);
            paint.run();
        });
        paint.run();
        return b;
    }

    private JMenuBar buildMenu() {
        JMenuBar mb = new JMenuBar();

        JMenu file = new JMenu("Файл");
        file.add(item("Новая вкладка", KeyEvent.VK_N, this::newTab));
        file.add(item("Открыть…", KeyEvent.VK_O, this::open));
        file.add(item("Открыть папку…", 0, this::openFolder));
        file.add(item("Сохранить", KeyEvent.VK_S, this::save));
        file.add(item("Сохранить как…", 0, this::saveAs));
        file.addSeparator();
        file.add(item("Вставить вкладку из буфера", 0, this::pasteTab));
        file.add(item("Копировать вкладку в буфер", 0, this::copyTab));
        file.add(item("Копировать для /dwq tab paste", 0, this::copyTabCompact));
        file.addSeparator();
        file.add(item("Перечитать файл", 0, this::reloadFile));
        file.addSeparator();
        file.add(item("Выход", 0, this::exit));
        mb.add(file);

        JMenu edit = new JMenu("Правка");
        undoItem = item("Отменить", KeyEvent.VK_Z, this::undo);
        redoItem = item("Повторить", KeyEvent.VK_Y, this::redo);
        edit.add(undoItem);
        edit.add(redoItem);
        edit.addSeparator();
        edit.add(item("Добавить квест", 0, () -> addQuest(0, 0)));
        edit.add(item("Дублировать квесты", KeyEvent.VK_D, this::duplicateSelected));
        edit.add(item("Удалить квесты", 0, this::deleteSelected));
        edit.add(item("Выделить всё", KeyEvent.VK_A, model::selectAll));
        edit.addSeparator();
        edit.add(item("Копировать квесты", KeyEvent.VK_C, this::copyQuests));
        edit.add(item("Вставить квесты", KeyEvent.VK_V, () -> pasteQuests(0, 0)));
        mb.add(edit);

        JMenu view = new JMenu("Вид");
        view.add(item("Вписать вкладку", 0, canvas::fitToContent));
        view.add(item("К выбранному квесту", 0, () -> {
            String id = model.single();
            if (id != null) canvas.centerOn(id);
        }));
        view.addSeparator();
        JCheckBoxMenuItem showProject = new JCheckBoxMenuItem("Список вкладок папки", true);
        showProject.addActionListener(e -> {
            projectPanel.setVisible(showProject.isSelected());
            projectSplit.setDividerLocation(showProject.isSelected() ? 230 : 0);
            projectSplit.setDividerSize(showProject.isSelected() ? 6 : 0);
        });
        view.add(showProject);
        JCheckBoxMenuItem watch = new JCheckBoxMenuItem("Следить за файлом", Config.get().watchFile);
        watch.addActionListener(e -> {
            Config.get().watchFile = watch.isSelected();
            Config.get().save();
        });
        view.add(watch);
        mb.add(view);

        JMenu tools = new JMenu("Инструменты");
        tools.add(item("История версий…", KeyEvent.VK_H, this::showVersions));
        tools.add(item("Сохранить версию сейчас", 0, () -> {
            File made = Backups.save(model.file(), model.tab().toJsonString(),
                    Backups.Reason.MANUAL, Config.get().backupKeep);
            status(made == null ? "Не удалось сохранить версию" : "Версия сохранена: " + made.getName());
        }));
        tools.addSeparator();
        tools.add(item("Проверить вкладку", KeyEvent.VK_T, this::validateTab));
        tools.add(item("Диагностика окружения", 0, this::diagnostics));
        tools.add(item("Настройки…", 0, this::settings));
        tools.addSeparator();
        tools.add(item("Помощник Claude", 0, () -> tabs.setSelectedIndex(2)));
        tools.add(item("Показать JSON вкладки", 0, this::showJson));
        mb.add(tools);

        JMenu help = new JMenu("Справка");
        help.add(item("Горячие клавиши", 0, this::showHelp));
        help.add(item("О программе", 0, this::showAbout));
        mb.add(help);

        return mb;
    }

    private JMenuItem item(String text, int key, Runnable action) {
        JMenuItem mi = new JMenuItem(text);
        if (key != 0) mi.setAccelerator(KeyStroke.getKeyStroke(key, InputEvent.CTRL_DOWN_MASK));
        mi.addActionListener(e -> action.run());
        return mi;
    }

    // ------------------------------------------------------------------ файл

    private void newTab() {
        if (!confirmDiscard()) return;
        String id = JOptionPane.showInputDialog(this, "Имя вкладки (tabID):", "NewTab");
        if (id == null || id.trim().isEmpty()) return;
        model.setTab(QuestTab.empty(id.trim()), null, false);
        canvas.fitToContent();
    }

    private void open() {
        if (!confirmDiscard()) return;
        JFileChooser fc = chooser();
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        try {
            QuestTab tab = TabIO.load(f);
            model.setTab(tab, f, false);
            selectFirstQuest();
            Config.get().lastDir = f.getParent();
            Config.get().save();
            canvas.fitToContent();
            if (!workspace.isOpen() && f.getParentFile() != null) {
                workspace.open(f.getParentFile());
                Config.get().lastFolder = f.getParent();
                projectPanel.refresh();
            }
            projectPanel.setCurrentFile(f);
            status("Открыто: " + f.getName() + ", квестов: " + tab.quests.size()
                    + "   •   " + Backups.summary(f));
        } catch (Exception e) {
            error("Не удалось открыть файл", e);
        }
    }

    /** При запуске вернуть папку, с которой работали в прошлый раз. */
    private void restoreLastFolder() {
        String last = Config.get().lastFolder;
        if (last == null || last.isEmpty()) return;
        File dir = new File(last);
        if (!dir.isDirectory()) return;
        workspace.open(dir);
        projectPanel.refresh();
        status("Папка: " + dir.getName() + ", вкладок: " + workspace.entries().size()
                + ", квестов: " + workspace.totalQuests());
    }

    /** Открыть папку с вкладками — список появится слева. */
    private void openFolder() {
        JFileChooser fc = new JFileChooser();
        fc.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        fc.setDialogTitle("Папка с вкладками квестов");
        String last = Config.get().lastFolder.isEmpty() ? Config.get().lastDir : Config.get().lastFolder;
        if (last != null && !last.isEmpty()) fc.setCurrentDirectory(new File(last));
        if (fc.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File dir = fc.getSelectedFile();
        workspace.open(dir);
        Config.get().lastFolder = dir.getAbsolutePath();
        Config.get().save();
        projectPanel.refresh();

        if (workspace.entries().isEmpty()) {
            status("В папке нет вкладок: " + dir.getName());
            return;
        }
        status("Папка открыта: " + dir.getName() + ", вкладок: " + workspace.entries().size()
                + ", квестов: " + workspace.totalQuests());
        Workspace.Entry first = workspace.entries().get(0);
        if (model.file() == null && !model.isDirty()) openTabFile(first.file);
        else projectPanel.setCurrentFile(model.file());
    }

    /** Переключиться на вкладку из списка папки. */
    private void openTabFile(File f) {
        if (f == null || !f.isFile()) return;
        if (model.file() != null && f.getAbsolutePath().equals(model.file().getAbsolutePath())) return;
        if (!confirmDiscard(Backups.Reason.BEFORE_OPEN)) {
            projectPanel.setCurrentFile(model.file());
            return;
        }
        try {
            QuestTab tab = TabIO.load(f);
            model.setTab(tab, f, false);
            selectFirstQuest();
            canvas.fitToContent();
            projectPanel.setCurrentFile(f);
            status("Открыто: " + f.getName() + ", квестов: " + tab.quests.size()
                    + "   •   " + Backups.summary(f));
        } catch (Exception e) {
            error("Не удалось открыть " + f.getName(), e);
        }
    }

    /** Создать вкладку прямо в открытой папке. */
    private void newTabInFolder() {
        if (!workspace.isOpen()) {
            openFolder();
            if (!workspace.isOpen()) return;
        }
        String id = JOptionPane.showInputDialog(this, "Имя новой вкладки (tabID):", "NewTab");
        if (id == null || id.trim().isEmpty()) return;
        File f = workspace.fileFor(id.trim());
        if (f.exists()) {
            JOptionPane.showMessageDialog(this, "Файл уже есть: " + f.getName(),
                    "Создание вкладки", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (!confirmDiscard(Backups.Reason.BEFORE_OPEN)) return;
        try {
            QuestTab tab = QuestTab.empty(id.trim());
            TabIO.save(f, tab);
            model.setTab(tab, f, false);
            projectPanel.refresh();
            projectPanel.setCurrentFile(f);
            canvas.fitToContent();
            status("Создана вкладка: " + f.getName());
        } catch (Exception e) {
            error("Не удалось создать вкладку", e);
        }
    }

    /** История версий текущей вкладки. */
    private void showVersions() {
        QuestTab restoredTab = VersionsDialog.open(this, model.file(), model.tab());
        if (restoredTab == null) return;
        Backups.save(model.file(), model.tab().toJsonString(),
                Backups.Reason.BEFORE_RESTORE, Config.get().backupKeep);
        model.setTab(restoredTab, model.file(), true);
        selectFirstQuest();
        status("Версия восстановлена — не забудь сохранить (Ctrl+S)");
    }

    /** Копия текущего состояния в историю версий. */
    private void backup(Backups.Reason reason) {
        if (Config.get().backupKeep <= 0) return;
        Backups.saveIfChanged(model.file(), model.tab().toJsonString(), reason, Config.get().backupKeep);
    }

    private void save() {
        if (model.file() == null) {
            saveAs();
            return;
        }
        try {
            if (Config.get().backupOnSave) backup(Backups.Reason.SAVE);
            TabIO.save(model.file(), model.tab());
            model.markSaved(model.file());
            projectPanel.refresh();
            status("Сохранено: " + model.file().getName() + "   •   " + Backups.summary(model.file()));
        } catch (Exception e) {
            error("Не удалось сохранить", e);
        }
    }

    private void saveAs() {
        JFileChooser fc = chooser();
        fc.setSelectedFile(new File(TabIO.fileName(model.tab())));
        if (fc.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File f = fc.getSelectedFile();
        if (!f.getName().toLowerCase().endsWith(".json")) f = new File(f.getParentFile(), f.getName() + ".json");
        if (f.exists() && JOptionPane.showConfirmDialog(this,
                "Файл существует. Перезаписать?", "Сохранить как",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        try {
            TabIO.save(f, model.tab());
            model.markSaved(f);
            if (Config.get().backupOnSave) backup(Backups.Reason.SAVE);
            projectPanel.refresh();
            Config.get().lastDir = f.getParent();
            Config.get().save();
            status("Сохранено: " + f.getName());
        } catch (Exception e) {
            error("Не удалось сохранить", e);
        }
    }

    /** После открытия файла показывать свойства первого квеста, а не пустую панель. */
    private void selectFirstQuest() {
        for (String id : model.tab().quests.keySet()) {
            model.select(id);
            return;
        }
    }

    private void reloadFile() {
        File f = model.file();
        if (f == null || !f.isFile()) {
            status("Файл не задан");
            return;
        }
        if (model.isDirty() && JOptionPane.showConfirmDialog(this,
                "Несохранённые изменения будут потеряны. Перечитать файл?" + "\n"
                        + "Текущее состояние сохранится копией в истории версий.", "Перечитать",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        backup(Backups.Reason.BEFORE_RELOAD);
        try {
            model.setTab(TabIO.load(f), f, false);
            status("Файл перечитан: " + f.getName());
        } catch (Exception e) {
            error("Не удалось перечитать файл", e);
        }
    }

    private JFileChooser chooser() {
        JFileChooser fc = new JFileChooser();
        String last = Config.get().lastDir;
        if (last != null && !last.isEmpty()) fc.setCurrentDirectory(new File(last));
        fc.setFileFilter(new FileNameExtensionFilter("Вкладки квестов (*.json)", "json"));
        return fc;
    }

    private void copyTab() {
        TabIO.toClipboard(model.tab().toJsonString());
        status("JSON вкладки скопирован в буфер");
    }

    private void copyTabCompact() {
        TabIO.toClipboard(TabIO.toPasteString(model.tab()));
        status("Компактный JSON скопирован — можно вставлять в /dwq tab paste");
    }

    private void pasteTab() {
        if (!confirmDiscard()) return;
        String text = TabIO.fromClipboard();
        if (text == null || text.trim().isEmpty()) {
            status("В буфере обмена пусто");
            return;
        }
        try {
            model.setTab(QuestTab.parse(text), null, true);
            canvas.fitToContent();
            status("Вкладка вставлена из буфера, квестов: " + model.tab().quests.size());
        } catch (Exception e) {
            error("В буфере не вкладка DwQuests", e);
        }
    }

    // ------------------------------------------------------------------ правка

    private void undo() {
        if (!model.undo()) status("Отменять нечего");
    }

    private void redo() {
        if (!model.redo()) status("Повторять нечего");
    }

    private void addQuest(float x, float y) {
        QuestTab tab = model.tab();
        String id = tab.freeQuestName();
        model.edit("Добавление квеста", () -> {
            Quest q = new Quest();
            q.questID = id;
            q.displayName = id;
            q.displayX = x;
            q.displayY = y;
            q.iconItem = "minecraft:book";
            tab.quests.put(id, q);
        });
        model.select(id);
        canvas.repaint();
    }

    private void duplicateSelected() {
        if (model.selection().isEmpty()) return;
        List<String> ids = new ArrayList<>(model.selection());
        List<String> created = new ArrayList<>();
        model.edit("Дублирование квестов", () -> {
            QuestTab tab = model.tab();
            for (String id : ids) {
                Quest src = tab.quests.get(id);
                if (src == null) continue;
                String newID = freeName(tab, id);
                Quest copy = src.copy();
                copy.questID = newID;
                copy.displayX += 1;
                copy.displayY += 1;
                tab.quests.put(newID, copy);
                created.add(newID);
            }
        });
        model.clearSelection();
        for (String id : created) model.addSelect(id);
        canvas.repaint();
    }

    private void deleteSelected() {
        if (model.selection().isEmpty()) return;
        List<String> ids = new ArrayList<>(model.selection());
        String message = ids.size() == 1
                ? "Удалить квест «" + ids.get(0) + "»?"
                : "Удалить квестов: " + ids.size() + "?";
        if (JOptionPane.showConfirmDialog(this, message + "\nСсылки на них в зависимостях тоже уйдут.",
                "Удаление", JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        model.edit("Удаление квестов", () -> {
            for (String id : ids) model.tab().removeQuest(id);
        });
        model.clearSelection();
        canvas.repaint();
    }

    private void copyQuests() {
        questClipboard.clear();
        for (String id : model.selection()) {
            Quest q = model.tab().quests.get(id);
            if (q != null) questClipboard.add(q.copy());
        }
        if (!questClipboard.isEmpty()) {
            JsonObject holder = new JsonObject();
            for (Quest q : questClipboard) holder.put(q.questID, q.toJson());
            TabIO.toClipboard(JsonWriter.write(holder, true));
            status("Скопировано квестов: " + questClipboard.size());
        }
    }

    private void pasteQuests(float atX, float atY) {
        List<Quest> source = new ArrayList<>(questClipboard);
        if (source.isEmpty()) {
            String text = TabIO.fromClipboard();
            if (text != null && text.trim().startsWith("{")) {
                try {
                    Object parsed = JsonParser.parse(text);
                    if (parsed instanceof JsonObject) {
                        JsonObject o = (JsonObject) parsed;
                        for (String key : o.keys()) {
                            JsonObject qo = o.obj(key);
                            if (qo != null && qo.has("displayX")) source.add(Quest.fromJson(key, qo));
                        }
                    }
                } catch (RuntimeException ignored) { }
            }
        }
        if (source.isEmpty()) {
            status("Нечего вставлять");
            return;
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE;
        for (Quest q : source) {
            minX = Math.min(minX, q.displayX);
            minY = Math.min(minY, q.displayY);
        }
        final float dx = atX - minX;
        final float dy = atY - minY;

        List<String> created = new ArrayList<>();
        model.edit("Вставка квестов", () -> {
            QuestTab tab = model.tab();
            Map<String, String> remap = new LinkedHashMap<>();
            for (Quest src : source) {
                String newID = freeName(tab, src.questID);
                remap.put(src.questID, newID);
            }
            for (Quest src : source) {
                Quest copy = src.copy();
                copy.questID = remap.get(src.questID);
                copy.displayX += dx;
                copy.displayY += dy;
                // ссылки между вставленными квестами переносим на их копии
                for (ru.dw.qeditor.model.Parent p : copy.parents) {
                    String r = remap.get(p.questID);
                    if (r != null) p.questID = r;
                }
                tab.quests.put(copy.questID, copy);
                created.add(copy.questID);
            }
        });
        model.clearSelection();
        for (String id : created) model.addSelect(id);
        canvas.repaint();
        status("Вставлено квестов: " + created.size());
    }

    private static String freeName(QuestTab tab, String base) {
        String name = base + " (копия)";
        int n = 2;
        while (tab.quests.containsKey(name)) name = base + " (копия " + n++ + ")";
        return name;
    }

    /** Разложить квесты сеткой, сохранив порядок — удобно после импорта. */
    private void autoLayout() {
        QuestTab tab = model.tab();
        if (tab.quests.isEmpty()) return;
        int cols = (int) Math.ceil(Math.sqrt(tab.quests.size()));
        if (JOptionPane.showConfirmDialog(this,
                "Разложить " + tab.quests.size() + " квестов сеткой " + cols + " в ряд?\n"
                        + "Текущие координаты будут перезаписаны.", "Разложить сеткой",
                JOptionPane.YES_NO_OPTION) != JOptionPane.YES_OPTION) return;
        backup(Backups.Reason.BEFORE_LAYOUT);
        model.edit("Раскладка сеткой", () -> {
            int i = 0;
            for (Quest q : tab.quests.values()) {
                q.displayX = (i % cols) * 2;
                q.displayY = (i / cols) * 2;
                i++;
            }
        });
        canvas.fitToContent();
    }

    // ------------------------------------------------------------------ инструменты

    private void validateTab() {
        List<TabIO.Problem> problems = TabIO.validate(model.tab());
        StringBuilder sb = new StringBuilder();
        int errors = 0;
        for (TabIO.Problem p : problems) {
            sb.append(p).append('\n');
            if (p.severity == TabIO.Severity.ERROR) errors++;
        }
        if (problems.isEmpty()) sb.append("Замечаний нет — вкладку можно заливать.\n");
        sb.append("\nВсего замечаний: ").append(problems.size()).append(", из них ошибок: ").append(errors);
        showText("Проверка вкладки", sb.toString());
        status(problems.isEmpty() ? "Вкладка в порядке"
                : "Замечаний: " + problems.size() + ", ошибок: " + errors);
    }

    private void diagnostics() {
        showText("Диагностика окружения", SelfTest.run());
    }

    private void showJson() {
        showText("JSON вкладки", model.tab().toJsonString());
    }

    private void showHelp() {
        showText("Горячие клавиши", String.join("\n",
                "Поле редактора:",
                "  ЛКМ по узлу            — выбрать; Ctrl+ЛКМ — добавить к выделению",
                "  ЛКМ по пустому + тяга  — рамка выделения",
                "  Тяга узла              — перемещение (с привязкой к сетке)",
                "  Shift+ЛКМ от узла к узлу — создать зависимость",
                "  Shift+ПКМ по линии     — удалить зависимость",
                "  ПКМ                    — меню: добавить / копировать / вставить / удалить",
                "  Средняя кнопка или Alt+ЛКМ — панорама",
                "  Колесо                 — зум",
                "  Стрелки                — сдвинуть выделенные на шаг сетки",
                "  Delete                 — удалить выделенные",
                "  Двойной клик           — открыть квест в просмотре",
                "",
                "Просмотр (как в игре):",
                "  Колесо                 — зум карты 1..2 (в панели квеста — прокрутка)",
                "  Тяга                   — панорама",
                "  ЛКМ по узлу            — открыть панель квеста",
                "  ЛКМ вне панели         — закрыть панель",
                "",
                "Меню:",
                "  Ctrl+N / Ctrl+O / Ctrl+S — новая / открыть / сохранить",
                "  Ctrl+Z / Ctrl+Y          — отменить / повторить",
                "  Ctrl+C / Ctrl+V          — копировать / вставить квесты",
                "  Ctrl+D                   — дублировать",
                "  Ctrl+A                   — выделить всё",
                "  Ctrl+T                   — проверить вкладку"));
    }

    private void showAbout() {
        showText("О программе", String.join("\n",
                "DwQuests Editor — редактор вкладок квестов для мода DwQuests (Minecraft 1.7.10).",
                "",
                "Вкладка «Просмотр» — порт игрового окна GuiStarAtlas: те же шрифт, цвета,",
                "звёзды-ноды, волнистые линии зависимостей и панель квеста, что и в игре.",
                "",
                "Помощник Claude работает через локальный Claude Code CLI, отдельный ключ API не нужен.",
                "",
                "Есть и консольный режим — для скриптов и для правок из терминала:",
                "  java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli help"));
    }

    private void settings() {
        Config c = Config.get();
        JTextField icons = new JTextField(c.iconsDir, 40);
        JTextField questsJar = new JTextField(c.dwQuestsJar, 40);
        JTextField coreJar = new JTextField(c.dwCoreJar, 40);
        JTextField mcJar = new JTextField(c.mcClientJar, 40);
        JTextField claudeExe = new JTextField(c.claudeExe, 40);
        JSpinner keep = new JSpinner(new SpinnerNumberModel(c.backupKeep, 0, 500, 10));
        JSpinner autoMinutes = new JSpinner(new SpinnerNumberModel(c.autoBackupMinutes, 0, 120, 1));
        JCheckBox onSave = new JCheckBox("Копия при каждом сохранении", c.backupOnSave);
        JComboBox<String> claudeModel = new JComboBox<>(new String[]{"sonnet", "opus", "haiku"});
        claudeModel.setSelectedItem(c.claudeModel);

        JPanel form = Ui.form();
        Ui.row(form, "Папка иконок (icon_exporter):", icons);
        Ui.row(form, "DwQuests jar:", questsJar);
        Ui.row(form, "DwCore jar:", coreJar);
        Ui.row(form, "client.jar 1.7.10:", mcJar);
        Ui.row(form, "Claude CLI:", claudeExe);
        Ui.row(form, "Модель по умолчанию:", claudeModel);
        Ui.row(form, "Хранить версий на вкладку:", keep);
        Ui.row(form, "Автокопия каждые, минут:", autoMinutes);
        Ui.rowFull(form, onSave);

        if (JOptionPane.showConfirmDialog(this, form, "Настройки",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) != JOptionPane.OK_OPTION) return;

        c.iconsDir = icons.getText().trim();
        c.dwQuestsJar = questsJar.getText().trim();
        c.dwCoreJar = coreJar.getText().trim();
        c.mcClientJar = mcJar.getText().trim();
        c.claudeExe = claudeExe.getText().trim();
        c.claudeModel = (String) claudeModel.getSelectedItem();
        c.backupKeep = ((Number) keep.getValue()).intValue();
        c.autoBackupMinutes = ((Number) autoMinutes.getValue()).intValue();
        c.backupOnSave = onSave.isSelected();
        c.save();

        ModAssets.get().reopen();
        ItemRegistry.get().reload();
        McFont.get().reload();
        GuiPainter.clearCaches();
        Ui.clearIconCache();
        repaint();
        status("Настройки сохранены, ресурсы перечитаны");
        checkAssets();
    }

    private void checkAssets() {
        List<String> problems = new ArrayList<>(ModAssets.get().problems());
        if (!McFont.get().isReady()) problems.add("Шрифт не загружен: " + McFont.get().error());
        if (ItemRegistry.get().size() == 0)
            problems.add("Не загружен список предметов: " + ItemRegistry.get().loadError());
        if (problems.isEmpty()) return;
        status("Проблемы с ресурсами: " + problems.size() + " — Инструменты → Настройки");
    }

    // ------------------------------------------------------------------ фоновые задачи

    private void startWatchTimer() {
        new Timer(1500, e -> {
            if (!Config.get().watchFile) return;
            File f = model.file();
            if (f == null || !f.isFile()) return;
            long stamp = f.lastModified();
            if (stamp == model.fileStamp()) return;
            if (model.isDirty()) {
                int answer = JOptionPane.showConfirmDialog(this,
                        "Файл изменён снаружи, но в редакторе есть несохранённые правки.\n"
                                + "Перечитать файл и потерять их?", "Файл изменён",
                        JOptionPane.YES_NO_OPTION);
                if (answer != JOptionPane.YES_OPTION) {
                    model.markSaved(f);   // запомнить новую метку, чтобы не спрашивать снова
                    model.touch();
                    return;
                }
            }
            try {
                model.setTab(TabIO.load(f), f, false);
                status("Файл изменён снаружи — перечитан: " + f.getName());
            } catch (Exception ex) {
                status("Файл изменён снаружи, но не читается: " + ex.getMessage());
            }
        }).start();
    }

    /**
     * Автоматическая копия, пока есть несохранённые правки: даже если программа
     * закроется неожиданно, работа найдётся в истории версий.
     */
    private void startAutosaveTimer() {
        int minutes = Config.get().autoBackupMinutes;
        if (minutes <= 0) return;
        new Timer(minutes * 60_000, e -> {
            if (!model.isDirty()) return;
            backup(Backups.Reason.AUTO);
        }).start();
    }

    private void onTabChanged() {
        boolean preview = tabs.getSelectedIndex() == 1;
        if (preview) {
            feedPreviewTabs();
            String id = model.single();
            if (id != null) previewPanel.preview().openQuest(id);
            previewPanel.start();
        } else {
            previewPanel.stop();
        }
    }

    /**
     * Отдать просмотру все вкладки папки — левая колонка тогда выглядит как в
     * игре. Открытая в редакторе вкладка передаётся живым объектом, чтобы были
     * видны несохранённые правки; остальные читаются с диска.
     */
    private void feedPreviewTabs() {
        java.util.List<QuestTab> list = new ArrayList<>();
        if (workspace.isOpen()) {
            File current = model.file();
            for (Workspace.Entry e : workspace.entries()) {
                if (e.error != null) continue;
                if (current != null && e.file.getAbsolutePath().equals(current.getAbsolutePath())) {
                    list.add(model.tab());
                    continue;
                }
                try {
                    list.add(TabIO.load(e.file));
                } catch (Exception ignored) {
                    // вкладка не читается — просто не показываем её в списке
                }
            }
        }
        if (list.isEmpty()) {
            previewPanel.preview().setSingleTab(model.tab());
        } else {
            if (!list.contains(model.tab())) list.add(model.tab());
            previewPanel.preview().setTabs(list, model.tab().tabID);
        }
    }

    // ------------------------------------------------------------------ модель

    @Override
    public void modelChanged(String what) {
        updateTitle();
        if ("tab".equals(what) && previewPanel.isRunning()) {
            feedPreviewTabs();
        }
        if (undoItem != null) {
            undoItem.setEnabled(model.history().canUndo());
            undoItem.setText(model.history().canUndo()
                    ? "Отменить: " + model.history().undoLabel() : "Отменить");
            redoItem.setEnabled(model.history().canRedo());
            redoItem.setText(model.history().canRedo()
                    ? "Повторить: " + model.history().redoLabel() : "Повторить");
        }
        canvas.repaint();
        if (previewPanel.isRunning()) previewPanel.repaint();
    }

    private void updateTitle() {
        setTitle(model.title());
    }

    private void status(String text) {
        statusBar.setText(text);
    }

    private boolean confirmDiscard() {
        return confirmDiscard(Backups.Reason.BEFORE_OPEN);
    }

    /**
     * Спросить про несохранённые правки. При отказе сохранять копия всё равно
     * уходит в историю версий — потерять работу по ошибке нельзя.
     */
    private boolean confirmDiscard(Backups.Reason reason) {
        if (!model.isDirty()) return true;
        int answer = JOptionPane.showConfirmDialog(this,
                "Есть несохранённые изменения. Сохранить?" + "\n"
                        + "Если нет — состояние всё равно попадёт в историю версий.",
                "DwQuests Editor", JOptionPane.YES_NO_CANCEL_OPTION);
        if (answer == JOptionPane.CANCEL_OPTION || answer == JOptionPane.CLOSED_OPTION) return false;
        if (answer == JOptionPane.YES_OPTION) {
            save();
            return !model.isDirty();
        }
        backup(reason);
        return true;
    }

    private void exit() {
        if (!confirmDiscard()) return;
        ModAssets.get().close();
        dispose();
        System.exit(0);
    }

    // ------------------------------------------------------------------ диалоги

    private void showText(String title, String text) {
        JTextArea area = new JTextArea(text);
        area.setEditable(false);
        area.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        area.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(900, 560));

        JDialog d = new JDialog(this, title, false);
        d.setLayout(new BorderLayout());
        d.add(scroll, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        buttons.add(Ui.btn("Копировать", () -> TabIO.toClipboard(text)));
        buttons.add(Ui.btn("Закрыть", d::dispose));
        d.add(buttons, BorderLayout.SOUTH);
        d.pack();
        d.setLocationRelativeTo(this);
        d.setVisible(true);
    }

    private void error(String message, Exception e) {
        JOptionPane.showMessageDialog(this, message + ":\n" + e.getMessage(),
                "Ошибка", JOptionPane.ERROR_MESSAGE);
        status(message + ": " + e.getMessage());
    }

    // ------------------------------------------------------------------ обратные вызовы поля

    private final class CanvasCallbacks implements EditorCanvas.Callbacks {
        @Override
        public void questDoubleClicked(String questID) {
            model.select(questID);
            tabs.setSelectedIndex(1);
            previewPanel.preview().openQuest(questID);
        }

        @Override public void requestAddQuest(float x, float y) { addQuest(x, y); }

        @Override public void requestCopy() { copyQuests(); }

        @Override public void requestPaste(float x, float y) { pasteQuests(x, y); }

        @Override public void requestDelete() { deleteSelected(); }
    }

    // ------------------------------------------------------------------ для снимков экрана

    /** Открыть файл из кода — используется отладочным UiProbe. */
    public void openFileForProbe(File f) {
        try {
            model.setTab(TabIO.load(f), f, false);
            selectFirstQuest();
            canvas.fitToContent();
            if (f.getParentFile() != null) {
                workspace.open(f.getParentFile());
                projectPanel.refresh();
                projectPanel.setCurrentFile(f);
            }
        } catch (Exception e) {
            status("Не удалось открыть " + f.getName() + ": " + e.getMessage());
        }
    }

    /** Поле редактора — для проверок отрисовки. */
    public EditorCanvas canvasForProbe() { return canvas; }

    /** Текущая страница — чтобы замерять её отрисовку отдельно от всего окна. */
    public JComponent pageForProbe() {
        JComponent page = tabs.getSelectedPage();
        return page == null ? canvas : page;
    }

    /** Переключить раздел из кода — используется отладочным UiProbe. */
    public void selectTabForProbe(int index) {
        tabs.setSelectedIndex(index);
        onTabChanged();
    }

    // ------------------------------------------------------------------ запуск

    public static void main(String[] args) {
        Theme.install();
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            frame.setVisible(true);
            if (args.length > 0) {
                File f = new File(args[0]);
                if (f.isFile()) {
                    try {
                        frame.model.setTab(TabIO.load(f), f, false);
                        frame.canvas.fitToContent();
                    } catch (Exception e) {
                        frame.error("Не удалось открыть " + f.getName(), e);
                    }
                }
            }
        });
    }
}
