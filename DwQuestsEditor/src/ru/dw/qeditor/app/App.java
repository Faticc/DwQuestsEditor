package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gui.Popup;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.io.Backups;
import ru.dw.qeditor.io.IdAudit;
import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.io.Workspace;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.render.GamePreview;
import ru.dw.qeditor.util.SelfTest;

import java.io.File;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_A;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_C;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_D;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_F11;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_H;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_N;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_O;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_S;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_T;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_V;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Y;
import static org.lwjgl.glfw.GLFW.GLFW_KEY_Z;

/**
 * Главное окно редактора: раскладка, меню, горячие клавиши и цикл кадров.
 *
 * <p>Порт {@code ui/MainFrame}. Компонентов и менеджеров раскладки нет:
 * прямоугольники колонок считаются заново каждый кадр, поэтому «спрятать
 * панель» — это просто не отдать ей ширину, а полный экран — не рисовать
 * обвязку.
 *
 * <p>Кадры рисуются не всё время: пока ничего не происходит, окно ждёт
 * события ({@link GlWindow#waitEvents}). Исключение — просмотр: там идёт
 * анимация, и кадры нужны постоянно.
 */
public final class App {

    private static final float PROJECT_WIDTH = 240;
    private static final String[] PAGES =
            {"Редактор", "Просмотр как в игре", "JSON", "Помощник Claude"};

    private final GlWindow window;
    private final Batch batch;
    private final Ui ui;
    private final Popup popup = new Popup();

    private final EditorModel model = new EditorModel();
    private final Workspace workspace = new Workspace();
    private final ProjectView project = new ProjectView(workspace);
    private final MapView map = new MapView(model);
    private final PreviewView preview = new PreviewView();
    private final Sidebar sidebar;
    private final AssistantView assistant;
    private final JsonView json;

    private final Deque<Dialog> dialogs = new ArrayDeque<>();
    private final List<Quest> questClipboard = new ArrayList<>();

    private int page;
    private int lastPage;
    private float sidebarWidth = 430;
    private boolean showProject = true;
    /** Полный экран: видна только страница, без меню, колонок и строки состояния. */
    private boolean chromeHidden;
    private String status = "Готово";

    /** Когда последний раз сверяли файл на диске и делали автокопию. */
    private double lastWatch;
    private double lastAutosave;
    /** Открытое меню строки меню: −1 — закрыто. */
    private int openMenu = -1;
    private List<Runnable> menuActions = new ArrayList<>();
    private final Object menuId = new Object();

    public App() {
        this(new GlWindow("DwQuests Editor", 1500, 950, true), new Batch());
    }

    /** Окно и пакет снаружи — так проверки отрисовки рисуют то же окно скрытым. */
    public App(GlWindow window, Batch batch) {
        this.window = window;
        this.batch = batch;
        ui = new Ui(window, batch);
        sidebar = new Sidebar(model, popup, this::open);
        assistant = new AssistantView(model, popup);
        json = new JsonView(model);

        map.setCallbacks(new MapCallbacks());
        preview.setGuiScale(Config.get().guiScale);
        preview.preview().setOnTabSwitched(t -> status("Просмотр: " + t.tabID
                + ", квестов: " + t.quests.size()
                + "   •   правится по-прежнему " + model.tab().tabID));
        preview.setOnQuestOpened(() -> {
            String id = preview.preview().getOpenQuestID();
            if (id != null) model.select(id);
        });

        project.setOnOpen(e -> openTabFile(e.file));
        project.setOnNewTab(this::newTabInFolder);
        project.setOnChooseFolder(this::openFolder);

        window.setOnCloseRequest(this::exit);
        model.addListener(this::onModelChanged);
        updateTitle();
        checkAssets();
        restoreLastFolder();
    }

    // ------------------------------------------------------------------ цикл

    public void run() {
        while (!window.shouldClose()) {
            // в просмотре идёт анимация — кадры нужны постоянно; в остальном
            // ждём события, иначе окно просто грело бы видеокарту
            if (page == 1 || !dialogs.isEmpty()) window.pollEvents();
            else window.waitEvents(0.25);

            window.beginFrame(Theme.BG & 0xFFFFFF);
            drawFrame();
            window.endFrame();
        }
        ModAssets.get().close();
        batch.dispose();
        window.dispose();
        GlWindow.terminate();
    }

    /** Один кадр окна целиком — этим же пользуются проверки отрисовки. */
    public void drawFrame() {
        batch.begin(window.width(), window.height());
        ui.begin();
        frame();
        ui.end();
        batch.end();
        // снимок вкладки для помощника рисуется тем же пакетом вершин, поэтому
        // только после того, как кадр окна закончен
        assistant.afterBatch(batch);
    }

    private void frame() {
        background();
        handleDropped();
        tick();

        // верхние слои забирают ввод у нижних ещё до того, как те нарисуются
        Dialog top = dialogs.peek();
        if (top != null) ui.blockInput();
        popup.blockIfOpen(ui);

        float y = 0;
        if (!chromeHidden) {
            drawMenuBar(ui, 0, 0, ui.width());
            y = Theme.MENU_BAR;
        }
        float bottom = chromeHidden ? ui.height() : ui.height() - Theme.STATUS_BAR;
        drawBody(y, bottom - y);
        if (!chromeHidden) Widgets.statusBar(ui, status, ui.height() - Theme.STATUS_BAR);

        popup.draw(ui);
        sidebar.afterFrame(ui);
        assistant.afterFrame(ui);

        if (top != null) {
            top.draw(ui);
            if (top.isClosed()) dialogs.pop();
        }

        hotkeys();
    }

