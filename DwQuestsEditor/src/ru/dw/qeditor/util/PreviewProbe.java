package ru.dw.qeditor.util;

import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Parent;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;
import ru.dw.qeditor.model.Task;
import ru.dw.qeditor.render.GamePreview;

import java.io.File;

/**
 * Отладочный рендер вкладки «Просмотр (как в игре)» в PNG.
 * Запуск: java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.PreviewProbe [каталог]
 */
public final class PreviewProbe {

    public static void main(String[] args) throws Exception {
        File dir = new File(args.length > 0 ? args[0] : ".");
        QuestTab tab = args.length > 1 ? ru.dw.qeditor.io.TabIO.load(new File(args[1])) : demoTab();

        GamePreview gp = new GamePreview();
        gp.setTab(tab);
        gp.setGuiScale(2);

        try (GlProbe probe = GlProbe.open()) {
            write(probe, dir, "preview-map.png", gp, 1280, 800);

            gp.openQuest(tab.quests.isEmpty() ? "Начало" : tab.quests.keySet().iterator().next());
            write(probe, dir, "preview-panel.png", gp, 1280, 800);

            gp.closePanel();
            gp.setMode(GamePreview.Mode.ACTIVE);
            write(probe, dir, "preview-active.png", gp, 1280, 800);
        }
    }

    private static void write(GlProbe probe, File dir, String name, GamePreview gp, int w, int h) {
        probe.write(new File(dir, name), probe.frame(w, h, gp.getGuiScale(), p -> gp.render(p, w, h)));
    }

    private static QuestTab demoTab() {
        QuestTab tab = QuestTab.empty("Botania");
        tab.iconItem = "Botania:lexicon";

        add(tab, "Начало", "Первые шаги", 0, 0, "minecraft:book", Defs.Rarity.COMMON, Defs.NodeType.NORMAL,
                "<BR>Добро пожаловать в §aBotania§r!<BR>Собери первые ресурсы и сделай палочку.", null);
        add(tab, "Дерево", "Заготовка дерева", 2, -1, "minecraft:log", Defs.Rarity.UNCOMMON, Defs.NodeType.NORMAL,
                "<BR>Нужно немного §6брёвен§r.", "Начало");
        add(tab, "Камень", "Добыча камня", 2, 1, "minecraft:stone", Defs.Rarity.UNCOMMON, Defs.NodeType.UNSTABLE,
                "<BR>Копай глубже.", "Начало");
        add(tab, "Цветы", "Мистические цветы", 4, 0, "Botania:flower", Defs.Rarity.RARE, Defs.NodeType.PURE,
                "<BR>Собери §dмистические цветы§r восьми оттенков.<BR>Они понадобятся дальше.", "Дерево");
        add(tab, "Мана", "Первая мана", 6, -1, "Botania:manaResource", Defs.Rarity.EPIC, Defs.NodeType.TAINTED,
                "<BR>Создай манапул и накопи ману.", "Цветы");
        add(tab, "Терра", "Терра-клинок", 8, 0, "Botania:terraSword", Defs.Rarity.LEGENDARY, Defs.NodeType.HUNGRY,
                "<BR>Легендарный клинок из чистой маны.", "Мана");
        add(tab, "Гайя", "Страж Гайи", 10, 1, "Botania:manaResource:5", Defs.Rarity.MYTHIC, Defs.NodeType.DARK,
                "<BR>§cОпасно!§r Победи стража Гайи.", "Терра");
        add(tab, "Тайна", "Тайная тропа", 6, 2, "minecraft:ender_pearl", Defs.Rarity.RARE, Defs.NodeType.SMOKE,
                "<BR>Скрытая ветка.", "Камень");

        Quest gaia = tab.quests.get("Гайя");
        Parent extra = new Parent();
        extra.questID = "Тайна";
        extra.lineType = Defs.LineType.INVERTED;
        gaia.parents.add(extra);

        return tab;
    }

    private static void add(QuestTab tab, String id, String name, float x, float y, String icon,
                            Defs.Rarity rarity, Defs.NodeType node, String desc, String parent) {
        Quest q = new Quest();
        q.questID = id;
        q.displayName = name;
        q.displayX = x;
        q.displayY = y;
        q.iconItem = icon;
        q.rarity = rarity;
        q.nodeType = node;
        q.description = desc;
        if (parent != null) q.parents.add(new Parent(parent));

        Task t1 = new Task();
        t1.type = Defs.TaskType.ITEM_DETECT;
        t1.forgeName = icon;
        t1.requiredCount = 16;
        q.tasks.put(0, t1);

        Task t2 = new Task();
        t2.type = Defs.TaskType.ITEM_CRAFT;
        t2.forgeName = "minecraft:stick";
        t2.requiredCount = 4;
        q.tasks.put(1, t2);

        Task t3 = new Task();
        t3.type = Defs.TaskType.TASK_CONFIRM;
        q.tasks.put(2, t3);

        q.rewards.add("minecraft:diamond=3");
        q.rewards.add("minecraft:gold_ingot=8");
        tab.quests.put(id, q);
    }
}
