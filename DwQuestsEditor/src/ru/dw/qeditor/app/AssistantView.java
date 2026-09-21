package ru.dw.qeditor.app;

import ru.dw.qeditor.ai.ClaudeClient;
import ru.dw.qeditor.ai.QuestPrompt;
import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.gui.Popup;
import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.gui.Widgets;
import ru.dw.qeditor.io.Backups;
import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.io.File;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Помощник на базе Claude: просишь словами — получаешь изменённую вкладку.
 *
 * <p>Работает через локальный Claude Code CLI, поэтому отдельный ключ API не
 * нужен. Ответ не применяется сразу: сначала показывается, что изменится.
 *
 * <p>Вместо {@code SwingWorker} — обычный поток: результат он кладёт в поле,
 * а кадр его подхватывает. Синхронизация не нужна — ссылку пишет один поток,
 * читает другой, и запись ссылки атомарна.
 */
public final class AssistantView {

    private static final String[] MODELS = {"sonnet", "opus", "haiku"};

    private final EditorModel model;
    private final ClaudeClient client = new ClaudeClient();
    private final Popup popup;

    private String request = "Добавь ветку из трёх квестов про добычу железа после квеста «Начало».";
    private String log = "";
    private String status = " ";
    private int modelIndex;

    private QuestTab pending;
    private Thread worker;
    /** Что принёс поток: результат запроса или строка версии CLI. */
    private volatile ClaudeClient.Result result;
    private volatile String probeResult;
    private volatile boolean busy;

    public AssistantView(EditorModel model, Popup popup) {
        this.model = model;
        this.popup = popup;
        // рабочий каталог — временный: помощнику нечего трогать в проекте
        client.setWorkingDir(new File(System.getProperty("java.io.tmpdir")));
        client.setExecutable(Config.get().claudeExe);
        for (int i = 0; i < MODELS.length; i++)
            if (MODELS[i].equals(Config.get().claudeModel)) modelIndex = i;
    }

    // ------------------------------------------------------------------ кадр

