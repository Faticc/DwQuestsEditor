package ru.dw.qeditor.util;

import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.GlWindow;
import ru.dw.qeditor.gl.Offscreen;
import ru.dw.qeditor.gl.Textures;
import ru.dw.qeditor.render.GuiPainter;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.function.Consumer;

/**
 * Обвязка для отладочных рендеров: скрытое окно с контекстом OpenGL и кадровый
 * буфер в картинку. Проверки вида по-прежнему ничего не открывают поверх того,
 * что у человека на экране, — просто теперь рисует видеокарта.
 */
public final class GlProbe implements AutoCloseable {

    private final GlWindow window;
    private final Batch batch;

    private GlProbe(GlWindow window, Batch batch) {
        this.window = window;
        this.batch = batch;
    }

    public static GlProbe open() { return open(64, 64); }

    /**
     * Окно нужного размера. Скрытое, но размер важен: интерфейс редактора
     * спрашивает его у окна, а не у кадрового буфера.
     */
    public static GlProbe open(int width, int height) {
        GlWindow w = new GlWindow("probe", width, height, false);
        return new GlProbe(w, new Batch());
    }

    public Batch batch() { return batch; }

    public GlWindow window() { return window; }

    /**
     * Кадр окна целиком. Размер берётся у самого окна, а не запрашивается:
     * система может урезать окно до рабочей области экрана (1920×1080 легко
     * превращается в 1920×1061), а интерфейс считает раскладку и отсечение
     * именно по размеру окна — снимок другого размера сдвинул бы отсечение
     * и срезал бы текст в полях.
     */
    public BufferedImage frameSelf(Runnable body) {
        return frameSelf(window.width(), window.height(), body);
    }

    /**
     * Кадр, который рисует себя сам — например, окно редактора: и пакет, и
     * рисователь у него свои. Рисуется дважды по той же причине, что и
     * {@link #frame}: иконки могли попасть в атлас уже во время кадра.
     */
    public BufferedImage frameSelf(int width, int height, Runnable body) {
        Offscreen target = Offscreen.create(width, height);
        try {
            for (int pass = 0; pass < 2; pass++) {
                Textures.refresh();
                target.bind(0xFF000000);
                body.run();
            }
            BufferedImage img = target.read();
            target.unbind();
            return img;
        } finally {
            target.dispose();
        }
    }

    /** Нарисовать кадр заданного размера и вернуть картинку. */
    public BufferedImage frame(int width, int height, float guiScale, Consumer<GuiPainter> body) {
        Offscreen target = Offscreen.create(width, height);
        try {
            Textures.refresh();
            target.bind(0xFF000000);
            batch.begin(width, height);
            GuiPainter p = GuiPainter.of(batch, guiScale, width, height);
            body.accept(p);
            p.dispose();
            batch.end();
            // атлас иконок мог пополниться прямо во время кадра: тогда уровни
            // уменьшения ещё не посчитаны, и кадр надо повторить
            Textures.refresh();
            target.bind(0xFF000000);
            batch.begin(width, height);
            GuiPainter p2 = GuiPainter.of(batch, guiScale, width, height);
            body.accept(p2);
            p2.dispose();
            batch.end();
            BufferedImage img = target.read();
            target.unbind();
            return img;
        } finally {
            target.dispose();
        }
    }

    /**
     * Дождаться, пока видеокарта действительно закончит кадр. Без этого замер
     * времени бессмыслен: вызовы отрисовки только ставятся в очередь.
     */
    public void sync() { org.lwjgl.opengl.GL33C.glFinish(); }

    public void write(File file, BufferedImage img) {
        try {
            ImageIO.write(img, "png", file);
            System.out.println("Записано: " + file.getAbsolutePath());
        } catch (Exception e) {
            System.out.println("Не записалось: " + e.getMessage());
        }
    }

    @Override
    public void close() {
        batch.dispose();
        window.dispose();
        GlWindow.terminate();
    }
}