    private void background() {
        ui.rect(0, 0, ui.width(), ui.height(), Theme.BG);
    }

    private void drawBody(float y, float h) {
        float x = 0;
        if (!chromeHidden && showProject) {
            project.draw(ui, x, y, PROJECT_WIDTH, h);
            x += PROJECT_WIDTH;
            ui.vSeparator(x, y, h);
            x += 1;
        }

        float right = ui.width();
        if (!chromeHidden) {
            float maxSidebar = Math.max(280, ui.width() - x - 420);
            sidebarWidth = Math.max(300, Math.min(maxSidebar, sidebarWidth));
            float sx = ui.width() - sidebarWidth;
            sidebarWidth = ui.width() - Widgets.splitterV(ui, "sidebar", sx - 3, y, h,
                    sx, x + 420, ui.width() - 300);
            sidebarWidth = Math.max(300, Math.min(maxSidebar, sidebarWidth));
            right = ui.width() - sidebarWidth;
            sidebar.draw(ui, right, y, sidebarWidth, h);
        }

        drawPage(x, y, right - x, h);
    }

    private void drawPage(float x, float y, float w, float h) {
        float cy = y + 4;
        page = Widgets.tabs(ui, x + 10, cy, Theme.ROW_LARGE, PAGES, page);
        cy += Theme.ROW_LARGE + 4;
        if (page != lastPage) {
            lastPage = page;
            // на просмотр переходим с готовым списком вкладок папки
            if (page == 1) {
                feedPreviewTabs();
                String id = model.single();
                if (id != null) preview.preview().openQuest(id);
            }
        }

        switch (page) {
            case 0 -> {
                float toolH = drawEditorToolbar(x + 10, cy, w - 20);
                cy += toolH;
                float mapH = Math.max(80, y + h - cy - 8);
                ui.panel(x + 8, cy, w - 16, mapH);
                map.draw(ui, popup, x + 9, cy + 1, w - 18, mapH - 2);
            }
            case 1 -> {
                float toolH = drawPreviewToolbar(x + 10, cy, w - 20);
                cy += toolH;
                float ph = Math.max(80, y + h - cy - 8);
                preview.draw(ui, x + 8, cy, w - 16, ph);
            }
            case 2 -> json.draw(ui, x + 12, cy + 4, w - 24, Math.max(80, y + h - cy - 16));
            default -> assistant.draw(ui, x + 12, cy + 4, w - 24, Math.max(80, y + h - cy - 16));
        }
    }

    // ------------------------------------------------------------------ панели инструментов

