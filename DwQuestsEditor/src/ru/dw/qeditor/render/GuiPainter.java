package ru.dw.qeditor.render;

import ru.dw.qeditor.assets.ItemLook;
import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.model.Defs;

import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Порт ru.sky.dw.core.client.api.gui.GuiHelper (+ Shader, NodeRenderer, UtilsFX)
 * на Java2D.
 *
 * <p>Все координаты методов — «виртуальные» пиксели GUI Minecraft, ровно те же
 * числа, что в исходниках мода. Масштаб (аналог guiScale) задаётся при создании
 * и живёт в трансформации {@link Graphics2D}; шейдерные элементы (круги,
 * градиенты) при этом растеризуются в реальном разрешении экрана — как и в игре,
 * где фрагментный шейдер считается на физических пикселях.
 *
 * <p>Для аддитивного свечения нодов (glBlendFunc(SRC_ALPHA, ONE)) используется
 * прямая запись в int[] целевой картинки — это на порядок быстрее
 * {@link AdditiveComposite}, который остаётся запасным путём, если рисуем не в
 * int-картинку.
 */
public final class GuiPainter {

    // ---- цвета GuiHelper.drawGuiBackground ------------------------------------
    public static final int BG_COLOR_1 = -13872284;
    public static final int BG_COLOR_2 = 1428175427;
    public static final int BG_COLOR_3 = -15785945;
    private static final int BG_HIGHLIGHT = 1157627903;
    private static final int BG_SHADOW = 1140850688;

    /** Размер кадра в nodes.png / nodes2.png (2048 / 32). */
    private static final int NODE_CELL = 64;
    private static final int NODE_FRAMES = 32;
    /** UtilsFX.renderAnimatedQuadStrip(..., alpha = 0.66F, ...) */
    private static final float NODE_ALPHA = 0.66F;

    /**
     * Крупнее этого спрайт нода не собираем. Свечение — мягкое пятно без
     * мелких деталей, на экране растяжение незаметно, зато пересборка кадра
     * анимации (а он меняется каждый тик) стоит втрое дешевле.
     */
    private static final int NODE_SPRITE_MAX = 160;

    private final Graphics2D g;
    private final boolean ownsGraphics;

    private final int[] targetPx;
    private final int targetW;
    private final int targetH;
    private final boolean targetPremultiplied;

    private static final AffineTransform IDENTITY = new AffineTransform();

    private final Deque<Object[]> stack = new ArrayDeque<>();

    // ------------------------------------------------------------------ создание

    /**
     * Рисование в картинку. Предпочтительный вариант: даёт быстрый аддитивный
     * блендинг нодов. Картинка должна быть TYPE_INT_ARGB / _ARGB_PRE / _RGB.
     */
    public static GuiPainter forImage(BufferedImage target, float guiScale) {
        Graphics2D g = target.createGraphics();
        return new GuiPainter(g, true, target, guiScale);
    }

    /** Рисование в чужой Graphics2D (например, прямо в компонент Swing). */
    public static GuiPainter forGraphics(Graphics2D g, float guiScale) {
        return new GuiPainter((Graphics2D) g.create(), true, null, guiScale);
    }

