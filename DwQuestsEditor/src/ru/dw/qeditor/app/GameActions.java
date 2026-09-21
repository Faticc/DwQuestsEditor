package ru.dw.qeditor.app;

import ru.dw.qeditor.assets.Config;
import ru.dw.qeditor.assets.ItemRegistry;
import ru.dw.qeditor.json.JsonArray;
import ru.dw.qeditor.json.JsonObject;
import ru.dw.qeditor.json.JsonParser;
import ru.dw.qeditor.link.GameLink;
import ru.dw.qeditor.link.IconFetcher;
import ru.dw.qeditor.link.IconImport;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gl.Textures;
import ru.dw.qeditor.io.Backups;
import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.io.Workspace;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Пункты меню «Игра»: всё, что редактор делает через мост с запущенным клиентом.
 *
 * <p>Запросы идут прямо из кадра, синхронно: игра отвечает в конце своего тика,
 * то есть в пределах 50 мс, и окно этого не замечает. Единственное долгое
 * действие — «забрать все вкладки»: там запросов столько, сколько вкладок.
 */
final class GameActions {

    private final App app;

    GameActions(App app) {
        this.app = app;
    }

    // ----------------------------------------------------------- подключение

    void connect() {
        GameLink link = GameLink.get();
        boolean ok = link.connect();
        if (ok) {
            IconFetcher.get().retryFailed();
            ItemRegistry.setExtra(IconFetcher.get());
        }
        app.status(link.status());
        if (!ok) {
            app.open(Dialogs.message("Связь с игрой",
                    link.status() + "\n\n"
                            + "Что проверить:\n"
                            + "• игра запущена, и в ней есть мод DwQuestsBridge;\n"
                            + "• в игре сказано /dwqb bridge start (по умолчанию мост поднимается сам);\n"
                            + "• в настройках редактора указана папка клиента."));
        }
    }

    /**
     * Попытка связаться при запуске. Молча: игры может и не быть, ругаться на
     * это незачем — соединение поднимется, когда человек нажмёт «Подключиться».
     */
    void autoConnect() {
        try {
            if (GameLink.get().connect()) {
                ItemRegistry.setExtra(IconFetcher.get());
                app.status(GameLink.get().status());
            }
        } catch (Throwable ignored) {
            // сеть, права, антивирус — что угодно; редактор работает и без игры
        }
    }

    void disconnect() {
        GameLink.get().disconnect();
        app.status("Связь с игрой отключена");
    }

    void showStatus() {
        GameLink link = GameLink.get();
        StringBuilder sb = new StringBuilder();
        sb.append(link.status()).append('\n');
        sb.append("адрес: ").append(link.baseUrl().isEmpty() ? "не найден" : link.baseUrl()).append('\n');
        String clientDir = !link.clientDir().isEmpty() ? link.clientDir() : Config.get().clientDir;
        sb.append("папка клиента: ").append(Config.isBlank(clientDir) ? "не указана" : clientDir).append('\n');
        sb.append("иконок дорисовано за сеанс: ").append(IconFetcher.get().fetched());
        if (IconFetcher.get().pending() > 0) sb.append(", в очереди ").append(IconFetcher.get().pending());
        sb.append('\n');
        if (!IconFetcher.get().lastError().isEmpty()) {
            sb.append("последняя ошибка иконок: ").append(IconFetcher.get().lastError()).append('\n');
        }
        try {
            JsonObject status = link.jobStatus();
            sb.append("в игре: ").append(status.bool("busy", false)
                    ? status.str("job", "работа") + " — " + Math.round(status.f("fraction", 0) * 100) + "%"
                    : "ничего не выгружается");
        } catch (Exception e) {
            sb.append("состояние выгрузки узнать не вышло: ").append(e.getMessage());
        }
        app.showText("Связь с игрой", sb.toString());
    }

    // --------------------------------------------------------------- вкладки

    private List<String> cachedTabs = new ArrayList<>();
    private long cachedAt;

    /**
     * Имена вкладок для меню. Меню перестраивается на каждое наведение мыши,
     * поэтому список кэшируется: иначе на каждый кадр уходил бы запрос в игру.
     */
    List<String> cachedTabNames() {
        long now = System.currentTimeMillis();
        if (now - cachedAt > 10000L) {
            cachedTabs = tabNames();
            cachedAt = now;
        }
        return cachedTabs;
    }

