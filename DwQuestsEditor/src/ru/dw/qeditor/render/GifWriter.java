package ru.dw.qeditor.render;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferByte;
import java.awt.image.IndexColorModel;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Зацикленный GIF из кадров плаката.
 *
 * <p>Своя палитра, а не та, что подбирает ImageIO: он строит её заново на
 * каждый кадр, и неподвижный фон туманности от кадра к кадру мигал бы другими
 * оттенками. Здесь палитра одна на всю анимацию, туманность — с упорядоченным
 * размытием (Байер 4×4): от кадра к кадру оно не меняется, поэтому не рябит.
 *
 * <p>Точки, которые с прошлого кадра не поменялись, пишутся прозрачными — фон
 * стоит на месте, двигаются только узлы и связи, и LZW жмёт такие кадры в
 * разы лучше полных.
 */
final class GifWriter {

    private GifWriter() { }

    /** Последний цвет палитры отдан под «не изменилось». */
    private static final int TRANSPARENT = 255;
    private static final int COLORS = 255;

    private static final int[] BAYER = {0, 8, 2, 10, 12, 4, 14, 6, 3, 11, 1, 9, 15, 7, 13, 5};
    /** Размах размытия — примерно шаг палитры в тёмных тонах туманности. */
    private static final float DITHER = 10f;

    static void write(File file, int count, int delayCs, IntFunction<BufferedImage> frames) throws IOException {
        BufferedImage first = frames.apply(0);
        int w = first.getWidth(), h = first.getHeight();

        // палитра по нескольким кадрам — узлы в разных фазах светятся по-разному
        List<int[]> samples = new ArrayList<>();
        samples.add(pixels(first));
        BufferedImage[] kept = new BufferedImage[count];
        kept[0] = first;
        for (int i = count / 4; i < count; i += count / 4) {
            kept[i] = frames.apply(i);
            samples.add(pixels(kept[i]));
        }
        Palette palette = Palette.build(samples, COLORS);

        ImageWriter writer = ImageIO.getImageWritersBySuffix("gif").next();
        file.delete();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(file)) {
            writer.setOutput(out);
            writer.prepareWriteSequence(null);
            byte[] prev = null;
            for (int i = 0; i < count; i++) {
                BufferedImage frame = kept[i] != null ? kept[i] : frames.apply(i);
                kept[i] = null;
                byte[] idx = palette.map(pixels(frame), w, h);
                byte[] outIdx = idx;
                if (prev != null) {
                    outIdx = idx.clone();
                    for (int k = 0; k < idx.length; k++) if (idx[k] == prev[k]) outIdx[k] = (byte) TRANSPARENT;
                }
                prev = idx;

                BufferedImage img = palette.image(outIdx, w, h);
                IIOMetadata meta = metadata(writer, img, delayCs, i == 0);
                writer.writeToSequence(new IIOImage(img, null, meta), null);
            }
            writer.endWriteSequence();
        } finally {
            writer.dispose();
        }
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    private static IIOMetadata metadata(ImageWriter writer, BufferedImage img, int delayCs, boolean first)
            throws IOException {
        ImageWriteParam param = writer.getDefaultWriteParam();
        IIOMetadata meta = writer.getDefaultImageMetadata(new ImageTypeSpecifier(img), param);
        String format = meta.getNativeMetadataFormatName();
        IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);

        IIOMetadataNode gce = child(root, "GraphicControlExtension");
        // кадр ложится поверх прошлого: прозрачное — это «оставить как было»
        gce.setAttribute("disposalMethod", "doNotDispose");
        gce.setAttribute("userInputFlag", "FALSE");
        gce.setAttribute("transparentColorFlag", first ? "FALSE" : "TRUE");
        gce.setAttribute("delayTime", Integer.toString(delayCs));
        gce.setAttribute("transparentColorIndex", Integer.toString(TRANSPARENT));

