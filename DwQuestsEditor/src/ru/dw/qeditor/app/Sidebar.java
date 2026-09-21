package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gui.Popup;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.model.Task;
import ru.dw.qeditor.util.ForgeName;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Правая колонка редактора: свойства вкладки и выбранного квеста, описание,
 * задачи, награды, зависимости и оповещение.
 *
 * <p>Порт пяти Swing-панелей ({@code SidebarPanel} и его страниц) в один класс:
 * поля и таблицы в режиме немедленного вывода занимают вдвое меньше кода, а
 * «тихого обновления из модели» больше нет вовсе — значение поля каждый кадр
 * приходит из модели, и в модель же уходит правка.
 */
public final class Sidebar {

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
    private static final String CODES = "0123456789abcdef";

    private static final String[] PAGES =
            {"Описание", "Задачи", "Награды", "Связи", "Оповещение"};

    private static final String[] RARITIES = ru(Defs.Rarity.values().length, i -> Defs.Rarity.values()[i].ru);
    private static final String[] ICON_SIZES = ru(Defs.IconSize.values().length, i -> Defs.IconSize.values()[i].ru);
    private static final String[] NODE_TYPES = ru(Defs.NodeType.values().length, i -> Defs.NodeType.values()[i].ru);
    private static final String[] TASKS_TYPES = ru(Defs.TasksType.values().length, i -> Defs.TasksType.values()[i].ru);
    private static final String[] TASK_TYPES = ru(Defs.TaskType.values().length, i -> Defs.TaskType.values()[i].ru);
    private static final String[] PARENT_TYPES = ru(Defs.ParentType.values().length, i -> Defs.ParentType.values()[i].ru);
    private static final String[] LINE_TYPES = ru(Defs.LineType.values().length, i -> Defs.LineType.values()[i].ru);

    private interface Naming { String at(int i); }

    private static String[] ru(int n, Naming naming) {
        String[] out = new String[n];
        for (int i = 0; i < n; i++) out[i] = naming.at(i);
        return out;
    }

    /** Награда: предмет, команда, гифт или скрипт — тип виден по самой строке. */
    public enum Kind { ITEM, COMMAND, GIFT, SCRIPT }

    private final EditorModel model;
    private final Popup popup;
    private final Consumer<Dialog> openDialog;

    private final McPreview descPreview = new McPreview();
    private final McPreview castPreview = new McPreview();

    private int page;
    /** Граница между свойствами и страницами — тянется мышью. */
    private float headH = 330;
    /** Высота свойств, посчитанная в прошлом кадре. */
    private float headContent = 420;

    private int taskRow = -1;
    private int rewardRow = -1;
    private int parentRow = -1;
    private String hint = " ";
    private static Task taskClipboard;

    public Sidebar(EditorModel model, Popup popup, Consumer<Dialog> openDialog) {
        this.model = model;
        this.popup = popup;
        this.openDialog = openDialog;
    }

    /** Переключить страницу свойств из кода — для проверок вида. */
    public void setPage(int index) { page = Math.max(0, Math.min(PAGES.length - 1, index)); }

    /** Показать сообщение в строке подсказки. */
    public void hint(String text) { hint = text == null ? " " : text; }

    // ------------------------------------------------------------------ кадр

    public void draw(Ui ui, float x, float y, float w, float h) {
        ui.rect(x, y, w, h, Theme.BG);
        ui.vSeparator(x, y, h);

        float minHead = 120;
        float maxHead = Math.max(minHead, h - 220);
        headH = Math.max(minHead, Math.min(maxHead, headH));

        drawHead(ui, x + 1, y, w - 1, headH);

        float sy = y + headH;
        headH = Widgets.splitterH(ui, this + "split", x + 1, sy, w - 1, headH + y, y + minHead, y + maxHead) - y;

        float py = sy + 6;
        page = Widgets.tabs(ui, x + 10, py, Theme.ROW, PAGES, page);
        py += Theme.ROW + 6;

        float pageH = y + h - py - 22;
        ui.pushClip(x + 1, py, w - 1, pageH);
        switch (page) {
            case 0 -> drawDescription(ui, x + 12, py, w - 24, pageH);
            case 1 -> drawTasks(ui, x + 12, py, w - 24, pageH);
            case 2 -> drawRewards(ui, x + 12, py, w - 24, pageH);
            case 3 -> drawParents(ui, x + 12, py, w - 24, pageH);
            default -> drawBroadcast(ui, x + 12, py, w - 24, pageH);
        }
        ui.popClip();

        ui.textIn(hint, x + 12, y + h - 20, w - 24, 20, Theme.TEXT_MUTED, Ui.LEFT,
                Theme.FONT_SMALL, false);
    }

    // ------------------------------------------------------------------ свойства

    private void drawHead(Ui ui, float x, float y, float w, float h) {
        Object id = this + "head";
        // высота содержимого известна только после отрисовки — берём прошлый
        // кадр: на глаз незаметно, а полосу прокрутки ставит правильно
        ui.beginScroll(id, x, y, w, h, w - Theme.SCROLLBAR, headContent);
        float cw = w - Theme.SCROLLBAR - 24;
        float cy = 12;
        cy = drawTabForm(ui, 12, cy, cw);
        cy += 14;
        cy = drawQuestForm(ui, 12, cy, cw);
        ui.endScroll(id, x, y, w, h, w - Theme.SCROLLBAR, headContent);
        headContent = cy + 12;
    }

