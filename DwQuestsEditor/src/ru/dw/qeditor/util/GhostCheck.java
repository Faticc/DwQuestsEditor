package ru.dw.qeditor.util;

import ru.dw.qeditor.ui.EditorCanvas;
import ru.dw.qeditor.ui.MainFrame;
import ru.dw.qeditor.ui.theme.Theme;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * Проверка, что поле редактора полностью перерисовывается.
 *
 * <p>Буфер канваса живёт между кадрами, поэтому если заливка фона не покрывает
 * его целиком, от прошлого кадра остаются «призраки»: смазанные подписи и сетка
 * при панораме и зуме. Тест уводит камеру в пустое место и требует, чтобы после
 * этого в буфере остался только цвет фона.
 *
 * <p>Запуск: {@code java -cp out ru.dw.qeditor.util.GhostCheck вкладка.json}
 */
public final class GhostCheck {

    private GhostCheck() { }

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            System.out.println("Укажите файл вкладки");
            return;
        }
        int width = 1400, height = 900;
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
        SwingUtilities.invokeAndWait(() -> frame.openFileForProbe(new File(args[0])));
        SwingUtilities.invokeAndWait(() -> frame.selectTabForProbe(0));
        Thread.sleep(900);

        EditorCanvas canvas = frame.canvasForProbe();
        // снимок ровно по размеру поля: за его границами буфер не при делах
        int cw = Math.max(1, canvas.getWidth());
        int ch = Math.max(1, canvas.getHeight());
        BufferedImage shot = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);

        // кадр с содержимым: вся вкладка на экране
        SwingUtilities.invokeAndWait(() -> {
            canvas.fitToContent();
            paint(canvas, shot);
        });

        // теперь уводим камеру далеко в пустоту и выключаем всё, кроме узлов:
        // в буфере не должно остаться ничего, кроме фона
        SwingUtilities.invokeAndWait(() -> {
            canvas.setShowGrid(false);
            canvas.setShowLinks(false);
            canvas.setShowNames(false);
            canvas.setZoom(0.9f);
            canvas.panToForProbe(500000f, 500000f);
            paint(canvas, shot);
        });

        Map<Integer, Integer> colors = new HashMap<>();
        for (int y = 0; y < ch; y += 2) {
            for (int x = 0; x < cw; x += 2) {
                colors.merge(shot.getRGB(x, y) & 0xFFFFFF, 1, Integer::sum);
            }
        }
        int total = colors.values().stream().mapToInt(Integer::intValue).sum();
        int background = colors.entrySet().stream()
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(0);
        int backgroundCount = colors.getOrDefault(background, 0);
        int strays = total - backgroundCount;

        System.out.printf("цветов в кадре: %d, фон #%06X занимает %d из %d точек%n",
                colors.size(), background, backgroundCount, total);
        if (strays == 0) {
            System.out.println("ПРОВЕРКА ПРОЙДЕНА: после ухода камеры остался только фон");
        } else {
            System.out.printf("ПРОВЕРКА НЕ ПРОЙДЕНА: осталось %d точек от прошлого кадра (%.1f%%)%n",
                    strays, 100.0 * strays / total);
        }

        SwingUtilities.invokeAndWait(frame::dispose);
        System.exit(strays == 0 ? 0 : 1);
    }

    private static void paint(EditorCanvas canvas, BufferedImage target) {
        Graphics2D g = target.createGraphics();
        canvas.paint(g);
        g.dispose();
    }
}
