package ru.dw.qeditor.util;

import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.render.GuiPainter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Отладочный рендер GuiPainter в PNG — для сверки с игрой.
 * Запуск: java -Dfile.encoding=UTF-8 -cp out ru.dw.qeditor.util.PainterProbe [файл.png]
 */
public final class PainterProbe {

    public static void main(String[] args) throws Exception {
        int guiScale = 2;
        int w = 480, h = 330;

        BufferedImage img = new BufferedImage(w * guiScale, h * guiScale, BufferedImage.TYPE_INT_ARGB);
        GuiPainter p = GuiPainter.forImage(img, guiScale);

        // фон окна мода
        p.guiBackground(0, 0, w, h);

        // ---- звёзды всех редкостей с разными нодами
        String[] icons = {
                "minecraft:book", "minecraft:diamond", "minecraft:golden_apple",
                "minecraft:nether_star", "minecraft:redstone", "minecraft:emerald"
        };
        Defs.Rarity[] rarities = Defs.Rarity.values();
        int tick = 7;
        for (int i = 0; i < rarities.length; i++) {
            Defs.Rarity r = rarities[i];
            float x = 60 + i * 70;
            p.questStar(x, 60, 24, 24, 1f, Defs.Star.of(r), Defs.NodeType.NORMAL, icons[i], tick);
            p.textCentered(r.name(), x + 12, 100, r.color, true);
        }

        // ---- все типы нодов
        Defs.NodeType[] nodes = Defs.NodeType.values();
        for (int i = 0; i < nodes.length; i++) {
            float x = 40 + i * 54;
            p.questStar(x, 150, 24, 24, 1f, Defs.Star.BLUE, nodes[i], "minecraft:book", tick);
            p.textCentered(nodes[i].name(), x + 12, 190, 0xFFFFFFFF, true);
        }

        // ---- «активный» квест (nodeType = null, серое ядро) и ховер-масштаб
        p.questStar(40, 215, 24, 24, 1f, Defs.Star.GRAY, null, "minecraft:stone", tick);
        p.questStar(100, 215, 24, 24, 1.25f, Defs.Star.ORANGE, Defs.NodeType.UNSTABLE, "minecraft:blaze_rod", tick);

        // ---- размеры иконок
        float x = 170;
        for (Defs.IconSize s : Defs.IconSize.values()) {
            p.questStar(x, 215, 24, 24, s.scale, Defs.Star.PURPLE, Defs.NodeType.TAINTED, "minecraft:diamond", tick);
            x += 60;
        }

        // ---- слоты, градиенты, тени, обводка предмета
        int panelY = 265;
        p.rect(20, panelY, 440, 50, 0x66000000);
        p.insideShadowRect(20, panelY, 440, 50, 0xFF000000, 8);

        for (int i = 0; i < 4; i++) p.slot(30 + i * 22, panelY + 8);
        p.item("minecraft:iron_ingot", 31, panelY + 9);
        p.item("minecraft:gold_ingot", 53, panelY + 9);
        p.item("BuildCraft|Transport:pipeLens:18", 75, panelY + 9);
        p.item("minecraft:bedrock", 97, panelY + 9);

        p.itemWithOutline("minecraft:nether_star", 130, panelY + 6, 2f, Defs.Rarity.MYTHIC.color, 1);

        p.gradient(180, panelY + 10, 130, 2, 0, Defs.Rarity.LEGENDARY.color, 90);
        p.gradient(180, panelY + 14, 130, 2, Defs.Rarity.RARE.color, 0, 90);
        p.lineShadow(180, panelY + 22, 130, 14, 0xFF000000, 0);

        p.text("Забрать награды", 330, panelY + 12, 0xFF55FF55, true);
        p.text("Выполнено задач: 2/5", 330, panelY + 26, 0xFFFFFFFF, true);

        p.dispose();

        File out = new File(args.length > 0 ? args[0] : "painter-probe.png");
        ImageIO.write(img, "png", out);
        System.out.println("Записано: " + out.getAbsolutePath() + " (" + img.getWidth() + "x" + img.getHeight() + ")");

        writeBackgrounds(new File(out.getParentFile(), "painter-probe-bg.png"), guiScale);
    }

    /** Проверка texturedModalRect: фоны вкладок из jar + слои параллакса Stars. */
    private static void writeBackgrounds(File file, int guiScale) throws Exception {
        ru.dw.qeditor.assets.ModAssets a = ru.dw.qeditor.assets.ModAssets.get();
        java.util.List<String> tabs = a.knownTabBackgrounds();
        int cell = 120;
        int cols = 5;
        int rows = (tabs.size() + cols - 1) / cols + 1;
        int w = cols * cell, h = rows * cell;

        BufferedImage img = new BufferedImage(w * guiScale, h * guiScale, BufferedImage.TYPE_INT_ARGB);
        GuiPainter p = GuiPainter.forImage(img, guiScale);
        p.rect(0, 0, w, h, 0xFF101218);

        for (int i = 0; i < tabs.size(); i++) {
            String tab = tabs.get(i);
            float x = (i % cols) * cell;
            float y = (i / cols) * cell;
            p.push();
            p.clip(x, y, cell, cell - 12);
            p.texturedModalRect(a.tabBackground(tab), x, y, 0, 0, cell, cell - 12);
            p.pop();
            p.text(tab, x + 2, y + cell - 10, 0xFFFFFFFF, true);
        }

        // слои параллакса Stars поверх фона Default — как UIQuestBrowser.backgroundToFrameBuffer
        float bx = 0, by = (rows - 1) * cell;
        float bw = cols * cell, bh = cell - 12;
        p.push();
        p.clip(bx, by, bw, bh);
        p.texturedModalRect(a.defaultBackground(), bx, by, 40, 40, bw, bh);
        p.translate(bx, by);
        float[] sizes = {1.33f, 1.77f, 2.37f, 3.16f, 4.21f};
        for (float s : sizes) {
            p.scale(0.75f);
            p.texturedModalRect(a.stars(), 0, 0, 40, 40, bw * s, bh * s);
        }
        p.pop();
        p.text("Default + 5 слоёв Stars", 4, by + cell - 10, 0xFFFFFFFF, true);

        p.dispose();
        ImageIO.write(img, "png", file);
        System.out.println("Записано: " + file.getAbsolutePath() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
    }
}
