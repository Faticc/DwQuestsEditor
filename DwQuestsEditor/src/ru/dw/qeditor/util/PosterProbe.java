package ru.dw.qeditor.util;

import ru.dw.qeditor.io.TabIO;
import ru.dw.qeditor.render.GamePreview;
import ru.dw.qeditor.render.TabPoster;

import java.io.File;

/**
 * Картинка всей вкладки без окна — то же, что «Файл → Сохранить картинку вкладки».
 * Запуск: java -Dfile.encoding=UTF-8 -cp "out;lib/*" ru.dw.qeditor.util.PosterProbe вкладка.json out.png [масштаб]
 */
public final class PosterProbe {

    private PosterProbe() { }

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            System.out.println("PosterProbe вкладка.json out.png [масштаб]");
            return;
        }
        float scale = args.length > 2 ? Float.parseFloat(args[2]) : 4f;
        GamePreview gp = new GamePreview();
        gp.setSingleTab(TabIO.load(new File(args[0])));
        try (GlProbe probe = GlProbe.open()) {
            long start = System.nanoTime();
            TabPoster.Result r = TabPoster.render(gp, probe.batch(), new File(args[1]), scale);
            System.out.printf("%s: %d × %d, ×%.2f, %.1f с%n", r.file(), r.width(), r.height(), r.scale(),
                    (System.nanoTime() - start) / 1e9);
        }
    }
}
