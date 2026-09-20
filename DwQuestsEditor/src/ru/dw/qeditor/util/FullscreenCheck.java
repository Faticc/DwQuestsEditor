package ru.dw.qeditor.util;

import ru.dw.qeditor.ui.MainFrame;
import ru.dw.qeditor.ui.theme.Theme;

import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import java.io.File;

/**
 * Проверка полноэкранного режима: туда и обратно, с возвратом прежних размеров.
 *
 * <p>Запускается с {@code -Ddwq.nofullscreen=true}, чтобы не перехватывать
 * настоящий экран — проверяется сама механика: окно пересоздаётся без рамки,
 * панели прячутся и возвращаются, размеры восстанавливаются.
 *
 * <p>Запуск: {@code java -Ddwq.nofullscreen=true -cp out ru.dw.qeditor.util.FullscreenCheck вкладка.json}
 */
public final class FullscreenCheck {

    private FullscreenCheck() { }

    public static void main(String[] args) throws Exception {
        Theme.install();
        int width = 1400, height = 900;

        final MainFrame[] holder = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame f = new MainFrame();
            f.setSize(width, height);
            f.setLocation(-6000, -6000);
            f.setVisible(true);
            holder[0] = f;
        });
        MainFrame frame = holder[0];
        if (args.length > 0) {
            SwingUtilities.invokeAndWait(() -> frame.openFileForProbe(new File(args[0])));
        }
        Thread.sleep(600);

        Rectangle before = frame.getBounds();
        boolean menuBefore = frame.getJMenuBar() != null;

        SwingUtilities.invokeAndWait(frame::toggleFullscreen);
        Thread.sleep(500);
        boolean undecorated = frame.isUndecorated();
        boolean menuHidden = frame.getJMenuBar() == null;

        SwingUtilities.invokeAndWait(frame::toggleFullscreen);
        Thread.sleep(500);
        Rectangle after = frame.getBounds();
        boolean decorated = !frame.isUndecorated();
        boolean menuBack = frame.getJMenuBar() != null;
        boolean sizeBack = after.width == before.width && after.height == before.height;

        report("меню было видно", menuBefore);
        report("в режиме окно без рамки", undecorated);
        report("в режиме меню спрятано", menuHidden);
        report("после выхода рамка вернулась", decorated);
        report("после выхода меню вернулось", menuBack);
        report("после выхода размер прежний", sizeBack);

        boolean ok = menuBefore && undecorated && menuHidden && decorated && menuBack && sizeBack;
        System.out.println(ok ? "ПРОВЕРКА ПРОЙДЕНА" : "ПРОВЕРКА НЕ ПРОЙДЕНА");

        SwingUtilities.invokeAndWait(frame::dispose);
        System.exit(ok ? 0 : 1);
    }

    private static void report(String what, boolean ok) {
        System.out.println((ok ? "  ок   " : "  нет  ") + what);
    }
}
