package ru.dw.qeditor.app;

import ru.dw.qeditor.android.Platform;
import ru.dw.qeditor.android.TouchInput;
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
import ru.dw.qeditor.io.TabDiff;
import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.io.Workspace;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.render.GamePreview;
import ru.dw.qeditor.render.TabPoster;
import ru.dw.qeditor.util.SelfTest;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static ru.dw.qeditor.gl.Keys.GLFW_KEY_A;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_C;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_D;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_ESCAPE;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_F11;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_H;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_N;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_O;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_S;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_T;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_V;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_Y;
import static ru.dw.qeditor.gl.Keys.GLFW_KEY_Z;
import static ru.dw.qeditor.gl.Keys.GLFW_MOD_CONTROL;
import static ru.dw.qeditor.gl.Keys.GLFW_MOD_SHIFT;

/**
 * Главное окно редактора на телефоне и планшете.
 *
 * <p>Android-вариант общего {@code app/App}: те же страницы, панели, меню,
 * диалоги и правка, но
 * <ul>
 *   <li>на узком экране колонки не помещаются — вместо них переключатель
 *       разделов «Вкладки / Карта / Квест / Просмотр / JSON», на широком
 *       (планшет) всё как на компьютере, тремя колонками;</li>
 *   <li>Shift и Ctrl, без которых на карте не связать квесты и не добрать
 *       выделение, — залипающими кнопками на панели;</li>
 *   <li>файлы выбираются своим окном ({@link FileDialogs}), а не системным, и
 *       ответ приходит обратным вызовом;</li>
 *   <li>моста с игрой нет — меню «Игра» и кнопок прогресса тоже;</li>
 *   <li>несохранённые правки переживают выгрузку приложения из памяти:
 *       при уходе в фон они пишутся черновиком и при запуске возвращаются.</li>
 * </ul>
 */
public final class App implements TouchInput.Zones {

    private static final float PROJECT_WIDTH = 240;
    /** С какой ширины (в логических точках) колонки идут рядом, как на компьютере. */
    private static final float WIDE = 1000;
    private static final String[] PAGES = {"Редактор", "Просмотр как в игре", "JSON"};
    /** Разделы узкого экрана. */
    private static final String[] SECTIONS = {"Вкладки", "Карта", "Квест", "Просмотр", "JSON"};
    private static final int S_TABS = 0, S_MAP = 1, S_QUEST = 2, S_PREVIEW = 3, S_JSON = 4;
    /** Высота строк, которые рисует само окно: под палец, а не под мышь. */
    private static final float BAR = 40;
    private static final float BTN = 32;

    private final GlWindow window;
    private final Batch batch;
    private final Ui ui;
    private final Popup popup = new Popup();

    private final EditorModel model = new EditorModel();

    EditorModel model() { return model; }

    private final Workspace workspace = new Workspace();
    private final ProjectView project = new ProjectView(workspace);
    private final MapView map = new MapView(model);
    private final PreviewView preview = new PreviewView();
    private final Sidebar sidebar;
    private final JsonView json;

    private final Deque<Dialog> dialogs = new ArrayDeque<>();
    private final List<Quest> questClipboard = new ArrayList<>();
    private String questClipboardText;

    /** Страница широкого экрана: 0 — карта, 1 — просмотр, 2 — JSON. */
    private int page;
    private int lastPage;
    /** Раздел узкого экрана. */
    private int section = S_MAP;
    private boolean wide;
    private float sidebarWidth = 430;
    private boolean showProject = true;
    private boolean chromeHidden;
    private String status = "Готово";

    private double lastWatch;
    private long askedStamp;
    private double lastAutosave;
    private int openMenu = -1;
    private List<Runnable> menuActions = new ArrayList<>();
    private final Object menuId = new Object();
    private final Object viewMenuId = new Object();
    private List<Runnable> viewMenuActions = new ArrayList<>();

    /** Залипшие Shift и Ctrl — с ними уходят касания карты. */
    private int stickyMods;

    /** Где сейчас карта и просмотр — в пикселях окна: по ним касание выбирает жест. */
    private final float[] mapRect = new float[4];
    private final float[] previewRect = new float[4];
    private boolean mapShown, previewShown;
    private boolean overlayOpen;
    /** Esc этого кадра достанется диалогу или меню, а не выходу. */
    private boolean escapeTaken;
    private boolean keyboardWanted;
    private boolean askedStorage;

    public App(GlWindow window, Batch batch) {
        this.window = window;
        this.batch = batch;
        ui = new Ui(window, batch);
        sidebar = new Sidebar(model, popup, this::open);
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

        project.setOnOpen(e -> {
            openTabFile(e.file);
            if (!wide) section = S_MAP;
        });
        project.setOnNewTab(this::newTabInFolder);
        project.setOnChooseFolder(this::openFolder);

        window.setOnCloseRequest(this::exit);
        model.addListener(this::onModelChanged);
        model.setTypingProbe(ui::keyboardCaptured);
        updateTitle();
        checkAssets();
        restoreLastFolder();
        restoreDraft();
    }

    // ------------------------------------------------------------------ для окна Android

    /** Модификаторы, которые добавить к касаниям. */
    public int stickyMods() { return stickyMods; }

    /** Нужна ли сейчас клавиатура телефона: в фокусе поле ввода. */
    public boolean wantsKeyboard() { return keyboardWanted; }

    /** Нужны ли кадры без остановки: в просмотре идёт анимация. */
    public boolean animating() {
        return previewShown || posterFile != null;
    }

    @Override
    public TouchInput.Zone at(float x, float y) {
        if (overlayOpen) return TouchInput.Zone.UI;
        if (mapShown && inside(mapRect, x, y)) return TouchInput.Zone.MAP;
        if (previewShown && inside(previewRect, x, y)) return TouchInput.Zone.PREVIEW;
        return TouchInput.Zone.UI;
    }

    private static boolean inside(float[] r, float x, float y) {
        return x >= r[0] && y >= r[1] && x < r[0] + r[2] && y < r[1] + r[3];
    }

    private void remember(float[] r, float x, float y, float w, float h) {
        float s = ui.scale();
        r[0] = x * s;
        r[1] = y * s;
        r[2] = w * s;
        r[3] = h * s;
    }

    // ------------------------------------------------------------------ цикл

    public void requestClose() { exit(); }

    public void drawFrame() {
        batch.begin(window.width(), window.height());
        ui.begin();
        frame();
        ui.end();
        batch.end();
        renderPendingPoster();
    }

