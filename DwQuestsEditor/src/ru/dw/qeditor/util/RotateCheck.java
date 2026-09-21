package ru.dw.qeditor.util;

import ru.dw.qeditor.app.App;
import ru.dw.qeditor.app.EditorModel;
import ru.dw.qeditor.app.GroupRotate;
import ru.dw.qeditor.app.MapView;
import ru.dw.qeditor.gl.Input;
import ru.dw.qeditor.gl.InputProbe;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.model.QuestTab;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

import static org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOD_SHIFT;
import static org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_LEFT;

/**
 * Проверка поворота группы за ручку: фигура поворачивается вокруг центра рамки,
 * координаты ложатся на сотые клетки, связи не меняются, а отмена возвращает
 * всё одним шагом.
 *
 * <p>Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.RotateCheck вкладка.json [снимок.png]}
 */
public final class RotateCheck {

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "testquests/Начало.json";
        String shot = args.length > 1 ? args[1] : null;
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
            if (model.tab().quests.size() < 2) {
                System.out.println("Во вкладке меньше двух квестов");
                return;
            }
            String linksBefore = links(model.tab());
            String jsonBefore = model.tab().toJsonString();

            model.selectAll();
            map.fitToContent();
            frame(app, in);
            frame(app, in);

            Method m = MapView.class.getDeclaredMethod("rotateHandle");
            m.setAccessible(true);
            float[] h = (float[]) m.invoke(map);
            float[] area = map.area();
            float hx = area[0] + h[0], hy = area[1] + h[1];
            // центр поворота — середина рамки по центрам узлов; ручка над ним
            float cx = area[0] + (h[2] + h[4]) / 2f, cy = area[1] + (h[3] + h[5]) / 2f;
            float rad = cy - hy;

            GroupRotate expected = new GroupRotate(QuestTab.parse(jsonBefore), model.selection());

            // ручку ведём по дуге на четверть оборота, с Shift угол липнет к 15°
            press(app, in, hx, hy);
            InputProbe.key(in, GLFW_KEY_LEFT_SHIFT, true, GLFW_MOD_SHIFT);
            for (int i = 1; i <= 9; i++) {
                double a = -Math.PI / 2 + Math.PI / 2 * i / 9.0;
                move(app, in, (float) (cx + rad * Math.cos(a)) + 1, (float) (cy + rad * Math.sin(a)) + 1);
            }
            if (shot != null) probe.write(new File(shot), probe.frameSelf(app::drawFrame));
            // отпускаем, не отпуская Shift: иначе последний кадр снимет привязку угла
            release(app, in, cx + rad + 1, cy + 1);
            InputProbe.key(in, GLFW_KEY_LEFT_SHIFT, false, 0);

            QuestTab want = QuestTab.parse(jsonBefore);
            expected.apply(want, 90);
            boolean same = true, hundredths = true;
            for (Quest q : model.tab().quests.values()) {
                Quest w = want.quests.get(q.questID);
                if (Math.abs(w.displayX - q.displayX) > 1e-4 || Math.abs(w.displayY - q.displayY) > 1e-4) same = false;
                if (Math.abs(q.displayX * 100 - Math.rint(q.displayX * 100)) > 1e-3
                        || Math.abs(q.displayY * 100 - Math.rint(q.displayY * 100)) > 1e-3) hundredths = false;
            }
            Quest w0 = want.quests.values().iterator().next();
            ok &= report("фигура повёрнута на 90° вокруг центра", same, sample(model.tab())
                    + ", ждали (" + w0.displayX + ", " + w0.displayY + ")");
            ok &= report("координаты — сотые клетки", hundredths, "");
            ok &= report("связи не изменились", linksBefore.equals(links(model.tab())), "");

            model.undo();
            ok &= report("отмена возвращает всё одним шагом",
                    jsonBefore.equals(model.tab().toJsonString()), "");
        }

        System.out.println(ok ? "ПРОВЕРКА ПРОЙДЕНА" : "ПРОВЕРКА НЕ ПРОЙДЕНА");
        if (!ok) System.exit(1);
    }

    private static String links(QuestTab tab) {
        StringBuilder sb = new StringBuilder();
        for (Quest q : tab.quests.values())
            sb.append(q.questID).append(':').append(q.toJson().get("parents")).append('\n');
        return sb.toString();
    }

    private static String sample(QuestTab tab) {
        Quest q = tab.quests.values().iterator().next();
        return q.questID + " (" + q.displayX + ", " + q.displayY + ")";
    }

    private static boolean report(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ок   " : "  НЕТ  ") + what + (detail.isEmpty() ? "" : " — " + detail));
        return ok;
    }

    private static void frame(App app, Input in) {
        app.drawFrame();
        InputProbe.endFrame(in);
    }

    private static void press(App app, Input in, float x, float y) {
        move(app, in, x, y);
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