    private float drawTabForm(Ui ui, float x, float y, float w) {
        QuestTab tab = model.tab();
        Widgets.section(ui, "ВКЛАДКА", x, y, w);
        float cy = y + 20;
        float row = Theme.ROW_LARGE;
        float labelW = 96;

        label(ui, "tabID", x, cy, labelW, row);
        String newID = ui.field(this + "tabID", x + labelW, cy, w - labelW, row, tab.tabID, null);
        if (!newID.equals(tab.tabID)) model.edit("tabID", () -> tab.tabID = newID);
        cy += row + 6;

        label(ui, "Иконка", x, cy, labelW, row);
        if (iconButton(ui, tab.iconItem, x + labelW, cy, row))
            openDialog.accept(ItemPicker.open(tab.iconItem,
                    s -> model.edit("Иконка вкладки", () -> tab.iconItem = s)));
        label(ui, "sortIndex", x + labelW + row + 16, cy, 70, row);
        int sort = ui.intField(this + "sort", x + labelW + row + 90, cy, 80, row, tab.sortIndex, -999, 999);
        if (sort != tab.sortIndex) model.edit("sortIndex вкладки", () -> tab.sortIndex = sort);
        cy += row + 6;

        int taskCount = 0, rewardCount = 0;
        for (Quest q : tab.quests.values()) {
            taskCount += q.tasks.size();
            rewardCount += q.rewards.size();
        }
        Widgets.caption(ui, "Квестов: " + tab.quests.size() + ", задач: " + taskCount
                + ", наград: " + rewardCount, x, cy);
        return cy + 16;
    }

    private float drawQuestForm(Ui ui, float x, float y, float w) {
        Quest q = model.selectedQuest();
        Widgets.section(ui, "КВЕСТ", x, y, w);
        float cy = y + 20;
        float row = Theme.ROW_LARGE;
        float labelW = 96;

        if (q == null) {
            int n = model.selection().size();
            Widgets.caption(ui, n > 1 ? "Выделено квестов: " + n + " — свойства правятся по одному"
                    : "Квест не выбран", x, cy + 6);
            return cy + 22;
        }

        label(ui, "questID", x, cy, labelW, row);
        String newID = ui.field(this + "questID", x + labelW, cy, w - labelW, row, q.questID, null);
        if (!newID.equals(q.questID)) renameQuest(q.questID, newID);
        cy += row + 6;

        label(ui, "Название", x, cy, labelW, row);
        String name = ui.field(this + "questName", x + labelW, cy, w - labelW, row,
                nz(q.displayName), q.questID);
        if (!name.equals(nz(q.displayName))) model.edit("Название квеста", () -> q.displayName = name);
        cy += row + 6;

        label(ui, "Иконка, место", x, cy, labelW, row);
        float px = x + labelW;
        if (iconButton(ui, q.iconItem, px, cy, row))
            openDialog.accept(ItemPicker.open(q.iconItem,
                    s -> model.edit("Иконка квеста", () -> q.iconItem = s)));
        px += row + 10;
        float numW = Math.max(60, (w - labelW - row - 34) / 2);
        label(ui, "X", px, cy, 12, row);
        float nx = ui.floatField(this + "questX", px + 14, cy, numW, row, q.displayX, 1);
        px += 14 + numW + 8;
        label(ui, "Y", px, cy, 12, row);
        float ny = ui.floatField(this + "questY", px + 14, cy, numW, row, q.displayY, 1);
        if (nx != q.displayX || ny != q.displayY) model.edit("Координаты квеста", () -> {
            q.displayX = nx;
            q.displayY = ny;
        });
        cy += row + 6;

        label(ui, "Редкость", x, cy, labelW, row);
        int rarity = Widgets.dropdown(ui, popup, this + "rarity", x + labelW, cy, w - labelW, row,
                RARITIES, q.rarity.ordinal());
        if (rarity != q.rarity.ordinal())
            model.edit("Редкость", () -> q.rarity = Defs.Rarity.values()[rarity]);
        cy += row + 6;

        label(ui, "Размер, узел", x, cy, labelW, row);
        float half = (w - labelW - 8) / 2;
        int size = Widgets.dropdown(ui, popup, this + "iconSize", x + labelW, cy, half, row,
                ICON_SIZES, q.iconSize.ordinal());
        if (size != q.iconSize.ordinal())
            model.edit("Размер иконки", () -> q.iconSize = Defs.IconSize.values()[size]);
        int node = Widgets.dropdown(ui, popup, this + "nodeType", x + labelW + half + 8, cy, half, row,
                NODE_TYPES, q.nodeType.ordinal());
        if (node != q.nodeType.ordinal())
            model.edit("Тип узла", () -> q.nodeType = Defs.NodeType.values()[node]);
        cy += row + 6;

        label(ui, "Режим задач", x, cy, labelW, row);
        int tasksType = Widgets.dropdown(ui, popup, this + "tasksType", x + labelW, cy, w - labelW, row,
                TASKS_TYPES, q.tasksType.ordinal());
        if (tasksType != q.tasksType.ordinal())
            model.edit("Режим задач", () -> q.tasksType = Defs.TasksType.values()[tasksType]);
        cy += row + 8;

        Widgets.caption(ui, "В игре покажется: " + q.displayNameOrID()
                + " [0/" + q.tasksCount() + "]", x, cy);
        return cy + 16;
    }

    private void renameQuest(String oldID, String newID) {
        if (newID.trim().isEmpty()) return;
        if (model.tab().quests.containsKey(newID)) {
            hint("Квест с таким id уже есть");
            return;
        }
        model.edit("Переименование квеста", () -> model.tab().renameQuest(oldID, newID));
        model.select(newID);
    }