    private void frame() {
        mapShown = previewShown = false;
        // три колонки — только на планшете: на телефоне боком ширины хватает,
        // но карта между колонками остаётся щелью
        wide = ui.width() >= WIDE && ui.height() >= 600;
        escapeTaken = !dialogs.isEmpty() || popup.isOpen();
        background();
        tick();

        Dialog top = dialogs.peek();
        if (top != null) ui.blockInput();
        popup.blockIfOpen(ui);

        float y = 0;
        if (!chromeHidden) {
            drawTopBar(0, 0, ui.width());
            y = BAR;
            if (!wide) {
                drawSections(0, y, ui.width());
                y += BAR;
            }
        }
        float bottom = chromeHidden ? ui.height() : ui.height() - Theme.STATUS_BAR;
        drawBody(y, bottom - y);
        if (!chromeHidden) Widgets.statusBar(ui, status, ui.height() - Theme.STATUS_BAR);
        if (chromeHidden) drawFullscreenExit();

        popup.draw(ui);
        sidebar.afterFrame(ui);

        if (top != null) {
            top.draw(ui);
            if (top.isClosed()) dialogs.remove(top);
        }

        hotkeys();
        overlayOpen = !dialogs.isEmpty() || popup.isOpen();
        keyboardWanted = ui.keyboardCaptured();
    }

    private void background() {
        ui.rect(0, 0, ui.width(), ui.height(), Theme.BG);
    }

    private void drawBody(float y, float h) {
        if (chromeHidden) {
            if (wide ? page == 1 : section == S_PREVIEW) drawPreviewArea(0, y, ui.width(), h);
            else drawMapArea(0, y, ui.width(), h, false);
            return;
        }
        if (!wide) {
            switch (section) {
                case S_TABS -> project.draw(ui, 0, y, ui.width(), h);
                case S_QUEST -> drawQuest(0, y, ui.width(), h);
                case S_PREVIEW -> {
                    float th = drawPreviewToolbar(10, y + 6, ui.width() - 20);
                    drawPreviewArea(8, y + 6 + th, ui.width() - 16, h - th - 14);
                }
                case S_JSON -> json.draw(ui, 12, y + 8, ui.width() - 24, Math.max(80, h - 16));
                default -> {
                    float th = drawEditorToolbar(10, y + 6, ui.width() - 20);
                    drawMapArea(8, y + 6 + th, ui.width() - 16, h - th - 14, true);
                }
            }
            return;
        }

        float x = 0;
        if (showProject) {
            project.draw(ui, x, y, PROJECT_WIDTH, h);
            x += PROJECT_WIDTH;
            ui.vSeparator(x, y, h);
            x += 1;
        }
        float maxSidebar = Math.max(280, ui.width() - x - 420);
        sidebarWidth = Math.max(300, Math.min(maxSidebar, sidebarWidth));
        float sx = ui.width() - sidebarWidth;
        sidebarWidth = ui.width() - Widgets.splitterV(ui, "sidebar", sx - 3, y, h, sx, x + 420, ui.width() - 300);
        sidebarWidth = Math.max(300, Math.min(maxSidebar, sidebarWidth));
        float right = ui.width() - sidebarWidth;
        sidebar.draw(ui, right, y, sidebarWidth, h);
        drawPage(x, y, right - x, h);
    }

    private void drawPage(float x, float y, float w, float h) {
        float cy = y + 4;
        page = Widgets.tabs(ui, x + 10, cy, Theme.ROW_LARGE, PAGES, page);
        cy += Theme.ROW_LARGE + 4;
        pageChanged(page == 1);
        switch (page) {
            case 0 -> {
                float toolH = drawEditorToolbar(x + 10, cy, w - 20);
                cy += toolH;
                drawMapArea(x + 8, cy, w - 16, Math.max(80, y + h - cy - 8), true);
            }
            case 1 -> {
                float toolH = drawPreviewToolbar(x + 10, cy, w - 20);
                cy += toolH;
                drawPreviewArea(x + 8, cy, w - 16, Math.max(80, y + h - cy - 8));
            }
            default -> json.draw(ui, x + 12, cy + 4, w - 24, Math.max(80, y + h - cy - 16));
        }
    }

    /** На просмотр переходим с готовым списком вкладок папки. */
    private void pageChanged(boolean previewNow) {
        int current = previewNow ? 1 : 0;
        if (current == lastPage) return;
        lastPage = current;
        if (previewNow) {
            feedPreviewTabs();
            String id = model.single();
            if (id != null) preview.preview().openQuest(id);
        }
    }

    private void drawMapArea(float x, float y, float w, float h, boolean framed) {
        h = Math.max(80, h);
        if (framed) {
            ui.panel(x, y, w, h);
            x += 1;
            y += 1;
            w -= 2;
            h -= 2;
        }
        map.draw(ui, popup, x, y, w, h);
        remember(mapRect, x, y, w, h);
        mapShown = true;
    }

    private void drawPreviewArea(float x, float y, float w, float h) {
        h = Math.max(80, h);
        preview.draw(ui, x, y, w, h);
        remember(previewRect, x, y, w, h);
        previewShown = true;
    }

    private void drawQuest(float x, float y, float w, float h) {
        if (model.selection().isEmpty()) {
            ui.textIn("Квест не выбран — коснись узла на карте", x, y, w, 60,
                    Theme.TEXT_MUTED, Ui.CENTER);
            if (ui.button(x + w / 2 - 90, y + 64, 180, BTN, "К карте", Ui.PRIMARY, true)) section = S_MAP;
            return;
        }
        sidebar.draw(ui, x, y, w, h);
    }

    private void drawSections(float x, float y, float w) {
        ui.rect(x, y, w, BAR, Theme.SURFACE);
        ui.hSeparator(x, y + BAR, w);
        int was = section;
        section = Widgets.tabs(ui, x + 6, y + 4, BAR - 8, SECTIONS, section);
        if (section != was) {
            pageChanged(section == S_PREVIEW);
            // список вкладок папки перечитывается при каждом заходе: файлы могли поменяться
            if (section == S_TABS) project.refresh();
        }
    }

    private void drawFullscreenExit() {
        float s = 44;
        float x = ui.width() - s - 8, y = 8;
        if (ui.button(x, y, s, s, "✕", Ui.NORMAL, true)) toggleFullscreen();
    }

    // ------------------------------------------------------------------ панели инструментов

    /** Раскладка кнопок потоком: что не влезло в строку — на следующую. */
    private final class Flow {
        final float x0, w;
        float x, y;
        final float h;

        Flow(float x, float y, float w, float h) {
            this.x0 = this.x = x;
            this.y = y;
            this.w = w;
            this.h = h;
        }

        float place(float bw) {
            if (x > x0 && x + bw > x0 + w) {
                x = x0;
                y += h + 6;
            }
            float at = x;
            x += bw + 6;
            return at;
        }

        float height(float top) { return y - top + h + 8; }
    }