    void forgetTabs() {
        cachedAt = 0;
    }

    /** Имена вкладок в игре — для подменю. */
    List<String> tabNames() {
        List<String> out = new ArrayList<>();
        try {
            JsonArray arr = GameLink.get().tabs().arr("tabs");
            if (arr == null) return out;
            for (int i = 0; i < arr.size(); i++) {
                JsonObject o = arr.obj(i);
                if (o != null) out.add(o.str("tabID", ""));
            }
        } catch (Exception e) {
            app.status("Список вкладок не пришёл: " + e.getMessage());
        }
        return out;
    }

    /**
     * Вкладка из игры ложится в открытую папку — туда, где с ней и работают.
     * Спрашиваем место, только если папка не открыта.
     */
    void openTab(String tabID) {
        try {
            String json = GameLink.get().tab(tabID);
            Workspace ws = app.workspace();
            File target;
            if (ws != null) {
                target = fileInFolder(ws, tabID);
            } else {
                target = FileDialogs.saveJson("Сохранить вкладку из игры",
                        new File(suggestedFolder(), TabIO.fileName(tabID)));
                if (target == null) return;
            }
            write(target, json);
            if (ws != null) app.folderChanged();
            app.openFile(target);
            app.status("Вкладка '" + tabID + "' взята из игры → " + target.getName());
        } catch (Exception e) {
            app.error("Не удалось забрать вкладку", e);
        }
    }

    void fetchAll() {
        Workspace ws = app.workspace();
        File dir = ws != null ? ws.dir() : FileDialogs.folder("Куда сложить вкладки", suggestedFolder());
        if (dir == null) return;
        List<String> names = tabNames();
        if (names.isEmpty()) {
            app.status("В игре нет вкладок (или связи нет)");
            return;
        }
        StringBuilder report = new StringBuilder();
        int ok = 0;
        for (String tabID : names) {
            try {
                String json = GameLink.get().tab(tabID);
                File file = ws != null ? fileInFolder(ws, tabID) : new File(dir, TabIO.fileName(tabID));
                write(file, json);
                ok++;
                report.append("✓ ").append(file.getName()).append(" — ")
                        .append(json.length()).append(" симв.\n");
            } catch (Exception e) {
                report.append("× ").append(tabID).append(" — ").append(e.getMessage()).append('\n');
            }
        }
        if (ws != null) app.folderChanged();
        app.status("Из игры взято вкладок: " + ok + " из " + names.size() + " → " + dir.getName());
        report.append('\n')
                .append("Это сборка из памяти клиента: в ней нет broadcast, а порядок наград\n")
                .append("может отличаться от исходного файла. Точную копию отдаёт сама игра\n")
                .append("командой /dwqb tabs raw — она требует прав на /dwq.");
        app.showText("Вкладки из игры", report.toString());
    }

    // --------------------------------------------------------------- заливка

    /** Отправка на сервер — только после подтверждения: вкладку увидят все игроки. */
    void sendCurrent(final boolean clipboardOnly) {
        final String tabID = app.model().tab().tabID;
        if (tabID == null || tabID.isEmpty()) {
            app.status("У вкладки нет tabID");
            return;
        }
        final String json = app.model().tab().toJsonString();
        if (clipboardOnly) {
            try {
                GameLink.get().toClipboard(tabID, json);
                app.status("Вкладка в буфере игры — в игре осталось сказать /dwq tab paste "
                        + tabID.replace(' ', '_'));
            } catch (Exception e) {
                app.error("Не удалось положить вкладку в буфер игры", e);
            }
            return;
        }
        app.open(Dialogs.confirm("Отправить вкладку в игру",
                "Вкладка '" + tabID + "' (" + app.model().tab().quests.size() + " квестов) уйдёт на сервер\n"
                        + "командой /dwq tab paste — она заменит вкладку у всех игроков сразу.\n\n"
                        + "Команда выполнится от вашего имени и пройдёт проверку прав на сервере.",
                new String[]{"Отправить", "Отмена"}, result -> {
                    if (result != 0) return;
                    try {
                        GameLink.get().paste(tabID, json);
                        app.status("Вкладка '" + tabID + "' отправлена в игру");
                    } catch (Exception e) {
                        app.error("Не удалось отправить вкладку", e);
                    }
                }));
    }

