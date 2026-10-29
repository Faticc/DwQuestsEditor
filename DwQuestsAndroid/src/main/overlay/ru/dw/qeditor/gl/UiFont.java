package ru.dw.qeditor.gl;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Typeface;

import ru.dw.qeditor.android.awt.BufferedImage;

import java.util.HashMap;
import java.util.Map;

/**
 * Шрифт интерфейса редактора — Android-вариант общего {@code gl/UiFont}.
 *
 * <p>Устроен так же: глифы растеризуются системным шрифтом в экранных точках и
 * складываются в атлас, на экран выходит четырёхугольник с текстурой. Только
 * рисует их {@link Paint}, а не AWT. Подстановку недостающих символов
 * (крестик закрытия, дуги связей, знак внимания) Android делает сам —
 * системные шрифты сцеплены в цепочку, и {@code drawText} её проходит.
 */
public final class UiFont {

    public record Glyph(Atlas.Region region, float advance, float left, float top) { }

    private static final Map<String, UiFont> CACHE = new HashMap<>();

    private final float scale;
    private final Atlas atlas = new Atlas(1024, 1, false, 0);
    private final Map<Integer, Glyph> glyphs = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final float height;
    private final float ascent;
    private final Rect bounds = new Rect();

    private UiFont(int sizeLogical, boolean bold, float scale) {
        this.scale = scale;
        paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        paint.setTextSize(Math.round(sizeLogical * scale));
        paint.setColor(0xFFFFFFFF);
        Paint.FontMetrics fm = paint.getFontMetrics();
        // как FontMetrics.getHeight у AWT: подъём, спуск и межстрочный зазор
        this.height = (fm.descent - fm.ascent + fm.leading) / scale;
        this.ascent = -fm.ascent / scale;
    }

    public static UiFont get(int sizeLogical, boolean bold, float scale) {
        String key = sizeLogical + (bold ? "b" : "") + "@" + scale;
        return CACHE.computeIfAbsent(key, k -> new UiFont(sizeLogical, bold, scale));
    }

    public static String family() { return "sans-serif"; }

    public float height() { return height; }

    public float ascent() { return ascent; }

    public float width(String text) {
        if (text == null || text.isEmpty()) return 0;
        float w = 0;
        for (int i = 0; i < text.length(); i++) w += glyph(text.charAt(i)).advance();
        return w;
    }

    public int fit(String text, float maxWidth) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            w += glyph(text.charAt(i)).advance();
            if (w > maxWidth) return i;
        }
        return text.length();
    }

    public float offsetOf(String text, int index) {
        float w = 0;
        for (int i = 0; i < Math.min(index, text.length()); i++) w += glyph(text.charAt(i)).advance();
        return w;
    }

    public int indexAt(String text, float x) {
        float w = 0;
        for (int i = 0; i < text.length(); i++) {
            float a = glyph(text.charAt(i)).advance();
            if (x < w + a / 2) return i;
            w += a;
        }
        return text.length();
    }

    public Glyph glyphOf(char c) { return glyph(c); }

    public float scale() { return scale; }

    private Glyph glyph(char c) {
        Glyph g = glyphs.get((int) c);
        if (g != null) return g;
        g = rasterize(c);
        glyphs.put((int) c, g);
        return g;
    }

    private Glyph rasterize(char c) {
        String s = String.valueOf(c);
        // половинки суррогатной пары по отдельности не рисуются — пусть будут пустыми
        if (Character.isSurrogate(c)) return new Glyph(null, 0, 0, 0);
        float advance = paint.measureText(s) / scale;
        if (c == ' ' || Character.isISOControl(c)) return new Glyph(null, advance, 0, 0);

        paint.getTextBounds(s, 0, 1, bounds);
        int w = bounds.width();
        int h = bounds.height();
        if (w <= 0 || h <= 0) return new Glyph(null, advance, 0, 0);

        // запас в пиксель со всех сторон: сглаженные края выходят за измеренное
        int bw = w + 2, bh = h + 2;
        Bitmap bmp = Bitmap.createBitmap(bw, bh, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bmp);
        canvas.drawText(s, -bounds.left + 1, -bounds.top + 1, paint);
        int[] px = new int[bw * bh];
        // getPixels отдаёт цвет без умножения на альфу — то, что ждёт атлас
        bmp.getPixels(px, 0, bw, 0, 0, bw, bh);
        bmp.recycle();
        for (int i = 0; i < px.length; i++) px[i] = (px[i] & 0xFF000000) | 0x00FFFFFF;

        Atlas.Region r = atlas.add(new BufferedImage(bw, bh, BufferedImage.TYPE_INT_ARGB, px));
        return new Glyph(r, advance, (bounds.left - 1) / scale, (-bounds.top + 1) / scale);
    }

    public static void refreshAll() {
        for (UiFont f : CACHE.values()) f.atlas.refresh();
    }

    public static void clear() {
        for (UiFont f : CACHE.values()) f.atlas.dispose();
        CACHE.clear();
    }
}