    private float drawEditorToolbar(float x, float y, float w) {
        float bh = Theme.ROW;
        float bx = x;
        float by = y;

        if (ui.button(bx, by, 120, bh, "Добавить квест", Ui.PRIMARY, true)) addQuest(0, 0);
        bx += 124;
        if (ui.button(bx, by, 100, bh, "Дублировать", Ui.NORMAL, !model.selection().isEmpty()))
            duplicateSelected();
        bx += 104;
        if (ui.button(bx, by, 80, bh, "Удалить", Ui.DANGER, !model.selection().isEmpty()))
            deleteSelected();
        bx += 88;

        ui.textIn("сетка", bx, by, 40, bh, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        bx += 40;
        // шаг дробный: в игре клетка — 24 пикселя, и половина клетки осмысленна
        map.setGridSize(ui.floatField("grid", bx, by, 56, bh, map.getGridSize(), 0.25f));
        bx += 62;

        ui.textIn("сдвиг", bx, by, 40, bh, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        bx += 40;
        float ox = ui.floatField("offX", bx, by, 62, bh, map.getGridOffsetX(), 0.5f);
        bx += 66;
        float oy = ui.floatField("offY", bx, by, 62, bh, map.getGridOffsetY(), 0.5f);
        map.setGridOffset(Math.max(-8, Math.min(8, ox)), Math.max(-8, Math.min(8, oy)));
        bx += 70;

        if (ui.toggleButton(bx, by, 58, bh, "сетка", map.isShowGrid())) map.setShowGrid(!map.isShowGrid());
        bx += 62;
        if (ui.toggleButton(bx, by, 76, bh, "привязка", map.isSnap())) map.setSnap(!map.isSnap());
        bx += 80;
        if (ui.toggleButton(bx, by, 84, bh, "все имена", map.isShowNames()))
            map.setShowNames(!map.isShowNames());
        bx += 88;
        if (ui.toggleButton(bx, by, 58, bh, "связи", map.isShowLinks()))
            map.setShowLinks(!map.isShowLinks());
        bx += 62;
        if (ui.toggleButton(bx, by, 74, bh, "скрытые", map.isShowHiddenLinks()))
            map.setShowHiddenLinks(!map.isShowHiddenLinks());
        bx += 78;

        // вторая строка кнопок: в узком окне первая уже закончилась
        if (bx + 300 > x + w) {
            bx = x;
            by += bh + 4;
        }
        if (ui.button(bx, by, 106, bh, "Во весь экран", Ui.GHOST, true)) toggleFullscreen();
        if (ui.over(bx, by, 106, bh)) ui.tooltip("F11 — развернуть поле на весь экран, Esc — вернуться");
        bx += 110;
        if (ui.button(bx, by, 74, bh, "Вписать", Ui.GHOST, true)) map.fitToContent();
        bx += 78;
        if (ui.button(bx, by, 130, bh, "Разложить сеткой", Ui.GHOST, true)) askAutoLayout();
        bx += 134;
        ui.textIn("зум " + Math.round(map.getZoom() * 100) + "%", bx, by, 90, bh,
                Theme.TEXT_DISABLED, Ui.LEFT, Theme.FONT_SMALL, false);

        return by - y + bh + 8;
    }

    private float drawPreviewToolbar(float x, float y, float w) {
        float bh = Theme.ROW;
        float bx = x;
        ui.textIn("прогресс", bx, y, 60, bh, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        bx += 62;
        GamePreview.Mode[] modes = GamePreview.Mode.values();
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) names[i] = modes[i].ru;
        int mode = Widgets.dropdown(ui, popup, "previewMode", bx, y, 150, bh, names,
                preview.preview().getMode().ordinal());
        if (mode != preview.preview().getMode().ordinal()) preview.preview().setMode(modes[mode]);
        bx += 158;

        ui.textIn("guiScale", bx, y, 56, bh, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        bx += 58;
        int scale = ui.intField("guiScale", bx, y, 56, bh, Math.round(preview.getGuiScale()), 1, 4);
        if (scale != Math.round(preview.getGuiScale())) {
            preview.setGuiScale(scale);
            Config.get().guiScale = scale;
            Config.get().save();
        }
        bx += 64;

        if (ui.button(bx, y, 130, bh, "Обновить вкладки", Ui.GHOST, true)) feedPreviewTabs();
        if (ui.over(bx, y, 130, bh)) ui.tooltip("Перечитать вкладки папки для левой колонки");
        bx += 134;
        if (ui.button(bx, y, 106, bh, "Во весь экран", Ui.GHOST, true)) toggleFullscreen();
        bx += 110;
        preview.drawHint(ui, bx, y, Math.max(0, x + w - bx));
        return bh + 8;
    }

    // ------------------------------------------------------------------ меню

    private void drawMenuBar(Ui ui, float x, float y, float w) {
        ui.rect(x, y, w, Theme.MENU_BAR, Theme.SURFACE);
        ui.hSeparator(x, y + Theme.MENU_BAR, w);

        int picked = popup.takePick(menuId);
        if (picked >= 0 && picked < menuActions.size()) {
            Runnable r = menuActions.get(picked);
            if (r != null) r.run();
        }
        if (!popup.isOpen(menuId)) openMenu = -1;

        String[] titles = {"Файл", "Правка", "Вид", "Инструменты", "Справка"};
        float mx = x + 8;
        for (int i = 0; i < titles.length; i++) {
            float mw = ui.textWidth(titles[i]) + 22;
            boolean hovered = ui.over(mx, y, mw, Theme.MENU_BAR);
            boolean active = openMenu == i;
            if (active || hovered) ui.roundRect(mx, y + 2, mw, Theme.MENU_BAR - 4,
                    Theme.RADIUS_SMALL, active ? Theme.PRESSED : Theme.HOVER);
            ui.textIn(titles[i], mx, y, mw, Theme.MENU_BAR,
                    active || hovered ? Theme.TEXT : Theme.TEXT_MUTED, Ui.CENTER);
            // меню открывается щелчком, а при открытом — ещё и наведением,
            // как в любой строке меню
            if (hovered && (ui.in().leftPressed() || (openMenu >= 0 && openMenu != i))) {
                openMenu = i;
                buildMenu(i, mx, y + Theme.MENU_BAR);
            }
            mx += mw;
        }

        ui.textIn(model.isDirty() ? "есть несохранённые правки" : "", x + w - 230, y, 220,
                Theme.MENU_BAR, Theme.WARNING, Ui.RIGHT, Theme.FONT_SMALL, false);
    }

    private void buildMenu(int index, float x, float y) {
        List<Popup.Item> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        switch (index) {
            case 0 -> {
                item(items, actions, "Новая вкладка", "Ctrl+N", this::newTab);
                item(items, actions, "Открыть…", "Ctrl+O", this::open);
                item(items, actions, "Открыть папку…", null, this::openFolder);
                item(items, actions, "Сохранить", "Ctrl+S", this::save);
                item(items, actions, "Сохранить как…", null, this::saveAs);
                separator(items, actions);
                item(items, actions, "Вставить вкладку из буфера", null, this::pasteTab);
                item(items, actions, "Копировать вкладку в буфер", null, this::copyTab);
                item(items, actions, "Копировать для /dwq tab paste", null, this::copyTabCompact);
                separator(items, actions);
                item(items, actions, "Перечитать файл", null, this::reloadFile);
                separator(items, actions);
                item(items, actions, "Выход", null, this::exit);
            }
            case 1 -> {
                if (model.history().canUndo())
                    item(items, actions, "Отменить: " + model.history().undoLabel(), "Ctrl+Z", this::undo);
                else disabled(items, actions, "Отменить");
                if (model.history().canRedo())
                    item(items, actions, "Повторить: " + model.history().redoLabel(), "Ctrl+Y", this::redo);
                else disabled(items, actions, "Повторить");
                separator(items, actions);
                item(items, actions, "Добавить квест", null, () -> addQuest(0, 0));
                item(items, actions, "Дублировать квесты", "Ctrl+D", this::duplicateSelected);
                item(items, actions, "Удалить квесты", null, this::deleteSelected);
                item(items, actions, "Выделить всё", "Ctrl+A", model::selectAll);
                separator(items, actions);
                item(items, actions, "Копировать квесты", "Ctrl+C", this::copyQuests);
                item(items, actions, "Вставить квесты", "Ctrl+V", () -> pasteQuests(0, 0));
            }
            case 2 -> {
                item(items, actions, "Во весь экран", "F11", this::toggleFullscreen);
                separator(items, actions);
                item(items, actions, "Вписать вкладку", null, map::fitToContent);
                item(items, actions, "К выбранному квесту", null, () -> {
                    String id = model.single();
                    if (id != null) map.centerOn(id);
                });
                separator(items, actions);
                check(items, actions, "Список вкладок папки", showProject, () -> showProject = !showProject);
                check(items, actions, "Следить за файлом", Config.get().watchFile, () -> {
                    Config.get().watchFile = !Config.get().watchFile;
                    Config.get().save();
                });
            }
            case 3 -> {
                item(items, actions, "История версий…", "Ctrl+H", this::showVersions);
                item(items, actions, "Сохранить версию сейчас", null, () -> {
                    File made = Backups.save(model.file(), model.tab().toJsonString(),
                            Backups.Reason.MANUAL, Config.get().backupKeep);
                    status(made == null ? "Не удалось сохранить версию"
                            : "Версия сохранена: " + made.getName());
                });
                separator(items, actions);
                item(items, actions, "Проверить вкладку", "Ctrl+T", this::validateTab);
                item(items, actions, "Сверить id предметов", null, this::auditIds);
                item(items, actions, "Диагностика окружения", null,
                        () -> showText("Диагностика окружения", SelfTest.run()));
                item(items, actions, "Настройки…", null, this::settings);
                separator(items, actions);
                item(items, actions, "Помощник Claude", null, () -> page = 3);
                item(items, actions, "Страница JSON", null, () -> page = 2);
            }
            default -> {
                item(items, actions, "Горячие клавиши", null, this::showHelp);
                item(items, actions, "О программе", null, this::showAbout);
            }
        }
        menuActions = actions;
        popup.open(menuId, x, y, items, 240);
    }

    private void item(List<Popup.Item> items, List<Runnable> actions, String label,
                      String accel, Runnable action) {
        items.add(accel == null ? Popup.Item.of(label) : Popup.Item.of(label, accel));
        actions.add(action);
    }

    private void disabled(List<Popup.Item> items, List<Runnable> actions, String label) {
        items.add(Popup.Item.disabled(label));
        actions.add(null);
    }

    private void check(List<Popup.Item> items, List<Runnable> actions, String label,
                       boolean checked, Runnable action) {
        items.add(Popup.Item.check(label, checked));
        actions.add(action);
    }

    private void separator(List<Popup.Item> items, List<Runnable> actions) {
        items.add(Popup.Item.line());
        actions.add(null);
    }

    // ------------------------------------------------------------------ клавиши

    private void hotkeys() {
        if (!dialogs.isEmpty()) return;
        if (ui.shortcut(GLFW_KEY_N, true)) newTab();
        if (ui.shortcut(GLFW_KEY_O, true)) open();
        if (ui.shortcut(GLFW_KEY_S, true)) save();
        if (ui.shortcut(GLFW_KEY_Z, true)) undo();
        if (ui.shortcut(GLFW_KEY_Y, true)) redo();
        if (ui.shortcut(GLFW_KEY_D, true)) duplicateSelected();
        if (ui.shortcut(GLFW_KEY_A, true)) model.selectAll();
        if (ui.shortcut(GLFW_KEY_C, true)) copyQuests();
        if (ui.shortcut(GLFW_KEY_V, true)) pasteQuests(0, 0);
        if (ui.shortcut(GLFW_KEY_T, true)) validateTab();
        if (ui.shortcut(GLFW_KEY_H, true)) showVersions();
        if (ui.shortcut(GLFW_KEY_F11, false)) toggleFullscreen();
        if (chromeHidden && ui.shortcut(GLFW_KEY_ESCAPE, false)) toggleFullscreen();
    }

    /**
     * Во весь экран остаётся только сама страница: панели, меню и строка
     * состояния прячутся, полоска разделов сверху — нет, чтобы можно было
     * переключаться между редактором и просмотром не выходя из режима.
     */
    public void toggleFullscreen() {
        chromeHidden = !chromeHidden;
        // -Ddwq.nofullscreen=true — не занимать экран целиком: нужно проверкам,
        // а заодно запасной выход, если полный экран где-то ведёт себя плохо
        if (!Boolean.getBoolean("dwq.nofullscreen")) window.toggleFullscreen();
        status(chromeHidden ? "Во весь экран. F11 или Esc — вернуться" : "Обычный режим");
    }

    // ------------------------------------------------------------------ фоновые задачи

    private void tick() {
        double now = ui.time();
        if (Config.get().watchFile && now - lastWatch > 1.5) {
            lastWatch = now;
            watchFile();
        }
        int minutes = Config.get().autoBackupMinutes;
        if (minutes > 0 && now - lastAutosave > minutes * 60.0) {
            lastAutosave = now;
            if (model.isDirty()) backup(Backups.Reason.AUTO);
        }
    }

    private void watchFile() {
        File f = model.file();
        if (f == null || !f.isFile()) return;
        long stamp = f.lastModified();
        if (stamp == model.fileStamp()) return;
        if (model.isDirty()) {
            open(Dialogs.confirm("Файл изменён",
                    "Файл изменён снаружи, но в редакторе есть несохранённые правки.\n"
                            + "Перечитать файл и потерять их?",
                    new String[]{"Оставить свои", "Перечитать"}, answer -> {
                        if (answer == 1) reloadSilently(f);
                        else {
                            // запомнить новую метку, чтобы не спрашивать снова
                            model.markSaved(f);
                            model.touch();
                        }
                    }));
            return;
        }
        reloadSilently(f);
    }

    private void reloadSilently(File f) {
        try {
            model.setTab(TabIO.load(f), f, false);
            status("Файл изменён снаружи — перечитан: " + f.getName());
        } catch (Exception ex) {
            status("Файл изменён снаружи, но не читается: " + ex.getMessage());
        }
    }

    private void handleDropped() {
        for (String path : ui.in().droppedFiles()) {
            File f = new File(path);
            if (f.isDirectory()) {
                openFolderAt(f);
                return;
            }
            if (f.isFile() && f.getName().toLowerCase().endsWith(".json")) {
                openTabFile(f);
                return;
            }
        }
    }

    // ------------------------------------------------------------------ файл

    private void newTab() {
        confirmDiscard(() -> open(Dialogs.prompt("Новая вкладка", "Имя вкладки (tabID)", "NewTab", id -> {
            if (id.trim().isEmpty()) return;
            model.setTab(QuestTab.empty(id.trim()), null, false);
            map.fitToContent();
            status("Создана вкладка: " + id.trim());
        })));
    }

    private void open() {
        confirmDiscard(() -> {
            File f = FileDialogs.openJson("Открыть вкладку", lastDir());
            if (f == null) return;
            try {
                QuestTab tab = TabIO.load(f);
                model.setTab(tab, f, false);
                selectFirstQuest();
                Config.get().lastDir = f.getParent();
                Config.get().save();
                map.fitToContent();
                if (!workspace.isOpen() && f.getParentFile() != null) {
                    workspace.open(f.getParentFile());
                    Config.get().lastFolder = f.getParent();
                    project.refresh();
                }
                project.setCurrentFile(f);
                status("Открыто: " + f.getName() + ", квестов: " + tab.quests.size()
                        + "   •   " + Backups.summary(f));
            } catch (Exception e) {
                error("Не удалось открыть файл", e);
            }
        });
    }

    /** При запуске вернуть папку, с которой работали в прошлый раз. */
    private void restoreLastFolder() {
        String last = Config.get().lastFolder;
        if (last == null || last.isEmpty()) return;
        File dir = new File(last);
        if (!dir.isDirectory()) return;
        workspace.open(dir);
        project.refresh();
        status("Папка: " + dir.getName() + ", вкладок: " + workspace.entries().size()
                + ", квестов: " + workspace.totalQuests());
    }

    /** Открыть папку с вкладками — список появится слева. */
    private void openFolder() {
        String last = Config.get().lastFolder.isEmpty() ? Config.get().lastDir : Config.get().lastFolder;
        File dir = FileDialogs.folder("Папка с вкладками квестов",
                last == null || last.isEmpty() ? null : new File(last));
        if (dir != null) openFolderAt(dir);
    }

    private void openFolderAt(File dir) {
        workspace.open(dir);
        Config.get().lastFolder = dir.getAbsolutePath();
        Config.get().save();
        project.refresh();
        showProject = true;

        if (workspace.entries().isEmpty()) {
            status("В папке нет вкладок: " + dir.getName());
            return;
        }
        status("Папка открыта: " + dir.getName() + ", вкладок: " + workspace.entries().size()
                + ", квестов: " + workspace.totalQuests());
        Workspace.Entry first = workspace.entries().get(0);
        if (model.file() == null && !model.isDirty()) openTabFile(first.file);
        else project.setCurrentFile(model.file());
    }

    /** Переключиться на вкладку из списка папки. */
    private void openTabFile(File f) {
        if (f == null || !f.isFile()) return;
        if (model.file() != null && f.getAbsolutePath().equals(model.file().getAbsolutePath())) return;
        confirmDiscard(Backups.Reason.BEFORE_OPEN, () -> {
            try {
                QuestTab tab = TabIO.load(f);
                model.setTab(tab, f, false);
                selectFirstQuest();
                map.fitToContent();
                if (!workspace.isOpen() && f.getParentFile() != null) {
                    workspace.open(f.getParentFile());
                    project.refresh();
                }
                project.setCurrentFile(f);
                status("Открыто: " + f.getName() + ", квестов: " + tab.quests.size()
                        + "   •   " + Backups.summary(f));
            } catch (Exception e) {
                error("Не удалось открыть " + f.getName(), e);
            }
        }, () -> project.setCurrentFile(model.file()));
    }

    /** Создать вкладку прямо в открытой папке. */
    private void newTabInFolder() {
        if (!workspace.isOpen()) {
            openFolder();
            if (!workspace.isOpen()) return;
        }
        open(Dialogs.prompt("Новая вкладка в папке", "Имя новой вкладки (tabID)", "NewTab", id -> {
            if (id.trim().isEmpty()) return;
            File f = workspace.fileFor(id.trim());
            if (f.exists()) {
                open(Dialogs.message("Создание вкладки", "Файл уже есть: " + f.getName()));
                return;
            }
            confirmDiscard(Backups.Reason.BEFORE_OPEN, () -> {
                try {
                    QuestTab tab = QuestTab.empty(id.trim());
                    TabIO.save(f, tab);
                    model.setTab(tab, f, false);
                    project.refresh();
                    project.setCurrentFile(f);
                    map.fitToContent();
                    status("Создана вкладка: " + f.getName());
                } catch (Exception e) {
                    error("Не удалось создать вкладку", e);
                }
            }, null);
        }));
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
            project.refresh();
            status("Сохранено: " + model.file().getName() + "   •   " + Backups.summary(model.file()));
        } catch (Exception e) {
            error("Не удалось сохранить", e);
        }
    }

    private void saveAs() {
        File suggested = new File(lastDir() == null ? new File(".") : lastDir(),
                TabIO.fileName(model.tab()));
        File f = FileDialogs.saveJson("Сохранить вкладку как", suggested);
        if (f == null) return;
        try {
            TabIO.save(f, model.tab());
            model.markSaved(f);
            if (Config.get().backupOnSave) backup(Backups.Reason.SAVE);
            project.refresh();
            Config.get().lastDir = f.getParent();
            Config.get().save();
            status("Сохранено: " + f.getName());
        } catch (Exception e) {
            error("Не удалось сохранить", e);
        }
    }

    private void reloadFile() {
        File f = model.file();
        if (f == null || !f.isFile()) {
            status("Файл не задан");
            return;
        }
        if (!model.isDirty()) {
            reloadNow(f);
            return;
        }
        open(Dialogs.confirm("Перечитать",
                "Несохранённые изменения будут потеряны. Перечитать файл?\n"
                        + "Текущее состояние сохранится копией в истории версий.",
                new String[]{"Отмена", "Перечитать"}, answer -> {
                    if (answer == 1) reloadNow(f);
                }));
    }

    private void reloadNow(File f) {
        backup(Backups.Reason.BEFORE_RELOAD);
        try {
            model.setTab(TabIO.load(f), f, false);
            status("Файл перечитан: " + f.getName());
        } catch (Exception e) {
            error("Не удалось перечитать файл", e);
        }
    }

    private File lastDir() {
        String last = Config.get().lastDir;
        return last == null || last.isEmpty() ? null : new File(last);
    }

    /** После открытия файла показывать свойства первого квеста, а не пустую панель. */
    private void selectFirstQuest() {
        for (String id : model.tab().quests.keySet()) {
            model.select(id);
            return;
        }
    }

    private void copyTab() {
        window.setClipboard(model.tab().toJsonString());
        status("JSON вкладки скопирован в буфер");
    }

    private void copyTabCompact() {
        window.setClipboard(TabIO.toPasteString(model.tab()));
        status("Компактный JSON скопирован — можно вставлять в /dwq tab paste");
    }

    private void pasteTab() {
        confirmDiscard(() -> {
            String text = window.clipboard();
            if (text == null || text.trim().isEmpty()) {
                status("В буфере обмена пусто");
                return;
            }
            try {
                model.setTab(QuestTab.parse(text), null, true);
                map.fitToContent();
                status("Вкладка вставлена из буфера, квестов: " + model.tab().quests.size());
            } catch (Exception e) {
                error("В буфере не вкладка DwQuests", e);
            }
        });
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
        status("Добавлен квест: " + id);
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
        status("Дублировано квестов: " + created.size());
    }

    private void deleteSelected() {
        if (model.selection().isEmpty()) return;
        List<String> ids = new ArrayList<>(model.selection());
        String message = ids.size() == 1
                ? "Удалить квест «" + ids.get(0) + "»?"
                : "Удалить квестов: " + ids.size() + "?";
        open(Dialogs.confirm("Удаление", message + "\nСсылки на них в зависимостях тоже уйдут.",
                new String[]{"Отмена", "Удалить"}, answer -> {
                    if (answer != 1) return;
                    model.edit("Удаление квестов", () -> {
                        for (String id : ids) model.tab().removeQuest(id);
                    });
                    model.clearSelection();
                    status("Удалено квестов: " + ids.size());
                }));
    }

    private void copyQuests() {
        questClipboard.clear();
        for (String id : model.selection()) {
            Quest q = model.tab().quests.get(id);
            if (q != null) questClipboard.add(q.copy());
        }
        if (questClipboard.isEmpty()) {
            status("Нечего копировать");
            return;
        }
        JsonObject holder = new JsonObject();
        for (Quest q : questClipboard) holder.put(q.questID, q.toJson());
        window.setClipboard(JsonWriter.write(holder, true));
        status("Скопировано квестов: " + questClipboard.size());
    }

    private void pasteQuests(float atX, float atY) {
        List<Quest> source = new ArrayList<>(questClipboard);
        if (source.isEmpty()) {
            String text = window.clipboard();
            if (text != null && text.trim().startsWith("{")) {
                try {
                    Object parsed = JsonParser.parse(text);
                    if (parsed instanceof JsonObject o) {
                        for (String key : o.keys()) {
                            JsonObject qo = o.obj(key);
                            if (qo != null && qo.has("displayX")) source.add(Quest.fromJson(key, qo));
                        }
                    }
                } catch (RuntimeException ignored) {
                    // в буфере не квесты — значит, вставлять нечего
                }
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
            for (Quest src : source) remap.put(src.questID, freeName(tab, src.questID));
            for (Quest src : source) {
                Quest copy = src.copy();
                copy.questID = remap.get(src.questID);
                copy.displayX += dx;
                copy.displayY += dy;
                // ссылки между вставленными квестами переносим на их копии
                for (Parent p : copy.parents) {
                    String r = remap.get(p.questID);
                    if (r != null) p.questID = r;
                }
                tab.quests.put(copy.questID, copy);
                created.add(copy.questID);
            }
        });
        model.clearSelection();
        for (String id : created) model.addSelect(id);
        status("Вставлено квестов: " + created.size());
    }

    private static String freeName(QuestTab tab, String base) {
        String name = base + " (копия)";
        int n = 2;
        while (tab.quests.containsKey(name)) name = base + " (копия " + n++ + ")";
        return name;
    }

    /** Разложить квесты сеткой, сохранив порядок — удобно после импорта. */
    private void askAutoLayout() {
        QuestTab tab = model.tab();
        if (tab.quests.isEmpty()) return;
        int cols = (int) Math.ceil(Math.sqrt(tab.quests.size()));
        open(Dialogs.confirm("Разложить сеткой",
                "Разложить " + tab.quests.size() + " квестов сеткой " + cols + " в ряд?\n"
                        + "Текущие координаты будут перезаписаны.",
                new String[]{"Отмена", "Разложить"}, answer -> {
                    if (answer != 1) return;
                    backup(Backups.Reason.BEFORE_LAYOUT);
                    model.edit("Раскладка сеткой", () -> {
                        int i = 0;
                        for (Quest q : tab.quests.values()) {
                            q.displayX = (i % cols) * 2;
                            q.displayY = (i / cols) * 2;
                            i++;
                        }
                    });
                    map.fitToContent();
                }));
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
        sb.append("\nВсего замечаний: ").append(problems.size())
                .append(", из них ошибок: ").append(errors);
        showText("Проверка вкладки", sb.toString());
        status(problems.isEmpty() ? "Вкладка в порядке"
                : "Замечаний: " + problems.size() + ", ошибок: " + errors);
    }

    /** Какие id вкладки в игре превратятся в бедрок — вместе с местом, где они. */
    private void auditIds() {
        IdAudit.Report r = IdAudit.check(model.tab());
        showText("Сверка id предметов", IdAudit.text(r));
        status(r.unknown.isEmpty() ? "Неизвестных id нет"
                : "Нет в базе id: " + r.unknown.size() + " — в игре это бедрок");
    }

    private void showVersions() {
        open(VersionsDialog.open(model.file(), model.tab(), restoredTab -> {
            Backups.save(model.file(), model.tab().toJsonString(),
                    Backups.Reason.BEFORE_RESTORE, Config.get().backupKeep);
            model.setTab(restoredTab, model.file(), true);
            selectFirstQuest();
            status("Версия восстановлена — не забудь сохранить (Ctrl+S)");
        }, this::open));
    }

    private void settings() {
        open(SettingsDialog.open(() -> {
            ModAssets.get().reopen();
            ItemRegistry.get().reload();
            McFont.get().reload();
            status("Настройки сохранены, ресурсы перечитаны");
            checkAssets();
        }));
    }

    private void checkAssets() {
        List<String> problems = new ArrayList<>(ModAssets.get().problems());
        if (!McFont.get().isReady()) problems.add("Шрифт не загружен: " + McFont.get().error());
        if (ItemRegistry.get().size() == 0)
            problems.add("Не загружен список предметов: " + ItemRegistry.get().loadError());
        if (problems.isEmpty()) return;
        status("Проблемы с ресурсами: " + problems.size() + " — Инструменты → Настройки");
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
                "  Ctrl+T                   — проверить вкладку",
                "  Ctrl+H                   — история версий",
                "  F11 / Esc                — во весь экран и обратно"));
    }