        if (first) {
            IIOMetadataNode apps = child(root, "ApplicationExtensions");
            IIOMetadataNode app = new IIOMetadataNode("ApplicationExtension");
            app.setAttribute("applicationID", "NETSCAPE");
            app.setAttribute("authenticationCode", "2.0");
            app.setUserObject(new byte[]{1, 0, 0}); // повторять бесконечно
            apps.appendChild(app);
        }
        meta.setFromTree(format, root);
        return meta;
    }

    private static IIOMetadataNode child(IIOMetadataNode root, String name) {
        for (int i = 0; i < root.getLength(); i++) {
            if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
        }
        IIOMetadataNode node = new IIOMetadataNode(name);
        root.appendChild(node);
        return node;
    }

    /** Палитра медианным сечением по гистограмме 6 бит на канал. */
    private static final class Palette {
        final int[] rgb;
        final IndexColorModel model;
        /** Ближайший цвет палитры для 18-битного ключа; -1 — ещё не искали. */
        final short[] nearest = new short[1 << 18];

        private Palette(int[] rgb) {
            this.rgb = rgb;
            byte[] r = new byte[256], g = new byte[256], b = new byte[256];
            for (int i = 0; i < rgb.length; i++) {
                r[i] = (byte) (rgb[i] >> 16);
                g[i] = (byte) (rgb[i] >> 8);
                b[i] = (byte) rgb[i];
            }
            model = new IndexColorModel(8, 256, r, g, b, TRANSPARENT);
            Arrays.fill(nearest, (short) -1);
        }

        static Palette build(List<int[]> samples, int colors) {
            int[] hist = new int[1 << 18];
            for (int[] px : samples) {
                // каждую вторую точку по кругу — палитре этого хватает с запасом
                for (int i = 0; i < px.length; i += 2) hist[key(px[i])]++;
            }
            int n = 0;
            for (int c : hist) if (c > 0) n++;
            int[] keys = new int[n];
            int[] counts = new int[n];
            n = 0;
            for (int k = 0; k < hist.length; k++) {
                if (hist[k] > 0) {
                    keys[n] = k;
                    counts[n++] = hist[k];
                }
            }

            List<Box> boxes = new ArrayList<>();
            boxes.add(new Box(keys, counts, 0, n));
            while (boxes.size() < colors) {
                Box best = null;
                for (Box bx : boxes) {
                    if (bx.end - bx.start < 2) continue;
                    // по размаху, а не только по числу точек: иначе яркие узлы,
                    // которых по площади мало, растворились бы в туманности
                    if (best == null || bx.priority() > best.priority()) best = bx;
                }
                if (best == null) break;
                boxes.remove(best);
                Box[] halves = best.split();
                boxes.add(halves[0]);
                boxes.add(halves[1]);
            }
            int[] rgb = new int[256];
            for (int i = 0; i < boxes.size(); i++) rgb[i] = boxes.get(i).average();
            return new Palette(rgb);
        }

        byte[] map(int[] px, int w, int h) {
            byte[] out = new byte[px.length];
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = y * w + x;
                    float d = (BAYER[(y & 3) * 4 + (x & 3)] / 16f - 0.5f) * DITHER;
                    int c = px[i];
                    int r = clamp((c >> 16 & 255) + d), g = clamp((c >> 8 & 255) + d), b = clamp((c & 255) + d);
                    out[i] = (byte) lookup(r, g, b);
                }
            }
            return out;
        }

        private int lookup(int r, int g, int b) {
            int k = (r >> 2) << 12 | (g >> 2) << 6 | (b >> 2);
            short v = nearest[k];
            if (v >= 0) return v;
            int rr = (r & ~3) | 2, gg = (g & ~3) | 2, bb = (b & ~3) | 2;
            int best = 0, bestD = Integer.MAX_VALUE;
            for (int i = 0; i < COLORS; i++) {
                int c = rgb[i];
                int dr = (c >> 16 & 255) - rr, dg = (c >> 8 & 255) - gg, db = (c & 255) - bb;
                // глаз к зелёному чувствительнее — веса как у яркости
                int dist = 3 * dr * dr + 6 * dg * dg + db * db;
                if (dist < bestD) {
                    bestD = dist;
                    best = i;
                }
            }
            nearest[k] = (short) best;
            return best;
        }

        BufferedImage image(byte[] idx, int w, int h) {
            BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_BYTE_INDEXED, model);
            byte[] data = ((DataBufferByte) img.getRaster().getDataBuffer()).getData();
            System.arraycopy(idx, 0, data, 0, idx.length);
            return img;
        }

        private static int clamp(float v) { return v < 0 ? 0 : v > 255 ? 255 : Math.round(v); }

        private static int key(int c) {
            return (c >> 18 & 63) << 12 | (c >> 10 & 63) << 6 | (c >> 2 & 63);
        }
    }

    /** Кусок гистограммы: ключи [start, end) и их число точек. */
    private static final class Box {
        final int[] keys, counts;
        final int start, end;
        final long total;
        final int range, axis;

        Box(int[] keys, int[] counts, int start, int end) {
            this.keys = keys;
            this.counts = counts;
            this.start = start;
            this.end = end;
            long t = 0;
            int[] lo = {63, 63, 63}, hi = {0, 0, 0};
            for (int i = start; i < end; i++) {
                t += counts[i];
                for (int a = 0; a < 3; a++) {
                    int v = channel(keys[i], a);
                    lo[a] = Math.min(lo[a], v);
                    hi[a] = Math.max(hi[a], v);
                }
            }
            total = t;
            int ax = 0;
            for (int a = 1; a < 3; a++) if (hi[a] - lo[a] > hi[ax] - lo[ax]) ax = a;
            axis = ax;
            range = hi[ax] - lo[ax];
        }

        double priority() { return range * Math.sqrt(total); }

        Box[] split() {
            // сортировка куска по длинной оси; ключи с числами — парами
            Integer[] order = new Integer[end - start];
            for (int i = 0; i < order.length; i++) order[i] = start + i;
            Arrays.sort(order, (x, y) -> Integer.compare(channel(keys[x], axis), channel(keys[y], axis)));
            int[] k2 = new int[order.length], c2 = new int[order.length];
            for (int i = 0; i < order.length; i++) {
                k2[i] = keys[order[i]];
                c2[i] = counts[order[i]];
            }
            System.arraycopy(k2, 0, keys, start, k2.length);
            System.arraycopy(c2, 0, counts, start, c2.length);

            long half = total / 2, acc = 0;
            int mid = start + 1;
            for (int i = start; i < end - 1; i++) {
                acc += counts[i];
                mid = i + 1;
                if (acc >= half) break;
            }
            return new Box[]{new Box(keys, counts, start, mid), new Box(keys, counts, mid, end)};
        }

        int average() {
            long r = 0, g = 0, b = 0;
            for (int i = start; i < end; i++) {
                r += (long) channel(keys[i], 0) * counts[i];
                g += (long) channel(keys[i], 1) * counts[i];
                b += (long) channel(keys[i], 2) * counts[i];
            }
            long t = Math.max(1, total);
            int rr = (int) Math.min(255, (r * 4 + 2 * t) / t);
            int gg = (int) Math.min(255, (g * 4 + 2 * t) / t);
            int bb = (int) Math.min(255, (b * 4 + 2 * t) / t);
            return rr << 16 | gg << 8 | bb;
        }

        private static int channel(int key, int axis) {
            return key >> (12 - axis * 6) & 63;
        }
    }
}
