package ru.dw.qeditor.render;

import ru.dw.qeditor.android.awt.BufferedImage;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.IntFunction;

/**
 * Зацикленный GIF из кадров плаката — Android-вариант общего
 * {@code render/GifWriter}.
 *
 * <p>Палитра, размытие Байера и прозрачные «неизменившиеся» точки — те же, что
 * в общем коде (одна палитра на всю анимацию, чтобы туманность не мигала).
 * Разница только в записи: GIF-писателя ImageIO на Android нет, поэтому файл
 * собирается здесь же — заголовок GIF89a, петля NETSCAPE2.0, на каждый кадр
 * блок управления (задержка, прозрачный индекс, «не стирать») и LZW.
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

        file.delete();
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file), 1 << 16)) {
            header(out, w, h, palette.rgb);
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
                frame(out, w, h, outIdx, delayCs, i > 0);
            }
            out.write(0x3B);
        }
    }

    private static int[] pixels(BufferedImage img) {
        return img.getRGB(0, 0, img.getWidth(), img.getHeight(), null, 0, img.getWidth());
    }

    // ------------------------------------------------------------------ запись

    private static void header(OutputStream out, int w, int h, int[] rgb) throws IOException {
        out.write("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        short16(out, w);
        short16(out, h);
        out.write(0xF7);            // глобальная палитра, 8 бит, 256 цветов
        out.write(0);               // фон
        out.write(0);               // соотношение сторон
        for (int i = 0; i < 256; i++) {
            int c = rgb[i];
            out.write(c >> 16 & 255);
            out.write(c >> 8 & 255);
            out.write(c & 255);
        }
        // NETSCAPE2.0: повторять бесконечно
        out.write(new byte[]{0x21, (byte) 0xFF, 11});
        out.write("NETSCAPE2.0".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        out.write(new byte[]{3, 1, 0, 0, 0});
    }

    private static void frame(OutputStream out, int w, int h, byte[] idx, int delayCs, boolean transparent)
            throws IOException {
        // блок управления: кадр ложится поверх прошлого, прозрачное — «оставить как было»
        out.write(new byte[]{0x21, (byte) 0xF9, 4});
        out.write((1 << 2) | (transparent ? 1 : 0));
        short16(out, delayCs);
        out.write(TRANSPARENT);
        out.write(0);

        out.write(0x2C);
        short16(out, 0);
        short16(out, 0);
        short16(out, w);
        short16(out, h);
        out.write(0);               // без своей палитры, без чересстрочности
        out.write(8);               // минимальный размер кода LZW
        new Lzw(out).encode(idx);
        out.write(0);               // конец данных кадра
    }

    private static void short16(OutputStream out, int v) throws IOException {
        out.write(v & 255);
        out.write(v >> 8 & 255);
    }

    /**
     * LZW по правилам GIF: коды переменной длины от 9 до 12 бит, словарь
     * сбрасывается кодом очистки, когда заполнен. Словарь — хеш-таблица
     * «префикс + байт → код», как в классическом compress.
     */
    private static final class Lzw {
        private static final int BITS = 12;
        private static final int HSIZE = 5003;
        private static final int CLEAR = 256;
        private static final int EOI = 257;

        private final OutputStream out;
        private final int[] hashKey = new int[HSIZE];
        private final short[] hashCode = new short[HSIZE];
        private final byte[] block = new byte[255];
        private int blockLen;
        private int acc;
        private int accBits;
        private int codeSize = 9;
        private int nextCode = 258;

        Lzw(OutputStream out) { this.out = out; }

        void encode(byte[] data) throws IOException {
            resetTable();
            put(CLEAR);
            if (data.length == 0) {
                put(EOI);
                flush();
                return;
            }
            int prefix = data[0] & 255;
            for (int i = 1; i < data.length; i++) {
                int c = data[i] & 255;
                int key = (c << 12) | prefix;
                int h = ((c << 4) ^ prefix) % HSIZE;
                if (h < 0) h += HSIZE;
                int step = h == 0 ? 1 : HSIZE - h;
                boolean found = false;
                while (hashKey[h] != -1) {
                    if (hashKey[h] == key) {
                        prefix = hashCode[h];
                        found = true;
                        break;
                    }
                    h -= step;
                    if (h < 0) h += HSIZE;
                }
                if (found) continue;

                put(prefix);
                prefix = c;
                if (nextCode < (1 << BITS)) {
                    hashKey[h] = key;
                    hashCode[h] = (short) nextCode++;
                    // новый код уже не помещается в текущую ширину — со следующего шире
                    if (nextCode > (1 << codeSize) && codeSize < BITS) codeSize++;
                } else {
                    put(CLEAR);
                    resetTable();
                }
            }
            put(prefix);
            put(EOI);
            flush();
        }

        private void resetTable() {
            Arrays.fill(hashKey, -1);
            nextCode = 258;
            codeSize = 9;
        }

        private void put(int code) throws IOException {
            acc |= code << accBits;
            accBits += codeSize;
            while (accBits >= 8) {
                byteOut(acc & 255);
                acc >>>= 8;
                accBits -= 8;
            }
        }

        private void byteOut(int b) throws IOException {
            block[blockLen++] = (byte) b;
            if (blockLen == 255) writeBlock();
        }

        private void writeBlock() throws IOException {
            if (blockLen == 0) return;
            out.write(blockLen);
            out.write(block, 0, blockLen);
            blockLen = 0;
        }

        private void flush() throws IOException {
            if (accBits > 0) byteOut(acc & 255);
            acc = 0;
            accBits = 0;
            writeBlock();
        }
    }

    // ------------------------------------------------------------------ палитра (как в общем коде)

    /** Палитра медианным сечением по гистограмме 6 бит на канал. */
    private static final class Palette {
        final int[] rgb;
        /** Ближайший цвет палитры для 18-битного ключа; -1 — ещё не искали. */
        final short[] nearest = new short[1 << 18];

        private Palette(int[] rgb) {
            this.rgb = rgb;
            Arrays.fill(nearest, (short) -1);
        }

        static Palette build(List<int[]> samples, int colors) {
            int[] hist = new int[1 << 18];
            for (int[] px : samples) {
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
                int dist = 3 * dr * dr + 6 * dg * dg + db * db;
                if (dist < bestD) {
                    bestD = dist;
                    best = i;
                }
            }
            nearest[k] = (short) best;
            return best;
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
