package ru.dw.qeditor.util;

import ru.dw.qeditor.ui.MainFrame;
import ru.dw.qeditor.ui.theme.Theme;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Снимок окна редактора в PNG — чтобы сверять вид, не мешая работе за
 * компьютером: окно выводится за пределы экрана и рисуется программно,
 * поверх других программ ничего не всплывает.
 *
 * <p>Запуск: {@code java -cp out ru.dw.qeditor.util.UiProbe файл.png [вкладка.json] [номер раздела]}
 */
public final class UiProbe {

    private UiProbe() { }

    public static void main(String[] args) throws Exception {
        String out = args.length > 0 ? args[0] : "ui-probe.png";
        String tabFile = args.length > 1 ? args[1] : null;
        int tabIndex = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        int width = args.length > 3 ? Integer.parseInt(args[3]) : 1600;
        int height = args.length > 4 ? Integer.parseInt(args[4]) : 1000;
        // пятым аргументом "full" — снимок без боковых панелей, как в полном экране
        boolean chromeless = args.length > 5 && "full".equals(args[5]);

        Theme.install();

        final MainFrame[] holder = new MainFrame[1];
        SwingUtilities.invokeAndWait(() -> {
            MainFrame f = new MainFrame();
            f.setSize(width, height);
            f.setLocation(-6000, -6000);
            f.setVisible(true);
            holder[0] = f;
        });
        MainFrame frame = holder[0];

        if (tabFile != null) SwingUtilities.invokeAndWait(() -> frame.openFileForProbe(new File(tabFile)));
        SwingUtilities.invokeAndWait(() -> frame.selectTabForProbe(tabIndex));
        if (chromeless) SwingUtilities.invokeAndWait(() -> frame.setChromeForProbe(false));

        // дать таймерам, раскладке и первому кадру отработать
        Thread.sleep(1200);
        SwingUtilities.invokeAndWait(() -> {
            frame.validate();
            frame.repaint();
        });
        Thread.sleep(400);

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D g = img.createGraphics();
            frame.printAll(g);
            g.dispose();
        });

        ImageIO.write(img, "png", new File(out));
        System.out.println("Записано: " + new File(out).getAbsolutePath());

        SwingUtilities.invokeAndWait(frame::dispose);
        System.exit(0);
    }
}
