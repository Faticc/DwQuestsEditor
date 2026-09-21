package ru.dw.qeditor.util;

import ru.dw.qeditor.app.App;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Проверка полноэкранного режима: туда и обратно, с возвратом прежнего вида.
 *
 * <p>Пересоздавать окно, как это делал Swing, больше не нужно: GLFW переводит
 * окно на монитор одним вызовом. Проверять остаётся то же самое — что обвязка
 * (меню, колонки, строка состояния) уходит и возвращается, а тело окна после
 * возврата совпадает с исходным.
 *
 * <p>Запускается с {@code -Ddwq.nofullscreen=true}, чтобы не перехватывать
 * настоящий экран: тогда проверяется только раскладка.
 *
 * <p>Запуск: {@code java -Ddwq.nofullscreen=true -cp "out;lib/*"
 * ru.dw.qeditor.util.FullscreenCheck [вкладка.json]}
 */
public final class FullscreenCheck {

    private FullscreenCheck() { }

    public static void main(String[] args) {
        int width = 1400, height = 900;
        boolean ok;

        try (GlProbe probe = GlProbe.open(width, height)) {
            App app = new App(probe.window(), probe.batch());
            if (args.length > 0) app.openFile(new File(args[0]));

            BufferedImage before = probe.frameSelf(app::drawFrame);
            app.toggleFullscreen();
            BufferedImage inside = probe.frameSelf(app::drawFrame);
            app.toggleFullscreen();
            BufferedImage after = probe.frameSelf(app::drawFrame);
            height = before.getHeight();

            // полоса строки меню: в полном экране её быть не должно
            boolean menuChanged = !sameStrip(before, inside, 0, 26);
            boolean menuBack = sameStrip(before, after, 0, 26);
            // строка состояния внизу — та же история. Сравнивать её с исходной
            // нельзя: сам переход пишет туда «Обычный режим», — поэтому
            // проверяем, что она отличается от полноэкранного кадра
            boolean statusChanged = !sameStrip(before, inside, height - 24, height);
            boolean statusBack = !sameStrip(inside, after, height - 24, height);
            // тело окна (колонки и страница) должно вернуться точно как было
            boolean bodyBack = sameStrip(before, after, 30, height - 30);

            report("в режиме строка меню исчезла", menuChanged);
            report("в режиме строка состояния исчезла", statusChanged);
            report("после выхода строка меню вернулась", menuBack);
            report("после выхода строка состояния вернулась", statusBack);
            report("после выхода колонки и страница на месте", bodyBack);

            ok = menuChanged && statusChanged && menuBack && statusBack && bodyBack;
            System.out.println(ok ? "ПРОВЕРКА ПРОЙДЕНА" : "ПРОВЕРКА НЕ ПРОЙДЕНА");
        }
        System.exit(ok ? 0 : 1);
    }

    private static boolean sameStrip(BufferedImage a, BufferedImage b, int y0, int y1) {
        for (int y = Math.max(0, y0); y < Math.min(a.getHeight(), y1); y += 2)
            for (int x = 0; x < a.getWidth(); x += 4)
                if (a.getRGB(x, y) != b.getRGB(x, y)) return false;
        return true;
    }

    private static void report(String what, boolean ok) {
        System.out.println((ok ? "  ок   " : "  нет  ") + what);
    }
}
