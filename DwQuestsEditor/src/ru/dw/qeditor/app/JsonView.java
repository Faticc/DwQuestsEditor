package ru.dw.qeditor.app;

import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.json.JsonWriter;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Страница «JSON»: то же самое, что правится полями, — текстом.
 *
 * <p>Быстрее всего так чинить то, чего в интерфейсе нет: неизвестное поле от
 * новой версии мода, хитрый NBT, порядок задач. Правка уходит в модель целиком,
 * поэтому рядом всегда видно, разбирается ли текст.
 *
 * <p>Вкладка целиком бывает на мегабайт: многострочное поле переносит строки
 * каждый кадр, и такой текст в него класть нельзя. Поэтому большая вкладка
 * показывается только на просмотр — правится либо квест, либо вкладка поменьше.
 */
public final class JsonView {

    /** Дальше этого размера поле ввода уже не тянет — только просмотр. */
    private static final int EDIT_LIMIT = 120_000;

    private static final String[] MODES = {"Выбранный квест", "Вся вкладка"};

    private final EditorModel model;

    private int mode;
    /** Текст в поле; null — ещё не читали из модели. */
    private String text;
    /** Что было в модели, когда текст читали, — по нему видно «изменено». */
    private String base = "";
    private String hint = " ";
    /** Разбитый на строки текст для просмотра больших вкладок. */
    private String cachedFor;
    private List<String> cachedLines = new ArrayList<>();

    public JsonView(EditorModel model) {
        this.model = model;
    }

    /** Модель изменилась — текст надо перечитать. */
    public void reset() {
        stale = true;
        hint = " ";
    }

    /** Текст не совпадает с моделью и его ещё не перечитали. */
    private boolean stale = true;

