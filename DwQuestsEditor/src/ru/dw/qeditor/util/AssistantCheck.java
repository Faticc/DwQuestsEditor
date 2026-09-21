package ru.dw.qeditor.util;

import ru.dw.qeditor.ai.ClaudeClient;
import ru.dw.qeditor.ai.QuestPrompt;
import ru.dw.qeditor.ai.TabWorkspace;
import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Живая проверка помощника: настоящий запрос к Claude Code CLI на тестовой
 * вкладке. Модель должна изменить квест, создать новый и удалить ещё один —
 * всё через файлы, — а редактор должен собрать это обратно.
 *
 * <p>Стоит денег (центы на haiku), поэтому в общие проверки не входит.
 * Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.AssistantCheck [вкладка.json] [модель]}
 */
public final class AssistantCheck {

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "testquests/Магия.json";
        String model = args.length > 1 ? args[1] : "haiku";
        QuestTab tab = TabIO.load(new File(file));
        List<String> ids = new ArrayList<>(tab.quests.keySet());
        String edit = ids.get(1);
        String parent = ids.get(0);
        String drop = ids.get(ids.size() - 1);
        int before = tab.quests.size();

        File dir = Files.createTempDirectory("dwq-assistant-check").toFile();
        TabWorkspace ws = new TabWorkspace(dir);
        ws.write(tab);
        long[] size = ws.size();
        System.out.println("Раскладка: " + dir + " — файлов квестов " + size[0] + ", " + size[1] / 1024 + " КБ");

        String request = "1) Квесту " + edit + " поставь displayName «Проверка связи» и rarity EPIC.\n"
                + "2) Создай новый квест с id Проверка_новый в свободной клетке рядом с " + parent
                + ", родитель — " + parent + ", задача TASK_CONFIRM, награда minecraft:apple=2.\n"
                + "3) Удали квест " + drop + ".";
        String message = QuestPrompt.message(tab, ws, List.of(edit), request, false);
        System.out.println("---- сообщение ----\n" + message + "-------------------");

        ClaudeClient client = new ClaudeClient();
        client.setModel(model);
        ClaudeClient.Result r = client.ask(dir, QuestPrompt.system(), message,
                UUID.randomUUID().toString(), false, e -> System.out.println("  | " + e));

        System.out.println(String.format("Итог: %s, %.1f с, ходов %d, $%.4f; токены: новые %d, из кэша %d, в кэш %d, ответ %d",
                r.ok ? "ок" : "ошибка: " + r.error, r.durationMs / 1000.0, r.turns, r.costUsd,
                r.inTokens, r.cacheRead, r.cacheWrite, r.outTokens));

        TabWorkspace.ReadResult back = ws.read();
        for (String e : back.errors) System.out.println("  ошибка разбора: " + e);
        boolean ok = r.ok && back.tab != null && back.errors.isEmpty();
        if (back.tab != null) {
            Quest q = back.tab.quests.get(edit);
            ok &= report("квест изменён Edit-ом", q != null && "Проверка связи".equals(q.displayName)
                    && q.rarity.name().equals("EPIC"), q == null ? "нет квеста" : q.displayName + ", " + q.rarity);
            Quest n = back.tab.quests.get("Проверка_новый");
            boolean linked = false;
            if (n != null) for (Parent p : n.parents) linked |= parent.equals(p.questID);
            ok &= report("новый квест создан Write-ом", n != null && linked && !n.tasks.isEmpty()
                    && n.rewards.contains("minecraft:apple=2"), n == null ? "нет" : n.toJson().toString().replace('\n', ' '));
            ok &= report("квест удалён", !back.tab.quests.containsKey(drop), drop);
            ok &= report("остальное на месте", back.tab.quests.size() == before,
                    before + " → " + back.tab.quests.size());
            ok &= report("порядок квестов прежний", new ArrayList<>(back.tab.quests.keySet()).get(0).equals(ids.get(0)), "");
            List<TabIO.Problem> problems = TabIO.validate(back.tab);
            System.out.println("  проверка вкладки: " + (problems.isEmpty() ? "замечаний нет" : problems.size() + " замечаний"));
        }
        System.out.println(ok ? "ПРОВЕРКА ПРОЙДЕНА" : "ПРОВЕРКА НЕ ПРОЙДЕНА");
        if (!ok) System.exit(1);
    }

    private static boolean report(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ок   " : "  НЕТ  ") + what + " — " + detail);
        return ok;
    }
}
