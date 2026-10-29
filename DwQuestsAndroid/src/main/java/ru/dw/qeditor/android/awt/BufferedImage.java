package ru.dw.qeditor.android.awt;

/**
 * Картинка в памяти вместо {@code java.awt.image.BufferedImage}: массив ARGB
 * без умножения на альфу, строка за строкой сверху вниз.
 *
 * <p>Редактору от AWT-шной картинки нужно немногое — размеры, точки по одной и
 * пачкой, сам массив для заливки в текстуру, — поэтому и прослойка маленькая.
 * Тип хранения один ({@code int ARGB}); {@link #TYPE_INT_RGB} лишь помнится,
 * чтобы альфа у такой картинки читалась непрозрачной, как в AWT.
 */
public class BufferedImage {

    public static final int TYPE_INT_RGB = 1;
    public static final int TYPE_INT_ARGB = 2;

    private final int width;
    private final int height;
    private final int type;
    private final int[] data;
    private final WritableRaster raster;

    public BufferedImage(int width, int height, int type) {
        this(width, height, type, new int[Math.max(0, width) * Math.max(0, height)]);
    }

    public BufferedImage(int width, int height, int type, int[] argb) {
        this.width = width;
        this.height = height;
        this.type = type == TYPE_INT_RGB ? TYPE_INT_RGB : TYPE_INT_ARGB;
        this.data = argb;
        this.raster = new WritableRaster(new DataBufferInt(argb));
    }

    public int getWidth() { return width; }

    public int getHeight() { return height; }

    public int getType() { return type; }

    public WritableRaster getRaster() { return raster; }

    public int getRGB(int x, int y) {
        int p = data[y * width + x];
        return type == TYPE_INT_RGB ? p | 0xFF000000 : p;
    }

    public void setRGB(int x, int y, int argb) { data[y * width + x] = argb; }

    public int[] getRGB(int x, int y, int w, int h, int[] out, int offset, int scan) {
        if (out == null) out = new int[offset + h * scan];
        for (int row = 0; row < h; row++) {
            System.arraycopy(data, (y + row) * width + x, out, offset + row * scan, w);
            if (type == TYPE_INT_RGB)
                for (int i = 0; i < w; i++) out[offset + row * scan + i] |= 0xFF000000;
        }
        return out;
    }

    public void setRGB(int x, int y, int w, int h, int[] in, int offset, int scan) {
        for (int row = 0; row < h; row++)
            System.arraycopy(in, offset + row * scan, data, (y + row) * width + x, w);
    }

    public BufferedImage getSubimage(int x, int y, int w, int h) {
        BufferedImage out = new BufferedImage(w, h, type);
        out.setRGB(0, 0, w, h, getRGB(x, y, w, h, null, 0, w), 0, w);
        return out;
    }

    public Graphics2D createGraphics() { return new Graphics2D(this); }

    public Graphics getGraphics() { return createGraphics(); }

    /** Сам массив точек — без копии. */
    public int[] pixels() { return data; }
}
