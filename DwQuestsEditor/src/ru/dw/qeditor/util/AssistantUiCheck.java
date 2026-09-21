package ru.dw.qeditor.util;

import ru.dw.qeditor.app.App;
import ru.dw.qeditor.app.AssistantView;
import ru.dw.qeditor.app.EditorModel;
import ru.dw.qeditor.gl.InputProbe;
import ru.dw.qeditor.model.Quest;

import java.io.File;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Живая проверка помощника через окно редактора: запрос уходит кнопкой
 * страницы помощника, ответ применяется в модель, а второе сообщение
 * продолжает тот же разговор. Стоит денег — в общие проверки не входит.
 *
 * <p>Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.AssistantUiCheck [вкладка.json] [модель]}
 */
public final class AssistantUiCheck {

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "testquests/Магия.json";
        String modelName = args.length > 1 ? args[1] : "haiku";
        boolean ok = true;

        try (GlProbe probe = GlProbe.open(1600, 1000)) {
            App app = new App(probe.window(), probe.batch());
            app.openFile(new File(file));
            app.selectPage(3);
            EditorModel model = (EditorModel) get(app, App.class, "model");
            AssistantView view = (AssistantView) get(app, App.class, "assistant");
            set(view, "modelIndex", indexOf((String[]) get(view, AssistantView.class, "MODELS"), modelName));
            List<String> ids = new ArrayList<>(model.tab().quests.keySet());
            String target = ids.get(2);
            model.select(target);
            int before = model.tab().quests.size();

            // 1. правка выделенного квеста
            ask(app, probe, view, "Поставь выделенному квесту displayName «Живая проверка».");
            System.out.println(get(view, AssistantView.class, "status"));
            call(view, "applyPending");
            Quest q = model.tab().quests.get(target);
            ok &= report("правка применилась в модель", q != null && "Живая проверка".equals(q.displayName),
                    q == null ? "нет квеста" : q.displayName);
            ok &= report("остальные квесты на месте", model.tab().quests.size() == before,
                    before + " → " + model.tab().quests.size());

            // 2. продолжение: «его» понятно только из прошлого сообщения
            model.clearSelection();
            ask(app, probe, view, "А теперь поменяй тому же квесту rarity на LEGENDARY.");
            System.out.println(get(view, AssistantView.class, "status"));
            call(view, "applyPending");
            q = model.tab().quests.get(target);
            ok &= report("продолжение разговора понимает «тому же квесту»",
                    q != null && q.rarity.name().equals("LEGENDARY"), q == null ? "нет" : q.rarity.name());
            System.out.println("---- журнал ----\n" + get(view, AssistantView.class, "journal"));
        }
        System.out.println(ok ? "ПРОВЕРКА ПРОЙДЕНА" : "ПРОВЕРКА НЕ ПРОЙДЕНА");
        if (!ok) System.exit(1);
    }

    private static void ask(App app, GlProbe probe, AssistantView view, String text) throws Exception {
        set(view, "request", text);
        call(view, "send");
        long until = System.currentTimeMillis() + 300_000;
        do {
            app.drawFrame();
            InputProbe.endFrame(probe.window().input());
            Thread.sleep(100);
        } while (((Boolean) get(view, AssistantView.class, "busy") || get(view, AssistantView.class, "result") != null)
                && System.currentTimeMillis() < until);
    }

    private static int indexOf(String[] a, String s) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(s)) return i;
        return 0;
    }

    private static boolean report(String what, boolean ok, String detail) {
        System.out.println((ok ? "  ок   " : "  НЕТ  ") + what + " — " + detail);
        return ok;
    }

    private static void call(Object o, String name) throws Exception {
        Method m = o.getClass().getDeclaredMethod(name);
        m.setAccessible(true);
        m.invoke(o);
    }

    private static Object get(Object o, Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f.get(o);
    }

    private static void set(Object o, String name, Object v) throws Exception {
        Field f = o.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(o, v);
    }
}
