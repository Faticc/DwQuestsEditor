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
 * игрок, но без рамки окна и сразу целиком. Либо то же анимацией в GIF.
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

    /**
     * Круг анимации. Узел — 32 кадра на 32 тика ({@code GuiPainter.node}),
     * колыхание связей — sin(t / 8), круг 16π ≈ 50,3 тика. Кратного обоим целого
     * нет, поэтому берётся 64 тика (узлы проходят круг дважды), а колыхание
     * чуть замедляется, чтобы уложиться в один свой круг ровно, — иначе на
     * стыке гифки связи дёргались бы.
     */
    public static final int GIF_FRAMES = 64;
    /** 20 тиков в секунду, как в игре: задержка кадра GIF — в сотых секунды. */
    private static final int GIF_DELAY_CS = 5;
    /** GIF — это 64 полных кадра: больше 2400 точек по стороне весит уже десятки мегабайт. */
    public static final int GIF_MAX_SIDE = 2400;
    public static final long GIF_MAX_PIXELS = 4_000_000L;

    /** Что получилось: файл, размер и масштаб, на котором нарисовано. */
    public record Result(File file, int width, int height, float scale) { }

    /**
     * Масштаб, который реально выйдет при запрошенном: плакат урезается по
     * {@link #MAX_SIDE} и {@link #MAX_PIXELS}. Целый, пока это возможно, —
     * текстуры мода пиксельные и на дробном масштабе расползаются.
     */
    public static float fitScale(GamePreview preview, float wanted) {
        return fitScale(preview, wanted, MAX_SIDE, MAX_PIXELS);
    }

    /** То же для анимации — там пределы свои, см. {@link #GIF_MAX_SIDE}. */
    public static float fitGifScale(GamePreview preview, float wanted) {
        return fitScale(preview, wanted, GIF_MAX_SIDE, GIF_MAX_PIXELS);
    }

    private static float fitScale(GamePreview preview, float wanted, int maxSide, long maxPixels) {
        float[] b = preview.posterBounds();
        float w = b[2] - b[0], h = b[3] - b[1];
        float s = wanted;
        while (s > 1f && !fits(w * s, h * s, maxSide, maxPixels)) s -= 1f;
        if (!fits(w * s, h * s, maxSide, maxPixels)) {
            s = Math.min(maxSide / Math.max(w, h), (float) Math.sqrt(maxPixels / (w * h)));
        }
        return s;
    }

    private static boolean fits(float w, float h, int maxSide, long maxPixels) {
        return w <= maxSide && h <= maxSide && (long) w * (long) h <= maxPixels;
    }

    /**
     * @param preview просмотр с нужной вкладкой и режимом прогресса
     * @param scale   точек картинки на пиксель GUI: 2 — как игра на guiScale 2,
     *                4 — иконки предметов в родном размере 64×64
     */
    public static Result render(GamePreview preview, Batch batch, File file, float scale) throws IOException {
        check(preview);
        scale = fitScale(preview, scale);
        try (Frames frames = new Frames(preview, batch, scale)) {
            BufferedImage img = frames.draw(0, 0f);
            write(file, () -> {
                if (!ImageIO.write(img, "png", file)) throw new IOException("PNG не записался");
            });
            return new Result(file, frames.width, frames.height, scale);
        }
    }

    /** Круг анимации узлов и связей в GIF — {@link #GIF_FRAMES} кадров. */
    public static Result renderGif(GamePreview preview, Batch batch, File file, float scale) throws IOException {
        check(preview);
        scale = fitGifScale(preview, scale);
        try (Frames frames = new Frames(preview, batch, scale)) {
            float linkPeriod = (float) (16 * Math.PI);
            write(file, () -> GifWriter.write(file, GIF_FRAMES, GIF_DELAY_CS,
                    i -> frames.draw(i, i * linkPeriod / GIF_FRAMES)));
            return new Result(file, frames.width, frames.height, scale);
        }
    }

    private static void check(GamePreview preview) throws IOException {
        QuestTab tab = preview.getTab();
        if (tab == null || tab.quests.isEmpty()) throw new IOException("во вкладке нет квестов");
    }

    private interface IoAction { void run() throws IOException; }

    private static void write(File file, IoAction body) throws IOException {
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs())
            throw new IOException("не создать папку " + dir);
        body.run();
    }

    /** Кадры плаката одного размера: кадровый буфер под куски живёт, пока рисуем. */
    private static final class Frames implements AutoCloseable {
        final GamePreview preview;
        final Batch batch;
        final float scale;
        final float[] bounds;
        final int guiW, guiH, width, height;
        final Offscreen target = Offscreen.create(TILE, TILE);

        Frames(GamePreview preview, Batch batch, float scale) {
            this.preview = preview;
            this.batch = batch;
            this.scale = scale;
            bounds = preview.posterBounds();
            guiW = (int) Math.ceil(bounds[2] - bounds[0]);
            guiH = (int) Math.ceil(bounds[3] - bounds[1]);
            width = Math.max(1, Math.round(guiW * scale));
            height = Math.max(1, Math.round(guiH * scale));
            // прогон вхолостую: иконки попадают в атлас во время рисования,
            // а уровни уменьшения у атласа досчитываются только между кадрами
            drawTile(0, 0, (float) TILE / Math.max(guiW, guiH), 0, 0f);
        }

        BufferedImage draw(int frameTick, float linkTick) {
            BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            for (int ty = 0; ty < height; ty += TILE) {
                for (int tx = 0; tx < width; tx += TILE) {
                    drawTile(tx, ty, scale, frameTick, linkTick);
                    BufferedImage part = target.read();
                    int w = Math.min(TILE, width - tx);
                    int h = Math.min(TILE, height - ty);
                    int[] row = part.getRGB(0, 0, w, h, null, 0, TILE);
                    img.setRGB(tx, ty, w, h, row, 0, TILE);
                }
            }
            return img;
        }

        private void drawTile(int tx, int ty, float s, int frameTick, float linkTick) {
            Textures.refresh();
            target.bind(0xFF000000);
            batch.begin(TILE, TILE);
            GuiPainter p = GuiPainter.of(batch, s, TILE, TILE);
            p.translate(-tx / s, -ty / s);
            preview.renderPoster(p, bounds[0], bounds[1], guiW, guiH, frameTick, linkTick);
            p.dispose();
            batch.end();
        }

        @Override
        public void close() {
            target.unbind();
            target.dispose();
        }
    }
}