    /**
     * Все вкладки открытой папки — в игру. Открытая вкладка уходит такой, как
     * в редакторе (с несохранёнными правками), остальные — как лежат на диске.
     */
    void sendAll() {
        Workspace ws = app.workspace();
        if (ws == null || ws.entries().isEmpty()) {
            app.status("Откройте папку с вкладками — отправляется всё, что в ней лежит");
            return;
        }
        final List<String[]> tabs = new ArrayList<>();
        StringBuilder names = new StringBuilder();
        StringBuilder skipped = new StringBuilder();
        File current = app.model().file();
        for (Workspace.Entry e : ws.entries()) {
            if (e.error != null || e.tabID == null || e.tabID.isEmpty()) {
                skipped.append("\n• ").append(e.file.getName()).append(e.error == null ? "" : " — " + e.error);
                continue;
            }
            try {
                String json = current != null && current.getAbsoluteFile().equals(e.file.getAbsoluteFile())
                        ? app.model().tab().toJsonString()
                        : new String(java.nio.file.Files.readAllBytes(e.file.toPath()), StandardCharsets.UTF_8);
                tabs.add(new String[]{e.tabID, json});
                names.append("\n• ").append(e.tabID).append(" (").append(e.questCount).append(")");
            } catch (Exception ex) {
                skipped.append("\n• ").append(e.file.getName()).append(" — ").append(ex.getMessage());
            }
        }
        if (tabs.isEmpty()) {
            app.status("В папке нет вкладок, которые можно отправить");
            return;
        }
        app.open(Dialogs.confirm("Отправить все вкладки в игру",
                "Вкладок: " + tabs.size() + " — каждая заменит одноимённую у всех игроков." + names
                        + (skipped.length() == 0 ? "" : "\n\nПропущены (не читаются):" + skipped)
                        + "\n\nУходят по одной: следующая — только после ответа сервера\n"
                        + "«… успешно сохранено». Нет ответа — отправка останавливается.",
                new String[]{"Отправить все", "Отмена"}, result -> {
                    if (result != 0) return;
                    try {
                        GameLink.get().pasteAll(tabs);
                        app.status("Вкладки уходят в игру: " + tabs.size() + " — ход виден в чате игры");
                        awaitJob(() -> app.status("Заливка вкладок закончилась — итог в чате игры"));
                    } catch (Exception e) {
                        app.error("Не удалось отправить вкладки", e);
                    }
                }));
    }

    // ------------------------------------------------- прогресс своего игрока

    /*
     * Синтаксис команд мода взят из подсказок сервера в чате (исходников
     * серверной части у нас нет): "/dwq complete < all | tab | active >" и
     * "/dwq complete_quest <вкладка> <квест> False <ник>". Ник всегда свой —
     * мост другие и не пропустит. Пробелы в именах — подчёркивания, как у
     * "/dwq tab paste". Если сервер ответит подсказкой — она покажется в окне.
     */

    private static String arg(String name) {
        return name.trim().replace(' ', '_');
    }

    private String me() {
        String me = GameLink.get().player();
        if (me.isEmpty() && GameLink.get().connect()) me = GameLink.get().player();
        if (me.isEmpty()) throw new IllegalStateException("игрок не в мире — зайдите на сервер");
        return me;
    }

    /** tabID открытой вкладки; null (и строка состояния), если его нет. */
    private String currentTab() {
        String tabID = app.model().tab().tabID;
        if (tabID != null && !tabID.isEmpty()) return tabID;
        app.status("У вкладки нет tabID — в игре её не найти");
        return null;
    }

    /** Шаг вперёд: выполнить все квесты, которые сейчас активны. */
    void completeActive(boolean reward) {
        run(reward ? "Выполнить активные и забрать награды" : "Выполнить активные квесты",
                () -> "/dwq complete active " + me(), reward);
    }

    void completeTab(boolean reward) {
        String tabID = currentTab();
        if (tabID == null) return;
        confirmThen("Выполнить всю вкладку",
                "Все квесты вкладки '" + tabID + "' станут выполненными у игрока " + GameLink.get().player() + ".",
                "Выполнить", () -> run("Выполнить вкладку " + tabID,
                        () -> "/dwq complete tab " + arg(tabID) + " " + me(), reward));
    }

