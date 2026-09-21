package ru.dw.qeditor.util;

import ru.dw.qeditor.model.Defs;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Иконка приложения: тот же узел квеста, что редактор рисует на карте, —
 * значит, иконку рисует сама программа, а не художник.
 *
 * <p>Пишет .ico с несколькими размерами внутри: Windows берёт подходящий сам.
 * Начиная с Vista в .ico можно складывать готовые PNG, поэтому формат тут
 * простой — заголовок, таблица размеров и картинки подряд.
 *
 * <p>Запуск: {@code java -cp "out;lib/*" ru.dw.qeditor.util.AppIcon dist/DwQuestsEditor.ico}
 */
public final class AppIcon {

    /** Размеры, которые кладём в .ico. */
    private static final int[] SIZES = {256, 128, 64, 48, 32, 16};

    private AppIcon() { }

    public static void main(String[] args) throws IOException {
        File out = new File(args.length > 0 ? args[0] : "DwQuestsEditor.ico");
        if (out.getParentFile() != null) {
            //noinspection ResultOfMethodCallIgnored
            out.getParentFile().mkdirs();
        }

        BufferedImage big = render(256);
        List<byte[]> pngs = new ArrayList<>();
        for (int size : SIZES) pngs.add(png(scale(big, size)));
        write(out, pngs);

        File preview = new File(out.getParentFile(), "app-icon.png");
        ImageIO.write(big, "png", preview);
        System.out.println("Записано: " + out.getAbsolutePath()
                + " (" + out.length() / 1024 + " КБ), рядом " + preview.getName());
    }

    /** Кадр с узлом квеста на тёмной плитке. */
    private static BufferedImage render(int size) {
        try (GlProbe probe = GlProbe.open()) {
            // рисуем в координатах 64×64 — так проще расставить, а размер даёт масштаб
            float unit = 64f;
            return probe.frame(size, size, size / unit, p -> {
                p.roundRect(0, 0, unit, unit, 14, 0xFF141A22, 0xFF2C5364, 1.5f);
                // узел занимает середину плитки; кадр анимации фиксирован
                p.questStar(20, 20, 24, 24, 1f, Defs.Star.of(Defs.Rarity.LEGENDARY),
                        Defs.NodeType.NORMAL, "minecraft:book", 7);
            });
        }
    }

    private static BufferedImage scale(BufferedImage src, int size) {
        if (src.getWidth() == size) return src;
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, size, size, null);
        g.dispose();
        return out;
    }

    private static byte[] png(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static void write(File file, List<byte[]> pngs) throws IOException {
        try (OutputStream raw = Files.newOutputStream(file.toPath());
             DataOutputStream out = new DataOutputStream(raw)) {
            le16(out, 0);                 // reserved
            le16(out, 1);                 // тип: иконка
            le16(out, pngs.size());

            int offset = 6 + 16 * pngs.size();
            for (int i = 0; i < pngs.size(); i++) {
                int size = SIZES[i];
                out.writeByte(size >= 256 ? 0 : size);   // 0 означает 256
                out.writeByte(size >= 256 ? 0 : size);
                out.writeByte(0);         // палитры нет
                out.writeByte(0);         // reserved
                le16(out, 1);             // плоскостей
                le16(out, 32);            // бит на точку
                le32(out, pngs.get(i).length);
                le32(out, offset);
                offset += pngs.get(i).length;
            }
            for (byte[] png : pngs) out.write(png);
        }
    }

    private static void le16(DataOutputStream out, int v) throws IOException {
        out.writeByte(v & 0xFF);
        out.writeByte((v >> 8) & 0xFF);
    }

    private static void le32(DataOutputStream out, int v) throws IOException {
        le16(out, v & 0xFFFF);
        le16(out, (v >>> 16) & 0xFFFF);
    }
}