    private float drawEditorToolbar(float x, float y, float w) {
        Flow f = new Flow(x, y, w, BTN);
        boolean any = !model.selection().isEmpty();

        float bx = f.place(118);
        if (ui.button(bx, f.y, 118, BTN, "+ Квест", Ui.PRIMARY, true)) addQuestInView();
        bx = f.place(96);
        if (ui.button(bx, f.y, 96, BTN, "Дубль", Ui.NORMAL, any)) duplicateSelected();
        bx = f.place(96);
        if (ui.button(bx, f.y, 96, BTN, "Удалить", Ui.DANGER, any)) deleteSelected();

        // Shift и Ctrl для карты: связь тягой от квеста к квесту и добор выделения
        bx = f.place(92);
        boolean link = (stickyMods & GLFW_MOD_SHIFT) != 0;
        if (ui.toggleButton(bx, f.y, 92, BTN, "Связь", link)) {
            stickyMods ^= GLFW_MOD_SHIFT;
            status((stickyMods & GLFW_MOD_SHIFT) != 0
                    ? "Связь: тяни от квеста-родителя к квесту; долгое нажатие на линии — удалить её"
                    : "Связь выключена");
        }
        bx = f.place(92);
        boolean multi = (stickyMods & GLFW_MOD_CONTROL) != 0;
        if (ui.toggleButton(bx, f.y, 92, BTN, "Набор", multi)) {
            stickyMods ^= GLFW_MOD_CONTROL;
            status((stickyMods & GLFW_MOD_CONTROL) != 0
                    ? "Набор: касание добавляет квест к выделению или убирает из него"
                    : "Набор выключен");
        }

        bx = f.place(90);
        if (ui.button(bx, f.y, 90, BTN, "Вписать", Ui.GHOST, true)) map.fitToContent();
        bx = f.place(90);
        if (ui.button(bx, f.y, 90, BTN, "Вид ▾", Ui.GHOST, true)) openViewMenu(bx, f.y + BTN, f.y);
        bx = f.place(54);
        if (ui.button(bx, f.y, 54, BTN, "⛶", Ui.GHOST, true)) toggleFullscreen();
        bx = f.place(70);
        ui.textIn(Math.round(map.getZoom() * 100) + "%", bx, f.y, 70, BTN,
                Theme.TEXT_DISABLED, Ui.LEFT, Theme.FONT_SMALL, false);
        return f.height(y);
    }

    /** Сетка, привязка и что показывать на карте — меню, чтобы не занимать строку. */
    private void openViewMenu(float x, float below, float above) {
        List<Popup.Item> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        check(items, actions, "Сетка", map.isShowGrid(), () -> map.setShowGrid(!map.isShowGrid()));
        check(items, actions, "Привязка к сетке", map.isSnap(), () -> map.setSnap(!map.isSnap()));
        check(items, actions, "Все имена", map.isShowNames(), () -> map.setShowNames(!map.isShowNames()));
        check(items, actions, "Связи", map.isShowLinks(), () -> map.setShowLinks(!map.isShowLinks()));
        check(items, actions, "Скрытые связи", map.isShowHiddenLinks(),
                () -> map.setShowHiddenLinks(!map.isShowHiddenLinks()));
        separator(items, actions);
        item(items, actions, "Шаг сетки: " + Ui.num(map.getGridSize()) + "…", null, () ->
                open(Dialogs.prompt("Шаг сетки", "Шаг в клетках (в игре клетка — 24 пикселя)",
                        Ui.num(map.getGridSize()), s -> {
                            try {
                                map.setGridSize(Float.parseFloat(s.trim().replace(',', '.')));
                            } catch (NumberFormatException ignored) {
                                status("Не число: " + s);
                            }
                        })));
        item(items, actions, "Сдвиг сетки: " + Ui.num(map.getGridOffsetX()) + "; "
                + Ui.num(map.getGridOffsetY()) + "…", null, () ->
                open(Dialogs.prompt("Сдвиг сетки", "Сдвиг по X и Y через пробел, от −8 до 8",
                        Ui.num(map.getGridOffsetX()) + " " + Ui.num(map.getGridOffsetY()), s -> {
                            String[] p = s.trim().replace(',', '.').split("[\\s;]+");
                            try {
                                float ox = Float.parseFloat(p[0]);
                                float oy = p.length > 1 ? Float.parseFloat(p[1]) : ox;
                                map.setGridOffset(Math.max(-8, Math.min(8, ox)), Math.max(-8, Math.min(8, oy)));
                            } catch (RuntimeException e) {
                                status("Не разобрать: " + s);
                            }
                        })));
        separator(items, actions);
        item(items, actions, "Разложить сеткой…", null, this::askAutoLayout);
        item(items, actions, "Повернуть выделенное на 90°", null, () -> map.rotateSelection(90));
        item(items, actions, "К выбранному квесту", null, () -> {
            String id = model.single();
            if (id != null) map.centerOn(id);
        });
        viewMenuActions = actions;
        popup.open(viewMenuId, x, below, above, items, 240);
    }

