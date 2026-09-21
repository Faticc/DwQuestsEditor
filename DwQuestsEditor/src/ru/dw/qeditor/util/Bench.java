package ru.dw.qeditor.util;

import ru.dw.qeditor.app.App;
import ru.dw.qeditor.gl.Offscreen;

import java.io.File;

/**
 * Замер скорости отрисовки на большой вкладке — чтобы правки в рендере
 * проверялись числами, а не на глаз.
 *
 * <p>Кадр рисует видеокарта, а вызовы отрисовки только ставятся в очередь,
 * поэтому после каждого кадра ждём {@code glFinish} — иначе замер показал бы
 * скорость заполнения очереди, а не отрисовки.
 *
 * <p>Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.Bench вкладка.json [кадров]}
 */
public final class Bench {

    private Bench() { }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Укажите файл вкладки");
            return;
        }
        File tab = new File(args[0]);
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        int width = args.length > 2 ? Integer.parseInt(args[2]) : 1600;
        int height = args.length > 3 ? Integer.parseInt(args[3]) : 1000;

        try (GlProbe probe = GlProbe.open(width, height)) {
            App app = new App(probe.window(), probe.batch());
            app.openFile(tab);

            // размер берём у окна: систему могло не устроить запрошенное
            Offscreen target = Offscreen.create(probe.window().width(), probe.window().height());
            try {
                for (int page = 0; page <= 1; page++) {
                    app.selectPage(page);
                    for (boolean pageOnly : new boolean[]{false, true}) {
                        app.setChromeVisible(!pageOnly);
                        double ms = measure(probe, app, target, frames);
                        System.out.printf("%-10s %-12s %.2f мс/кадр (%.0f к/с)%n",
                                page == 0 ? "Редактор:" : "Просмотр:",
                                pageOnly ? "только поле" : "окно целиком",
                                ms, 1000.0 / Math.max(0.01, ms));
                    }
                }
            } finally {
                target.dispose();
                app.setChromeVisible(true);
            }
        }
    }

    private static double measure(GlProbe probe, App app, Offscreen target, int frames) {
        // прогрев: первые кадры наполняют атласы картинок и шрифта
        for (int i = 0; i < 3; i++) {
            target.bind(0xFF000000);
            app.drawFrame();
        }
        probe.sync();

        long start = System.nanoTime();
        for (int i = 0; i < frames; i++) {
            target.bind(0xFF000000);
            app.drawFrame();
        }
        probe.sync();
        long ns = System.nanoTime() - start;
        target.unbind();
        return ns / 1_000_000.0 / frames;
    }
}