    public void draw(Ui ui, float x, float y, float w, float h) {
        // собирать JSON вкладки каждый кадр нельзя: на тысяче квестов это
        // мегабайт текста на каждое движение мыши
        boolean edited = text != null && !text.equals(base);
        if (text == null || stale && !edited) {
            text = fromModel();
            base = text;
            stale = false;
        }

        float bh = Theme.ROW;
        float bx = x;
        int newMode = Widgets.tabs(ui, bx, y, bh, MODES, mode);
        if (newMode != mode) {
            mode = newMode;
            text = null;
            hint = " ";
            ui.forgetState(this + "json");
            return;
        }

        bx = x + 320;
        boolean changed = edited;
        if (ui.button(bx, y, 100, bh, "Применить", Ui.PRIMARY, changed)) apply();
        bx += 104;
        if (ui.button(bx, y, 100, bh, "Сбросить", Ui.NORMAL, changed)) {
            text = fromModel();
            base = text;
            hint = "Текст перечитан из модели";
            ui.forgetState(this + "json");
        }
        bx += 104;
        if (ui.button(bx, y, 110, bh, "Копировать", Ui.NORMAL, true)) {
            ui.window().setClipboard(text);
            hint = "JSON скопирован в буфер";
        }
        bx += 114;
        if (ui.button(bx, y, 150, bh, "Вставить из буфера", Ui.NORMAL, true)) {
            String s = ui.window().clipboard();
            if (s == null || s.trim().isEmpty()) {
                hint = "В буфере пусто";
            } else {
                text = s;
                ui.forgetState(this + "json");
                hint = "Вставлено из буфера — нажми «Применить»";
            }
        }
        bx += 154;
        if (ui.button(bx, y, 110, bh, "Проверить", Ui.GHOST, true)) check();

        float ty = y + bh + 8;
        float th = Math.max(80, y + h - ty - 22);

        if (text.length() > EDIT_LIMIT) {
            viewOnly(ui, x, ty, w, th);
            if (" ".equals(hint))
                hint = "Вкладка на " + text.length() + " символов — только просмотр; "
                        + "правь отдельный квест или консольным режимом";
        } else {
            text = Widgets.textArea(ui, this + "json", x, ty, w, th, text, "{ }");
        }

        ui.textIn(hint, x, y + h - 20, w, 20, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
    }

    /** Просмотр без правки: рисуются только видимые строки. */
    private void viewOnly(Ui ui, float x, float y, float w, float h) {
        if (!text.equals(cachedFor)) {
            cachedFor = text;
            cachedLines = Arrays.asList(text.split("\n", -1));
        }
        float lineH = ui.font(Theme.FONT_SMALL, false).height();
        float content = cachedLines.size() * lineH + 16;
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        float[] scroll = ui.beginScroll(this + "view", x + 1, y + 1, w - 2, h - 2, w - 2, content);
        int first = Math.max(0, (int) (scroll[1] / lineH) - 1);
        int last = Math.min(cachedLines.size(), first + (int) (h / lineH) + 3);
        for (int i = first; i < last; i++)
            ui.text(cachedLines.get(i), 8, 8 + i * lineH, Theme.TEXT, Theme.FONT_SMALL, false);
        ui.endScroll(this + "view", x + 1, y + 1, w - 2, h - 2, w - 2, content);
    }

    // ------------------------------------------------------------------ модель

    private String fromModel() {
        if (mode == 1) return model.tab().toJsonString();
        Quest q = model.selectedQuest();
        if (q == null) return "";
        JsonObject o = new JsonObject();
        o.put(q.questID, q.toJson());
        return JsonWriter.write(o, true);
    }

    private void check() {
        try {
            Object parsed = JsonParser.parse(text);
            if (!(parsed instanceof JsonObject)) {
                hint = "Это не JSON-объект";
                return;
            }
            JsonObject o = (JsonObject) parsed;
            hint = mode == 1
                    ? "Разбирается: квестов " + (o.obj("quests") == null ? 0 : o.obj("quests").size())
                    : "Разбирается: ключей " + o.size();
        } catch (RuntimeException e) {
            hint = "Ошибка разбора: " + e.getMessage();
        }
    }

    private void apply() {
        try {
            Object parsed = JsonParser.parse(text);
            if (!(parsed instanceof JsonObject)) {
                hint = "Это не JSON-объект";
                return;
            }
            if (mode == 1) applyTab((JsonObject) parsed);
            else applyQuest((JsonObject) parsed);
        } catch (RuntimeException e) {
            hint = "Ошибка разбора: " + e.getMessage();
        }
    }

    private void applyTab(JsonObject o) {
        QuestTab next = QuestTab.parse(JsonWriter.write(o, true));
        model.edit("Правка JSON вкладки", () -> {
            QuestTab tab = model.tab();
            tab.tabID = next.tabID;
            tab.iconItem = next.iconItem;
            tab.sortIndex = next.sortIndex;
            tab.quests.clear();
            tab.quests.putAll(next.quests);
            tab.extra.map().clear();
            tab.extra.map().putAll(next.extra.map());
        });
        base = fromModel();
        text = base;
        hint = "Вкладка заменена, квестов: " + model.tab().quests.size();
    }

    private void applyQuest(JsonObject o) {
        String oldID = model.single();
        if (oldID == null) {
            hint = "Квест не выбран";
            return;
        }
        if (o.size() != 1) {
            hint = "Нужен объект вида { \"ИдКвеста\": { … } }";
            return;
        }
        String newID = o.keys().get(0);
        JsonObject body = o.obj(newID);
        if (body == null) {
            hint = "У квеста должно быть тело-объект";
            return;
        }
        Quest q = Quest.fromJson(newID, body);
        model.edit("Правка JSON квеста", () -> {
            QuestTab tab = model.tab();
            // переименование чинит ссылки на квест в остальных
            if (!newID.equals(oldID)) tab.renameQuest(oldID, newID);
            tab.quests.put(newID, q);
        });
        if (!newID.equals(oldID)) model.select(newID);
        base = fromModel();
        text = base;
        hint = "Квест применён";
    }
}
