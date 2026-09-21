package ru.dw.qeditor.util;

import ru.dw.qeditor.app.App;

import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Снимок окна редактора в PNG — чтобы сверять вид, не мешая работе за
 * компьютером: окно GLFW создаётся скрытым, кадр рисуется в кадровый буфер,
 * поверх других программ ничего не всплывает.
 *
 * <p>Раньше для этого окно Swing уводилось за пределы экрана и печаталось
 * через {@code printAll}. Теперь окно и так никому не видно.
 *
 * <p>Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.UiProbe файл.png
 * [вкладка.json] [номер раздела] [ширина] [высота] [full] [scroll]
 * [страница правой колонки]}
 */
public final class UiProbe {

    private UiProbe() { }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "ui-probe.png";
        String tabFile = args.length > 1 ? args[1] : null;
        int pageIndex = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int width = args.length > 3 ? Integer.parseInt(args[3]) : 1600;
        int height = args.length > 4 ? Integer.parseInt(args[4]) : 1000;
        // "full" — снимок без боковых панелей, как в полном экране
        boolean chromeless = args.length > 5 && "full".equals(args[5]);
        boolean scroll = args.length > 6 && "scroll".equals(args[6]);
        int sidebarPage = args.length > 7 ? Integer.parseInt(args[7]) : 0;

        try (GlProbe probe = GlProbe.open(width, height)) {
            App app = new App(probe.window(), probe.batch());
            if (tabFile != null) app.openFile(new File(tabFile));
            app.selectPage(pageIndex);
            if (chromeless) app.setChromeVisible(false);
            if (scroll) for (int i = 0; i < 5; i++) app.scrollPreviewTabs();
            app.selectSidebarPage(sidebarPage);
            // -Ddwq.probe.changes=другая.json — окно изменений: будто вкладку отредактировали до неё
            String changes = System.getProperty("dwq.probe.changes");
            if (changes != null) app.probeChanges(new File(changes));
            // -Ddwq.probe.bag=questID — окно пластикового пакета из наград этого квеста
            String bag = System.getProperty("dwq.probe.bag");
            if (bag != null) app.probeBag(bag);

            BufferedImage img = probe.frameSelf(app::drawFrame);
            if (img.getWidth() != width || img.getHeight() != height)
                System.out.println("Окно урезано до рабочей области экрана: "
                        + img.getWidth() + "x" + img.getHeight());
            probe.write(new File(out), img);
        }
    }
}
