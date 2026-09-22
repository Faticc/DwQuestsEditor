package ru.dw.qeditor.render;

import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.Offscreen;
import ru.dw.qeditor.gl.Textures;
import ru.dw.qeditor.model.QuestTab;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/**
 * Вся вкладка одной большой картинкой — «плакат» карты квестов, как её видит
 * игрок, но без рамки окна и сразу целиком.
 *
 * <p>Кадровый буфер размером с плакат не создать: видеокарты держат текстуры
 * до 16 384 точек, а на тысяче квестов плакат выходит больше. Поэтому он
 * рисуется кусками по {@link #TILE} точек и склеивается в памяти.
 *
 * <p>Вызывать с главного потока и вне {@code batch.begin()/end()} текущего
 * кадра — пакет вершин один на всё окно.
 */
public final class TabPoster {

    private TabPoster() { }

    /** Кусок плаката: делится на 2, 3 и 4, чтобы край куска попадал на целый пиксель GUI. */
    private static final int TILE = 2040;
    /** Самая длинная сторона плаката — больше не открывает половина просмотрщиков. */
    public static final int MAX_SIDE = 32000;
    /** Не больше 150 млн точек: это 600 МБ картинки в памяти. */
    public static final long MAX_PIXELS = 150_000_000L;

    /** Что получилось: файл, размер и масштаб, на котором нарисовано. */
    public record Result(File file, int width, int height, float scale) { }

    /**
     * Масштаб, который реально выйдет при запрошенном: плакат урезается по
     * {@link #MAX_SIDE} и {@link #MAX_PIXELS}. Целый, пока это возможно, —
     * текстуры мода пиксельные и на дробном масштабе расползаются.
     */
    public static float fitScale(GamePreview preview, float wanted) {
        float[] b = preview.posterBounds();
        float w = b[2] - b[0], h = b[3] - b[1];
        float s = wanted;
        while (s > 1f && !fits(w * s, h * s)) s -= 1f;
        if (!fits(w * s, h * s)) {
            s = Math.min(MAX_SIDE / Math.max(w, h), (float) Math.sqrt(MAX_PIXELS / (w * h)));
        }
        return s;
    }

    private static boolean fits(float w, float h) {
        return w <= MAX_SIDE && h <= MAX_SIDE && (long) w * (long) h <= MAX_PIXELS;
    }

    /**
     * @param preview просмотр с нужной вкладкой и режимом прогресса
     * @param scale   точек картинки на пиксель GUI: 2 — как игра на guiScale 2,
     *                4 — иконки предметов в родном размере 64×64
     */
    public static Result render(GamePreview preview, Batch batch, File file, float scale) throws IOException {
        QuestTab tab = preview.getTab();
        if (tab == null || tab.quests.isEmpty()) throw new IOException("во вкладке нет квестов");

        scale = fitScale(preview, scale);
        float[] b = preview.posterBounds();
        int guiW = (int) Math.ceil(b[2] - b[0]);
        int guiH = (int) Math.ceil(b[3] - b[1]);
        int width = Math.max(1, Math.round(guiW * scale));
        int height = Math.max(1, Math.round(guiH * scale));

        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Offscreen target = Offscreen.create(TILE, TILE);
        try {
            // прогон вхолостую: иконки попадают в атлас во время рисования,
            // а уровни уменьшения у атласа досчитываются только между кадрами
            drawTile(preview, batch, target, b, guiW, guiH, 0, 0, (float) TILE / Math.max(guiW, guiH));
            for (int ty = 0; ty < height; ty += TILE) {
                for (int tx = 0; tx < width; tx += TILE) {
                    drawTile(preview, batch, target, b, guiW, guiH, tx, ty, scale);
                    BufferedImage part = target.read();
                    int w = Math.min(TILE, width - tx);
                    int h = Math.min(TILE, height - ty);
                    int[] row = part.getRGB(0, 0, w, h, null, 0, TILE);
                    img.setRGB(tx, ty, w, h, row, 0, TILE);
                }
            }
        } finally {
            target.unbind();
            target.dispose();
        }

        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("не создать папку " + dir);
        if (!ImageIO.write(img, "png", file)) throw new IOException("PNG не записался");
        return new Result(file, width, height, scale);
    }

    private static void drawTile(GamePreview preview, Batch batch, Offscreen target, float[] b,
                                 int guiW, int guiH, int tx, int ty, float scale) {
        Textures.refresh();
        target.bind(0xFF000000);
        batch.begin(TILE, TILE);
        GuiPainter p = GuiPainter.of(batch, scale, TILE, TILE);
        p.translate(-tx / scale, -ty / scale);
        preview.renderPoster(p, b[0], b[1], guiW, guiH);
        p.dispose();
        batch.end();
    }
}
