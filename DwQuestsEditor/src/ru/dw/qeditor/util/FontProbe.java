package ru.dw.qeditor.util;

import ru.dw.qeditor.assets.McFont;

import java.io.File;

/** Отладочный рендер шрифта в PNG — для сверки с игрой. */
public final class FontProbe {

    private FontProbe() { }

    public static void main(String[] args) {
        McFont f = McFont.get();
        if (!f.isReady()) {
            System.out.println("Шрифт не загружен: " + f.error());
            return;
        }
        int scale = 4;
        int w = 260, h = 120;

        String[] lines = {
                "Привет, мир! ABC abc 0123",
                "§aЗелёный §cкрасный §9синий",
                "§lЖирный§r §oКурсив§r §nПодчёрк",
                "§mЗачёркнутый§r обычный",
                "Сбор: Кленовые листья (3/16)",
                "Выполнено задач: 2/5"
        };

        File out = new File(args.length > 0 ? args[0] : "font-probe.png");
        try (GlProbe probe = GlProbe.open()) {
            probe.write(out, probe.frame(w * scale, h * scale, scale, p -> {
                p.rect(0, 0, w, h, 0xFF101218);
                int y = 6;
                for (String s : lines) {
                    p.text(s, 6, y, 0xFFFFFFFF, true);
                    y += 14;
                }
                p.text("Ширина строки 1: " + f.width(lines[0]) + "px", 6, y + 4, 0xFFAAAAAA, true);
            }));
        }
    }
}