    private float drawPreviewToolbar(float x, float y, float w) {
        Flow f = new Flow(x, y, w, BTN);
        GamePreview.Mode[] modes = GamePreview.Mode.values();
        String[] names = new String[modes.length];
        for (int i = 0; i < modes.length; i++) names[i] = modes[i].ru;
        float bx = f.place(170);
        int mode = Widgets.dropdown(ui, popup, "previewMode", bx, f.y, 170, BTN, names,
                preview.preview().getMode().ordinal());
        if (mode != preview.preview().getMode().ordinal()) preview.preview().setMode(modes[mode]);

        bx = f.place(150);
        ui.textIn("масштаб", bx, f.y, 60, BTN, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        int scale = ui.intField("guiScale", bx + 62, f.y, 80, BTN, Math.round(preview.getGuiScale()), 1, 4);
        if (scale != Math.round(preview.getGuiScale())) {
            preview.setGuiScale(scale);
            Config.get().guiScale = scale;
            Config.get().save();
        }
        bx = f.place(110);
        if (ui.button(bx, f.y, 110, BTN, "Обновить", Ui.GHOST, true)) feedPreviewTabs();
        bx = f.place(54);
        if (ui.button(bx, f.y, 54, BTN, "⛶", Ui.GHOST, true)) toggleFullscreen();
        float rest = x + w - f.x;
        if (rest > 200) {
            ui.textIn("тап — квест, тяга — панорама, два пальца — прокрутка и зум",
                    f.x, f.y, rest, BTN, Theme.TEXT_DISABLED, Ui.LEFT, Theme.FONT_SMALL, false);
        }
        return f.height(y);
    }

    // ------------------------------------------------------------------ меню

    private static final String[] MENUS = {"Файл", "Правка", "Вид", "Сервис", "Справка"};

    private void drawTopBar(float x, float y, float w) {
        ui.rect(x, y, w, BAR, Theme.SURFACE);
        ui.hSeparator(x, y + BAR, w);

        int picked = popup.takePick(menuId);
        if (picked >= 0 && picked < menuActions.size()) {
            Runnable r = menuActions.get(picked);
            if (r != null) r.run();
        }
        picked = popup.takePick(viewMenuId);
        if (picked >= 0 && picked < viewMenuActions.size()) {
            Runnable r = viewMenuActions.get(picked);
            if (r != null) r.run();
        }
        if (!popup.isOpen(menuId)) openMenu = -1;

        // справа — то, что нужно чаще всего: отмена, повтор, сохранить
        float qw = 46;
        float right = x + w - 6;
        float sx = right - 104;
        if (ui.button(sx, y + 4, 104, BAR - 8, "Сохранить",
                model.isDirty() ? Ui.PRIMARY : Ui.NORMAL, true)) save();
        float rx = sx - qw - 4;
        if (ui.button(rx, y + 4, qw, BAR - 8, "↷", Ui.GHOST, model.history().canRedo())) redo();
        float ux = rx - qw - 2;
        if (ui.button(ux, y + 4, qw, BAR - 8, "↶", Ui.GHOST, model.history().canUndo())) undo();

        // меню: пока влезают — рядом, иначе одной кнопкой с общим списком
        float total = 0;
        for (String t : MENUS) total += ui.textWidth(t) + 24;
        float avail = ux - x - 12;
        float mx = x + 6;
        if (total <= avail) {
            for (int i = 0; i < MENUS.length; i++) {
                float mw = ui.textWidth(MENUS[i]) + 24;
                if (menuTitle(MENUS[i], mx, y, mw, openMenu == i)) {
                    openMenu = i;
                    buildMenu(i, mx, y + BAR);
                }
                mx += mw;
            }
        } else {
            float mw = 86;
            if (menuTitle("☰ Меню", mx, y, mw, openMenu == 99)) {
                openMenu = 99;
                buildAllMenus(mx, y + BAR);
            }
            mx += mw;
        }

        if (model.isDirty() && mx + 30 < ux) {
            ui.textIn("●", mx + 4, y, 20, BAR, Theme.WARNING, Ui.CENTER);
            if (ui.over(mx, y, 28, BAR) && ui.in().leftPressed()) showChanges();
        }
    }

    private boolean menuTitle(String title, float mx, float y, float mw, boolean active) {
        boolean hovered = ui.over(mx, y, mw, BAR);
        if (active || hovered) ui.roundRect(mx, y + 4, mw, BAR - 8,
                Theme.RADIUS_SMALL, active ? Theme.PRESSED : Theme.HOVER);
        ui.textIn(title, mx, y, mw, BAR, active || hovered ? Theme.TEXT : Theme.TEXT_MUTED, Ui.CENTER);
        return hovered && ui.in().leftPressed();
    }

    private void buildMenu(int index, float x, float y) {
        List<Popup.Item> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        menuItems(index, items, actions);
        menuActions = actions;
        popup.open(menuId, x, y, items, 260);
    }

    /** Узкий экран: все меню одним списком, с заголовками разделов. */
    private void buildAllMenus(float x, float y) {
        List<Popup.Item> items = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();
        for (int i = 0; i < MENUS.length; i++) {
            if (i > 0) separator(items, actions);
            disabled(items, actions, MENUS[i].toUpperCase());
            menuItems(i, items, actions);
        }
        // Popup раскрывается прокрученным к отмеченному пункту — в общем списке
        // это увело бы начало меню за край, поэтому отметка здесь просто значок
        for (int i = 0; i < items.size(); i++) {
            Popup.Item it = items.get(i);
            if (it.checkbox()) items.set(i, Popup.Item.of((it.checked() ? "✓ " : "    ") + it.label()));
        }
        menuActions = actions;
        popup.open(menuId, x, y, items, 280);
    }

    private void menuItems(int index, List<Popup.Item> items, List<Runnable> actions) {
        switch (index) {
            case 0 -> {
                item(items, actions, "Новая вкладка", null, this::newTab);
                item(items, actions, "Открыть…", null, this::open);
                item(items, actions, "Открыть папку…", null, this::openFolder);
                item(items, actions, "Сохранить", null, this::save);
                item(items, actions, "Сохранить как…", null, this::saveAs);
                item(items, actions, "Несохранённые изменения…", null, this::showChanges);
                separator(items, actions);
                item(items, actions, "Импорт вкладок из телефона…", null, this::importTabs);
                item(items, actions, "Поделиться вкладкой…", null, this::shareTab);
                item(items, actions, "Сохранить копию в…", null, this::exportTab);
                item(items, actions, "Примеры вкладок", null, this::openExamples);
                separator(items, actions);
                item(items, actions, "Вставить вкладку из буфера", null, this::pasteTab);
                item(items, actions, "Копировать вкладку в буфер", null, this::copyTab);
                item(items, actions, "Копировать для /dwq tab paste", null, this::copyTabCompact);
                item(items, actions, "Сохранить картинку вкладки…", null, () -> savePoster(false));
                item(items, actions, "Сохранить анимацию вкладки (GIF)…", null, () -> savePoster(true));
                separator(items, actions);
                item(items, actions, "Перечитать файл", null, this::reloadFile);
                item(items, actions, "Выход", null, this::exit);
            }
            case 1 -> {
                if (model.history().canUndo())
                    item(items, actions, "Отменить: " + model.history().undoLabel(), null, this::undo);
                else disabled(items, actions, "Отменить");
                if (model.history().canRedo())
                    item(items, actions, "Повторить: " + model.history().redoLabel(), null, this::redo);
                else disabled(items, actions, "Повторить");
                separator(items, actions);
                item(items, actions, "Добавить квест", null, this::addQuestInView);
                item(items, actions, "Дублировать квесты", null, this::duplicateSelected);
                item(items, actions, "Удалить квесты", null, this::deleteSelected);
                item(items, actions, "Выделить всё", null, model::selectAll);
                item(items, actions, "Снять выделение", null, model::clearSelection);
                separator(items, actions);
                item(items, actions, "Копировать квесты", null, this::copyQuests);
                item(items, actions, "Вставить квесты", null, () -> pasteQuests(0, 0));
            }
            case 2 -> {
                item(items, actions, "Во весь экран", null, this::toggleFullscreen);
                separator(items, actions);
                item(items, actions, "Вписать вкладку", null, map::fitToContent);
                item(items, actions, "К выбранному квесту", null, () -> {
                    String id = model.single();
                    if (id != null) map.centerOn(id);
                });
                separator(items, actions);
                if (wide) check(items, actions, "Список вкладок папки", showProject, () -> showProject = !showProject);
                check(items, actions, "Следить за файлом", Config.get().watchFile, () -> {
                    Config.get().watchFile = !Config.get().watchFile;
                    Config.get().save();
                });
            }
            case 3 -> {
                item(items, actions, "История версий…", null, this::showVersions);
                item(items, actions, "Сохранить версию сейчас", null, () -> {
                    File made = Backups.save(model.file(), model.tab().toJsonString(),
                            Backups.Reason.MANUAL, Config.get().backupKeep);
                    status(made == null ? "Не удалось сохранить версию"
                            : "Версия сохранена: " + made.getName());
                });
                separator(items, actions);
                item(items, actions, "Проверить вкладку", null, this::validateTab);
                item(items, actions, "Сверить id предметов", null, this::auditIds);
                item(items, actions, "Диагностика", null,
                        () -> showText("Диагностика", Platform.get().describe() + "\n\n" + SelfTest.run()));
                item(items, actions, "Настройки…", null, this::settings);
            }
            default -> {
                item(items, actions, "Жесты и кнопки", null, this::showHelp);
                item(items, actions, "О программе", null, this::showAbout);
            }
        }
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

    /** Внешняя клавиатура (Bluetooth, планшет с чехлом) — те же сочетания, что на компьютере. */
    private void hotkeys() {
        if (!dialogs.isEmpty()) {
            return;
        }
        if (ui.shortcut(GLFW_KEY_N, true)) newTab();
        if (ui.shortcut(GLFW_KEY_O, true)) open();
        if (ui.shortcut(GLFW_KEY_S, true)) save();
        if (!ui.keyboardCaptured()) {
            if (ui.shortcut(GLFW_KEY_Z, true)) undo();
            if (ui.shortcut(GLFW_KEY_Y, true)) redo();
            if (ui.shortcut(GLFW_KEY_D, true)) duplicateSelected();
            if (ui.shortcut(GLFW_KEY_A, true)) model.selectAll();
            if (ui.shortcut(GLFW_KEY_C, true)) copyQuests();
            if (ui.shortcut(GLFW_KEY_V, true)) pasteQuests(0, 0);
        }
        if (ui.shortcut(GLFW_KEY_T, true)) validateTab();
        if (ui.shortcut(GLFW_KEY_H, true)) showVersions();
        if (ui.shortcut(GLFW_KEY_F11, false)) toggleFullscreen();
        // «Назад» телефона приходит как Esc: сначала выход из полного экрана,
        // потом с узкого раздела — на карту, и только потом из приложения
        if (!escapeTaken && ui.shortcut(GLFW_KEY_ESCAPE, false) && !ui.in().leftDown()) {
            if (chromeHidden) toggleFullscreen();
            else if (!wide && section != S_MAP) section = S_MAP;
            else exit();
        }
    }

    public void toggleFullscreen() {
        chromeHidden = !chromeHidden;
        Platform.get().setImmersive(chromeHidden);
        status(chromeHidden ? "Во весь экран. «Назад» или ✕ — вернуться" : "Обычный режим");
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
        if (!askedStorage) {
            askedStorage = true;
            askStorageOnce();
        }
    }

    private void watchFile() {
        File f = model.file();
        if (f == null || !f.isFile()) return;
        long stamp = f.lastModified();
        if (stamp == model.fileStamp()) return;
        if (model.isDirty()) {
            if (stamp == askedStamp) return;
            askedStamp = stamp;
            open(Dialogs.confirm("Файл изменён",
                    "Файл изменён снаружи, но в редакторе есть несохранённые правки.\n"
                            + "Перечитать файл и потерять их?",
                    new String[]{"Оставить свои", "Перечитать"}, answer -> {
                        if (answer == 1) reloadSilently(f);
                        else {
                            model.markSaved(f);
                            model.touch();
                        }
                    }));
            return;
        }
        reloadSilently(f);
    }

    Workspace workspace() {
        return workspace.isOpen() ? workspace : null;
    }

    void folderChanged() {
        workspace.rescan();
        project.refresh();
        feedPreviewTabs();
        watchFile();
    }

    private void reloadSilently(File f) {
        try {
            model.setTab(TabIO.load(f), f, false);
            status("Файл изменён снаружи — перечитан: " + f.getName());
        } catch (Exception ex) {
            status("Файл изменён снаружи, но не читается: " + ex.getMessage());
        }
    }

    /** Первый запуск без доступа к памяти: объяснить, зачем он, и предложить дать. */
    private void askStorageOnce() {
        Platform p = Platform.get();
        if (p.hasStorageAccess() || Config.get().askedStorage) return;
        Config.get().askedStorage = true;
        Config.get().save();
        open(Dialogs.confirm("Доступ к файлам",
                "Редактор работает с папками вкладок: рядом с файлом хранит версии,\n"
                        + "следит, не изменили ли файл другим приложением.\n\n"
                        + "Чтобы открывать вкладки в любой папке телефона (Документы, Загрузки,\n"
                        + "папка игры), разреши доступ ко всем файлам.\n\n"
                        + "Без него вкладки живут в каталоге приложения, а в него и из него\n"
                        + "их можно передать через «Импорт» и «Поделиться».",
                new String[]{"Позже", "Разрешить"}, answer -> {
                    if (answer == 1) p.requestStorageAccess();
                }));
    }

    /** Доступ к памяти выдан (или отозван) — окно Android вернулось на передний план. */
    public void onResume() {
        if (!workspace.isOpen() || !workspace.dir().isDirectory()) restoreLastFolder();
        else folderChanged();
    }

    // ------------------------------------------------------------------ файл

    private void newTab() {
        confirmDiscard(() -> open(Dialogs.prompt("Новая вкладка", "Имя вкладки (tabID)", "NewTab", id -> {
            if (id.trim().isEmpty()) return;
            if (workspace.isOpen()) {
                createTabFile(id.trim());
                return;
            }
            model.setTab(QuestTab.empty(id.trim()), null, false);
            map.fitToContent();
            status("Создана вкладка: " + id.trim());
        })));
    }

    private void open() {
        confirmDiscard(() -> open(FileDialogs.openJson("Открыть вкладку", startDir(), f -> {
            try {
                QuestTab tab = TabIO.load(f);
                model.setTab(tab, f, false);
                selectFirstQuest();
                Config.get().lastDir = f.getParent();
                Config.get().save();
                map.fitToContent();
                if (f.getParentFile() != null && (!workspace.isOpen()
                        || !f.getParentFile().equals(workspace.dir()))) {
                    workspace.open(f.getParentFile());
                    Config.get().lastFolder = f.getParent();
                    Config.get().save();
                    project.refresh();
                }
                project.setCurrentFile(f);
                if (!wide) section = S_MAP;
                status("Открыто: " + f.getName() + ", квестов: " + tab.quests.size()
                        + "   •   " + Backups.summary(f));
            } catch (Exception e) {
                error("Не удалось открыть файл", e);
            }
        })));
    }

    private File startDir() {
        if (workspace.isOpen()) return workspace.dir();
        File last = lastDir();
        return last != null && last.isDirectory() ? last : Platform.get().defaultFolder();
    }

    /** При запуске вернуть папку, с которой работали в прошлый раз, иначе — папку по умолчанию. */
    private void restoreLastFolder() {
        String last = Config.get().lastFolder;
        File dir = last == null || last.isEmpty() ? null : new File(last);
        if (dir == null || !dir.isDirectory()) {
            dir = Platform.get().defaultFolder();
            if (!dir.isDirectory() && !dir.mkdirs()) return;
        }
        workspace.open(dir);
        project.refresh();
        if (workspace.entries().isEmpty()) {
            status("Папка " + dir.getName() + " пуста — «+ Вкладка», импорт или Файл → Примеры вкладок");
            return;
        }
        status("Папка: " + dir.getName() + ", вкладок: " + workspace.entries().size()
                + ", квестов: " + workspace.totalQuests());
        if (model.file() == null && !model.isDirty()) {
            Workspace.Entry first = workspace.entries().get(0);
            if (first.error == null) openTabFile(first.file);
        }
    }

    private void openFolder() {
        open(FileDialogs.folder("Папка с вкладками квестов", startDir(), this::openFolderAt));
    }

    private void openFolderAt(File dir) {
        workspace.open(dir);
        Config.get().lastFolder = dir.getAbsolutePath();
        Config.get().save();
        project.refresh();
        showProject = true;
        if (!wide) section = S_TABS;

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

    /** Файл, переданный приложению снаружи («Открыть с помощью», «Поделиться»). */
    public void openExternal(File f) {
        if (f.getParentFile() != null && (!workspace.isOpen() || !f.getParentFile().equals(workspace.dir()))) {
            workspace.open(f.getParentFile());
            project.refresh();
        }
        openTabFile(f);
        if (!wide) section = S_MAP;
    }

    /** Вкладка текстом («Поделиться» из мессенджера) — как вставка из буфера. */
    public void openExternalText(String text) {
        confirmDiscard(() -> {
            try {
                model.setTab(QuestTab.parse(text), null, true);
                map.fitToContent();
                status("Вкладка принята текстом, квестов: " + model.tab().quests.size() + " — сохрани её");
            } catch (Exception e) {
                error("Это не вкладка DwQuests", e);
            }
        });
    }

    private void newTabInFolder() {
        if (!workspace.isOpen()) {
            openFolder();
            return;
        }
        open(Dialogs.prompt("Новая вкладка в папке", "Имя новой вкладки (tabID)", "NewTab", id -> {
            if (id.trim().isEmpty()) return;
            createTabFile(id.trim());
        }));
    }

    private void createTabFile(String id) {
        File f = workspace.fileFor(id);
        if (f.exists()) {
            open(Dialogs.message("Создание вкладки", "Файл уже есть: " + f.getName()));
            return;
        }
        confirmDiscard(Backups.Reason.BEFORE_OPEN, () -> {
            try {
                QuestTab tab = QuestTab.empty(id);
                TabIO.save(f, tab);
                model.setTab(tab, f, false);
                project.refresh();
                project.setCurrentFile(f);
                map.fitToContent();
                if (!wide) section = S_MAP;
                status("Создана вкладка: " + f.getName());
            } catch (Exception e) {
                error("Не удалось создать вкладку", e);
            }
        }, null);
    }

    private void save() {
        if (model.file() == null || !Config.get().reviewBeforeSave) {
            saveNow();
            return;
        }
        TabDiff diff;
        try {
            diff = changes();
        } catch (Exception e) {
            saveNow();
            return;
        }
        if (diff.isEmpty()) {
            saveNow();
            return;
        }
        open(new ChangesDialog(model.file().getName(), diff, this::saveNow, null));
    }

    private TabDiff changes() throws Exception {
        File f = model.file();
        QuestTab old = f != null && f.isFile() ? TabIO.load(f) : null;
        return TabDiff.compare(old, model.tab());
    }

    private void showChanges() {
        showChanges(this::saveNow, null);
    }

    private void showChanges(Runnable onSave, Runnable onClose) {
        try {
            String name = model.file() == null ? "Вкладка ещё не сохранена в файл" : model.file().getName();
            open(new ChangesDialog(name, changes(), model.isDirty() ? onSave : null, onClose));
        } catch (Exception e) {
            error("Не удалось прочитать файл вкладки", e);
            if (onClose != null) onClose.run();
        }
    }

    private void saveNow() {
        if (model.file() == null) {
            saveAs();
            return;
        }
        try {
            if (Config.get().backupOnSave) backup(Backups.Reason.SAVE);
            TabIO.save(model.file(), model.tab());
            model.markSaved(model.file());
            dropDraft();
            project.refresh();
            status("Сохранено: " + model.file().getName() + "   •   " + Backups.summary(model.file()));
        } catch (Exception e) {
            error("Не удалось сохранить", e);
        }
    }

    private void saveAs() {
        File suggested = new File(startDir(), TabIO.fileName(model.tab()));
        open(FileDialogs.save("Сохранить вкладку как", suggested, ".json", f -> {
            try {
                TabIO.save(f, model.tab());
                model.markSaved(f);
                dropDraft();
                if (Config.get().backupOnSave) backup(Backups.Reason.SAVE);
                if (f.getParentFile() != null && (!workspace.isOpen()
                        || !f.getParentFile().equals(workspace.dir()))) {
                    workspace.open(f.getParentFile());
                    Config.get().lastFolder = f.getParent();
                }
                project.refresh();
                project.setCurrentFile(f);
                Config.get().lastDir = f.getParent();
                Config.get().save();
                status("Сохранено: " + f.getName());
            } catch (Exception e) {
                error("Не удалось сохранить", e);
            }
        }));
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

    // ------------------------------------------------------------------ обмен с телефоном

    /** Вкладки из Загрузок, мессенджера, облака — копией в открытую папку. */
    private void importTabs() {
        File target = workspace.isOpen() ? workspace.dir() : Platform.get().defaultFolder();
        target.mkdirs();
        Platform.get().importFiles(target, files -> {
            if (files.isEmpty()) {
                status("Ничего не выбрано");
                return;
            }
            if (!workspace.isOpen() || !target.equals(workspace.dir())) openFolderAt(target);
            else project.refresh();
            status("Импортировано вкладок: " + files.size() + " → " + target.getName());
            openTabFile(files.get(0));
        });
    }

    private void shareTab() {
        File f = fileForSharing();
        if (f != null) Platform.get().share(f, "application/json", "Вкладка " + model.tab().tabID);
    }

    private void exportTab() {
        File f = fileForSharing();
        if (f != null) Platform.get().exportFile(f, "application/json");
    }

    /**
     * Файл с тем, что сейчас в редакторе: несохранённое уходит в «Поделиться»
     * таким, каким его видно, — через временную копию, а сам файл не трогается.
     */
    private File fileForSharing() {
        try {
            if (model.file() != null && !model.isDirty()) return model.file();
            File dir = new File(Config.programDir(), "share");
            dir.mkdirs();
            File f = new File(dir, TabIO.fileName(model.tab()));
            TabIO.save(f, model.tab());
            return f;
        } catch (Exception e) {
            error("Не удалось подготовить файл", e);
            return null;
        }
    }

    /** Примеры из APK — в подпапку «Примеры» папки по умолчанию. */
    private void openExamples() {
        File dir = new File(Platform.get().defaultFolder(), "Примеры");
        dir.mkdirs();
        int copied = 0;
        for (String name : new String[]{"Начало.json", "Магия.json", "Техника.json", "Огромная.json"}) {
            File out = new File(dir, name);
            if (out.isFile()) continue;
            try (InputStream in = Platform.get().asset("examples/" + name)) {
                if (in == null) continue;
                try (OutputStream os = new FileOutputStream(out)) {
                    byte[] buf = new byte[65536];
                    int n;
                    while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
                }
                copied++;
            } catch (IOException e) {
                status("Не удалось скопировать " + name + ": " + e.getMessage());
            }
        }
        openFolderAt(dir);
        if (copied > 0) status("Примеры скопированы в " + dir.getAbsolutePath());
    }

    // ------------------------------------------------------------------ картинка вкладки

    private File posterFile;
    private float posterScale;
    private boolean posterGif;
    private boolean posterWait;

    private void savePoster(boolean gif) {
        if (model.tab().quests.isEmpty()) {
            status("Во вкладке нет квестов — рисовать нечего");
            return;
        }
        GamePreview gp = posterPreview();
        float[] scales = gif ? new float[]{1f, 2f} : new float[]{2f, 3f, 4f};
        StringBuilder text = new StringBuilder(gif
                ? "Вся карта вкладки анимацией, как в игре, без рамки окна: "
                  + TabPoster.GIF_FRAMES + " кадров по кругу, 3,2 с.\n"
                  + "В GIF 256 цветов и не больше " + TabPoster.GIF_MAX_SIDE + " точек по стороне.\n\n"
                : "Вся карта вкладки одной картинкой, как в игре, без рамки окна.\n\n");
        String[] buttons = new String[scales.length + 1];
        buttons[0] = "Отмена";
        float[] b = gp.posterBounds();
        for (int i = 0; i < scales.length; i++) {
            float s = gif ? TabPoster.fitGifScale(gp, scales[i]) : TabPoster.fitScale(gp, scales[i]);
            int w = Math.round((b[2] - b[0]) * s), h = Math.round((b[3] - b[1]) * s);
            buttons[i + 1] = "×" + (int) scales[i];
            text.append("×").append((int) scales[i]).append(" — ").append(w).append(" × ").append(h);
            if (s != scales[i]) text.append(String.format(" (урезано до ×%.2f)", s));
            text.append('\n');
        }
        String ext = gif ? ".gif" : ".png";
        open(Dialogs.confirm(gif ? "Анимация вкладки" : "Картинка вкладки", text.toString(), buttons, answer -> {
            if (answer < 1) return;
            File dir = model.file() != null ? model.file().getParentFile() : startDir();
            File suggested = new File(dir == null ? Platform.get().defaultFolder() : dir,
                    (model.tab().tabID == null ? "вкладка" : model.tab().tabID) + ext);
            float scale = scales[answer - 1];
            open(FileDialogs.save(gif ? "Сохранить анимацию вкладки" : "Сохранить картинку вкладки",
                    suggested, ext, f -> {
                        posterFile = f;
                        posterScale = scale;
                        posterGif = gif;
                        posterWait = true;
                        status(gif ? "Рисую анимацию вкладки…" : "Рисую картинку вкладки…");
                    }));
        }));
    }

    private GamePreview posterPreview() {
        GamePreview gp = new GamePreview();
        gp.setSingleTab(model.tab());
        gp.setMode(preview.preview().getMode());
        return gp;
    }

    private void renderPendingPoster() {
        if (posterFile == null) return;
        if (posterWait) {
            posterWait = false;
            return;
        }
        File f = posterFile;
        posterFile = null;
        boolean gif = posterGif;
        long start = System.nanoTime();
        try {
            TabPoster.Result r = gif
                    ? TabPoster.renderGif(posterPreview(), batch, f, posterScale)
                    : TabPoster.render(posterPreview(), batch, f, posterScale);
            String done = String.format((gif ? "Анимация" : "Картинка") + " сохранена: %s, %d × %d за %.1f с",
                    f.getName(), r.width(), r.height(), (System.nanoTime() - start) / 1e9);
            status(done);
            open(Dialogs.confirm(gif ? "Анимация готова" : "Картинка готова", done + "\n" + f.getAbsolutePath(),
                    new String[]{"Готово", "Поделиться"}, answer -> {
                        if (answer == 1) Platform.get().share(f, gif ? "image/gif" : "image/png", f.getName());
                    }));
        } catch (OutOfMemoryError e) {
            status("Не хватило памяти на картинку — попробуй качество пониже");
        } catch (Exception e) {
            error("Не удалось сохранить картинку", e);
        }
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

    /** Новый квест — в середину того, что сейчас видно на карте, а не в начало координат. */
    /**
     * Новый квест — справа от выбранного (так удобно наращивать ветку), а без
     * выбора — в начало координат; карта сразу показывает его.
     */
    private void addQuestInView() {
        float x = 0, y = 0;
        Quest sel = model.selectedQuest();
        if (sel != null) {
            x = Math.round(sel.displayX) + 2;
            y = Math.round(sel.displayY);
        }
        addQuest(x, y);
        String id = model.single();
        if (id != null) map.centerOn(id);
        if (!wide && section != S_MAP) section = S_MAP;
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
        questClipboardText = JsonWriter.write(holder, true);
        window.setClipboard(questClipboardText);
        status("Скопировано квестов: " + questClipboard.size());
    }

    private void pasteQuests(float atX, float atY) {
        String text = window.clipboard();
        List<Quest> source = new ArrayList<>();
        if (text != null && text.equals(questClipboardText)) source.addAll(questClipboard);
        if (source.isEmpty()) {
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
            status("Версия восстановлена — не забудь сохранить");
        }, this::open));
    }

    private void settings() {
        open(SettingsDialog.open(() -> {
            ModAssets.get().reopen();
            McFont.get().reload();
            status("Настройки сохранены");
            checkAssets();
        }));
    }

    private void checkAssets() {
        List<String> problems = new ArrayList<>(ModAssets.get().problems());
        if (!McFont.get().isReady()) problems.add("Шрифт не загружен: " + McFont.get().error());
        if (ItemRegistry.get().size() == 0)
            problems.add("Не загружен список предметов: " + ItemRegistry.get().loadError());
        if (problems.isEmpty()) return;
        status("Проблемы с ресурсами: " + String.join("; ", problems));
    }

    private void showHelp() {
        showText("Жесты и кнопки", String.join("\n",
                "Карта (раздел «Карта» или страница «Редактор»):",
                "  Касание узла           — выбрать квест",
                "  Касание пустого места  — снять выделение",
                "  Тяга узла              — перенести (с привязкой к сетке)",
                "  Тяга по пустому        — рамка выделения",
                "  Двойное касание узла   — открыть квест в просмотре",
                "  Долгое нажатие         — меню: добавить / копировать / вставить / удалить",
                "  Два пальца             — сдвиг карты и зум разведением",
                "",
                "Кнопки над картой:",
                "  «Связь»  — залипший Shift: тяни от квеста-родителя к квесту — появится",
                "             зависимость; долгое нажатие на линии — удалить её",
                "  «Набор»  — залипший Ctrl: касание добавляет квест к выделению",
                "  «Вид ▾»  — сетка, привязка, имена, связи, поворот, раскладка",
                "  ⛶        — карта во весь экран («Назад» — вернуться)",
                "",
                "Просмотр (как в игре):",
                "  Касание узла           — панель квеста, касание мимо — закрыть",
                "  Тяга                   — панорама",
                "  Два пальца вверх-вниз  — прокрутка панели и списка вкладок",
                "  Разведение пальцев     — зум карты",
                "",
                "Панели и списки:",
                "  Тяга пальцем           — прокрутка",
                "  Задержать палец и вести — тяга (разделители, выделение текста)",
                "  Долгое нажатие         — как правая кнопка мыши",
                "",
                "Поля ввода: над клавиатурой есть строка «Всё, Копировать, Вставить,",
                "Отменить…» — то же, что Ctrl+A/C/V/Z на компьютере.",
                "",
                "С внешней клавиатурой работают и сочетания компьютера:",
                "  Ctrl+N / O / S — новая / открыть / сохранить; Ctrl+Z / Y — отмена / повтор;",
                "  Ctrl+C / V / D / A — копировать / вставить / дублировать / выделить всё;",
                "  Ctrl+T — проверить вкладку, Ctrl+H — история версий."));
    }

    private void showAbout() {
        showText("О программе", String.join("\n",
                "DwQuests Editor для Android — редактор вкладок квестов мода DwQuests",
                "(Minecraft 1.7.10).",
                "",
                "Тот же редактор, что на компьютере: карта, просмотр как в игре со шрифтом,",
                "звёздами-нодами и волнистыми связями, панель квеста, задачи, награды,",
                "гифты и пакеты, история версий, проверка вкладки и сверка id.",
                "Иконки предметов пака и текстуры мода встроены в приложение.",
                "",
                "Моста с игрой здесь нет: вкладку в игру переносят через буфер",
                "(«Копировать для /dwq tab paste») или файлом.",
                "",
                Platform.get().describe()));
    }

    // ------------------------------------------------------------------ диалоги и состояние

    public void open(Dialog dialog) {
        if (dialog != null) dialogs.push(dialog);
    }

    void showText(String title, String text) {
        open(Dialogs.text(title, text, () -> window.setClipboard(text)));
    }

    void error(String message, Exception e) {
        open(Dialogs.message("Ошибка", message + ":\n" + e.getMessage()));
        status(message + ": " + e.getMessage());
    }

    void status(String text) {
        status = text;
    }

    private void updateTitle() {
        window.setTitle(model.title());
    }

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
                new String[]{"Отмена", "Изменения", "Не сохранять", "Сохранить"}, answer -> {
                    if (answer == 3) {
                        saveNow();
                        if (!model.isDirty()) next.run();
                        else if (onCancel != null) onCancel.run();
                    } else if (answer == 1) {
                        showChanges(() -> {
                            saveNow();
                            if (!model.isDirty()) next.run();
                            else if (onCancel != null) onCancel.run();
                        }, () -> confirmDiscard(reason, next, onCancel));
                    } else if (answer == 2) {
                        backup(reason);
                        dropDraft();
                        next.run();
                    } else if (onCancel != null) {
                        onCancel.run();
                    }
                }));
    }

