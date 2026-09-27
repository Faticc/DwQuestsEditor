package ru.dw.qeditor.render;

/**
 * Работа с цветом так, как её делает мод: смешивание, альфа, интерполяция.
 *
 * <p>Раньше здесь же растеризовались шейдеры DwCore (circle.frag /
 * gradient.frag) — картинками в память, с кэшем на каждый размер и цвет.
 * Теперь это снова шейдеры, а тут остались только формулы над числами.
 */
public final class Fx {

    private Fx() { }

    /** GuiHelper.mixColors — среднее двух ARGB. */
    public static int mixColors(int a, int b) {
        int aa = ((a >>> 24) + (b >>> 24)) / 2;
        int rr = ((a >> 16 & 255) + (b >> 16 & 255)) / 2;
        int gg = ((a >> 8 & 255) + (b >> 8 & 255)) / 2;
        int bb = ((a & 255) + (b & 255)) / 2;
        return aa << 24 | rr << 16 | gg << 8 | bb;
    }

    public static int applyAlpha(int color, float alpha) {
        int a = (int) (alpha * 255f);
        return (a & 255) << 24 | color & 0xFFFFFF;
    }

    /** Линейная интерполяция RGB как UIQuestBrowser.lerpColor (без альфы). */
    public static int lerpRgb(int color1, int color2, float factor) {
        int r1 = color1 >> 16 & 255, g1 = color1 >> 8 & 255, b1 = color1 & 255;
        int r2 = color2 >> 16 & 255, g2 = color2 >> 8 & 255, b2 = color2 & 255;
        int r = (int) (r1 + (r2 - r1) * factor);
        int g = (int) (g1 + (g2 - g1) * factor);
        int b = (int) (b1 + (b2 - b1) * factor);
        return r << 16 | g << 8 | b;
    }

    /** GLSL smoothstep — работает и при edge0 &gt; edge1. */
    public static float smoothstep(float edge0, float edge1, float x) {
        float d = edge1 - edge0;
        if (d == 0f) return x < edge0 ? 0f : 1f;
        float t = (x - edge0) / d;
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return t * t * (3f - 2f * t);
    }
}