    private GuiPainter(Graphics2D g, boolean ownsGraphics, BufferedImage target, float guiScale) {
        this.g = g;
        this.ownsGraphics = ownsGraphics;

        int[] px = null;
        int w = 0, h = 0;
        boolean pre = false;
        if (target != null) {
            int type = target.getType();
            if ((type == BufferedImage.TYPE_INT_ARGB || type == BufferedImage.TYPE_INT_ARGB_PRE
                    || type == BufferedImage.TYPE_INT_RGB)
                    && target.getRaster().getDataBuffer() instanceof DataBufferInt) {
                px = ((DataBufferInt) target.getRaster().getDataBuffer()).getData();
                w = target.getWidth();
                h = target.getHeight();
                pre = type == BufferedImage.TYPE_INT_ARGB_PRE;
            }
        }
        this.targetPx = px;
        this.targetW = w;
        this.targetH = h;
        this.targetPremultiplied = pre;

        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_COLOR_RENDERING, RenderingHints.VALUE_COLOR_RENDER_SPEED);
        g.setRenderingHint(RenderingHints.KEY_ALPHA_INTERPOLATION, RenderingHints.VALUE_ALPHA_INTERPOLATION_SPEED);
        g.scale(guiScale, guiScale);
    }

    public void dispose() {
        if (ownsGraphics) g.dispose();
    }

    /** Graphics2D в виртуальных координатах — для McFont и нестандартной отрисовки. */
    public Graphics2D g2() { return g; }

    /** Сколько физических пикселей приходится на один виртуальный. */
    public float deviceScale() { return (float) g.getTransform().getScaleX(); }

    // ------------------------------------------------------------------ состояние

    public void push() {
        stack.push(new Object[]{ g.getTransform(), g.getClip() });
    }

    public void pop() {
        Object[] s = stack.pop();
        g.setTransform((AffineTransform) s[0]);
        g.setClip((Shape) s[1]);
    }

    public void translate(float x, float y) { g.translate(x, y); }

    public void scale(float s) { g.scale(s, s); }

    public void scale(float sx, float sy) { g.scale(sx, sy); }

    /** Пересечение с текущей областью отсечения — аналог GuiHelper.glScissor. */
    public void clip(float x, float y, float width, float height) {
        g.clip(new Rectangle2D.Float(x, y, width, height));
    }

    public void clipReset(float x, float y, float width, float height) {
        g.setClip(new Rectangle2D.Float(x, y, width, height));
    }

    // ------------------------------------------------------------------ прямоугольники

    /** GuiHelper.drawRectangleWithBlend — с учётом альфы. */
    public void rect(float x, float y, float width, float height, int color) {
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) return;
        g.setColor(awt(color));
        g.fill(new Rectangle2D.Float(x, y, width, height));
    }

    /** GuiHelper.drawRectangle — glColor3f, то есть альфа игнорируется (всегда непрозрачно). */
    public void rectOpaque(float x, float y, float width, float height, int color) {
        if (width <= 0 || height <= 0) return;
        g.setColor(awt(color | 0xFF000000));
        g.fill(new Rectangle2D.Float(x, y, width, height));
    }

    /** GuiHelper.drawHorizontalLine */
    public void hLine(float x, float y, float width, int color) {
        rect(x, y, width, 1f, color);
    }

    /** GuiHelper.drawVerticalLine */
    public void vLine(float x, float y, float height, int color) {
        rect(x, y, 1f, height, color);
    }

    /** GuiHelper.drawFrame */
    public void frame(float x, float y, int width, int height, int thick, int color) {
        rect(x, y, width, thick, color);
        rect(x + width - thick, y + thick, thick, height - thick * 2, color);
        rect(x, y + height - thick, width, thick, color);
        rect(x, y + thick, thick, height - thick * 2, color);
    }

    // ------------------------------------------------------------------ градиенты

    /** GuiHelper.drawGradient(…, colorStart, colorEnd, angle) — середина = mixColors. */
    public void gradient(float x, float y, int width, int height, int colorStart, int colorEnd, int angle) {
        gradient(x, y, width, height, colorStart, Fx.mixColors(colorStart, colorEnd), colorEnd, angle);
    }

    /** GuiHelper.drawGradient(…, start, middle, end, angle) → Shader.GRADIENT */
    public void gradient(float x, float y, int width, int height,
                         int colorStart, int colorMiddle, int colorEnd, int angle) {
        if (width <= 0 || height <= 0) return;
        float s = deviceScale();
        int dw = Math.max(1, Math.round(width * s));
        int dh = Math.max(1, Math.round(height * s));
        BufferedImage img = Fx.gradient(dw, dh, colorStart, colorMiddle, colorEnd, angle);
        if (img != null) blit(img, x, y, width, height, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    /** GuiHelper.drawLineShadow — градиент «цвет → прозрачный». */
    public void lineShadow(float x, float y, int width, int height, int angle) {
        lineShadow(x, y, width, height, 0xFF000000, angle);
    }

    public void lineShadow(float x, float y, int width, int height, int color, int angle) {
        gradient(x, y, width, height, color, 0, angle);
    }

    /** GuiHelper.drawInsideShadowRect — тень по всем четырём сторонам внутрь. */
    public void insideShadowRect(float x, float y, int width, int height, int shadowSize) {
        insideShadowRect(x, y, width, height, 0xFF000000, shadowSize);
    }

    public void insideShadowRect(float x, float y, int width, int height, int color, int shadowSize) {
        lineShadow(x, y, width, shadowSize, color, 0);
        lineShadow(x + width - shadowSize, y, shadowSize, height, color, 90);
        lineShadow(x, y + height - shadowSize, width, shadowSize, color, 180);
        lineShadow(x, y, shadowSize, height, color, 270);
    }

    // ------------------------------------------------------------------ рамка окна

    public void guiBackground(float x, float y, int width, int height) {
        guiBackground(x, y, width, height, BG_COLOR_1, BG_COLOR_2, BG_COLOR_3);
    }

    /** GuiHelper.drawGuiBackground — фон окна мода со «скошенными» углами и объёмом. */
    public void guiBackground(float x, float y, int width, int height, int color1, int color2, int color3) {
        width = Math.max(width, 50);
        height = Math.max(height, 50);
        gradientMinecraftGui(x + 1f, y + 1f, width - 2, height - 2, color1, color2, color3, -45);

        rect(x + 1f, y + 1f, 3f, 3f, BG_HIGHLIGHT);
        rect(x + 4f, y + 1f, width - 7, 2f, BG_HIGHLIGHT);
        rect(x + 1f, y + 4f, 2f, height - 7, BG_HIGHLIGHT);

        rect(x + width - 4f, y + height - 4f, 3f, 3f, BG_SHADOW);
        rect(x + 3f, y + height - 3f, width - 7, 2f, BG_SHADOW);
        rect(x + width - 3f, y + 3f, 2f, height - 7, BG_SHADOW);

        hLine(x + 2f, y, width - 5, 0xFF000000);
        rectOpaque(x + width - 3f, y + 1f, 1f, 1f, 0xFF000000);
        rectOpaque(x + width - 2f, y + 2f, 1f, 1f, 0xFF000000);
        vLine(x + width - 1f, y + 3f, height - 5, 0xFF000000);
        rectOpaque(x + width - 2f, y + height - 2f, 1f, 1f, 0xFF000000);
        hLine(x + 3f, y + height - 1f, width - 5, 0xFF000000);
        rectOpaque(x + 1f, y + height - 3f, 1f, 1f, 0xFF000000);
        rectOpaque(x + 2f, y + height - 2f, 1f, 1f, 0xFF000000);
        vLine(x, y + 2f, height - 5, 0xFF000000);
        rectOpaque(x + 1f, y + 1f, 1f, 1f, 0xFF000000);
        rectOpaque(x + width - 3f, y + 2f, 1f, 1f, 0xFF808080);
        rectOpaque(x + 2f, y + height - 3f, 1f, 1f, 0xFF808080);
    }

    /**
     * Shader.drawGradientMinecraftGui: тот же градиент, но выводится тремя
     * полосами, из-за чего четыре угловых пикселя остаются пустыми.
     */
    private void gradientMinecraftGui(float x, float y, int width, int height,
                                      int colorStart, int colorMiddle, int colorEnd, int angle) {
        if (width <= 0 || height <= 0) return;
        float s = deviceScale();
        int dw = Math.max(1, Math.round(width * s));
        int dh = Math.max(1, Math.round(height * s));
        BufferedImage img = Fx.gradient(dw, dh, colorStart, colorMiddle, colorEnd, angle);
        if (img == null) return;
        blitPart(img, x, y, width, height, x, y + 1f, 1f, height - 2);
        blitPart(img, x, y, width, height, x + 1f, y, width - 2, height);
        blitPart(img, x, y, width, height, x + width - 1f, y + 1f, 1f, height - 2);
    }

    /**
     * Рисует кусок заранее растеризованной картинки: картинка занимает
     * прямоугольник (ox, oy, ow, oh), а показывается только часть (cx, cy, cw, ch).
     */
    private void blitPart(BufferedImage img, float ox, float oy, float ow, float oh,
                          float cx, float cy, float cw, float ch) {
        if (cw <= 0 || ch <= 0) return;
        push();
        clip(cx, cy, cw, ch);
        blit(img, ox, oy, ow, oh, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        pop();
    }

    // ------------------------------------------------------------------ круги (Shader.CIRCLE)

    public void circle(float x, float y, int width, int height, int color, float smoothEdge) {
        circle(x, y, width, height, color, -1, smoothEdge, 0f, 0);
    }

    /** Shader.drawCircle(x, y, w, h, color, shadowColor, smoothEdge, shadowSize, shadowType) */
    public void circle(float x, float y, int width, int height, int color, int shadowColor,
                       float smoothEdge, float shadowSize, int shadowType) {
        if (width <= 0 || height <= 0) return;
        float s = deviceScale();
        int dw = Math.max(1, Math.round(width * s));
        int dh = Math.max(1, Math.round(height * s));
        BufferedImage img = Fx.circle(dw, dh, color, shadowColor, smoothEdge, shadowSize, shadowType);
        if (img != null) blit(img, x, y, width, height, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    // ------------------------------------------------------------------ слот

    public void slot(float x, float y) {
        slot(x, y, -1, -7631989, -13158601);
    }

    /** GuiHelper.drawSlot */
    public void slot(float x, float y, int highLightColor, int mainColor, int shadowColor) {
        hLine(x + 1f, y + 17f, 17, highLightColor);
        vLine(x + 17f, y + 1f, 16, highLightColor);
        rectOpaque(x + 1f, y + 1f, 16f, 16f, mainColor);
        rectOpaque(x + 17f, y, 1f, 1f, mainColor);
        rectOpaque(x, y + 17f, 1f, 1f, mainColor);
        hLine(x, y, 17, shadowColor);
        vLine(x, y + 1f, 16, shadowColor);
    }

    // ------------------------------------------------------------------ текстуры

    /** Картинка целиком в заданный прямоугольник, «пиксельно» (как GL_NEAREST). */
    public void image(BufferedImage img, float x, float y, float width, float height) {
        if (img != null) blit(img, x, y, width, height, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
    }

    public void imageSmooth(BufferedImage img, float x, float y, float width, float height) {
        if (img != null) blit(img, x, y, width, height, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    /**
     * GuiHelper.drawTexturedModalRect: u/v/uw/vh измеряются в «256-й» системе
     * (в шейдере множитель 1/256) независимо от реального размера текстуры.
     */
    public void texturedModalRect(BufferedImage tex, float x, float y,
                                  float u, float v, float width, float height) {
        texturedModalRect(tex, x, y, u, v, width, height, false, false);
    }

    public void texturedModalRect(BufferedImage tex, float x, float y,
                                  float u, float v, float width, float height,
                                  boolean flippedX, boolean flippedY) {
        if (tex == null) return;
        float f = 1f / 256f;
        uvRect(tex, x, y, width, height, u * f, v * f, (u + width) * f, (v + height) * f, flippedX, flippedY);
    }

    /**
     * GuiHelper.drawModalRectWithCustomSizedTexture: u/v/width/height в пикселях
     * текстуры размера textureWidth × textureHeight.
     */
    public void texturedRect(BufferedImage tex, float x, float y, float u, float v,
                             float width, float height, int textureWidth, int textureHeight) {
        if (tex == null) return;
        uvRect(tex, x, y, width, height,
                u / textureWidth, v / textureHeight,
                (u + width) / textureWidth, (v + height) / textureHeight, false, false);
    }

    /**
     * Рисует прямоугольник текстуры по UV в диапазоне 0..1. Выход за края
     * допустим — текстура повторяется, как GL_REPEAT (именно на этом держится
     * параллакс фона в UIQuestBrowser).
     */
    public void uvRect(BufferedImage tex, float x, float y, float width, float height,
                       float u1, float v1, float u2, float v2, boolean flippedX, boolean flippedY) {
        if (tex == null || width <= 0 || height <= 0) return;
        if (flippedX) { float t = u1; u1 = u2; u2 = t; }
        if (flippedY) { float t = v1; v1 = v2; v2 = t; }
        if (u1 == u2 || v1 == v2) return;

        int tw = tex.getWidth();
        int th = tex.getHeight();
        float sx1 = u1 * tw, sx2 = u2 * tw;
        float sy1 = v1 * th, sy2 = v2 * th;

        push();
        clip(x, y, width, height);
        g.translate(x, y);
        g.scale(width / (sx2 - sx1), height / (sy2 - sy1));
        g.translate(-sx1, -sy1);
        g.setPaint(new java.awt.TexturePaint(tex, new Rectangle2D.Float(0, 0, tw, th)));
        g.fill(new Rectangle2D.Float(Math.min(sx1, sx2), Math.min(sy1, sy2),
                Math.abs(sx2 - sx1), Math.abs(sy2 - sy1)));
        pop();
    }

    // ------------------------------------------------------------------ предметы

    /** GuiHelper.renderItem — иконка предмета 16×16. */
    public void item(String forgeName, float x, float y) {
        item(forgeName, x, y, 1f);
    }

    /** Иконка предмета размером 16·scale. */
    public void item(String forgeName, float x, float y, float scale) {
        float size = 16f * scale;
        int dev = Math.max(1, Math.round(size * deviceScale()));
        BufferedImage icon = itemIcon(forgeName, dev);
        if (icon != null) blit(icon, x, y, size, size, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    /**
     * GuiHelper.drawItemWithOutline — предмет 16·scale с обводкой.
     * outlineSize задан в физических пикселях (в моде обводка делается по
     * текстуре кадрового буфера), как в QuestPanel: 1.
     */
    public void itemWithOutline(String forgeName, float x, float y, float scale, int color, int outlineSize) {
        if (outlineSize <= 0) {
            item(forgeName, x, y, scale);
            return;
        }
        float size = 16f * scale;
        float s = deviceScale();
        int dev = Math.max(1, Math.round(size * s));
        BufferedImage img = itemOutlineIcon(forgeName, dev, color, outlineSize);
        if (img == null) return;
        float pad = outlineSize / s;
        float full = (dev + outlineSize * 2) / s;
        blit(img, x - pad, y - pad, full, full, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    // ------------------------------------------------------------------ ноды и звёзды

    /**
     * QuestButton.Star.drawStar. x/y/width/height — прямоугольник звезды
     * (в моде это кнопка, расширенная на 20 пикселей во все стороны: 64×64).
     * nodeType == null — квест «активен»: рисуется только серое ядро.
     */
    public void star(float x, float y, int width, int height, Defs.Star star, Defs.NodeType nodeType, int tick) {
        if (nodeType != null) {
            node(x + 32f, y + 32f, nodeType, 140f, star.mainColor, tick);
            circle(x, y, width, height, star.shadowColor2, star.shadowColor1, 0.5f, 0.5f, 1);
        }
        circle(x + 20f, y + 20f, width - 40, height - 40, star.mainColor, -1, 0.05f, 0.75f, 1);
    }

    /**
     * Полный аналог QuestButton.drawBackground: x/y — позиция кнопки,
     * scaleFactor = currentScaleFactor · iconSize.scale.
     */
    public void questStar(float x, float y, int buttonWidth, int buttonHeight, float scaleFactor,
                          Defs.Star star, Defs.NodeType nodeType, String iconItem, int tick) {
        float sx = x - 20f;
        float sy = y - 20f;
        int w = buttonWidth + 40;
        int h = buttonHeight + 40;
        float scaledWidth = w * scaleFactor;
        float scaledHeight = h * scaleFactor;
        float offsetX = (w - scaledWidth) / 2f;
        float offsetY = (h - scaledHeight) / 2f;

        push();
        translate(sx + offsetX, sy + offsetY);
        scale(scaleFactor);
        star(0f, 0f, w, h, star, nodeType, tick);
        if (iconItem != null && !iconItem.isEmpty()) item(iconItem, 24f, 24f);
        pop();
    }

    /**
     * NodeRenderer.render: анимированный кадр из nodes.png / nodes2.png размером
     * size × size с центром в (cx, cy), окрашенный в color с альфой 0.66.
     */
    public void node(float cx, float cy, Defs.NodeType nodeType, float size, int color, int tick) {
        int strip = 1;
        boolean additive;
        boolean second = false;
        switch (nodeType) {
            case NORMAL:   strip = 1; additive = true; break;
            case UNSTABLE: strip = 6; additive = true; break;
            case DARK:     strip = 2; additive = false; break;
            case TAINTED:  strip = 5; additive = false; break;
            case PURE:     strip = 4; additive = true; size *= 0.5f; break;
            case HUNGRY:   strip = 3; additive = true; size *= 0.5f; break;
            case SMOKE:    strip = 0; additive = false; size *= 0.5f; second = true; break;
            case SMOKE2:   strip = 1; additive = false; size *= 0.5f; second = true; break;
            default:       additive = true; break;
        }
        int part = Math.floorMod(tick, NODE_FRAMES);
        int cframe = NODE_FRAMES - 1 - part;

        int dev = Math.max(1, Math.round(size * deviceScale()));
        BufferedImage sprite = nodeSprite(second, strip, cframe, color, Math.min(dev, NODE_SPRITE_MAX));
        if (sprite == null) return;

        float x = cx - size / 2f;
        float y = cy - size / 2f;
        if (additive) additiveBlit(sprite, x, y, dev, dev);
        else blit(sprite, x, y, size, size, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
    }

    // ------------------------------------------------------------------ текст

    public int textWidth(String text) { return McFont.get().width(text); }

    public float text(String text, float x, float y, int color, boolean shadow) {
        return McFont.get().draw(g, text, x, y, color, shadow);
    }

    /** GuiHelper.drawCenterString */
    public float textCentered(String text, float centerX, float y, int color, boolean shadow) {
        return McFont.get().drawCentered(g, text, centerX, y, color, shadow);
    }

    // ------------------------------------------------------------------ внутреннее

    private void blit(BufferedImage img, float x, float y, float width, float height, Object interpolation) {
        if (img == null || width <= 0 || height <= 0) return;

        // Картинки шейдеров, нодов и иконок растеризуются сразу в экранном
        // размере, поэтому почти всегда выводятся один в один. Общий путь
        // Java2D с трансформацией и фильтрацией для этого лишний и заметно
        // дороже обычного копирования — ловим такой случай отдельно.
        AffineTransform at0 = g.getTransform();
        double sx = at0.getScaleX();
        double sy = at0.getScaleY();
        if (at0.getShearX() == 0 && at0.getShearY() == 0) {
            double devW = width * sx;
            double devH = height * sy;
            if (Math.abs(devW - img.getWidth()) < 0.51 && Math.abs(devH - img.getHeight()) < 0.51) {
                double devX = x * sx + at0.getTranslateX();
                double devY = y * sy + at0.getTranslateY();
                Shape clip = g.getClip();
                Shape devClip = clip == null ? null : at0.createTransformedShape(clip);
                g.setTransform(IDENTITY);
                if (devClip != null) g.setClip(devClip);
                g.drawImage(img, (int) Math.round(devX), (int) Math.round(devY), null);
                g.setTransform(at0);
                if (clip != null) g.setClip(clip);
                return;
            }
        }

        Object old = g.getRenderingHint(RenderingHints.KEY_INTERPOLATION);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, interpolation);
        AffineTransform at = new AffineTransform(
                width / img.getWidth(), 0, 0, height / img.getHeight(), x, y);
        g.drawImage(img, at, null);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                old == null ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR : old);
    }

    /**
     * glBlendFunc(GL_SRC_ALPHA, GL_ONE). Картинка уже в физическом размере и с
     * предумноженной альфой; пишем прямо в пиксели цели.
     */
    private void additiveBlit(BufferedImage sprite, float x, float y, int devW, int devH) {
        if (targetPx == null) {
            java.awt.Composite old = g.getComposite();
            g.setComposite(AdditiveComposite.INSTANCE);
            float scale = deviceScale();
            blit(sprite, x, y, devW / scale, devH / scale, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setComposite(old);
            return;
        }

        AffineTransform at = g.getTransform();
        Point2D p = at.transform(new Point2D.Float(x, y), null);
        int dx = (int) Math.round(p.getX());
        int dy = (int) Math.round(p.getY());

        Rectangle clip = deviceClip();
        int sw = sprite.getWidth();
        int sh = sprite.getHeight();

        int x0 = Math.max(dx, Math.max(0, clip.x));
        int y0 = Math.max(dy, Math.max(0, clip.y));
        int x1 = Math.min(dx + devW, Math.min(targetW, clip.x + clip.width));
        int y1 = Math.min(dy + devH, Math.min(targetH, clip.y + clip.height));
        if (x0 >= x1 || y0 >= y1) return;

        int[] src = ((DataBufferInt) sprite.getRaster().getDataBuffer()).getData();
        // спрайт может быть мельче места на экране — растягиваем ближайшим
        // соседом в целочисленной арифметике, свечение от этого не страдает
        int stepX = (int) (((long) sw << 16) / devW);
        int stepY = (int) (((long) sh << 16) / devH);

        for (int yy = y0; yy < y1; yy++) {
            int sy = ((yy - dy) * stepY) >> 16;
            if (sy >= sh) sy = sh - 1;
            int sRow = sy * sw;
            int dRow = yy * targetW;
            for (int xx = x0; xx < x1; xx++) {
                int sx = ((xx - dx) * stepX) >> 16;
                if (sx >= sw) sx = sw - 1;
                int s = src[sRow + sx];
                if ((s & 0x00FFFFFF) == 0) continue;
                int di = dRow + xx;
                int d = targetPx[di];
                int r = (d >> 16 & 255) + (s >> 16 & 255);
                int gg = (d >> 8 & 255) + (s >> 8 & 255);
                int b = (d & 255) + (s & 255);
                if (r > 255) r = 255;
                if (gg > 255) gg = 255;
                if (b > 255) b = 255;
                int a = d >>> 24;
                if (targetPremultiplied || a != 0) {
                    a += s >>> 24;
                    if (a > 255) a = 255;
                } else {
                    a = s >>> 24;
                }
                targetPx[di] = a << 24 | r << 16 | gg << 8 | b;
            }
        }
    }

    private Rectangle deviceClip() {
        Shape clip = g.getClip();
        if (clip == null) return new Rectangle(0, 0, targetW, targetH);
        return g.getTransform().createTransformedShape(clip).getBounds();
    }

    // ------------------------------------------------------------------ кэши картинок

    private static final Map<Integer, java.awt.Color> COLORS =
            new LinkedHashMap<Integer, java.awt.Color>(128, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Integer, java.awt.Color> eldest) {
                    return size() > 256;
                }
            };

    /** ARGB int → java.awt.Color с кэшем: цветов в GUI мало, а прямоугольников много. */
    public static java.awt.Color awt(int argb) {
        java.awt.Color c = COLORS.get(argb);
        if (c == null) {
            c = new java.awt.Color(argb, true);
            COLORS.put(argb, c);
        }
        return c;
    }

    /**
     * Кэши раздельные не случайно: кадров анимации нода 32 на каждую пару
     * «тип × цвет», а иконок предметов на большой вкладке под четыре сотни.
     * В общем кэше они вытесняли друг друга, и каждый второй кадр приходилось
     * заново собирать все спрайты нодов — отсюда были скачки времени вдвое.
     */
    private static Map<String, BufferedImage> lru(int limit) {
        return new LinkedHashMap<String, BufferedImage>(64, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, BufferedImage> eldest) {
                return size() > limit;
            }
        };
    }

    private static final Map<String, BufferedImage> NODE_CACHE = lru(1200);
    private static final Map<String, BufferedImage> ITEM_CACHE = lru(3000);
    private static final Map<String, BufferedImage> OUTLINE_CACHE = lru(256);

    public static void clearCaches() {
        NODE_CACHE.clear();
        ITEM_CACHE.clear();
        OUTLINE_CACHE.clear();
        Fx.clearCaches();
    }

    /** Иконка предмета, приведённая к нужному размеру в физических пикселях. */
    private static BufferedImage itemIcon(String forgeName, int devSize) {
        String key = forgeName + "|" + devSize;
        BufferedImage cached = ITEM_CACHE.get(key);
        if (cached != null) return cached;
        BufferedImage src = ItemLook.icon(forgeName);
        if (src == null) return null;
        BufferedImage out = resize(src, devSize, devSize);
        ITEM_CACHE.put(key, out);
        return out;
    }

    /** Иконка с обводкой: дилатация альфы на outline физических пикселей (outline.frag). */
    private static BufferedImage itemOutlineIcon(String forgeName, int devSize, int color, int outline) {
        String key = forgeName + "|" + devSize + "|" + color + "|" + outline;
        BufferedImage cached = OUTLINE_CACHE.get(key);
        if (cached != null) return cached;

        BufferedImage icon = itemIcon(forgeName, devSize);
        if (icon == null) return null;

        int w = devSize + outline * 2;
        BufferedImage out = new BufferedImage(w, w, BufferedImage.TYPE_INT_ARGB);
        int[] dst = ((DataBufferInt) out.getRaster().getDataBuffer()).getData();
        int[] src = new int[devSize * devSize];
        icon.getRGB(0, 0, devSize, devSize, src, 0, devSize);

        int oc = color | 0xFF000000;
        for (int y = 0; y < w; y++) {
            for (int x = 0; x < w; x++) {
                int ix = x - outline;
                int iy = y - outline;
                if (ix >= 0 && iy >= 0 && ix < devSize && iy < devSize && (src[iy * devSize + ix] >>> 24) != 0) {
                    dst[y * w + x] = src[iy * devSize + ix];
                    continue;
                }
                boolean near = false;
                for (int j = -outline; j <= outline && !near; j++) {
                    int ny = iy + j;
                    if (ny < 0 || ny >= devSize) continue;
                    for (int i = -outline; i <= outline; i++) {
                        int nx = ix + i;
                        if (nx < 0 || nx >= devSize) continue;
                        if ((src[ny * devSize + nx] >>> 24) != 0) { near = true; break; }
                    }
                }
                if (near) dst[y * w + x] = oc;
            }
        }
        OUTLINE_CACHE.put(key, out);
        return out;
    }

    /**
     * Кадр нода: клетка 64×64 из атласа, окрашенная в color с альфой 0.66,
     * растянутая до devSize и с предумноженной альфой (TYPE_INT_ARGB_PRE).
     */
    private static BufferedImage nodeSprite(boolean second, int strip, int cframe, int color, int devSize) {
        String key = (second ? 2 : 1) + "|" + strip + "|" + cframe + "|" + color + "|" + devSize;
        BufferedImage cached = NODE_CACHE.get(key);
        if (cached != null) return cached;

        BufferedImage atlas = second ? ModAssets.get().nodes2() : ModAssets.get().nodes();
        if (atlas == null) return null;
        int cellW = atlas.getWidth() / NODE_FRAMES;
        int cellH = atlas.getHeight() / NODE_FRAMES;
        if (cellW <= 0 || cellH <= 0) return null;

        int cx = cframe * cellW;
        int cy = strip * cellH;
        if (cx + cellW > atlas.getWidth() || cy + cellH > atlas.getHeight()) return null;

        int[] cell = new int[cellW * cellH];
        atlas.getRGB(cx, cy, cellW, cellH, cell, 0, cellW);

        int tr = color >> 16 & 255;
        int tg = color >> 8 & 255;
        int tb = color & 255;

        BufferedImage tinted = new BufferedImage(cellW, cellH, BufferedImage.TYPE_INT_ARGB_PRE);
        int[] tp = ((DataBufferInt) tinted.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < cell.length; i++) {
            int p = cell[i];
            int a = (int) ((p >>> 24) * NODE_ALPHA);
            if (a == 0) continue;
            int r = ((p >> 16 & 255) * tr / 255) * a / 255;
            int gg = ((p >> 8 & 255) * tg / 255) * a / 255;
            int b = ((p & 255) * tb / 255) * a / 255;
            tp[i] = a << 24 | r << 16 | gg << 8 | b;
        }

        BufferedImage out;
        if (devSize == cellW && devSize == cellH) {
            out = tinted;
        } else {
            out = new BufferedImage(devSize, devSize, BufferedImage.TYPE_INT_ARGB_PRE);
            Graphics2D gg = out.createGraphics();
            gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gg.drawImage(tinted, 0, 0, devSize, devSize, null);
            gg.dispose();
        }
        NODE_CACHE.put(key, out);
        return out;
    }

    /** Качественное уменьшение: последовательное деление пополам, затем билинейно. */
    private static BufferedImage resize(BufferedImage src, int w, int h) {
        if (w <= 0 || h <= 0) return null;
        BufferedImage cur = src;
        int cw = src.getWidth();
        int ch = src.getHeight();
        while (cw / 2 >= w && ch / 2 >= h && cw / 2 > 0 && ch / 2 > 0) {
            cw /= 2;
            ch /= 2;
            BufferedImage next = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_ARGB);
            Graphics2D gg = next.createGraphics();
            gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            gg.drawImage(cur, 0, 0, cw, ch, null);
            gg.dispose();
            cur = next;
        }
        if (cw == w && ch == h) return cur;
        BufferedImage out = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        Graphics2D gg = out.createGraphics();
        gg.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                w >= src.getWidth() ? RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR
                        : RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        gg.drawImage(cur, 0, 0, w, h, null);
        gg.dispose();
        return out;
    }
}
