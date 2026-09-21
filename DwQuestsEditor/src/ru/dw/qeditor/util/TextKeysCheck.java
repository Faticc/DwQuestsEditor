package ru.dw.qeditor.util;

import ru.dw.qeditor.app.App;
import ru.dw.qeditor.app.EditorModel;
import ru.dw.qeditor.gl.Input;
import ru.dw.qeditor.gl.InputProbe;
import ru.dw.qeditor.gui.TextBuf;
import ru.dw.qeditor.gui.Ui;

import java.io.File;
import java.lang.reflect.Field;

import static org.lwjgl.glfw.GLFW.*;

/**
 * Проверка клавиш в поле ввода: Ctrl+A/C/V/Z/Y в фокусе поля работают с
 * текстом, а не с квестами.
 *
 * <p>Раньше глобальные горячие клавиши срабатывали поверх поля: Ctrl+C клал в
 * буфер JSON выделенных квестов вместо выделенного текста, Ctrl+A выделял все
 * квесты, а Ctrl+V вдобавок к тексту вставлял квесты на карту.
 *
 * <p>Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.TextKeysCheck вкладка.json}
 */
public final class TextKeysCheck {

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "testquests/Магия.json";
        boolean ok = true;

        try (GlProbe probe = GlProbe.open(1400, 900)) {
            App app = new App(probe.window(), probe.batch());
            app.openFile(new File(file));
            app.selectPage(0);
            app.selectSidebarPage(0);
            Input in = probe.window().input();
            EditorModel model = (EditorModel) get(app, App.class, "model");
            Ui ui = (Ui) get(app, App.class, "ui");
            String questID = model.tab().quests.keySet().iterator().next();
            model.select(questID);
            frame(app, in);
            int quests = model.tab().quests.size();

            // поле ищем щелчками по боковой панели сверху вниз
            Object id = null;
            float sx = probe.window().width() - 200;
            for (float y = 40; y < probe.window().height() - 40 && id == null; y += 6) {
                click(app, in, sx, y);
                if (ui.anyFocus() && ui.keyboardCaptured()) id = get(ui, Ui.class, "focus");
            }
            if (id == null) {
                System.out.println("В боковой панели не нашлось поля ввода");
                System.exit(1);
            }
            TextBuf b = ui.state(id, TextBuf::new);
            String original = b.text;
            System.out.println("  поле: " + id + " = «" + original + "»");

            int steps = undoSteps(model);
            for (String c : new String[]{"x", "y", "z"}) {
                InputProbe.type(in, c);
                frame(app, in);
            }
            ok &= report("набор попадает в поле", b.text.length() == original.length() + 3, b.text);
            ok &= report("набор — один шаг отмены", undoSteps(model) == steps + 1,
                    "шагов: " + steps + " → " + undoSteps(model));

            ctrl(app, in, GLFW_KEY_A);
            ok &= report("Ctrl+A выделяет текст поля", b.selectedText().equals(b.text), b.selectedText());
            ok &= report("Ctrl+A не выделяет квесты", model.selection().size() == 1,
                    "выделено квестов: " + model.selection().size());

            ctrl(app, in, GLFW_KEY_C);
            String clip = probe.window().clipboard();
            ok &= report("Ctrl+C копирует текст поля", b.text.equals(clip), "в буфере: " + shorten(clip));

            ctrl(app, in, GLFW_KEY_V);
            ok &= report("Ctrl+V не вставляет квесты", model.tab().quests.size() == quests,
                    "квестов: " + quests + " → " + model.tab().quests.size());
            ok &= report("Ctrl+V вставляет текст", b.text.equals(clip), b.text);

            ctrl(app, in, GLFW_KEY_Z);
            ctrl(app, in, GLFW_KEY_Z);
            ok &= report("Ctrl+Z отменяет набор в поле", b.text.equals(original), b.text);
            ctrl(app, in, GLFW_KEY_Y);
            ok &= report("Ctrl+Y возвращает набор", b.text.length() == original.length() + 3, b.text);
            ctrl(app, in, GLFW_KEY_Z);

            // вне поля те же клавиши снова про квесты
            click(app, in, 300, 450);
            Thread.sleep(450);
            ctrl(app, in, GLFW_KEY_A);
            ok &= report("вне поля Ctrl+A выделяет все квесты", model.selection().size() == quests,
                    "выделено: " + model.selection().size() + " из " + quests);
        }

        System.out.println(ok ? "ПРОВЕРКА ПРОЙДЕНА" : "ПРОВЕРКА НЕ ПРОЙДЕНА");
        if (!ok) System.exit(1);
    }

    private static String shorten(String s) {
        if (s == null) return "null";
        s = s.replace('\n', ' ');
        return s.length() > 60 ? s.substring(0, 60) + "…" : s;
    }

    private static int undoSteps(EditorModel model) throws Exception {
        return ((java.util.Collection<?>) get(model.history(), ru.dw.qeditor.app.History.class, "undo")).size();
    }

    private static Object get(Object o, Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static boolean report(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ок   " : "  НЕТ  ") + what + " — " + detail);
        return ok;
    }

    private static void frame(App app, Input in) {
        app.drawFrame();
        InputProbe.endFrame(in);
    }

    private static void click(App app, Input in, float x, float y) {
        InputProbe.move(in, x, y);
        frame(app, in);
        InputProbe.button(in, GLFW_MOUSE_BUTTON_LEFT, true);
        frame(app, in);
        InputProbe.button(in, GLFW_MOUSE_BUTTON_LEFT, false);
        frame(app, in);
    }

    private static void ctrl(App app, Input in, int key) {
        InputProbe.key(in, key, true, GLFW_MOD_CONTROL);
        frame(app, in);
        InputProbe.key(in, key, false, GLFW_MOD_CONTROL);
        frame(app, in);
    }
}
