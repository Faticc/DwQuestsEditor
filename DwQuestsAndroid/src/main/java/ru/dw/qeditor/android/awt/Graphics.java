package ru.dw.qeditor.android.awt;

/**
 * Рисование по {@link BufferedImage} — то немногое, что нужно редактору:
 * залить и обвести прямоугольник, положить картинку с растяжением (ближайшая
 * точка, как у AWT по умолчанию) и смешиванием «поверх».
 */
public class Graphics {

    protected final BufferedImage target;
    protected int color = 0xFF000000;

    Graphics(BufferedImage target) { this.target = target; }

    public void setColor(Color c) { color = c.getRGB(); }

    public void fillRect(int x, int y, int w, int h) {
        int x0 = Math.max(0, x), y0 = Math.max(0, y);
        int x1 = Math.min(target.getWidth(), x + w), y1 = Math.min(target.getHeight(), y + h);
        for (int yy = y0; yy < y1; yy++)
            for (int xx = x0; xx < x1; xx++) plot(xx, yy, color);
    }

    public void drawRect(int x, int y, int w, int h) {
        fillRect(x, y, w + 1, 1);
        fillRect(x, y + h, w + 1, 1);
        fillRect(x, y, 1, h + 1);
        fillRect(x + w, y, 1, h + 1);
    }

    public boolean drawImage(BufferedImage img, int x, int y, Object observer) {
        return drawImage(img, x, y, img.getWidth(), img.getHeight(), observer);
    }

    public boolean drawImage(BufferedImage img, int x, int y, int w, int h, Object observer) {
        if (img == null || w <= 0 || h <= 0) return true;
        int sw = img.getWidth(), sh = img.getHeight();
        int[] src = img.getRGB(0, 0, sw, sh, null, 0, sw);
        for (int dy = 0; dy < h; dy++) {
            int ty = y + dy;
            if (ty < 0 || ty >= target.getHeight()) continue;
            int sy = dy * sh / h;
            for (int dx = 0; dx < w; dx++) {
                int tx = x + dx;
                if (tx < 0 || tx >= target.getWidth()) continue;
                plot(tx, ty, src[sy * sw + dx * sw / w]);
            }
        }
        return true;
    }

    /** Смешивание «источник поверх» для цветов без умножения на альфу. */
    private void plot(int x, int y, int src) {
        int sa = src >>> 24;
        if (sa == 0) return;
        if (sa == 255) {
            target.setRGB(x, y, src);
            return;
        }
        int dst = target.getRGB(x, y);
        int da = dst >>> 24;
        float a = sa / 255f, b = da / 255f * (1 - a);
        float outA = a + b;
        int r = Math.round(((src >> 16 & 255) * a + (dst >> 16 & 255) * b) / outA);
        int g = Math.round(((src >> 8 & 255) * a + (dst >> 8 & 255) * b) / outA);
        int bl = Math.round(((src & 255) * a + (dst & 255) * b) / outA);
        target.setRGB(x, y, Math.round(outA * 255) << 24 | r << 16 | g << 8 | bl);
    }

    public void dispose() { }
}