    public void draw(Ui ui, float x, float y, float w, float h) {
        takeResults();

        float cy = y;
        Widgets.section(ui, "ЧТО СДЕЛАТЬ СО ВКЛАДКОЙ", x, cy, w);
        cy += 20;

        float requestH = Math.max(70, h * 0.22f);
        request = Widgets.textArea(ui, this + "request", x, cy, w, requestH, request,
                "опиши правку словами");
        cy += requestH + 8;

        float bh = Theme.ROW;
        float bx = x;
        ui.textIn("Модель", bx, cy, 56, bh, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        bx += 58;
        modelIndex = Widgets.dropdown(ui, popup, this + "model", bx, cy, 110, bh, MODELS, modelIndex);
        bx += 118;
        if (ui.button(bx, cy, 100, bh, "Отправить", Ui.PRIMARY, !busy && !request.trim().isEmpty())) send();
        bx += 104;
        if (ui.button(bx, cy, 86, bh, "Прервать", Ui.NORMAL, busy)) cancel();
        bx += 90;
        if (ui.button(bx, cy, 96, bh, "Применить", Ui.NORMAL, pending != null)) applyPending();
        bx += 100;
        if (ui.button(bx, cy, 110, bh, "Проверить CLI", Ui.GHOST, !busy)) probe();
        bx += 114;
        if (ui.button(bx, cy, 86, bh, "Примеры", Ui.GHOST, true)) examples();
        cy += bh + 10;

        Widgets.section(ui, "ОТВЕТ", x, cy, w);
        cy += 20;
        float logH = Math.max(80, y + h - cy - 24);
        drawLog(ui, x, cy, w, logH);

        ui.textIn(status, x, y + h - 22, w, 22, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
    }

    private void drawLog(Ui ui, float x, float y, float w, float h) {
        Object id = this + "log";
        List<String> lines = ui.wrap(log.isEmpty() ? "Ответа ещё нет." : log,
                w - 24, Theme.FONT_SMALL, false);
        float lineH = ui.font(Theme.FONT_SMALL, false).height();
        float content = lines.size() * lineH + 16;
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        float[] scroll = ui.beginScroll(id, x + 1, y + 1, w - 2, h - 2, w - 2, content);
        float ly = 8;
        for (String l : lines) {
            if (ly > scroll[1] - lineH && ly < scroll[1] + h)
                ui.text(l, 10, ly, Theme.TEXT, Theme.FONT_SMALL, false);
            ly += lineH;
        }
        ui.endScroll(id, x + 1, y + 1, w - 2, h - 2, w - 2, content);
    }

    // ------------------------------------------------------------------ запрос

    private void examples() {
        request = String.join("\n",
                "Примеры запросов (оставь один):",
                "• Добавь ветку из трёх квестов про добычу железа после квеста «Начало».",
                "• Переведи все описания на более живой язык, сохранив §-коды.",
                "• Расставь квесты аккуратной сеткой, не меняя связи.",
                "• Сделай последний квест в ветке MYTHIC и добавь ему гифт в награду.",
                "• Проставь всем задачам ITEM_DETECT requiredCount кратно 16.");
        requestDirty = true;
    }

    /** Текст запроса подменили кодом — поле должно перечитать его. */
    private boolean requestDirty;

    public void afterFrame(Ui ui) {
        if (!requestDirty) return;
        ui.forgetState(this + "request");
        ui.forgetState(this + "request scroll");
        ui.clearFocus();
        requestDirty = false;
    }

    private void probe() {
        status = "Проверяю…";
        busy = true;
        start(() -> {
            client.setExecutable(Config.get().claudeExe);
            probeResult = nz(client.probeVersion());
        });
    }

    private void send() {
        String text = request.trim();
        if (text.isEmpty()) return;

        String prompt = QuestPrompt.build(model.tab(), text);
        client.setExecutable(Config.get().claudeExe);
        client.setModel(MODELS[modelIndex]);

        log = "Запрос отправлен (" + prompt.length() + " символов). Жду ответ…";
        status = "Работает…";
        pending = null;
        busy = true;
        start(() -> result = client.ask(prompt));
    }

    private void start(Runnable body) {
        worker = new Thread(() -> {
            try {
                body.run();
            } finally {
                busy = false;
            }
        }, "claude");
        worker.setDaemon(true);
        worker.start();
    }

    private void cancel() {
        client.cancel();
        if (worker != null) worker.interrupt();
        status = "Прервано";
        busy = false;
    }

    /** Забрать то, что принёс фоновый поток. */
    private void takeResults() {
        String probe = probeResult;
        if (probe != null) {
            probeResult = null;
            status = probe.isEmpty()
                    ? "CLI «claude» не найден — установи Claude Code или укажи путь в настройках"
                    : "Claude CLI: " + probe;
        }
        ClaudeClient.Result r = result;
        if (r != null) {
            result = null;
            handle(r);
        }
    }

    private void handle(ClaudeClient.Result r) {
        if (!r.ok) {
            log += "\n" + (r.error == null ? "Неизвестная ошибка" : r.error);
            status = "Не получилось";
            return;
        }

        String json = ClaudeClient.extractJson(r.text);
        if (json == null) {
            log += "\nВ ответе нет JSON:\n" + r.text;
            status = "В ответе нет JSON вкладки";
            return;
        }

        QuestTab candidate;
        try {
            candidate = QuestTab.parse(json);
        } catch (RuntimeException e) {
            log += "\nJSON не разобрался: " + e.getMessage() + "\n\n" + json;
            status = "Ответ не похож на вкладку";
            return;
        }

        pending = candidate;
        StringBuilder sb = new StringBuilder(diff(model.tab(), candidate));
        sb.append("\n--- Проверка новой вкладки ---\n");
        List<TabIO.Problem> problems = TabIO.validate(candidate);
        if (problems.isEmpty()) sb.append("Замечаний нет.\n");
        else for (TabIO.Problem p : problems) sb.append(p).append('\n');
        log = sb.toString();

        String cost = r.costUsd > 0 ? String.format(", стоимость $%.4f", r.costUsd) : "";
        status = "Готово за " + (r.durationMs / 1000) + " с" + cost + " — нажми «Применить»";
    }

    private void applyPending() {
        if (pending == null) return;
        QuestTab candidate = pending;
        // ответ модели может переписать полвкладки — кладём копию до правки
        Backups.saveIfChanged(model.file(), model.tab().toJsonString(),
                Backups.Reason.BEFORE_ASSISTANT, Config.get().backupKeep);
        model.edit("Правка помощником", () -> {
            QuestTab tab = model.tab();
            tab.tabID = candidate.tabID;
            tab.iconItem = candidate.iconItem;
            tab.sortIndex = candidate.sortIndex;
            tab.quests.clear();
            tab.quests.putAll(candidate.quests);
            tab.extra.map().clear();
            tab.extra.map().putAll(candidate.extra.map());
        });
        status = "Применено — прежнее состояние осталось в истории версий";
        pending = null;
    }

    /** Короткая сводка изменений, чтобы не читать весь JSON. */
    private static String diff(QuestTab before, QuestTab after) {
        StringBuilder sb = new StringBuilder("--- Что изменится ---\n");
        if (!eq(before.tabID, after.tabID)) sb.append("tabID: ").append(before.tabID)
                .append(" → ").append(after.tabID).append('\n');
        if (!eq(before.iconItem, after.iconItem)) sb.append("iconItem: ").append(before.iconItem)
                .append(" → ").append(after.iconItem).append('\n');
        if (before.sortIndex != after.sortIndex) sb.append("sortIndex: ").append(before.sortIndex)
                .append(" → ").append(after.sortIndex).append('\n');

        Set<String> all = new LinkedHashSet<>(before.quests.keySet());
        all.addAll(after.quests.keySet());
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        for (String id : all) {
            Quest b = before.quests.get(id);
            Quest a = after.quests.get(id);
            if (b == null) added.add(id);
            else if (a == null) removed.add(id);
            else if (!b.toJson().toString().equals(a.toJson().toString())) changed.add(id);
        }
        line(sb, "Добавлено", added);
        line(sb, "Удалено", removed);
        line(sb, "Изменено", changed);
        if (added.isEmpty() && removed.isEmpty() && changed.isEmpty()) sb.append("Квесты не изменились.\n");
        sb.append("Всего квестов: ").append(before.quests.size()).append(" → ")
                .append(after.quests.size()).append('\n');
        return sb.toString();
    }

    private static void line(StringBuilder sb, String label, List<String> ids) {
        if (ids.isEmpty()) return;
        sb.append(label).append(" (").append(ids.size()).append("): ");
        sb.append(String.join(", ", ids)).append('\n');
    }

    private static boolean eq(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