    private void showAbout() {
        showText("О программе", String.join("\n",
                "DwQuests Editor — редактор вкладок квестов для мода DwQuests (Minecraft 1.7.10).",
                "",
                "Интерфейс и карта рисуются на OpenGL через LWJGL: те же шрифт, цвета,",
                "звёзды-ноды, волнистые линии зависимостей и панель квеста, что и в игре.",
                "",
                "Помощник Claude работает через локальный Claude Code CLI, отдельный ключ API не нужен.",
                "",
                "Есть и консольный режим — для скриптов и для правок из терминала:",
                "  java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.cli.QuestCli help"));
    }

    // ------------------------------------------------------------------ диалоги и состояние

    /** Показать модальное окно; оно рисуется поверх всего до закрытия. */
    public void open(Dialog dialog) {
        if (dialog != null) dialogs.push(dialog);
    }

    private void showText(String title, String text) {
        open(Dialogs.text(title, text, () -> window.setClipboard(text)));
    }

    private void error(String message, Exception e) {
        open(Dialogs.message("Ошибка", message + ":\n" + e.getMessage()));
        status(message + ": " + e.getMessage());
    }

    private void status(String text) {
        status = text;
    }

    private void updateTitle() {
        window.setTitle(model.title());
    }

    /** Копия текущего состояния в историю версий. */
    private void backup(Backups.Reason reason) {
        if (Config.get().backupKeep <= 0) return;
        Backups.saveIfChanged(model.file(), model.tab().toJsonString(), reason, Config.get().backupKeep);
    }

