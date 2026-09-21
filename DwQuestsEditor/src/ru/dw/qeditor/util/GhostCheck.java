package ru.dw.qeditor.util;

import ru.dw.qeditor.app.App;
import ru.dw.qeditor.app.MapView;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Проверка, что на поле редактора не остаётся лишнего.
 *
 * <p>Раньше поле рисовалось в картинку, которая жила между кадрами, и если
 * заливка фона не покрывала её целиком, от прошлого кадра оставались
 * «призраки»: смазанные подписи и сетка при панораме и зуме. В OpenGL кадр
 * очищается целиком, так что призраков быть не может, — но сама проверка
 * осталась полезной: она ловит узлы и подписи, которые нарисовались не там,
 * где должны. Камера уводится в пустое место, и в поле должен остаться
 * только цвет фона.
 *
 * <p>Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.GhostCheck вкладка.json}
 */
public final class GhostCheck {

    private GhostCheck() { }

    public static void main(String[] args) {
        if (args.length == 0) {
            System.out.println("Укажите файл вкладки");
            return;
        }
        int width = 1400, height = 900;
        int strays;

        try (GlProbe probe = GlProbe.open(width, height)) {
            App app = new App(probe.window(), probe.batch());
            app.openFile(new File(args[0]));
            app.selectPage(0);
            app.setChromeVisible(false);

            // кадр с содержимым: вся вкладка на экране
            MapView map = app.map();
            map.fitToContent();
            probe.frameSelf(app::drawFrame);

            // теперь уводим камеру далеко в пустоту и выключаем всё, кроме узлов
            map.setShowGrid(false);
            map.setShowLinks(false);
            map.setShowNames(false);
            map.setZoom(0.9f);
            map.panTo(500000f, 500000f);
            BufferedImage shot = probe.frameSelf(app::drawFrame);

            float[] area = map.area();
            float scale = Math.max(1f, probe.window().contentScale());
            int x0 = Math.round(area[0] * scale);
            int y0 = Math.round(area[1] * scale);
            int x1 = Math.min(shot.getWidth(), Math.round((area[0] + area[2]) * scale));
            int y1 = Math.min(shot.getHeight(), Math.round((area[1] + area[3]) * scale));

            Map<Integer, Integer> colors = new HashMap<>();
            for (int y = y0 + 2; y < y1 - 2; y += 2)
                for (int x = x0 + 2; x < x1 - 2; x += 2)
                    colors.merge(shot.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);

            int total = colors.values().stream().mapToInt(Integer::intValue).sum();
            int background = colors.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
            int backgroundCount = colors.getOrDefault(background, 0);
            strays = total - backgroundCount;

            System.out.printf("поле %dx%d, цветов в кадре: %d, фон #%06X занимает %d из %d точек%n",
                    x1 - x0, y1 - y0, colors.size(), background, backgroundCount, total);
            if (strays == 0)
                System.out.println("ПРОВЕРКА ПРОЙДЕНА: после ухода камеры остался только фон");
            else
                System.out.printf("ПРОВЕРКА НЕ ПРОЙДЕНА: осталось %d лишних точек (%.1f%%)%n",
                        strays, 100.0 * strays / total);
        }
        System.exit(strays == 0 ? 0 : 1);
    }
}
