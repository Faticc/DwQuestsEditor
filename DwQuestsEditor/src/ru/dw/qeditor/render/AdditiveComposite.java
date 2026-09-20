package ru.dw.qeditor.render;

import java.awt.Composite;
import java.awt.CompositeContext;
import java.awt.RenderingHints;
import java.awt.image.ColorModel;
import java.awt.image.Raster;
import java.awt.image.WritableRaster;

/**
 * Аддитивное смешивание — аналог glBlendFunc(GL_SRC_ALPHA, GL_ONE),
 * которым мод рисует свечение нодов.
 */
public final class AdditiveComposite implements Composite {

    public static final AdditiveComposite INSTANCE = new AdditiveComposite(1f);

    private final float alpha;

    public AdditiveComposite(float alpha) { this.alpha = alpha; }

    @Override
    public CompositeContext createContext(ColorModel srcColorModel, ColorModel dstColorModel, RenderingHints hints) {
        return new Context(alpha);
    }

    private static final class Context implements CompositeContext {
        private final float alpha;

        Context(float alpha) { this.alpha = alpha; }

        @Override
        public void compose(Raster src, Raster dstIn, WritableRaster dstOut) {
            int w = Math.min(src.getWidth(), dstIn.getWidth());
            int h = Math.min(src.getHeight(), dstIn.getHeight());
            int srcBands = src.getNumBands();
            int dstBands = dstIn.getNumBands();
            int[] s = new int[srcBands];
            int[] d = new int[dstBands];

            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    src.getPixel(x, y, s);
                    dstIn.getPixel(x, y, d);
                    float sa = (srcBands > 3 ? s[3] / 255f : 1f) * alpha;
                    for (int b = 0; b < 3 && b < dstBands; b++) {
                        int v = (int) (d[b] + s[b] * sa);
                        d[b] = v > 255 ? 255 : v;
                    }
                    if (dstBands > 3) {
                        int a = (int) (d[3] + 255 * sa);
                        d[3] = a > 255 ? 255 : a;
                    }
                    dstOut.setPixel(x, y, d);
                }
            }
        }

        @Override public void dispose() { }
    }
}