    private void confirmDiscard(Runnable next) {
        confirmDiscard(Backups.Reason.BEFORE_OPEN, next, null);
    }

    private void confirmDiscard(Backups.Reason reason, Runnable next, Runnable onCancel) {
        if (!model.isDirty()) {
            next.run();
            return;
        }
        open(Dialogs.confirm("DwQuests Editor",
                "Есть несохранённые изменения. Сохранить?\n"
                        + "Если нет — состояние всё равно попадёт в историю версий.",
                new String[]{"Отмена", "Не сохранять", "Сохранить"}, answer -> {
                    if (answer == 2) {
                        save();
                        if (!model.isDirty()) next.run();
                        else if (onCancel != null) onCancel.run();
                    } else if (answer == 1) {
                        backup(reason);
                        next.run();
                    } else if (onCancel != null) {
                        onCancel.run();
                    }
                }));
    }

    /**
     * Выход. Окно уже перехватило закрытие ({@code setOnCloseRequest}), так что
     * спросить про несохранённое можно спокойно: закроется оно только по
     * {@code window.close()}.
     */
    private void exit() {
        if (dialogs.isEmpty()) confirmDiscard(Backups.Reason.BEFORE_OPEN, window::close, null);
    }

    /**
     * Отдать просмотру все вкладки папки — левая колонка тогда выглядит как в
     * игре. Открытая в редакторе вкладка передаётся живым объектом, чтобы были
     * видны несохранённые правки; остальные читаются с диска.
     */
    private void feedPreviewTabs() {
        List<QuestTab> list = new ArrayList<>();
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
            preview.preview().setSingleTab(model.tab());
        } else {
            if (!list.contains(model.tab())) list.add(model.tab());
            preview.preview().setTabs(list, model.tab().tabID);
        }
    }

    /** Открыть файл из кода — запуск с аргументом и отладочные проверки. */
    public void openFile(File f) {
        try {
            model.setTab(TabIO.load(f), f, false);
            selectFirstQuest();
            map.fitToContent();
            if (f.getParentFile() != null) {
                workspace.open(f.getParentFile());
                project.refresh();
                project.setCurrentFile(f);
            }
            feedPreviewTabs();
        } catch (Exception e) {
            status("Не удалось открыть " + f.getName() + ": " + e.getMessage());
        }
    }

    /** Переключить раздел из кода — запуск с аргументом и проверки вида. */
    public void selectPage(int index) {
        page = Math.max(0, Math.min(PAGES.length - 1, index));
        if (page != lastPage) {
            lastPage = page;
            if (page == 1) feedPreviewTabs();
        }
    }

    /** Переключить страницу правой колонки из кода — для проверок вида. */
    public void selectSidebarPage(int index) { sidebar.setPage(index); }

    /** Спрятать обвязку без захвата экрана — для снимков и проверок. */
    public void setChromeVisible(boolean visible) { chromeHidden = !visible; }

    /** Прокрутить список вкладок в просмотре — для проверки. */
    public void scrollPreviewTabs() {
        preview.preview().closePanel();
        preview.preview().onWheel(60, 200, 2);
    }

    public MapView map() { return map; }

    // ------------------------------------------------------------------ поле редактора

    private final class MapCallbacks implements MapView.Callbacks {
        @Override
        public void questDoubleClicked(String questID) {
            model.select(questID);
            page = 1;
            feedPreviewTabs();
            preview.preview().openQuest(questID);
        }

        @Override public void requestAddQuest(float x, float y) { addQuest(x, y); }

        @Override public void requestCopy() { copyQuests(); }

        @Override public void requestPaste(float x, float y) { pasteQuests(x, y); }

        @Override public void requestDelete() { deleteSelected(); }
    }

    /** Модель поменялась — заголовок и просмотр должны это заметить. */
    private void onModelChanged(String what) {
        updateTitle();
        if ("tab".equals(what) && page == 1) feedPreviewTabs();
        // страница JSON показывает модель, а не свою копию: любая правка —
        // хоть из полей справа, хоть сменой вкладки — требует перечитать текст
        json.reset();
    }
}
