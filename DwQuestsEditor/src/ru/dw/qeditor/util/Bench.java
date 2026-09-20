package ru.dw.qeditor.util;

import ru.dw.qeditor.ui.MainFrame;
import ru.dw.qeditor.ui.theme.Theme;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

/**
 * Замер скорости отрисовки на большой вкладке — чтобы правки в рендере
 * проверялись числами, а не на глаз.
 *
 * <p>Запуск: {@code java -cp out ru.dw.qeditor.util.Bench вкладка.json [кадров]}
 */
public final class Bench {

    private Bench() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Укажите файл вкладки");
            return;
        }
        File tab = new File(args[0]);
        int frames = args.length > 1 ? Integer.parseInt(args[1]) : 12;
        int width = args.length > 2 ? Integer.parseInt(args[2]) : 1600;
        int height = args.length > 3 ? Integer.parseInt(args[3]) : 1000;

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
        SwingUtilities.invokeAndWait(() -> frame.openFileForProbe(tab));
        SwingUtilities.invokeAndWait(() -> frame.setChromeForProbe(false));
        Thread.sleep(800);

        boolean fast = Boolean.getBoolean("dwq.fast");
        if (fast) SwingUtilities.invokeAndWait(() -> frame.setFastModeForProbe(true));
        for (int page = 0; page <= 1; page++) {
            final int index = page;
            SwingUtilities.invokeAndWait(() -> frame.selectTabForProbe(index));
            Thread.sleep(500);

            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            // прогрев: первый кадр наполняет кэши картинок
            paint(frame, img, false);

            long start = System.nanoTime();
            for (int i = 0; i < frames; i++) paint(frame, img, false);
            long whole = (System.nanoTime() - start) / 1_000_000;

            paint(frame, img, true);
            start = System.nanoTime();
            for (int i = 0; i < frames; i++) paint(frame, img, true);
            long pageMs = (System.nanoTime() - start) / 1_000_000;

            System.out.printf("%-10s окно %.1f мс/кадр  •  только поле %.1f мс/кадр (%.0f к/с)%n",
                    index == 0 ? "Редактор:" : "Просмотр:",
                    whole / (double) frames, pageMs / (double) frames,
                    1000.0 * frames / Math.max(1, pageMs));
        }

        SwingUtilities.invokeAndWait(frame::dispose);
        System.exit(0);
    }

    private static void paint(MainFrame frame, BufferedImage img, boolean pageOnly) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Graphics2D g = img.createGraphics();
            if (pageOnly) frame.pageForProbe().paint(g);
            else frame.printAll(g);
            g.dispose();
        });
    }
}
