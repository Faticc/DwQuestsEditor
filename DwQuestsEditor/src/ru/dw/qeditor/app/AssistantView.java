package ru.dw.qeditor.app;

import ru.dw.qeditor.ai.ClaudeClient;
import ru.dw.qeditor.ai.QuestPrompt;
import ru.dw.qeditor.ai.TabShot;
import ru.dw.qeditor.ai.TabWorkspace;
import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.gl.GlWindow;
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
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Помощник на базе Claude: просишь словами — Claude правит файлы вкладки.
 *
 * <p>Работает через локальный Claude Code CLI, поэтому отдельный ключ API не
 * нужен. Вкладка раскладывается по файлам ({@link TabWorkspace}), модель
 * сама читает нужные и правит их; ответ не применяется сразу — сначала
 * показывается, что изменится.
 *
 * <p>Всё, что уходит, видно до отправки на странице «Что уйдёт»: команда,
 * сообщение и системный промпт — ровно тем текстом, что получит CLI. Ход
 * работы (какие файлы Claude читает и правит) идёт в журнал по мере работы.
 *
 * <p>Запрос выполняет обычный поток: события он кладёт в очередь, результат —
 * в поле, а кадр их подхватывает.
 */
public final class AssistantView {

    private static final String[] MODELS = {"sonnet", "opus", "fable", "haiku"};
    private static final String[] EFFORTS = {"авто", "low", "medium", "high"};
    private static final String[] PAGES = {"Что уйдёт", "Ход работы", "Изменения"};

    private final EditorModel model;
    private final ClaudeClient client = new ClaudeClient();
    private final Popup popup;

    private String request = "Добавь ветку из трёх квестов про добычу железа после квеста «Начало».";
    private String status = " ";
    private int modelIndex;
    private int effortIndex;
    private int page;
    /** Прикладывать ли снимок вкладки: помощник увидит карту, а не только JSON. */
    private boolean withShot;
    /** Продолжать разговор: модель помнит прошлые просьбы, а начало берётся из кэша. */
    private boolean continueChat = true;
    /**
     * Применять ответ сразу. Откатить его легко (Ctrl+Z, версии), а
     * неприменённый ответ легко не заметить: квесты «пропадают», хотя
     * помощник написал, что сделал.
     */
    private boolean autoApply = true;
    /** К какой вкладке относится ответ — чтобы не применить его к другой. */
    private File pendingFor;

    /** Текущий разговор: id сеанса CLI, его каталог и число сообщений. */
    private String session;
    private File sessionDir;
    private int sessionMessages;

    private TabWorkspace workspace;
    /** Вкладка в момент отправки — от неё считаются изменения помощника. */
    private QuestTab base;
    private QuestTab pending;
    private String changes = "";
    private final StringBuilder journal = new StringBuilder();
    /** Что именно ушло последним запросом — страница «Что уйдёт» во время работы. */
    private String sent;

    /** Запрос, который ждёт снимка (его делают после кадра). */
    private String queued;
    private Thread worker;
    private final ConcurrentLinkedQueue<String> events = new ConcurrentLinkedQueue<>();
    private volatile ClaudeClient.Result result;
    private volatile String probeResult;
    private volatile boolean busy;