    void completeAll(boolean reward) {
        confirmThen("Выполнить все квесты",
                "Все квесты всех вкладок станут выполненными у игрока " + GameLink.get().player() + ".",
                "Выполнить все", () -> run("Выполнить все квесты", () -> "/dwq complete all " + me(), reward));
    }

    void completeQuest(String questID, boolean reward) {
        String tabID = currentTab();
        if (tabID == null) return;
        String command = "/dwq complete_quest " + arg(tabID) + " " + arg(questID) + " False ";
        if (!reward) {
            run("Выполнить: " + questID, () -> command + me(), false);
            return;
        }
        // по очереди: награду можно просить только когда клиент узнал о выполнении,
        // а мост как раз полторы секунды ждёт ответа сервера
        async("Выполнить и забрать: " + questID, () -> {
            JsonObject r = GameLink.get().command(command + me());
            JsonObject claimed;
            try {
                claimed = GameLink.get().claim(tabID, questID);
            } catch (Exception e) {
                claimed = new JsonObject().put("done", "награда: " + e.getMessage());
            }
            return merge(r, claimed);
        });
    }

    void claimQuest(String questID) {
        String tabID = currentTab();
        if (tabID == null) return;
        async("Забрать награду: " + questID, () -> GameLink.get().claim(tabID, questID));
    }

    void claimAll() {
        async("Забрать все награды", () -> GameLink.get().claimAll());
    }

    void clearQuest(String questID) {
        String tabID = currentTab();
        if (tabID == null) return;
        confirmThen("Сбросить квест",
                "Квест '" + questID + "' снова станет невыполненным у игрока " + GameLink.get().player() + ".",
                "Сбросить", () -> run("Сбросить: " + questID,
                        () -> "/dwq clear_quest " + arg(tabID) + " " + arg(questID) + " " + me(), false));
    }

    void clearTab() {
        String tabID = currentTab();
        if (tabID == null) return;
        confirmThen("Сбросить прогресс вкладки",
                "Весь прогресс вкладки '" + tabID + "' у игрока " + GameLink.get().player() + " пропадёт.",
                "Сбросить", () -> run("Сбросить вкладку " + tabID,
                        () -> "/dwq clear tab " + arg(tabID) + " " + me(), false));
    }

    void clearAll() {
        confirmThen("Сбросить весь прогресс",
                "Весь прогресс квестов игрока " + GameLink.get().player() + " пропадёт — во всех вкладках.",
                "Сбросить всё", () -> run("Сбросить весь прогресс", () -> "/dwq clear all " + me(), false));
    }

    /** Своя команда — для того, чего нет в меню; мост всё равно пропустит только /dwq complete…/clear… на себя. */
    void customCommand() {
        String me;
        try {
            me = me();
        } catch (Exception e) {
            app.error("Команда не отправлена", e);
            return;
        }
        app.open(Dialogs.prompt("Команда прогресса",
                "Команда (/dwq complete…, complete_quest…, complete_with_reward…, clear…, clear_quest…; последним — " + me + ")",
                "/dwq complete_quest " + arg(currentTabOr("вкладка")) + " квест False " + me,
                text -> run("Команда", () -> text, false)));
    }

    private String currentTabOr(String fallback) {
        String tabID = app.model().tab().tabID;
        return tabID == null || tabID.isEmpty() ? fallback : tabID;
    }

    private void confirmThen(String title, String text, String ok, Runnable then) {
        app.open(Dialogs.confirm(title, text + "\n\nКоманда уйдёт от вашего имени, права проверит сервер.",
                new String[]{ok, "Отмена"}, result -> {
                    if (result == 0) then.run();
                }));
    }

    /** Команда в чат игры; с reward — следом «Забрать все награды». */
    private void run(String title, java.util.concurrent.Callable<String> command, boolean reward) {
        async(title, () -> {
            JsonObject r = GameLink.get().command(command.call());
            if (!reward) return r;
            JsonObject claimed;
            try {
                claimed = GameLink.get().claimAll();
            } catch (Exception e) {
                claimed = new JsonObject().put("done", "награды: " + e.getMessage());
            }
            return merge(r, claimed);
        });
    }

