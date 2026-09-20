package ru.dw.qeditor.render;

import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * Растеризация шейдеров DwCore (circle.frag / gradient.frag) средствами Java2D.
 * Формулы перенесены из исходных .frag, поэтому звёзды квестов и градиенты
 * выглядят как в игре.
 */
public final class Fx {

    private Fx() { }

    private static final Map<String, BufferedImage> CIRCLES = new HashMap<>();
    private static final Map<String, BufferedImage> GRADIENTS = new HashMap<>();

    /** GLSL smoothstep — работает и при edge0 &gt; edge1. */
    public static float smoothstep(float edge0, float edge1, float x) {
        float d = edge1 - edge0;
        if (d == 0f) return x < edge0 ? 0f : 1f;
        float t = (x - edge0) / d;
        t = t < 0f ? 0f : (t > 1f ? 1f : t);
        return t * t * (3f - 2f * t);
    }

    /**
     * circle.frag. shadowType: 0 — просто круг, 1 — свет в центре, 2 — тень внутри края.
     *
     * @param color       основной цвет (ARGB)
     * @param shadowColor цвет «тени»/подсветки (ARGB), учитывается его альфа
     * @param smoothEdge  мягкость края (доля радиуса)
     * @param shadowSize  u_InnerShadowSize
     */
    public static BufferedImage circle(int w, int h, int color, int shadowColor,
                                       float smoothEdge, float shadowSize, int shadowType) {
        if (w <= 0 || h <= 0) return null;
        String key = w + "x" + h + ":" + color + ":" + shadowColor + ":" + smoothEdge + ":" + shadowSize + ":" + shadowType;
        BufferedImage cached = CIRCLES.get(key);
        if (cached != null) return cached;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[w * h];

        float cr = (color >> 16 & 255) / 255f;
        float cg = (color >> 8 & 255) / 255f;
        float cb = (color & 255) / 255f;
        float ca = (color >>> 24) / 255f;
        float sr = (shadowColor >> 16 & 255) / 255f;
        float sg = (shadowColor >> 8 & 255) / 255f;
        float sb = (shadowColor & 255) / 255f;
        float sa = (shadowColor >>> 24) / 255f;

        float aspect = (float) w / h;
        float radius = 0.5f;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                float ux = ((x + 0.5f) / w - 0.5f) * aspect;
                float uy = (y + 0.5f) / h - 0.5f;
                float d = (float) Math.sqrt(ux * ux + uy * uy);

                float alpha;
                float rr, gg, bb;
                if (shadowType > 0) {
                    float edgeAlpha = smoothstep(radius, radius - smoothEdge, d);
                    float size = shadowType == 1 ? 1f - shadowSize : shadowSize;
                    float shadowAlpha = smoothstep(radius - size, radius, d);
                    if (shadowType == 1) shadowAlpha = 1f - shadowAlpha;
                    float m = sa * shadowAlpha;
                    rr = cr + (sr - cr) * m;
                    gg = cg + (sg - cg) * m;
                    bb = cb + (sb - cb) * m;
                    alpha = edgeAlpha;
                } else {
                    alpha = smoothstep(radius, radius - smoothEdge, d) * ca;
                    rr = cr; gg = cg; bb = cb;
                }
                int a = clamp255(alpha * 255f);
                px[y * w + x] = a << 24 | clamp255(rr * 255f) << 16 | clamp255(gg * 255f) << 8 | clamp255(bb * 255f);
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        if (CIRCLES.size() > 512) CIRCLES.clear();
        CIRCLES.put(key, img);
        return img;
    }

    /** gradient.frag: трёхточечный линейный градиент под углом (угол в градусах, как в моде). */
    public static BufferedImage gradient(int w, int h, int colorStart, int colorMiddle, int colorEnd, int angleDeg) {
        if (w <= 0 || h <= 0) return null;
        String key = w + "x" + h + ":" + colorStart + ":" + colorMiddle + ":" + colorEnd + ":" + angleDeg;
        BufferedImage cached = GRADIENTS.get(key);
        if (cached != null) return cached;

        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = new int[w * h];

        double angle = Math.toRadians(angleDeg + 90.0);
        double dirX = Math.cos(angle);
        double dirY = Math.sin(angle);
        double gradientLength = Math.sqrt(Math.pow(w * dirX, 2) + Math.pow(h * dirY, 2));
        if (gradientLength == 0) gradientLength = 1;

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                double relX = (x + 0.5) - w / 2.0;
                double relY = (y + 0.5) - h / 2.0;
                double projection = relX * dirX + relY * dirY;
                double t = (projection + 0.5 * gradientLength) / gradientLength;
                t = Math.max(0, Math.min(1, t));

                int c1, c2;
                double f;
                if (t < 0.5) { c1 = colorStart; c2 = colorMiddle; f = t * 2.0; }
                else { c1 = colorMiddle; c2 = colorEnd; f = (t - 0.5) * 2.0; }

                int r = mix(c1 >> 16 & 255, c2 >> 16 & 255, f);
                int g = mix(c1 >> 8 & 255, c2 >> 8 & 255, f);
                int b = mix(c1 & 255, c2 & 255, f);
                int a = mix(c1 >>> 24, c2 >>> 24, f);
                // color.a *= mix(start.a, end.a, gradient) — как в шейдере
                double aMix = mix(colorStart >>> 24, colorEnd >>> 24, t) / 255.0;
                a = clamp255((float) (a * aMix));
                px[y * w + x] = a << 24 | r << 16 | g << 8 | b;
            }
        }
        img.setRGB(0, 0, w, h, px, 0, w);
        if (GRADIENTS.size() > 256) GRADIENTS.clear();
        GRADIENTS.put(key, img);
        return img;
    }

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

    private static int mix(int a, int b, double f) { return clamp255((float) (a + (b - a) * f)); }

    private static int clamp255(float v) {
        int i = Math.round(v);
        return i < 0 ? 0 : (i > 255 ? 255 : i);
    }

    public static void clearCaches() {
        CIRCLES.clear();
        GRADIENTS.clear();
    }
}