    private void label(Ui ui, String text, float x, float y, float w, float h) {
        ui.textIn(text, x, y, w, h, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
    }

    /** Квадратная кнопка с иконкой предмета; true — нажали. */
    private boolean iconButton(Ui ui, String item, float x, float y, float size) {
        boolean hovered = ui.over(x, y, size, size);
        ui.roundRectBorder(x, y, size, size, Theme.RADIUS_SMALL,
                hovered ? Theme.HOVER : Theme.SURFACE_2,
                hovered ? Theme.BORDER_STRONG : Theme.BORDER, 1);
        Icons.item(ui, item, x + (size - 20) / 2, y + (size - 20) / 2, 20);
        if (hovered) {
            ui.cursor(GlWindow.CURSOR_HAND);
            ui.tooltip(ItemRegistry.get().displayName(item) + "\n" + item);
        }
        return hovered && ui.in().leftPressed();
    }

    // ------------------------------------------------------------------ описание

    private void drawDescription(Ui ui, float x, float y, float w, float h) {
        Quest q = model.selectedQuest();
        float cy = y;

        // ---- кнопки §-форматирования
        float bw = 34;
        float bh = 24;
        float bx = x;
        bx = fmtButton(ui, q, "Ж", "§l — жирный", "§l", bx, cy, bw, bh);
        bx = fmtButton(ui, q, "К", "§o — курсив", "§o", bx, cy, bw, bh);
        bx = fmtButton(ui, q, "П", "§n — подчёркнутый", "§n", bx, cy, bw, bh);
        bx = fmtButton(ui, q, "З", "§m — зачёркнутый", "§m", bx, cy, bw, bh);
        bx = fmtButton(ui, q, "?", "§k — обфускация", "§k", bx, cy, bw, bh);
        bx = fmtButton(ui, q, "R", "§r — сброс форматирования", "§r", bx, cy, bw, bh);
        // вторая строка: в колонке шириной 400 всё в один ряд не влезает
        bx = x;
        cy += bh + 4;
        bx = fmtButton(ui, q, "<BR>", "Новый абзац", "<BR>", bx, cy, 56, bh);
        bx = fmtButton(ui, q, "<RI>", "Отступ строки", "<RI>", bx, cy, 56, bh);
        if (ui.button(bx, cy, 44, bh, "IMG", Ui.NORMAL, q != null)) askImage(q);
        if (ui.over(bx, cy, 44, bh)) ui.tooltip("Вставить картинку из ресурсов мода");
        bx += 48;
        if (ui.button(bx, cy, 48, bh, descPreview.getScale() + "×", Ui.GHOST, true))
            descPreview.toggleScale();
        if (ui.over(bx, cy, 48, bh)) ui.tooltip("Масштаб предпросмотра");
        cy += bh + 6;

        // ---- палитра цветов
        float sw = 20;
        for (int i = 0; i < COLORS.length; i++) {
            float px = x + i * (sw + 3);
            boolean hovered = ui.over(px, cy, sw, sw);
            ui.roundRectBorder(px, cy, sw, sw, 3, 0xFF000000 | COLORS[i],
                    hovered ? Theme.TEXT : Theme.BORDER, 1);
            if (hovered) {
                ui.cursor(GlWindow.CURSOR_HAND);
                ui.tooltip("§" + CODES.charAt(i) + " — " + COLOR_NAMES[i]);
                if (ui.in().leftPressed() && q != null) insert(q, "§" + CODES.charAt(i));
            }
        }
        cy += sw + 8;

        // ---- текст и предпросмотр делят остаток
        float rest = y + h - cy - 24;
        float areaH = Math.max(80, rest * 0.45f);
        String text = Widgets.textArea(ui, this + "desc", x, cy, w, areaH,
                q == null ? "" : nz(q.description), q == null ? "выберите квест" : "<BR>Описание квеста…");
        if (q != null && !text.equals(nz(q.description)))
            model.edit("Описание квеста", () -> q.description = text);
        cy += areaH + 8;

        Widgets.caption(ui, "Как это выглядит в игре", x, cy);
        cy += 16;
        descPreview.showQuest(q);
        descPreview.draw(ui, x, cy, w, Math.max(60, y + h - cy));
    }

    private float fmtButton(Ui ui, Quest q, String text, String tip, String code,
                            float x, float y, float w, float h) {
        if (ui.button(x, y, w, h, text, Ui.NORMAL, q != null)) insert(q, code);
        if (ui.over(x, y, w, h)) ui.tooltip(tip);
        return x + w + 4;
    }

    /**
     * Вставить код в описание. Курсор в поле знает только сам тулкит, поэтому
     * код дописывается в конец — как это делал и Swing-редактор, когда поле было
     * не в фокусе.
     */
    private void insert(Quest q, String code) {
        if (q == null) return;
        String text = nz(q.description) + code;
        model.edit("Описание квеста", () -> q.description = text);
        forget = "desc";
    }

    /** Какое многострочное поле должно перечитать модель после кадра. */
    private String forget;

    private void askImage(Quest q) {
        openDialog.accept(Dialogs.prompt("Картинка в описании",
                "Формат: mod:textures/путь.png:u:v:ширина:высота[:масштаб]",
                "dwquests:textures/gui/backgrounds/Botania.png:0:0:256:128:0.5",
                s -> {
                    if (s == null || s.trim().isEmpty()) return;
                    insert(q, "<IMG>" + s.trim() + "</IMG>");
                }));
    }

    // ------------------------------------------------------------------ задачи

    private void drawTasks(Ui ui, float x, float y, float w, float h) {
        Quest q = model.selectedQuest();
        List<Integer> ids = q == null ? new ArrayList<>() : new ArrayList<>(q.tasks.keySet());
        if (taskRow >= ids.size()) taskRow = ids.size() - 1;

        // кнопок восемь, колонка узкая — вторая строка неизбежна
        float bh = 24;
        float bx = x;
        if (ui.button(bx, y, 78, bh, "Добавить", Ui.PRIMARY, q != null)) addTask(q);
        bx += 82;
        if (ui.button(bx, y, 70, bh, "Удалить", Ui.DANGER, taskRow >= 0)) removeTask(q, ids);
        bx += 74;
        if (ui.button(bx, y, 26, bh, "↑", Ui.NORMAL, taskRow > 0)) moveTask(q, taskRow, taskRow - 1);
        bx += 30;
        if (ui.button(bx, y, 26, bh, "↓", Ui.NORMAL, taskRow >= 0 && taskRow < ids.size() - 1))
            moveTask(q, taskRow, taskRow + 1);
        bx += 30;
        if (ui.button(bx, y, 32, bh, "№", Ui.GHOST, q != null)) renumberTasks(q);
        if (ui.over(bx, y, 32, bh)) ui.tooltip("Перенумеровать задачи подряд");

        float by = y + bh + 4;
        bx = x;
        if (ui.button(bx, by, 88, bh, "Предмет…", Ui.NORMAL, taskRow >= 0)) pickTaskItem(q, ids);
        bx += 92;
        if (ui.button(bx, by, 88, bh, "Копировать", Ui.NORMAL, taskRow >= 0)) copyTask(q, ids);
        bx += 92;
        if (ui.button(bx, by, 78, bh, "Вставить", Ui.NORMAL, q != null && taskClipboard != null))
            pasteTask(q);

        float ty = by + bh + 8;
        float tableH = Math.max(60, y + h - ty);
        drawTaskTable(ui, q, ids, x, ty, w, tableH);
    }

    /**
     * Таблица задач. В Swing-версии столбцов было шесть в один ряд, но правая
     * колонка редактора шириной около 400 точек столько не вмещает, поэтому
     * задача занимает две строки: сверху номер, тип и количество, снизу
     * предмет, своё описание и «после задач».
     */
    private void drawTaskTable(Ui ui, Quest q, List<Integer> ids, float x, float y, float w, float h) {
        float line = 24;
        float rowH = line * 2 + 8;
        float headH = 20;

        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        ui.textIn("№, тип, количество · предмет, описание, после задач",
                x + 8, y, w - 16, headH, Theme.TEXT_DISABLED, Ui.LEFT, Theme.FONT_SMALL, false);
        ui.hSeparator(x + 1, y + headH, w - 2);

        Object scrollId = this + "tasks";
        float innerW = w - 2;
        float viewH = h - headH - 2;
        float content = ids.size() * rowH;
        float fieldW = innerW - Theme.SCROLLBAR - 6;

        float[] scroll = ui.beginScroll(scrollId, x + 1, y + headH + 1, innerW, viewH, innerW, content);
        for (int r = 0; r < ids.size(); r++) {
            int id = ids.get(r);
            Task t = q.tasks.get(id);
            float ry = r * rowH;
            if (ry + rowH < scroll[1] || ry > scroll[1] + viewH) continue;

            boolean hovered = ui.over(0, ry, innerW, rowH);
            if (r == taskRow) ui.rect(0, ry, innerW, rowH, Theme.ACCENT_SOFT);
            else if (hovered) ui.rect(0, ry, innerW, rowH, Theme.HOVER);
            if (hovered && ui.in().leftPressed()) taskRow = r;
            if (r > 0) ui.hSeparator(2, ry, innerW - 4);

            // ---- строка 1: номер, тип, количество
            float cy = ry + 3;
            float numW = 46;
            float countW = 62;
            int newID = ui.intField(this + "tid" + id, 3, cy, numW, line, id, -999, 9999);
            if (newID != id) retaskID(q, id, newID);

            float typeW = fieldW - numW - countW - 12;
            int type = Widgets.dropdown(ui, popup, this + "ttype" + id, numW + 9, cy, typeW, line,
                    TASK_TYPES, t.type.ordinal());
            if (type != t.type.ordinal())
                model.edit("Тип задачи", () -> t.type = Defs.TaskType.values()[type]);

            int count = ui.intField(this + "tcount" + id, numW + typeW + 15, cy, countW, line,
                    t.requiredCount, 0, 999999);
            if (count != t.requiredCount) model.edit("Количество задачи", () -> t.requiredCount = count);

            // ---- строка 2: предмет (или класс моба), описание, «после задач»
            cy = ry + line + 6;
            boolean kill = t.type.kind == Defs.TaskType.Kind.KILL;
            String subject = kill ? nz(t.className) : nz(t.forgeName);
            float parW = 54;
            float half = (fieldW - parW - 12) / 2f;

            float sx = 3;
            if (!kill && !subject.isEmpty()) {
                Icons.item(ui, subject, sx, cy + (line - 18) / 2f, 18);
                sx += 21;
            }
            String newSubject = ui.field(this + "tsub" + id, sx, cy, half - (sx - 3), line, subject,
                    kill ? "EntityZombie" : "minecraft:dirt");
            if (!newSubject.equals(subject)) model.edit("Предмет задачи", () -> {
                if (kill) t.className = newSubject;
                else t.forgeName = newSubject;
            });

            String desc = kill ? nz(t.displayName) : nz(t.customDescription);
            String newDesc = ui.field(this + "tdesc" + id, half + 9, cy, half, line, desc,
                    kill ? "как назвать моба" : "своё описание");
            if (!newDesc.equals(desc)) model.edit("Описание задачи", () -> {
                if (kill) t.displayName = newDesc;
                else t.customDescription = newDesc.isEmpty() ? null : newDesc;
            });

            String parents = join(t.parents);
            String newParents = ui.field(this + "tpar" + id, half * 2 + 15, cy, parW, line,
                    parents, "0, 1");
            if (ui.over(half * 2 + 15, cy, parW, line)) ui.tooltip("После каких задач открывается");
            if (!newParents.equals(parents)) model.edit("Зависимости задачи", () -> {
                t.parents.clear();
                for (String part : newParents.split("[,;\s]+")) {
                    if (part.trim().isEmpty()) continue;
                    try {
                        t.parents.add(Integer.parseInt(part.trim()));
                    } catch (NumberFormatException ignored) {
                        // мусор в поле просто не попадает в модель
                    }
                }
            });
        }
        ui.endScroll(scrollId, x + 1, y + headH + 1, innerW, viewH, innerW, content);

        if (taskRow >= 0 && taskRow < ids.size()) {
            Task t = q.tasks.get(ids.get(taskRow));
            hint("В игре: " + t.description(ItemLook.name(t.forgeName))
                    + (t.type.serverSupported() ? "" : "   ⚠ тип не отслеживается сервером"));
        }
    }

    private void addTask(Quest q) {
        if (q == null) return;
        model.edit("Добавление задачи", () -> {
            Task t = new Task();
            t.type = Defs.TaskType.ITEM_DETECT;
            t.forgeName = "minecraft:dirt";
            t.requiredCount = 1;
            q.tasks.put(q.nextTaskID(), t);
        });
        taskRow = q.tasks.size() - 1;
    }

    private void removeTask(Quest q, List<Integer> ids) {
        if (q == null || taskRow < 0 || taskRow >= ids.size()) return;
        int id = ids.get(taskRow);
        model.edit("Удаление задачи", () -> {
            q.tasks.remove(id);
            for (Task t : q.tasks.values()) t.parents.removeIf(p -> p.equals(id));
        });
        taskRow = Math.min(taskRow, q.tasks.size() - 1);
    }

    private void moveTask(Quest q, int from, int to) {
        if (q == null) return;
        List<Integer> ids = new ArrayList<>(q.tasks.keySet());
        if (from < 0 || to < 0 || from >= ids.size() || to >= ids.size()) return;
        Integer moved = ids.remove(from);
        ids.add(to, moved);
        model.edit("Порядок задач", () -> {
            LinkedHashMap<Integer, Task> copy = new LinkedHashMap<>(q.tasks);
            q.tasks.clear();
            for (Integer id : ids) q.tasks.put(id, copy.get(id));
        });
        taskRow = to;
    }

    /** Сменить номер задачи, поправив ссылки на неё в остальных. */
    private void retaskID(Quest q, int id, int newID) {
        if (q == null || newID == id || q.tasks.containsKey(newID)) return;
        model.edit("Номер задачи", () -> {
            LinkedHashMap<Integer, Task> copy = new LinkedHashMap<>(q.tasks);
            q.tasks.clear();
            for (Map.Entry<Integer, Task> en : copy.entrySet())
                q.tasks.put(en.getKey() == id ? newID : en.getKey(), en.getValue());
            for (Task other : q.tasks.values()) {
                List<Integer> np = new ArrayList<>();
                for (int p : other.parents) np.add(p == id ? newID : p);
                other.parents.clear();
                other.parents.addAll(np);
            }
        });
    }

    private void pickTaskItem(Quest q, List<Integer> ids) {
        if (q == null || taskRow < 0 || taskRow >= ids.size()) return;
        Task t = q.tasks.get(ids.get(taskRow));
        if (t.type.kind != Defs.TaskType.Kind.ITEM) {
            hint("Для этого типа задачи предмет не используется");
            return;
        }
        openDialog.accept(ItemPicker.open(t.forgeName,
                s -> model.edit("Предмет задачи", () -> t.forgeName = s)));
    }

    private void copyTask(Quest q, List<Integer> ids) {
        if (q == null || taskRow < 0 || taskRow >= ids.size()) return;
        taskClipboard = q.tasks.get(ids.get(taskRow)).copy();
        hint("Задача скопирована");
    }

    private void pasteTask(Quest q) {
        if (q == null || taskClipboard == null) return;
        model.edit("Вставка задачи", () -> q.tasks.put(q.nextTaskID(), taskClipboard.copy()));
    }

    private void renumberTasks(Quest q) {
        if (q == null) return;
        List<Integer> before = new ArrayList<>(q.tasks.keySet());
        model.edit("Перенумерация задач", () -> {
            Map<Integer, Integer> remap = new LinkedHashMap<>();
            for (int i = 0; i < before.size(); i++) remap.put(before.get(i), i);
            q.renumberTasks();
            for (Task t : q.tasks.values()) {
                List<Integer> np = new ArrayList<>();
                for (int p : t.parents) {
                    Integer r = remap.get(p);
                    if (r != null) np.add(r);
                }
                t.parents.clear();
                t.parents.addAll(np);
            }
        });
    }

    // ------------------------------------------------------------------ награды

    public static Kind kindOf(String reward) {
        ForgeName fn = ForgeName.parse(reward);
        if (fn.isCommand()) return Kind.COMMAND;
        if (ru.dw.qeditor.assets.Gifts.isGift(reward)) return Kind.GIFT;
        if (fn.isScript()) return Kind.SCRIPT;
        return Kind.ITEM;
    }

    private void drawRewards(Ui ui, float x, float y, float w, float h) {
        Quest q = model.selectedQuest();
        List<String> rewards = q == null ? new ArrayList<>() : q.rewards;
        if (rewardRow >= rewards.size()) rewardRow = rewards.size() - 1;

        float bh = 24;
        float bx = x;
        float by = y;
        // кнопок много — переносим их на вторую строку
        bx = rewardButton(ui, "Предмет…", bx, by, 76, bh, q != null, () ->
                openDialog.accept(ItemPicker.open("minecraft:diamond", s -> addReward(q, s))));
        bx = rewardButton(ui, "Гифт…", bx, by, 60, bh, q != null, () ->
                openDialog.accept(GiftDialog.edit(null, s -> addReward(q, s), openDialog)));
        bx = rewardButton(ui, "Команда…", bx, by, 82, bh, q != null, () -> askCommand(q, -1));
        bx = rewardButton(ui, "Скрипт…", bx, by, 74, bh, q != null, () ->
                openDialog.accept(NbtItemDialog.editScript("customnpcs:scripted_item",
                        s -> addReward(q, s), openDialog)));
        rewardButton(ui, "NBT…", bx, by, 54, bh, rewardRow >= 0, () ->
                openDialog.accept(NbtItemDialog.edit(rewards.get(rewardRow),
                        s -> setReward(q, rewardRow, s), openDialog)));

        by += bh + 4;
        bx = x;
        bx = rewardButton(ui, "Изменить…", bx, by, 84, bh, rewardRow >= 0, () -> editReward(q, rewardRow));
        bx = rewardButton(ui, "Кол-во…", bx, by, 72, bh, rewardRow >= 0, () -> askCount(q, rewardRow));
        bx = rewardButton(ui, "Удалить", bx, by, 68, bh, rewardRow >= 0, () -> {
            int i = rewardRow;
            model.edit("Удаление награды", () -> q.rewards.remove(i));
            rewardRow = Math.min(i, q.rewards.size() - 1);
        });
        bx = rewardButton(ui, "↑", bx, by, 26, bh, rewardRow > 0, () -> moveReward(q, -1));
        rewardButton(ui, "↓", bx, by, 26, bh, rewardRow >= 0 && rewardRow < rewards.size() - 1,
                () -> moveReward(q, 1));

        float ly = by + bh + 8;
        drawRewardList(ui, q, rewards, x, ly, w, Math.max(60, y + h - ly));
    }

    private float rewardButton(Ui ui, String label, float x, float y, float w, float h,
                               boolean enabled, Runnable action) {
        if (ui.button(x, y, w, h, label, Ui.NORMAL, enabled)) action.run();
        return x + w + 4;
    }

    private void drawRewardList(Ui ui, Quest q, List<String> rewards, float x, float y, float w, float h) {
        float rowH = 26;
        Object id = this + "rewards";
        float content = rewards.size() * rowH;
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        if (rewards.isEmpty()) {
            ui.textIn(q == null ? "квест не выбран" : "наград нет",
                    x, y, w, h, Theme.TEXT_DISABLED, Ui.CENTER);
            return;
        }
        float innerW = w - 2;
        ui.beginScroll(id, x + 1, y + 1, innerW, h - 2, innerW, content);
        for (int i = 0; i < rewards.size(); i++) {
            String s = rewards.get(i);
            float ry = i * rowH;
            boolean hovered = ui.over(0, ry, innerW, rowH);
            if (i == rewardRow) ui.rect(0, ry, innerW, rowH, Theme.ACCENT_SOFT);
            else if (hovered) ui.rect(0, ry, innerW, rowH, Theme.HOVER);

            Kind kind = kindOf(s);
            String text;
            if (kind == Kind.COMMAND) {
                Icons.item(ui, "minecraft:command_block", 5, ry + 3, 20);
                text = "Команда: " + s.substring("command:".length());
            } else {
                Icons.item(ui, s, 5, ry + 3, 20);
                String tag = kind == Kind.GIFT ? " [гифт]" : kind == Kind.SCRIPT ? " [скрипт]" : "";
                text = ItemLook.name(s) + " × " + ItemLook.count(s) + tag;
            }
            ui.textIn(text, 30, ry, innerW - 40, rowH,
                    i == rewardRow ? Theme.TEXT : Theme.TEXT_MUTED, Ui.LEFT);

            if (hovered) {
                ui.cursor(GlWindow.CURSOR_HAND);
                ui.tooltip(s);
                if (ui.in().leftPressed()) rewardRow = i;
                if (ui.in().doubleClick()) {
                    rewardRow = i;
                    editReward(q, i);
                }
            }
        }
        ui.endScroll(id, x + 1, y + 1, innerW, h - 2, innerW, content);
    }

    private void addReward(Quest q, String s) {
        if (q == null || s == null) return;
        model.edit("Добавление награды", () -> q.rewards.add(s));
        rewardRow = q.rewards.size() - 1;
    }

    private void setReward(Quest q, int i, String s) {
        if (q == null || s == null || i < 0 || i >= q.rewards.size()) return;
        model.edit("Изменение награды", () -> q.rewards.set(i, s));
    }

    private void editReward(Quest q, int i) {
        if (q == null || i < 0 || i >= q.rewards.size()) return;
        String current = q.rewards.get(i);
        switch (kindOf(current)) {
            case GIFT -> openDialog.accept(GiftDialog.edit(current, s -> setReward(q, i, s), openDialog));
            case COMMAND -> askCommand(q, i);
            case SCRIPT -> openDialog.accept(
                    NbtItemDialog.editScript(current, s -> setReward(q, i, s), openDialog));
            default -> {
                if (ForgeName.parse(current).nbt != null)
                    openDialog.accept(NbtItemDialog.edit(current, s -> setReward(q, i, s), openDialog));
                else
                    openDialog.accept(ItemPicker.open(current, s -> setReward(q, i, s)));
            }
        }
    }

    /** i < 0 — добавить новую команду, иначе изменить существующую. */
    private void askCommand(Quest q, int i) {
        if (q == null) return;
        String initial = i < 0 ? "give @p minecraft:diamond 1"
                : q.rewards.get(i).substring("command:".length());
        openDialog.accept(Dialogs.prompt("Команда-награда",
                "Команда без ведущего «/», подставляется @p", initial, s -> {
                    if (s == null || s.trim().isEmpty()) return;
                    String value = "command:" + s.trim();
                    if (i < 0) addReward(q, value);
                    else setReward(q, i, value);
                }));
    }

    private void askCount(Quest q, int i) {
        if (q == null || i < 0 || i >= q.rewards.size()) return;
        ForgeName fn = ForgeName.parse(q.rewards.get(i));
        if (fn.isCommand()) {
            hint("У команды количества нет");
            return;
        }
        openDialog.accept(Dialogs.prompt("Количество", "Сколько выдавать",
                String.valueOf(fn.hasCount ? fn.count : 1), s -> {
                    int n;
                    try {
                        n = Integer.parseInt(s.trim());
                    } catch (NumberFormatException e) {
                        return;
                    }
                    setReward(q, i, fn.withCount(Math.max(1, n)));
                }));
    }

    private void moveReward(Quest q, int delta) {
        if (q == null || rewardRow < 0) return;
        int to = rewardRow + delta;
        if (to < 0 || to >= q.rewards.size()) return;
        int from = rewardRow;
        model.edit("Порядок наград", () -> {
            String s = q.rewards.remove(from);
            q.rewards.add(to, s);
        });
        rewardRow = to;
    }

    // ------------------------------------------------------------------ связи

    private void drawParents(Ui ui, float x, float y, float w, float h) {
        Quest q = model.selectedQuest();
        List<Parent> parents = q == null ? new ArrayList<>() : q.parents;
        if (parentRow >= parents.size()) parentRow = parents.size() - 1;

        float bh = 24;
        if (ui.button(x, y, 84, bh, "Добавить", Ui.PRIMARY, q != null)) addParent(q);
        if (ui.button(x + 88, y, 76, bh, "Удалить", Ui.DANGER, parentRow >= 0)) {
            int i = parentRow;
            model.edit("Удаление зависимости", () -> q.parents.remove(i));
            parentRow = Math.min(i, q.parents.size() - 1);
        }

        float ty = y + bh + 8;
        float tableH = Math.max(60, y + h - ty);
        float rowH = 28;
        float headH = 22;
        float[] cols = {0, 130, 130};
        cols[0] = Math.max(120, w - 260 - Theme.SCROLLBAR - 8);

        ui.roundRectBorder(x, ty, w, tableH, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        String[] titles = {"Родитель", "Тип", "Линия"};
        float hx = x + 1;
        for (int c = 0; c < cols.length; c++) {
            ui.textIn(titles[c], hx + 4, ty + 1, cols[c] - 6, headH, Theme.TEXT_DISABLED, Ui.LEFT,
                    Theme.FONT_SMALL, false);
            hx += cols[c];
        }
        ui.hSeparator(x + 1, ty + headH, w - 2);

        String[] candidates = candidates(q);
        Object scrollId = this + "parents";
        float innerW = w - 2;
        float viewH = tableH - headH - 2;
        float content = parents.size() * rowH;
        ui.beginScroll(scrollId, x + 1, ty + headH + 1, innerW, viewH, innerW, content);
        for (int r = 0; r < parents.size(); r++) {
            Parent p = parents.get(r);
            float ry = r * rowH;
            boolean hovered = ui.over(0, ry, innerW, rowH);
            if (r == parentRow) ui.rect(0, ry, innerW, rowH, Theme.ACCENT_SOFT);
            else if (hovered) ui.rect(0, ry, innerW, rowH, Theme.HOVER);
            if (hovered && ui.in().leftPressed()) parentRow = r;

            float cx = 2;
            int cur = indexOf(candidates, p.questID);
            int picked = Widgets.dropdown(ui, popup, this + "pq" + r, cx, ry + 3, cols[0] - 4, rowH - 6,
                    candidates, cur);
            if (picked != cur && picked >= 0 && picked < candidates.length) {
                String value = candidates[picked];
                model.edit("Изменение зависимости", () -> p.questID = value);
            }
            cx += cols[0];

            int type = Widgets.dropdown(ui, popup, this + "pt" + r, cx, ry + 3, cols[1] - 4, rowH - 6,
                    PARENT_TYPES, p.parentType.ordinal());
            if (type != p.parentType.ordinal()) model.edit("Тип зависимости", () -> {
                p.parentType = Defs.ParentType.values()[type];
                if (p.parentType != Defs.ParentType.DEFAULT) p.plainString = false;
            });
            cx += cols[1];

            int line = Widgets.dropdown(ui, popup, this + "pl" + r, cx, ry + 3, cols[2] - 4, rowH - 6,
                    LINE_TYPES, p.lineType.ordinal());
            if (line != p.lineType.ordinal()) model.edit("Тип линии", () -> {
                p.lineType = Defs.LineType.values()[line];
                if (p.lineType != Defs.LineType.DEFAULT) p.plainString = false;
            });
        }
        ui.endScroll(scrollId, x + 1, ty + headH + 1, innerW, viewH, innerW, content);
    }

    private void addParent(Quest q) {
        if (q == null) return;
        String[] cand = candidates(q);
        if (cand.length == 0) {
            hint("В вкладке нет других квестов");
            return;
        }
        model.edit("Добавление зависимости", () -> q.parents.add(new Parent(cand[0])));
        parentRow = q.parents.size() - 1;
    }

    private String[] candidates(Quest self) {
        List<String> out = new ArrayList<>();
        if (self == null) return new String[0];
        for (String id : model.tab().quests.keySet())
            if (!id.equals(self.questID)) out.add(id);
        // родитель может указывать на квест другой вкладки — его тоже надо показать
        for (Parent p : self.parents)
            if (p.questID != null && !out.contains(p.questID)) out.add(p.questID);
        return out.toArray(new String[0]);
    }

    private static int indexOf(String[] arr, String value) {
        for (int i = 0; i < arr.length; i++) if (arr[i].equals(value)) return i;
        return -1;
    }

    // ------------------------------------------------------------------ оповещение

    private void drawBroadcast(Ui ui, float x, float y, float w, float h) {
        Quest q = model.selectedQuest();
        float bh = 24;
        float bx = x;
        bx = castButton(ui, q, "%nick%", "Ник игрока", "%nick%", bx, y, 62, bh);
        bx = castButton(ui, q, "%player%", "Ник игрока (синоним)", "%player%", bx, y, 70, bh);
        bx = castButton(ui, q, "%quest%", "Название квеста", "%quest%", bx, y, 66, bh);
        bx = castButton(ui, q, "%tab%", "Название вкладки", "%tab%", bx, y, 56, bh);
        bx = castButton(ui, q, "Ж", "&l — жирный", "&l", bx, y, 28, bh);
        bx = castButton(ui, q, "R", "&r — сброс", "&r", bx, y, 28, bh);
        if (ui.button(bx, y, 74, bh, "Шаблон", Ui.NORMAL, q != null)) setBroadcast(q,
                "&6[Квесты] &eИгрок &b%nick% &eвыполнил квест &a%quest% &eво вкладке &a%tab%&e!");
        float cy = y + bh + 6;

        float sw = 18;
        for (int i = 0; i < COLORS.length; i++) {
            float px = x + i * (sw + 3);
            boolean hovered = ui.over(px, cy, sw, sw);
            ui.roundRectBorder(px, cy, sw, sw, 3, 0xFF000000 | COLORS[i],
                    hovered ? Theme.TEXT : Theme.BORDER, 1);
            if (hovered) {
                ui.cursor(GlWindow.CURSOR_HAND);
                ui.tooltip("&" + CODES.charAt(i) + " — " + COLOR_NAMES[i]);
                if (ui.in().leftPressed() && q != null) appendBroadcast(q, "&" + CODES.charAt(i));
            }
        }
        cy += sw + 8;

        Widgets.caption(ui, "Текст оповещения", x, cy);
        cy += 16;
        float areaH = 76;
        String current = q == null || q.broadcast == null ? "" : q.broadcast;
        String text = Widgets.textArea(ui, this + "cast", x, cy, w, areaH, current,
                q == null ? "выберите квест" : "&eИгрок &b%nick% &eвыполнил &a%quest%");
        if (q != null && !text.equals(current)) {
            String value = text.isEmpty() ? null : text;
            model.edit("Оповещение квеста", () -> q.broadcast = value);
        }
        cy += areaH + 10;

        Widgets.caption(ui, "Так увидят в чате", x, cy);
        cy += 16;
        castPreview.setLabelName("Так увидят в чате", 0xFFFFAA00);
        castPreview.setText(resolved(q, current));
        castPreview.draw(ui, x, cy, w, Math.max(50, y + h - cy - 18));

        hint(current.isEmpty()
                ? "Отправляется всем игрокам один раз, когда квест становится выполненным."
                : "Ник в примере — Steve. Мод сам переводит &-коды в §.");
    }

    private float castButton(Ui ui, Quest q, String label, String tip, String code,
                             float x, float y, float w, float h) {
        if (ui.button(x, y, w, h, label, Ui.NORMAL, q != null)) appendBroadcast(q, code);
        if (ui.over(x, y, w, h)) ui.tooltip(tip);
        return x + w + 4;
    }

    private void appendBroadcast(Quest q, String code) {
        if (q == null) return;
        setBroadcast(q, (q.broadcast == null ? "" : q.broadcast) + code);
    }

    private void setBroadcast(Quest q, String value) {
        if (q == null) return;
        String v = value == null || value.isEmpty() ? null : value;
        model.edit("Оповещение квеста", () -> q.broadcast = v);
        forget = "cast";
    }

    /** Показать так, как это увидит игрок: плейсхолдеры подставлены, & → §. */
    private String resolved(Quest q, String text) {
        if (text == null || text.isEmpty()) return "§7(оповещения нет — квест выполняется молча)";
        return text
                .replace("%nick%", "Steve")
                .replace("%player%", "Steve")
                .replace("%quest%", q == null ? "Квест" : q.displayNameOrID())
                .replace("%tab%", model.tab().tabID == null ? "Вкладка" : model.tab().tabID)
                .replaceAll("&([0-9a-fk-orA-FK-OR])", "§$1");
    }

    // ------------------------------------------------------------------ прочее

    /**
     * Многострочное поле держит свой текст, пока в фокусе, поэтому вставка
     * кода кнопкой должна заставить его перечитать модель.
     */
    public void afterFrame(Ui ui) {
        if (forget == null) return;
        ui.forgetState(this + forget);
        ui.forgetState(this + forget + " scroll");
        ui.clearFocus();
        forget = null;
    }

    private static String nz(String s) { return s == null ? "" : s; }

    private static String join(List<Integer> list) {
        StringBuilder sb = new StringBuilder();
        for (int v : list) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(v);
        }
        return sb.toString();
    }
}