    private static JsonObject merge(JsonObject a, JsonObject b) {
        JsonArray replies = new JsonArray();
        for (JsonObject o : new JsonObject[]{a, b}) {
            JsonArray arr = o.arr("replies");
            if (arr != null) for (int i = 0; i < arr.size(); i++) replies.add(arr.str(i));
        }
        return new JsonObject().put("done", a.str("done", "") + "\n" + b.str("done", ""))
                .put("replies", replies);
    }

    /** Ответы, дошедшие из потока моста, — их показывает кадр. */
    private final java.util.concurrent.ConcurrentLinkedQueue<Runnable> inbox =
            new java.util.concurrent.ConcurrentLinkedQueue<>();

    /**
     * Мост отвечает не сразу: после действия он полторы секунды слушает чат,
     * чтобы вернуть ответ сервера. Поэтому запрос — в своём потоке, а итог
     * показывает кадр.
     */
    private void async(String title, java.util.concurrent.Callable<JsonObject> work) {
        app.status(title + "…");
        Thread t = new Thread(() -> {
            Runnable result;
            try {
                JsonObject r = work.call();
                result = () -> showResult(title, r);
            } catch (Exception e) {
                result = () -> {
                    app.status(title + ": не вышло — " + e.getMessage());
                    app.open(Dialogs.message(title, "Не вышло: " + e.getMessage()));
                };
            }
            inbox.add(result);
            GlWindow.postEmptyEvent();
        }, "dwq-progress");
        t.setDaemon(true);
        t.start();
    }

    private void showResult(String title, JsonObject r) {
        StringBuilder sb = new StringBuilder();
        sb.append(r.str("done", "")).append('\n');
        JsonArray replies = r.arr("replies");
        if (replies == null || replies.size() == 0) {
            sb.append("\nСервер в чат ничего не ответил.");
        } else {
            sb.append("\nОтвет сервера:\n");
            for (int i = 0; i < replies.size(); i++) sb.append("  ").append(replies.str(i)).append('\n');
        }
        app.status(title + " — готово" + (replies == null || replies.size() == 0 ? "" : ": " + replies.str(0)));
        app.showText(title, sb.toString().trim());
    }

    // -------------------------------------------------------------- выгрузка

    void export(final String what, final String scope) {
        try {
            GameLink.get().export(what, scope);
            app.status("В игре пошла выгрузка: " + what + (scope == null ? "" : " (" + scope + ")")
                    + " — следите за чатом игры");
            if ("icons".equals(what)) awaitJob(this::pullIcons);
            else if ("tabs".equals(what)) awaitJob(this::pullTabs);
        } catch (Exception e) {
            app.error("Выгрузка не запустилась", e);
        }
    }

    /** Что сделать в кадре, когда выгрузка в игре кончится. */
    private volatile Runnable afterJob;
    private Thread jobWatcher;

    /**
     * Ждать конца выгрузки отдельным потоком: она идёт минутами, а кадр
     * блокировать нельзя. Сами файлы переносит главный поток — ему же
     * перечитывать базу, список папки и сбрасывать атлас иконок.
     */
    private void awaitJob(Runnable then) {
        if (jobWatcher != null && jobWatcher.isAlive()) return;
        jobWatcher = new Thread(() -> {
            try {
                Thread.sleep(1500);
                while (GameLink.get().jobStatus().bool("busy", false)) Thread.sleep(2000);
                afterJob = then;
                GlWindow.postEmptyEvent();
            } catch (Exception ignored) {
                // связь пропала — забрать можно и руками, пунктом меню
            }
        }, "dwq-export-wait");
        jobWatcher.setDaemon(true);
        jobWatcher.start();
    }

    /** Раз в кадр из App.tick. */
    void tick() {
        for (Runnable done; (done = inbox.poll()) != null; ) done.run();
        Runnable r = afterJob;
        if (r == null) return;
        afterJob = null;
        r.run();
    }