    private void exit() {
        if (dialogs.isEmpty()) confirmDiscard(Backups.Reason.BEFORE_OPEN, window::close, null);
    }

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

    // ------------------------------------------------------------------ черновик

    /**
     * Android выгружает приложение из памяти, когда захочет. Поэтому при уходе в
     * фон несохранённое пишется черновиком: вкладка и путь её файла. Сохранили
     * или отказались от правок — черновик удаляется.
     */
    public void onPause() {
        if (!model.isDirty()) {
            dropDraft();
            return;
        }
        File dir = draftDir();
        dir.mkdirs();
        try {
            Files.write(new File(dir, "tab.json").toPath(),
                    model.tab().toJsonString().getBytes(StandardCharsets.UTF_8));
            String src = model.file() == null ? "" : model.file().getAbsolutePath();
            Files.write(new File(dir, "file.txt").toPath(), src.getBytes(StandardCharsets.UTF_8));
        } catch (IOException ignored) {
            // нечего делать: версия в истории всё равно есть, если включены автокопии
        }
    }

    private void restoreDraft() {
        File dir = draftDir();
        File tabFile = new File(dir, "tab.json");
        if (!tabFile.isFile()) return;
        try {
            String text = new String(Files.readAllBytes(tabFile.toPath()), StandardCharsets.UTF_8);
            File fileTxt = new File(dir, "file.txt");
            String src = fileTxt.isFile()
                    ? new String(Files.readAllBytes(fileTxt.toPath()), StandardCharsets.UTF_8).trim() : "";
            File f = src.isEmpty() ? null : new File(src);
            model.setTab(QuestTab.parse(text), f, true);
            if (f != null) project.setCurrentFile(f);
            selectFirstQuest();
            map.fitToContent();
            status("Восстановлены несохранённые правки" + (f == null ? "" : " вкладки " + f.getName()));
        } catch (Exception e) {
            status("Черновик не читается: " + e.getMessage());
        }
    }