    public AssistantView(EditorModel model, Popup popup) {
        this.model = model;
        this.popup = popup;
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

        float requestH = Math.max(70, h * 0.18f);
        request = Widgets.textArea(ui, this + "request", x, cy, w, requestH, request,
                "опиши правку словами");
        cy += requestH + 8;

        float bh = Theme.ROW;
        float bx = x;
        ui.textIn("Модель", bx, cy, 56, bh, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        bx += 58;
        modelIndex = Widgets.dropdown(ui, popup, this + "model", bx, cy, 90, bh, MODELS, modelIndex);
        bx += 96;
        ui.textIn("Усилие", bx, cy, 52, bh, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
        bx += 54;
        effortIndex = Widgets.dropdown(ui, popup, this + "effort", bx, cy, 90, bh, EFFORTS, effortIndex);
        bx += 100;
        if (ui.button(bx, cy, 100, bh, "Отправить", Ui.PRIMARY, !busy && !request.trim().isEmpty())) send();
        bx += 104;
        if (ui.button(bx, cy, 86, bh, "Прервать", Ui.NORMAL, busy)) cancel();
        bx += 90;
        if (ui.button(bx, cy, 96, bh, "Применить", Ui.NORMAL, pending != null && !busy)) applyPending();
        cy += bh + 6;

        bx = x;
        String shotLabel = "со снимком карты";
        if (ui.checkbox(bx, cy + 3, shotLabel, withShot)) withShot = !withShot;
        if (ui.over(bx, cy, ui.checkboxWidth(shotLabel), bh))
            ui.tooltip("К запросу приложится PNG вкладки — помощник увидит,\nкак она выглядит в игре");
        bx += ui.checkboxWidth(shotLabel) + 16;
        String chatLabel = "продолжать разговор";
        if (ui.checkbox(bx, cy + 3, chatLabel, continueChat)) continueChat = !continueChat;
        if (ui.over(bx, cy, ui.checkboxWidth(chatLabel), bh))
            ui.tooltip("Помощник помнит прошлые просьбы («а теперь ещё…»),\n"
                    + "и начало разговора берётся из кэша — дешевле.\n"
                    + "Но каждое сообщение тянет за собой всю историю:\n"
                    + "для новой темы жми «Новый разговор».");
        bx += ui.checkboxWidth(chatLabel) + 16;
        String applyLabel = "применять сразу";
        if (ui.checkbox(bx, cy + 3, applyLabel, autoApply)) autoApply = !autoApply;
        if (ui.over(bx, cy, ui.checkboxWidth(applyLabel), bh))
            ui.tooltip("Ответ сразу попадает во вкладку; вернуть — Ctrl+Z.\n"
                    + "Без галки сначала смотришь «Изменения» и жмёшь «Применить».");
        bx += ui.checkboxWidth(applyLabel) + 16;
        if (ui.button(bx, cy, 130, bh, "Новый разговор", Ui.GHOST, !busy && session != null)) newChat();
        bx += 134;
        if (ui.button(bx, cy, 70, bh, "Папка", Ui.GHOST, workspace != null)) openFolder();
        if (ui.over(bx, cy, 70, bh)) ui.tooltip("Открыть каталог с файлами вкладки,\nкоторые правит помощник");
        bx += 74;
        if (ui.button(bx, cy, 110, bh, "Проверить CLI", Ui.GHOST, !busy)) probe();
        bx += 114;
        if (ui.button(bx, cy, 86, bh, "Примеры", Ui.GHOST, true)) examples();
        cy += bh + 8;

        page = Widgets.tabs(ui, x, cy, bh, PAGES, page);
        String text = pageText(ui.time());
        if (ui.button(x + w - 110, cy, 110, bh, "Копировать", Ui.GHOST, !text.isEmpty())) {
            ui.window().setClipboard(text);
            status = "Скопировано: «" + PAGES[page] + "»";
        }
        cy += bh + 4;

        float logH = Math.max(80, y + h - cy - 24);
        drawText(ui, x, cy, w, logH, text);

        ui.textIn(status, x, y + h - 22, w, 22, Theme.TEXT_MUTED, Ui.LEFT, Theme.FONT_SMALL, false);
    }

    private String pageText(double now) {
        return switch (page) {
            case 0 -> busy && sent != null ? "Отправлено и выполняется:\n\n" + sent : preview(now);
            case 1 -> journal.length() == 0 ? "Запросов ещё не было." : journal.toString();
            default -> changes.isEmpty() ? "Изменений пока нет." : changes;
        };
    }

    /**
     * Текст для чтения: рисуются только видимые строки, а переносятся строки
     * заново, лишь когда поменялись текст или ширина — системный промпт и
     * длинный журнал иначе переносились бы каждый кадр.
     */
    private void drawText(Ui ui, float x, float y, float w, float h, String text) {
        if (!text.equals(wrappedText) || w != wrappedW) {
            wrapped = ui.wrap(text, w - 24, Theme.FONT_SMALL, false);
            wrappedText = text;
            wrappedW = w;
        }
        Object id = this + "text" + page;
        float lineH = ui.font(Theme.FONT_SMALL, false).height();
        float content = wrapped.size() * lineH + 16;
        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, Theme.SURFACE_2, Theme.BORDER, 1);
        float[] scroll = ui.beginScroll(id, x + 1, y + 1, w - 2, h - 2, w - 2, content);
        int first = Math.max(0, (int) ((scroll[1] - 8) / lineH));
        for (int i = first; i < wrapped.size(); i++) {
            float ly = 8 + i * lineH;
            if (ly > scroll[1] + h) break;
            ui.text(wrapped.get(i), 10, ly, Theme.TEXT, Theme.FONT_SMALL, false);
        }
        ui.endScroll(id, x + 1, y + 1, w - 2, h - 2, w - 2, content);
    }

    private List<String> wrapped = List.of();
    private String wrappedText;
    private float wrappedW;

    // ------------------------------------------------------------------ что уйдёт

    private String previewText = "";
    private double previewAt = -10;

    /**
     * Точный текст запроса. Собирается не чаще раза в секунду: имена файлов
     * раздаются по всей вкладке, а на тысяче квестов это заметно.
     */
    private String preview(double now) {
        if (now - previewAt < 1.0) return previewText;
        previewAt = now;
        File dir = workspaceDir();
        TabWorkspace ws = new TabWorkspace(dir);
        ws.plan(model.tab());
        String message = QuestPrompt.message(model.tab(), ws, model.selection(), request, withShot);
        String system = QuestPrompt.system();
        configure();
        boolean resume = resumes(dir);
        previewText = describe(dir, message, system, resume ? session : "<новый id>", resume);
        return previewText;
    }

    /** Полное описание запроса: команда, сообщение, системный промпт и файлы. */
    private String describe(File dir, String message, String system, String id, boolean resume) {
        StringBuilder sb = new StringBuilder();
        sb.append("── Команда (каталог ").append(dir.getAbsolutePath()).append(") ──\n");
        sb.append(String.join(" ", client.arguments(id, resume))).append("\n");
        sb.append(resume
                ? "Продолжение разговора: сообщений в нём уже " + sessionMessages
                  + ". Модель видит и всю прошлую переписку — её начало читается из кэша.\n\n"
                : "Новый разговор.\n\n");
        sb.append("── Сообщение (уходит в stdin, ").append(message.length()).append(" симв.) ──\n");
        sb.append(message).append('\n');
        sb.append("── Системный промпт (system.md, ").append(system.length())
                .append(" симв.; с другого запроса берётся из кэша) ──\n");
        sb.append(system).append('\n');
        sb.append("── Файлы, которые Claude может открыть ──\n");
        sb.append("tab.json, map.txt, quests/ — ").append(model.tab().quests.size())
                .append(" файлов по квесту").append(withShot ? ", tab.png" : "").append(".\n");
        sb.append("Сами файлы в запрос не входят: Claude читает только нужные, и какие именно —\n");
        sb.append("видно на странице «Ход работы». Кроме этого уходят описания пяти инструментов\n");
        sb.append("(").append(ClaudeClient.TOOLS).append(") — около 3,5 тыс. токенов, тоже из кэша.\n");
        return sb.toString();
    }

    // ------------------------------------------------------------------ запрос

    private void examples() {
        request = String.join("\n",
                "Примеры запросов (оставь один):",
                "• Добавь ветку из трёх квестов про добычу железа после квеста «Начало».",
                "• Переведи описания выделенных квестов на более живой язык, сохранив §-коды.",
                "• Расставь выделенные квесты аккуратной сеткой, не меняя связи.",
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

    /** Каталог вкладки: у каждой свой, чтобы разговоры про разные вкладки не путались. */
    private File workspaceDir() {
        File f = model.file();
        String name = f != null ? f.getName() : TabIO.fileName(model.tab());
        if (name.toLowerCase().endsWith(".json")) name = name.substring(0, name.length() - 5);
        return new File(new File(System.getProperty("java.io.tmpdir"), "dwq-assistant"), name);
    }

    private boolean resumes(File dir) {
        return continueChat && session != null && dir.equals(sessionDir);
    }

    private void configure() {
        client.setExecutable(Config.get().claudeExe);
        client.setModel(MODELS[modelIndex]);
        client.setEffort(effortIndex == 0 ? null : EFFORTS[effortIndex]);
    }

    private void newChat() {
        session = null;
        sessionDir = null;
        sessionMessages = 0;
        status = "Следующий запрос начнёт новый разговор";
    }

    private void send() {
        String text = request.trim();
        if (text.isEmpty()) return;
        if (withShot) {
            // снимок рисуется тем же пакетом вершин, что и окно, поэтому не
            // здесь, а после кадра — иначе кадр оборвётся на середине
            queued = text;
            status = "Готовлю снимок вкладки…";
            busy = true;
            return;
        }
        fire(text, false);
    }

    /**
     * После кадра: если запрос ждал снимка вкладки, снимок делается сейчас и
     * запрос уходит. Вызывается из {@code App.drawFrame} за пределами
     * {@code batch.begin()/end()}.
     */
    public void afterBatch(ru.dw.qeditor.gl.Batch batch) {
        if (queued == null) return;
        String text = queued;
        queued = null;
        File shot = TabShot.render(model.tab(), model.single(), batch, workspaceDir(), 1280, 800);
        fire(text, shot != null);
    }

    private void fire(String text, boolean shot) {
        File dir = workspaceDir();
        workspace = new TabWorkspace(dir);
        try {
            workspace.write(model.tab());
            if (!shot) java.nio.file.Files.deleteIfExists(new File(dir, "tab.png").toPath());
        } catch (Exception e) {
            status = "Не разложить вкладку по файлам: " + e.getMessage();
            busy = false;
            return;
        }
        base = model.tab().copy();
        pendingFor = dir;
        configure();
        boolean resume = resumes(dir);
        if (!resume) {
            session = UUID.randomUUID().toString();
            sessionDir = dir;
            sessionMessages = 0;
        }
        String message = QuestPrompt.message(model.tab(), workspace, model.selection(), text, shot);
        String system = QuestPrompt.system();
        sent = describe(dir, message, system, session, resume);

        if (journal.length() > 0) journal.append("\n");
        journal.append("━━ ").append(resume ? "Продолжение" : "Новый разговор").append(", ")
                .append(MODELS[modelIndex]).append(" ━━\n")
                .append("Вы: ").append(text).append('\n');
        page = 1;
        pending = null;
        status = "Работает…";
        busy = true;
        final String id = session;
        start(() -> result = client.ask(dir, system, message, id, resume, e -> {
            events.add(e);
            GlWindow.postEmptyEvent();
        }));
    }

    private void start(Runnable body) {
        worker = new Thread(() -> {
            try {
                body.run();
            } finally {
                busy = false;
                GlWindow.postEmptyEvent();
            }
        }, "claude");
        worker.setDaemon(true);
        worker.start();
    }

    private void cancel() {
        client.cancel();
        status = "Прерываю…";
    }

    /** Забрать то, что принёс фоновый поток. */
    private void takeResults() {
        for (String e; (e = events.poll()) != null; ) journal.append(e).append('\n');
        String probe = probeResult;
        if (probe != null) {
            probeResult = null;
            status = probe.isEmpty()
                    ? "CLI «claude» не найден — установи Claude Code или укажи путь в настройках"
                    : "Claude CLI: " + probe;
        }
        ClaudeClient.Result r = result;
        if (r != null && !busy) {
            result = null;
            for (String e; (e = events.poll()) != null; ) journal.append(e).append('\n');
            handle(r);
        }
    }

    private void handle(ClaudeClient.Result r) {
        String usage = String.format("%.0f с, ходов %d, $%.4f · токены: новые %d, из кэша %d, в кэш %d, ответ %d",
                r.durationMs / 1000.0, r.turns, r.costUsd, r.inTokens, r.cacheRead, r.cacheWrite, r.outTokens);
        if (!r.ok) {
            journal.append("Ошибка: ").append(r.error == null ? "неизвестная" : r.error).append('\n');
            status = "Не получилось";
            // разговор мог и не начаться — тогда продолжать нечего
            if (sessionMessages == 0) newChat();
            return;
        }
        sessionMessages++;
        journal.append("· ").append(usage).append('\n');

        TabWorkspace.ReadResult back = workspace.read();
        StringBuilder sb = new StringBuilder();
        if (r.text != null && !r.text.isBlank()) sb.append("Claude: ").append(r.text.trim()).append("\n\n");
        if (!back.errors.isEmpty()) {
            sb.append("--- Файлы не разобрались ---\n");
            for (String e : back.errors) sb.append(e).append('\n');
            sb.append("Попроси помощника исправить их — или поправь сам в каталоге («Папка»).\n");
        }
        if (back.tab == null) {
            changes = sb.toString();
            page = 2;
            status = "Ответ не собрался во вкладку";
            return;
        }
        String diff = diff(base, back.tab);
        sb.append(diff);
        boolean changed = !base.toJsonString().equals(back.tab.toJsonString());
        if (changed) {
            sb.append("\n--- Проверка новой вкладки ---\n");
            List<TabIO.Problem> problems = TabIO.validate(back.tab);
            if (problems.isEmpty()) sb.append("Замечаний нет.\n");
            else for (TabIO.Problem p : problems) sb.append(p).append('\n');
        }
        changes = sb.toString();
        pending = changed ? back.tab : null;
        page = 2;
        status = (changed ? "Готово — нажми «Применить». " : "Готово, вкладка не изменилась. ") + usage;
        if (changed && autoApply) {
            applyPending();
            if (pending == null) status = "Готово и применено (Ctrl+Z — отменить). " + usage;
        }
    }

    /**
     * Применить сделанное помощником. Переносится только то, что поменял он
     * (относительно вкладки на момент отправки): если пока он работал, в
     * редакторе правили другие квесты, эти правки не пропадут.
     */
    private void applyPending() {
        if (pending == null) return;
        if (!workspaceDir().equals(pendingFor)) {
            status = "Ответ относится к вкладке «" + pendingFor.getName() + "» — открой её, чтобы применить";
            return;
        }
        QuestTab done = pending;
        QuestTab from = base;
        Backups.saveIfChanged(model.file(), model.tab().toJsonString(),
                Backups.Reason.BEFORE_ASSISTANT, Config.get().backupKeep);
        model.edit("Правка помощником", () -> {
            QuestTab tab = model.tab();
            if (!Objects.equals(from.tabID, done.tabID)) tab.tabID = done.tabID;
            if (!Objects.equals(from.iconItem, done.iconItem)) tab.iconItem = done.iconItem;
            if (from.sortIndex != done.sortIndex) tab.sortIndex = done.sortIndex;
            if (!from.extra.toString().equals(done.extra.toString())) {
                tab.extra.map().clear();
                tab.extra.map().putAll(done.extra.map());
            }
            Set<String> ids = new LinkedHashSet<>(from.quests.keySet());
            ids.addAll(done.quests.keySet());
            for (String id : ids) {
                Quest was = from.quests.get(id);
                Quest now = done.quests.get(id);
                if (same(was, now)) continue;
                if (now == null) tab.removeQuest(id);
                else tab.quests.put(id, now.copy());
            }
        });
        status = "Применено — прежнее состояние в истории версий и в Ctrl+Z";
        pending = null;
    }

    private static boolean same(Quest a, Quest b) {
        if (a == null || b == null) return a == b;
        return a.toJson().toString().equals(b.toJson().toString());
    }

    /** Короткая сводка изменений, чтобы не читать весь JSON. */
    private static String diff(QuestTab before, QuestTab after) {
        StringBuilder sb = new StringBuilder("--- Что изменится ---\n");
        if (!Objects.equals(before.tabID, after.tabID)) sb.append("tabID: ").append(before.tabID)
                .append(" → ").append(after.tabID).append('\n');
        if (!Objects.equals(before.iconItem, after.iconItem)) sb.append("iconItem: ").append(before.iconItem)
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
            else if (!same(a, b)) changed.add(id);
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

    private void openFolder() {
        File dir = workspace == null ? null : workspace.dir();
        if (dir == null || !dir.isDirectory()) return;
        try {
            boolean win = System.getProperty("os.name", "").toLowerCase().contains("win");
            new ProcessBuilder(win ? "explorer" : "xdg-open", dir.getAbsolutePath()).start();
        } catch (Exception e) {
            status = "Не открыть папку: " + e.getMessage();
        }
    }

    private static String nz(String s) { return s == null ? "" : s; }
}
