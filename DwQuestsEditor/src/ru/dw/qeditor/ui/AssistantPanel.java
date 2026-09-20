package ru.dw.qeditor.ui;

import ru.dw.qeditor.ai.ClaudeClient;
import ru.dw.qeditor.ai.QuestPrompt;
import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.SwingWorker;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Помощник на базе Claude: просишь словами — получаешь изменённую вкладку.
 *
 * <p>Работает через локальный Claude Code CLI, поэтому отдельный ключ API не
 * нужен. Ответ не применяется сразу: сначала показывается, что изменится.
 */
public class AssistantPanel extends JPanel {

    private final EditorModel model;
    private final ClaudeClient client = new ClaudeClient();

    private final JTextArea request = new JTextArea(4, 40);
    private final JTextArea log = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final JComboBox<String> modelBox = new JComboBox<>(new String[]{"sonnet", "opus", "haiku"});
    private final JButton send;
    private final JButton cancel;
    private final JButton apply;

    private QuestTab pending;
    private SwingWorker<ClaudeClient.Result, Void> worker;

    public AssistantPanel(EditorModel model) {
        this.model = model;
        // рабочий каталог — временный: помощнику нечего трогать в проекте
        client.setWorkingDir(new java.io.File(System.getProperty("java.io.tmpdir")));
        client.setExecutable(ru.dw.qeditor.assets.Config.get().claudeExe);
        modelBox.setSelectedItem(ru.dw.qeditor.assets.Config.get().claudeModel);

        request.setLineWrap(true);
        request.setWrapStyleWord(true);
        request.setText("Добавь ветку из трёх квестов про добычу железа после квеста «Начало».");

        log.setEditable(false);
        log.setFont(ru.dw.qeditor.ui.theme.Theme.mono(12));

        send = Ui.primary("Отправить", this::send);
        cancel = Ui.btn("Прервать", this::cancel);
        apply = Ui.btn("Применить", this::applyPending);
        cancel.setEnabled(false);
        apply.setEnabled(false);

        modelBox.setPreferredSize(new java.awt.Dimension(110, 28));
        request.setFont(ru.dw.qeditor.ui.theme.Theme.font(ru.dw.qeditor.ui.theme.Theme.FONT_SIZE));

        JPanel top = new JPanel(new BorderLayout(0, 6));
        top.setOpaque(false);
        top.setBorder(ru.dw.qeditor.ui.theme.Theme.padding(4, 0, 0, 0));
        top.add(ru.dw.qeditor.ui.theme.Theme.sectionTitle("Что сделать со вкладкой"), BorderLayout.NORTH);
        JScrollPane requestScroll = new JScrollPane(request);
        ru.dw.qeditor.ui.theme.Theme.styleScroll(requestScroll);
        requestScroll.setBorder(BorderFactory.createLineBorder(ru.dw.qeditor.ui.theme.Theme.BORDER));
        top.add(requestScroll, BorderLayout.CENTER);

        JPanel controls = new JPanel(new ru.dw.qeditor.ui.theme.WrapLayout(FlowLayout.LEFT, 6, 6));
        controls.setOpaque(false);
        controls.add(new JLabel("Модель:"));
        controls.add(modelBox);
        controls.add(send);
        controls.add(cancel);
        controls.add(apply);
        controls.add(Ui.smallBtn("Проверить CLI", "Проверить, доступен ли claude", this::probe));
        controls.add(Ui.smallBtn("Примеры", "Подставить пример запроса", this::examples));
        top.add(controls, BorderLayout.SOUTH);

        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setOpaque(false);
        bottom.setBorder(ru.dw.qeditor.ui.theme.Theme.padding(6, 0, 0, 0));
        bottom.add(ru.dw.qeditor.ui.theme.Theme.sectionTitle("Ответ"), BorderLayout.NORTH);
        JScrollPane logScroll = new JScrollPane(log);
        ru.dw.qeditor.ui.theme.Theme.styleScroll(logScroll);
        logScroll.setBorder(BorderFactory.createLineBorder(ru.dw.qeditor.ui.theme.Theme.BORDER));
        bottom.add(logScroll, BorderLayout.CENTER);
        status.setForeground(ru.dw.qeditor.ui.theme.Theme.TEXT_MUTED);
        status.setFont(ru.dw.qeditor.ui.theme.Theme.font(ru.dw.qeditor.ui.theme.Theme.FONT_SIZE_SMALL + 1));
        status.setBorder(ru.dw.qeditor.ui.theme.Theme.padding(4, 2, 0, 2));
        bottom.add(status, BorderLayout.SOUTH);

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, top, bottom);
        split.setResizeWeight(0.35);
        split.setBorder(null);
        split.setOpaque(false);
        setOpaque(false);
        setBorder(ru.dw.qeditor.ui.theme.Theme.padding(0, 0, 12, 8));
        setLayout(new BorderLayout());
        add(split, BorderLayout.CENTER);
        setPreferredSize(new Dimension(600, 400));
    }

    private void examples() {
        request.setText(String.join("\n",
                "Примеры запросов (оставь один):",
                "• Добавь ветку из трёх квестов про добычу железа после квеста «Начало».",
                "• Переведи все описания на более живой язык, сохранив §-коды.",
                "• Расставь квесты аккуратной сеткой, не меняя связи.",
                "• Сделай последний квест в ветке MYTHIC и добавь ему гифт в награду.",
                "• Проставь всем задачам ITEM_DETECT requiredCount кратно 16."));
    }

    private void probe() {
        status.setText("Проверяю…");
        new SwingWorker<String, Void>() {
            @Override
            protected String doInBackground() {
                client.setExecutable(ru.dw.qeditor.assets.Config.get().claudeExe);
                return client.probeVersion();
            }

            @Override
            protected void done() {
                try {
                    String v = get();
                    status.setText(v == null || v.isEmpty()
                            ? "CLI «claude» не найден — установи Claude Code или укажи путь в настройках"
                            : "Claude CLI: " + v);
                } catch (Exception e) {
                    status.setText("Ошибка проверки: " + e.getMessage());
                }
            }
        }.execute();
    }

    private void send() {
        String text = request.getText().trim();
        if (text.isEmpty()) return;

        String prompt = QuestPrompt.build(model.tab(), text);
        client.setExecutable(ru.dw.qeditor.assets.Config.get().claudeExe);
        client.setModel((String) modelBox.getSelectedItem());

        log.setText("Запрос отправлен (" + prompt.length() + " символов). Жду ответ…\n");
        status.setText("Работает…");
        send.setEnabled(false);
        cancel.setEnabled(true);
        apply.setEnabled(false);
        pending = null;

        worker = new SwingWorker<>() {
            @Override protected ClaudeClient.Result doInBackground() { return client.ask(prompt); }

            @Override
            protected void done() {
                send.setEnabled(true);
                cancel.setEnabled(false);
                try {
                    handle(get());
                } catch (java.util.concurrent.CancellationException e) {
                    status.setText("Прервано");
                } catch (Exception e) {
                    status.setText("Ошибка: " + e.getMessage());
                }
            }
        };
        worker.execute();
    }

    private void cancel() {
        client.cancel();
        if (worker != null) worker.cancel(true);
        status.setText("Прервано");
        cancel.setEnabled(false);
        send.setEnabled(true);
    }

    private void handle(ClaudeClient.Result r) {
        if (r == null) {
            status.setText("Пустой ответ");
            return;
        }
        if (!r.ok) {
            log.append("\n" + (r.error == null ? "Неизвестная ошибка" : r.error) + "\n");
            status.setText("Не получилось");
            return;
        }

        String json = ClaudeClient.extractJson(r.text);
        if (json == null) {
            log.append("\nВ ответе нет JSON:\n" + r.text + "\n");
            status.setText("В ответе нет JSON вкладки");
            return;
        }

        QuestTab candidate;
        try {
            candidate = QuestTab.parse(json);
        } catch (RuntimeException e) {
            log.append("\nJSON не разобрался: " + e.getMessage() + "\n\n" + json + "\n");
            status.setText("Ответ не похож на вкладку");
            return;
        }

        pending = candidate;
        log.setText(diff(model.tab(), candidate));
        log.append("\n--- Проверка новой вкладки ---\n");
        List<TabIO.Problem> problems = TabIO.validate(candidate);
        if (problems.isEmpty()) log.append("Замечаний нет.\n");
        else for (TabIO.Problem p : problems) log.append(p + "\n");
        log.setCaretPosition(0);

        String cost = r.costUsd > 0 ? String.format(", стоимость $%.4f", r.costUsd) : "";
        status.setText("Готово за " + (r.durationMs / 1000) + " с" + cost + " — нажми «Применить»");
        apply.setEnabled(true);
    }

    private void applyPending() {
        if (pending == null) return;
        QuestTab candidate = pending;
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
        status.setText("Применено");
        apply.setEnabled(false);
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
}