    /** Вкладки, выгруженные игрой в {@code <клиент>/dwq_export/tabs}, — в открытую папку. */
    void pullTabs() {
        String clientDir = !GameLink.get().clientDir().isEmpty()
                ? GameLink.get().clientDir() : Config.get().clientDir;
        File from = Config.isBlank(clientDir) ? null : new File(new File(clientDir), "dwq_export/tabs");
        File[] files = from == null ? null
                : from.listFiles((d, n) -> n.endsWith(".json") && !n.equals("index.json"));
        if (files == null || files.length == 0) {
            app.status("Выгруженных вкладок нет" + (from == null ? "" : ": " + from));
            return;
        }
        Workspace ws = app.workspace();
        File dir = ws != null ? ws.dir() : FileDialogs.folder("Куда сложить вкладки", suggestedFolder());
        if (dir == null) return;
        int ok = 0;
        StringBuilder failed = new StringBuilder();
        for (File f : files) {
            try {
                String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
                String tabID = JsonParser.parseObject(json).str("tabID", "");
                File target = ws != null && !tabID.isEmpty() ? fileInFolder(ws, tabID) : new File(dir, f.getName());
                write(target, json);
                ok++;
            } catch (Exception e) {
                failed.append(' ').append(f.getName());
            }
        }
        if (ws != null) app.folderChanged();
        app.status("Вкладки из игры → " + dir.getName() + ": " + ok + " из " + files.length
                + (failed.length() == 0 ? "" : "; не вышло:" + failed));
    }

    /** Перенести выгруженные игрой иконки в папку редактора и сразу их показать. */
    void pullIcons() {
        String clientDir = !GameLink.get().clientDir().isEmpty()
                ? GameLink.get().clientDir() : Config.get().clientDir;
        File from = IconImport.source(clientDir);
        if (from == null) {
            app.status("Выгруженных иконок нет: " + (Config.isBlank(clientDir) ? "клиент не найден"
                    : clientDir + "/dwq_export/icons"));
            return;
        }
        File to = Config.get().iconsWriteDir();
        if (to.getAbsoluteFile().equals(from.getAbsoluteFile())) {
            reloadIcons();
            app.status("Иконки перечитаны прямо из выгрузки игры");
            return;
        }
        try {
            IconImport.Result r = IconImport.run(from, to);
            reloadIcons();
            app.status("Иконки из игры: " + r);
        } catch (Exception e) {
            app.error("Иконки не перенеслись", e);
        }
    }

    private void reloadIcons() {
        ItemRegistry.get().reload();
        IconFetcher.get().forget();
        Textures.clear();
    }

    void toggleAutoIcons() {
        Config cfg = Config.get();
        cfg.linkAutoIcons = !cfg.linkAutoIcons;
        cfg.save();
        app.status(cfg.linkAutoIcons
                ? "Недостающие иконки будут дорисовываться игрой"
                : "Дорисовка иконок из игры выключена");
    }

    // ----------------------------------------------------------------- мелочи

    private File suggestedFolder() {
        Config cfg = Config.get();
        if (!Config.isBlank(cfg.lastFolder)) return new File(cfg.lastFolder);
        String clientDir = !GameLink.get().clientDir().isEmpty() ? GameLink.get().clientDir() : cfg.clientDir;
        if (!Config.isBlank(clientDir)) {
            File tabs = new File(new File(clientDir), "dwq_export/tabs");
            if (tabs.isDirectory()) return tabs;
        }
        return new File(".");
    }

    /**
     * Файл вкладки в папке: если вкладка там уже есть под другим именем файла —
     * пишем в него, чтобы не плодить двойников.
     */
    private static File fileInFolder(Workspace ws, String tabID) {
        File wanted = ws.fileFor(tabID);
        for (Workspace.Entry e : ws.entries()) {
            if (!tabID.equals(e.tabID)) continue;
            // Прежние версии называли файл по tabID как есть, с пробелами, — такой
            // переименовываем. Имя, которое файлу дал человек, не трогаем.
            if (e.file.getName().equals(tabID + ".json") && !wanted.exists() && e.file.renameTo(wanted)) {
                return wanted;
            }
            return e.file;
        }
        return wanted;
    }

    /** Записать, а прежнее содержимое оставить версией — его можно вернуть. */
    private static void write(File file, String text) throws Exception {
        if (file.isFile()) {
            String old = new String(java.nio.file.Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            if (old.equals(text)) return;
            Backups.save(file, old, Backups.Reason.BEFORE_GAME, Config.get().backupKeep);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IllegalStateException("нет папки " + parent);
        }
        try (Writer w = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8)) {
            w.write(text);
        }
    }
}
