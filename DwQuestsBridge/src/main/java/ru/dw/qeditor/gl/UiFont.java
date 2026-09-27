package ru.dw.qeditor.gl;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.GraphicsEnvironment;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Шрифт интерфейса редактора.
 *
 * <p>Шрифт Minecraft (см. {@code McFont}) годится для просмотра, но не для
 * самого редактора: восемь точек высотой, без сглаживания и с половинной
 * кириллицей. Поэтому глифы растеризуются обычным системным шрифтом и
 * складываются в атлас: рисует их AWT, но только в память — на экран всё
 * равно выходит четырёхугольник с текстурой.
 *
 * <p>Растр делается сразу в экранных точках (с учётом системного масштаба),
 * поэтому текст ложится пиксель в пиксель и не мылится.
 */
public final class UiFont {

    /**
     * Глиф в атласе: где лежит и как его ставить относительно опорной точки.
     * Размеры куска — в экранных точках, поэтому при выводе их делят на scale.
     */
    public record Glyph(Atlas.Region region, float advance, float left, float top) { }

    private static final Map<String, UiFont> CACHE = new HashMap<>();
    /** Шрифты для подстановки: ключ — имя, размер и начертание. */
    private static final Map<String, Font> FALLBACKS = new HashMap<>();
    private static String family;

    private final Font font;
    private final float scale;
    private final Atlas atlas = new Atlas(1024, 1, false, 0);
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final java.awt.Graphics2D scratch;
    private final FontMetrics metrics;
    private final float height;
    private final float ascent;

    private UiFont(int sizeLogical, boolean bold, float scale) {
        this.scale = scale;
        this.font = new Font(family(), bold ? Font.BOLD : Font.PLAIN, Math.round(sizeLogical * scale));
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        this.scratch = probe.createGraphics();
        scratch.setFont(font);
        scratch.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        scratch.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        this.metrics = scratch.getFontMetrics();
        this.height = metrics.getHeight() / scale;
        this.ascent = metrics.getAscent() / scale;
    }

    /**
     * @param sizeLogical размер в логических точках интерфейса
     * @param scale       во сколько раз экранная точка мельче логической
     */
    public static UiFont get(int sizeLogical, boolean bold, float scale) {
        String key = sizeLogical + (bold ? "b" : "") + "@" + scale;
        return CACHE.computeIfAbsent(key, k -> new UiFont(sizeLogical, bold, scale));
    }

    /** Системный шрифт интерфейса — тот же список, что был в теме Swing. */
    public static String family() {
        if (family != null) return family;
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String candidate : new String[]{
                "Segoe UI Variable Text", "Segoe UI", "Inter", "Roboto", "Noto Sans", "SF Pro Text", "Dialog"}) {
            if (available.contains(candidate)) {
                family = candidate;
                return family;
            }
        }
        family = "Dialog";
        return family;
    }

    /** Высота строки в логических точках. */
    public float height() { return height; }

    public float ascent() { return ascent; }

    public float width(String text) {
        if (text == null || text.isEmpty()) return 0;
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += glyph(text.charAt(i)).advance();
        return w;
    }

    /** Сколько символов строки влезает в заданную ширину. */
    public int fit(String text, float maxWidth) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += glyph(text.charAt(i)).advance();
            if (w > maxWidth) return i;
        }
        return text.length();
    }

    /** X-координата символа с индексом index (для курсора в поле ввода). */
    public float offsetOf(String text, int index) {
        float w = 0;
        for (int i = 0; i < Math.min(index, text.length()); i++) w += glyph(text.charAt(i)).advance();
        return w;
    }

    /** Индекс символа под точкой x — обратная задача к {@link #offsetOf}. */
    public int indexAt(String text, float x) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            float a = glyph(text.charAt(i)).advance();
            if (x < w + a / 2) return i;
            w += a;
        }
        return text.length();
    }

    /** Глиф символа; рисует его тот, у кого в руках рисователь. */
    public Glyph glyphOf(char c) { return glyph(c); }

    /** Во сколько раз экранная точка мельче логической. */
    public float scale() { return scale; }

    private Glyph glyph(char c) {
        Glyph g = glyphs.get((int) c);
        if (g != null) return g;
        g = rasterize(c);
        glyphs.put((int) c, g);
        return g;
    }

    private Glyph rasterize(char c) {
        // Segoe UI знает не всё: крестик закрытия, дуги связей, знак внимания.
        // Java для физического шрифта подстановку не делает сама — ищем сами,
        // иначе на месте символа пустота
        Font use = font.canDisplay(c) ? font : fallbackFor(c);
        float advance = (use == font ? metrics.charWidth(c)
                : (float) use.getStringBounds(String.valueOf(c), scratch.getFontRenderContext()).getWidth())
                / scale;
        if (c == ' ' || Character.isISOControl(c)) return new Glyph(null, advance, 0, 0);

        String s = String.valueOf(c);
        java.awt.geom.Rectangle2D bounds = use.createGlyphVector(
                scratch.getFontRenderContext(), s).getPixelBounds(null, 0, 0);
        int w = (int) bounds.getWidth();
        int h = (int) bounds.getHeight();
        if (w <= 0 || h <= 0) return new Glyph(null, advance, 0, 0);

        // запас в пиксель со всех сторон: сглаженные края любят выходить за
        // измеренный прямоугольник
        BufferedImage img = new BufferedImage(w + 2, h + 2, BufferedImage.TYPE_INT_ARGB);
        java.awt.Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        g2.setFont(use);
        g2.setColor(java.awt.Color.WHITE);
        g2.drawString(s, (float) -bounds.getX() + 1, (float) -bounds.getY() + 1);
        g2.dispose();

        Atlas.Region r = atlas.add(img);
        return new Glyph(r, advance, ((float) bounds.getX() - 1) / scale, ((float) -bounds.getY() + 1) / scale);
    }

    /**
     * Шрифт, которым рисовать символ, которого нет в основном.
     *
     * <p>Список — от «тот же по духу» к «лишь бы нарисовалось»; логический
     * Dialog идёт последним: он составной, и подстановку по всем системным
     * шрифтам делает уже сам.
     */
    private Font fallbackFor(char c) {
        for (String name : new String[]{
                "Segoe UI Symbol", "Segoe UI Emoji", "Arial Unicode MS", "DejaVu Sans", "Dialog"}) {
            Font f = FALLBACKS.computeIfAbsent(name + '/' + font.getSize() + '/' + font.getStyle(),
                    k -> new Font(name, font.getStyle(), font.getSize()));
            if (f.canDisplay(c)) return f;
        }
        return font;
    }

    /** Атлас мог пополниться — вызывается раз в кадр. */
    public static void refreshAll() {
        for (UiFont f : CACHE.values()) f.atlas.refresh();
    }

    public static void clear() {
        for (UiFont f : CACHE.values()) {
            f.atlas.dispose();
            f.scratch.dispose();
        }
        CACHE.clear();
    }
}
