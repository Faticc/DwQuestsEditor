package ru.dw.qeditor.util;

import ru.dw.qeditor.assets.McFont;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;

/** Отладочный рендер шрифта в PNG — для сверки с игрой. */
public final class FontProbe {

    public static void main(String[] args) throws Exception {
        McFont f = McFont.get();
        if (!f.isReady()) {
            System.out.println("Шрифт не загружен: " + f.error());
            return;
        }
        int scale = 4;
        int w = 260, h = 120;
        BufferedImage img = new BufferedImage(w * scale, h * scale, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setColor(new Color(0x10, 0x12, 0x18));
        g.fillRect(0, 0, img.getWidth(), img.getHeight());
        g.scale(scale, scale);

        String[] lines = {
                "Привет, мир! ABC abc 0123",
                "§aЗелёный §cкрасный §9синий",
                "§lЖирный§r §oКурсив§r §nПодчёрк",
                "§mЗачёркнутый§r обычный",
                "Сбор: Кленовые листья (3/16)",
                "Выполнено задач: 2/5"
        };
        int y = 6;
        for (String s : lines) {
            f.draw(g, s, 6, y, 0xFFFFFFFF, true);
            y += 14;
        }
        f.draw(g, "Ширина строки 1: " + f.width(lines[0]) + "px", 6, y + 4, 0xFFAAAAAA, true);
        g.dispose();

        File out = new File(args.length > 0 ? args[0] : "font-probe.png");
        ImageIO.write(img, "png", out);
        System.out.println("Записано: " + out.getAbsolutePath());
    }
}