    private void dropDraft() {
        File dir = draftDir();
        new File(dir, "tab.json").delete();
        new File(dir, "file.txt").delete();
    }

    private static File draftDir() { return new File(Config.programDir(), "draft"); }

    // ------------------------------------------------------------------ поле редактора

    public MapView map() { return map; }

    /** Переключить раздел из кода — для проверок вида. */
    public void selectSection(int index) {
        section = Math.max(0, Math.min(SECTIONS.length - 1, index));
        page = section == S_PREVIEW ? 1 : section == S_JSON ? 2 : 0;
        pageChanged(section == S_PREVIEW);
    }

    /** Открыть файл из кода — проверки вида. */
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

    private final class MapCallbacks implements MapView.Callbacks {
        @Override
        public void questDoubleClicked(String questID) {
            model.select(questID);
            if (wide) page = 1;
            else section = S_PREVIEW;
            lastPage = 0;
            pageChanged(true);
            preview.preview().openQuest(questID);
        }

        @Override public void requestAddQuest(float x, float y) { addQuest(x, y); }

        @Override public void requestCopy() { copyQuests(); }

        @Override public void requestPaste(float x, float y) { pasteQuests(x, y); }

        @Override public void requestDelete() { deleteSelected(); }

        @Override public void questInGame(String questID, MapView.GameAction action) { }
    }

    private void onModelChanged(String what) {
        updateTitle();
        if ("tab".equals(what) && previewShown) feedPreviewTabs();
        json.reset();
    }
}
