package ru.dw.qeditor.util;

import ru.dw.qeditor.app.App;
import ru.dw.qeditor.app.EditorModel;
import ru.dw.qeditor.app.MapView;
import ru.dw.qeditor.gl.Input;
import ru.dw.qeditor.gl.InputProbe;
import ru.dw.qeditor.model.Quest;

import java.io.File;
import java.lang.reflect.Field;

import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Проверка жеста на поле: щелчок по квесту выделяет его и НЕ двигает, а
 * перетаскивание — двигает.
 *
 * <p>Раньше квест уезжал от простого щелчка: жест начинался сразу с нажатия, и
 * первый же кадр притягивал узел к сетке. Проверка держит это исправленным.
 *
 * <p>Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.DragCheck вкладка.json}
 */
public final class DragCheck {

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "testquests/Магия.json";
        boolean ok = true;

        try (GlProbe probe = GlProbe.open(1400, 900)) {
            App app = new App(probe.window(), probe.batch());
            app.openFile(new File(file));
            app.selectPage(0);
            Input in = probe.window().input();
            MapView map = app.map();

            Field f = App.class.getDeclaredField("model");
            f.setAccessible(true);
            EditorModel model = (EditorModel) f.get(app);
            if (model.tab().quests.isEmpty()) {
                System.out.println("Во вкладке нет квестов");
                return;
            }
            Quest q = model.tab().quests.values().iterator().next();

            frame(app, in);
            map.setZoom(1f);
            float[] area = map.area();
            map.panTo(q.displayX * MapView.CELL + 12 - area[2] / 2,
                    q.displayY * MapView.CELL + 12 - area[3] / 2);
            frame(app, in);

            area = map.area();
            float nodeX = area[0] + area[2] / 2;
            float nodeY = area[1] + area[3] / 2;
            float x0 = q.displayX;
            float y0 = q.displayY;

            // щелчок с дрожанием руки в пару пикселей
            press(app, in, nodeX, nodeY);
            move(app, in, nodeX + 2, nodeY + 1);
            release(app, in, nodeX + 2, nodeY + 1);
            ok &= report("щелчок не двигает квест", q.displayX == x0 && q.displayY == y0,
                    "(" + x0 + "," + y0 + ") → (" + q.displayX + "," + q.displayY + ")");
            ok &= report("щелчок выделяет квест", model.selection().contains(q.questID),
                    "выделено: " + model.selection());

            // курсор в сторону и пауза: иначе следующее нажатие сочтут двойным щелчком
            move(app, in, nodeX + 200, nodeY + 200);
            Thread.sleep(450);

            // перетаскивание на две клетки вправо
            press(app, in, nodeX, nodeY);
            move(app, in, nodeX + 20, nodeY);
            move(app, in, nodeX + 48, nodeY);
            release(app, in, nodeX + 48, nodeY);
            ok &= report("перетаскивание двигает квест",
                    q.displayX == x0 + 2 && q.displayY == y0,
                    "(" + x0 + "," + y0 + ") → (" + q.displayX + "," + q.displayY + ")");
        }

        System.out.println(ok ? "ПРОВЕРКА ПРОЙДЕНА" : "ПРОВЕРКА НЕ ПРОЙДЕНА");
        if (!ok) System.exit(1);
    }

    private static boolean report(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ок   " : "  НЕТ  ") + what + " — " + detail);
        return ok;
    }

    private static void frame(App app, Input in) {
        app.drawFrame();
        InputProbe.endFrame(in);
    }

    private static void press(App app, Input in, float x, float y) {
        move(app, in, x, y);
        InputProbe.move(in, x, y);
        InputProbe.button(in, GLFW_MOUSE_BUTTON_LEFT, true);
        frame(app, in);
    }

    private static void move(App app, Input in, float x, float y) {
        InputProbe.move(in, x, y);
        frame(app, in);
    }

    private static void release(App app, Input in, float x, float y) {
        InputProbe.move(in, x, y);
        InputProbe.button(in, GLFW_MOUSE_BUTTON_LEFT, false);
        frame(app, in);
    }
}
