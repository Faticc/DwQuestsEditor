package ru.dw.qeditor.render;

import ru.dw.qeditor.assets.McFont;
import ru.dw.qeditor.assets.ModAssets;
import ru.dw.qeditor.gl.Atlas;
import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.GlTexture;
import ru.dw.qeditor.gl.Textures;
import ru.dw.qeditor.model.Defs;

import java.awt.image.BufferedImage;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Порт ru.sky.dw.core.client.api.gui.GuiHelper (+ Shader, NodeRenderer, UtilsFX)
 * на OpenGL.
 *
 * <p>Все координаты методов — «виртуальные» пиксели GUI Minecraft, ровно те же
 * числа, что в исходниках мода. Масштаб (аналог guiScale) задаётся при создании
 * и живёт в собственной матрице: поворотов в интерфейсе нет, поэтому хватает
 * масштаба и сдвига, а вершины считаются сразу в пикселях экрана.
 *
 * <p>В отличие от прежней версии на Java2D здесь ничего не растеризуется на
 * процессоре: круги и градиенты снова считает фрагментный шейдер (как
 * {@code circle.frag} и {@code gradient.frag} в моде), тонировка нода — это
 * цвет вершины, а аддитивное свечение — {@code glBlendFunc(GL_SRC_ALPHA, GL_ONE)},
 * то есть ровно то, чем оно является в игре. Кэши перекрашенных спрайтов,
 * которые раньше съедали и память, и время, исчезли вместе с надобностью.
 */
public final class GuiPainter implements McFont.Sink {

    // ---- цвета GuiHelper.drawGuiBackground ------------------------------------
    public static final int BG_COLOR_1 = -13872284;
    public static final int BG_COLOR_2 = 1428175427;
    public static final int BG_COLOR_3 = -15785945;
    private static final int BG_HIGHLIGHT = 1157627903;
    private static final int BG_SHADOW = 1140850688;

    /** Кадров в анимации нода (nodes.png — сетка 32×32 клеток). */
    private static final int NODE_FRAMES = 32;
    /** UtilsFX.renderAnimatedQuadStrip(..., alpha = 0.66F, ...) */
    private static final float NODE_ALPHA = 0.66F;

    private final Batch batch;

    /** Текущее преобразование: только масштаб и сдвиг. */
    private float scaleX;
    private float scaleY;
    private float transX;
    private float transY;

    /** Область отсечения в пикселях экрана. */
    private float clipX, clipY, clipW, clipH;

    private final Deque<float[]> stack = new ArrayDeque<>();

    private GuiPainter(Batch batch, float guiScale, int viewportW, int viewportH) {
        this.batch = batch;
        this.scaleX = guiScale;
        this.scaleY = guiScale;
        this.clipX = 0;
        this.clipY = 0;
        this.clipW = viewportW;
        this.clipH = viewportH;
    }

    /**
     * Рисование в текущий кадр.
     *
     * @param guiScale во сколько раз виртуальный пиксель крупнее экранного
     */
    public static GuiPainter of(Batch batch, float guiScale, int viewportW, int viewportH) {
        return new GuiPainter(batch, guiScale, viewportW, viewportH);
    }

    /** Осталось от версии на Java2D: в GL закрывать нечего, состояние в стеке. */
    public void dispose() {
        applyClip();
    }

    public Batch batch() { return batch; }

    /** Сколько физических пикселей приходится на один виртуальный. */
    public float deviceScale() { return scaleX; }

    // ------------------------------------------------------------------ состояние

    public void push() {
        stack.push(new float[]{scaleX, scaleY, transX, transY, clipX, clipY, clipW, clipH});
    }

    public void pop() {
        float[] s = stack.pop();
        scaleX = s[0];
        scaleY = s[1];
        transX = s[2];
        transY = s[3];
        clipX = s[4];
        clipY = s[5];
        clipW = s[6];
        clipH = s[7];
        applyClip();
    }

    public void translate(float x, float y) {
        transX += x * scaleX;
        transY += y * scaleY;
    }

    /**
     * Прижать начало координат к целому пикселю экрана.
     *
     * <p>Нужно «пиксельному» выводу: шрифт и текстуры мода рисуются
     * GL_NEAREST, и полпикселя сдвига размазывают их по соседним — буквы
     * получаются разной толщины.
     */
    public void snap() {
        transX = Math.round(transX);
        transY = Math.round(transY);
    }

    public void scale(float s) { scale(s, s); }

    public void scale(float sx, float sy) {
        scaleX *= sx;
        scaleY *= sy;
    }

    /** Пересечение с текущей областью отсечения — аналог GuiHelper.glScissor. */
    public void clip(float x, float y, float width, float height) {
        float x1 = dx(x), y1 = dy(y);
        float x2 = dx(x + width), y2 = dy(y + height);
        float nx = Math.max(clipX, Math.min(x1, x2));
        float ny = Math.max(clipY, Math.min(y1, y2));
        float nx2 = Math.min(clipX + clipW, Math.max(x1, x2));
        float ny2 = Math.min(clipY + clipH, Math.max(y1, y2));
        clipX = nx;
        clipY = ny;
        clipW = Math.max(0, nx2 - nx);
        clipH = Math.max(0, ny2 - ny);
        applyClip();
    }

    public void clipReset(float x, float y, float width, float height) {
        clipX = dx(x);
        clipY = dy(y);
        clipW = Math.max(0, width * scaleX);
        clipH = Math.max(0, height * scaleY);
        applyClip();
    }

    private void applyClip() {
        batch.scissor(Math.round(clipX), Math.round(clipY), Math.round(clipW), Math.round(clipH));
    }

    private float dx(float x) { return x * scaleX + transX; }

    private float dy(float y) { return y * scaleY + transY; }

    /** Виртуальная координата → пиксель экрана. Нужна тем, кто кладёт вершины сам. */
    public float deviceX(float x) { return dx(x); }

    public float deviceY(float y) { return dy(y); }

    // ------------------------------------------------------------------ прямоугольники

    /** GuiHelper.drawRectangleWithBlend — с учётом альфы. Он же приёмник полосок McFont. */
    @Override
    public void rect(float x, float y, float width, float height, int color) {
        if (width <= 0 || height <= 0 || (color >>> 24) == 0) return;
        fillDevice(dx(x), dy(y), width * scaleX, height * scaleY, color);
    }

    /** GuiHelper.drawRectangle — glColor3f, то есть альфа игнорируется. */
    public void rectOpaque(float x, float y, float width, float height, int color) {
        if (width <= 0 || height <= 0) return;
        fillDevice(dx(x), dy(y), width * scaleX, height * scaleY, color | 0xFF000000);
    }

    /**
     * Заливка прямоугольника с привязкой к целым пикселям экрана: иначе
     * однопиксельные линии интерфейса то пропадают, то удваиваются.
     */
    private void fillDevice(float x, float y, float w, float h, int color) {
        int x0 = Math.round(x);
        int y0 = Math.round(y);
        int x1 = Math.round(x + w);
        int y1 = Math.round(y + h);
        batch.solid(x0, y0, Math.max(1, x1 - x0), Math.max(1, y1 - y0), color);
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
        batch.gradient(dx(x), dy(y), width * scaleX, height * scaleY,
                colorStart, colorMiddle, colorEnd, angle);
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
        gradientPart(x, y, width, height, 0, 1f / height, 1f / width, 1f - 1f / height,
                colorStart, colorMiddle, colorEnd, angle);
        gradientPart(x, y, width, height, 1f / width, 0, 1f - 1f / width, 1f,
                colorStart, colorMiddle, colorEnd, angle);
        gradientPart(x, y, width, height, 1f - 1f / width, 1f / height, 1f, 1f - 1f / height,
                colorStart, colorMiddle, colorEnd, angle);
    }

    /**
     * Кусок градиента: цвет считается так, будто нарисован весь прямоугольник,
     * а показывается только доля (u1,v1)…(u2,v2).
     */
    private void gradientPart(float x, float y, int width, int height,
                              float u1, float v1, float u2, float v2,
                              int colorStart, int colorMiddle, int colorEnd, int angleDeg) {
        float w = width * scaleX;
        float h = height * scaleY;
        float px = dx(x) + u1 * w;
        float py = dy(y) + v1 * h;
        float pw = (u2 - u1) * w;
        float ph = (v2 - v1) * h;
        if (pw <= 0 || ph <= 0) return;
        batch.noTexture();
        batch.quad(px, py, pw, ph, u1, v1, u2, v2,
                colorStart, colorMiddle, colorEnd,
                (float) Math.toRadians(angleDeg + 90.0), w, h, 0, Batch.MODE_GRADIENT);
    }

    /**
     * Прямоугольник со скруглёнными углами и рамкой — этого в моде нет, это
     * язык интерфейса самого редактора.
     */
    public void roundRect(float x, float y, float width, float height, float radius,
                          int fill, int border, float thickness) {
        if (width <= 0 || height <= 0) return;
        batch.roundRect(dx(x), dy(y), width * scaleX, height * scaleY,
                radius * scaleX, fill, border, thickness * scaleX);
    }

    /** Отрезок заданной толщины (в виртуальных единицах). */
    public void segment(float x1, float y1, float x2, float y2, float width, int color) {
        batch.segment(dx(x1), dy(y1), dx(x2), dy(y2), width * scaleX, color, color);
    }

    // ------------------------------------------------------------------ круги (Shader.CIRCLE)

    public void circle(float x, float y, int width, int height, int color, float smoothEdge) {
        circle(x, y, width, height, color, -1, smoothEdge, 0f, 0);
    }

    /** Shader.drawCircle(x, y, w, h, color, shadowColor, smoothEdge, shadowSize, shadowType) */
    public void circle(float x, float y, int width, int height, int color, int shadowColor,
                       float smoothEdge, float shadowSize, int shadowType) {
        if (width <= 0 || height <= 0) return;
        batch.circle(dx(x), dy(y), width * scaleX, height * scaleY,
                color, shadowColor, smoothEdge, shadowSize, shadowType);
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
        drawTexture(Textures.pixel(img), x, y, width, height, 0, 0, 1, 1, 0xFFFFFFFF);
    }

    public void imageSmooth(BufferedImage img, float x, float y, float width, float height) {
        drawTexture(Textures.smooth(img), x, y, width, height, 0, 0, 1, 1, 0xFFFFFFFF);
    }

    /** Готовая текстура — ей пользуется интерфейс редактора. */
    public void texture(GlTexture tex, float x, float y, float width, float height, int tint) {
        drawTexture(tex, x, y, width, height, 0, 0, 1, 1, tint);
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
        texturedRect(tex, x, y, u, v, width, height, textureWidth, textureHeight, 0xFFFFFFFF);
    }

    /** То же, но с умножением на цвет: так красится, например, зелёная галочка. */
    public void texturedRect(BufferedImage tex, float x, float y, float u, float v,
                             float width, float height, int textureWidth, int textureHeight, int tint) {
        if (tex == null) return;
        drawTexture(Textures.pixel(tex), x, y, width, height,
                u / textureWidth, v / textureHeight,
                (u + width) / textureWidth, (v + height) / textureHeight, tint);
    }

    /**
     * Прямоугольник текстуры по UV в диапазоне 0..1. Выход за края допустим —
     * текстура повторяется (GL_REPEAT), именно на этом держится параллакс фона
     * в UIQuestBrowser.
     */
    public void uvRect(BufferedImage tex, float x, float y, float width, float height,
                       float u1, float v1, float u2, float v2, boolean flippedX, boolean flippedY) {
        if (tex == null || width <= 0 || height <= 0) return;
        if (flippedX) { float t = u1; u1 = u2; u2 = t; }
        if (flippedY) { float t = v1; v1 = v2; v2 = t; }
        if (u1 == u2 || v1 == v2) return;
        boolean tiles = u1 < 0 || v1 < 0 || u2 > 1 || v2 > 1;
        GlTexture t = tiles ? Textures.pixelRepeating(tex) : Textures.pixel(tex);
        drawTexture(t, x, y, width, height, u1, v1, u2, v2, 0xFFFFFFFF);
    }

    private void drawTexture(GlTexture tex, float x, float y, float width, float height,
                             float u1, float v1, float u2, float v2, int tint) {
        if (tex == null || width <= 0 || height <= 0) return;
        batch.texture(tex);
        batch.sprite(dx(x), dy(y), width * scaleX, height * scaleY, u1, v1, u2, v2, tint);
    }

    /** Кусок атласа как картинка. */
    public void region(Atlas.Region r, float x, float y, float width, float height, int tint) {
        if (r == null || !r.valid()) return;
        batch.texture(r.page());
        batch.sprite(dx(x), dy(y), width * scaleX, height * scaleY,
                r.u1(), r.v1(), r.u2(), r.v2(), tint);
    }

    /**
     * Кусок атласа как силуэт: цвет берётся из аргумента, из текстуры — только
     * прозрачность. Так рисуются глифы интерфейса, лежащие в атласе серым.
     */
    public void regionMask(Atlas.Region r, float x, float y, float width, float height, int color) {
        if (r == null || !r.valid()) return;
        batch.texture(r.page());
        // текст должен попадать в целые экранные точки, иначе мылится
        float px = Math.round(dx(x));
        float py = Math.round(dy(y));
        batch.mask(px, py, Math.round(width * scaleX), Math.round(height * scaleY),
                r.u1(), r.v1(), r.u2(), r.v2(), color);
    }

    // ------------------------------------------------------------------ предметы

    /** GuiHelper.renderItem — иконка предмета 16×16. */
    public void item(String forgeName, float x, float y) {
        item(forgeName, x, y, 1f);
    }

    /** Иконка предмета размером 16·scale. */
    public void item(String forgeName, float x, float y, float scale) {
        Atlas.Region r = Textures.item(forgeName);
        if (r == null) return;
        float size = 16f * scale;
        batch.texture(r.page());
        batch.sprite(dx(x), dy(y), size * scaleX, size * scaleY, r.u1(), r.v1(), r.u2(), r.v2(), 0xFFFFFFFF);
    }

    /**
     * GuiHelper.drawItemWithOutline — предмет 16·scale с обводкой.
     *
     * <p>В моде обводка — отдельный проход по текстуре кадрового буфера
     * (outline.frag). Здесь тот же результат даёт силуэт иконки, восемь раз
     * выведенный со сдвигом в пиксель: для обводки в один пиксель это в точности
     * расширение по восьми соседям, только без второго буфера.
     */
    public void itemWithOutline(String forgeName, float x, float y, float scale, int color, int outlineSize) {
        if (outlineSize <= 0) {
            item(forgeName, x, y, scale);
            return;
        }
        Atlas.Region r = Textures.item(forgeName);
        if (r == null) return;

        float size = 16f * scale;
        float step = outlineSize / scaleX;                 // пиксель экрана в виртуальных
        int outline = color | 0xFF000000;
        batch.texture(r.page());
        for (int oy = -1; oy <= 1; oy++) {
            for (int ox = -1; ox <= 1; ox++) {
                if (ox == 0 && oy == 0) continue;
                batch.mask(dx(x + ox * step), dy(y + oy * step), size * scaleX, size * scaleY,
                        r.u1(), r.v1(), r.u2(), r.v2(), outline);
            }
        }
        batch.sprite(dx(x), dy(y), size * scaleX, size * scaleY, r.u1(), r.v1(), r.u2(), r.v2(), 0xFFFFFFFF);
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
     * NodeRenderer.render: кадр анимации из nodes.png / nodes2.png размером
     * size × size с центром в (cx, cy), окрашенный в color с альфой 0.66.
     *
     * <p>Раньше это было самым дорогим местом редактора: каждый узел — своя
     * перекрашенная картинка и смешивание ~280×280 точек на процессоре. Теперь
     * это один четырёхугольник с цветом вершины, а сложение цветов делает
     * видеокарта — как и в самом моде.
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
        BufferedImage atlas = second ? ModAssets.get().nodes2() : ModAssets.get().nodes();
        GlTexture tex = Textures.smooth(atlas);
        if (tex == null) return;

        int part = Math.floorMod(tick, NODE_FRAMES);
        int cframe = NODE_FRAMES - 1 - part;
        float cell = 1f / NODE_FRAMES;
        float u1 = cframe * cell;
        float v1 = strip * cell;

        int tint = ((int) (NODE_ALPHA * 255f) << 24) | (color & 0xFFFFFF);
        float x = cx - size / 2f;
        float y = cy - size / 2f;

        batch.texture(tex);
        if (additive) batch.blend(Batch.BLEND_ADDITIVE);
        batch.sprite(dx(x), dy(y), size * scaleX, size * scaleY, u1, v1, u1 + cell, v1 + cell, tint);
        if (additive) batch.blend(Batch.BLEND_NORMAL);
    }

    // ------------------------------------------------------------------ текст

    public int textWidth(String text) { return McFont.get().width(text); }

    public float text(String text, float x, float y, int color, boolean shadow) {
        return McFont.get().draw(this, text, x, y, color, shadow);
    }

    /** GuiHelper.drawCenterString */
    public float textCentered(String text, float centerX, float y, int color, boolean shadow) {
        return McFont.get().drawCentered(this, text, centerX, y, color, shadow);
    }

    // ---- приёмник глифов McFont ----------------------------------------------

    @Override
    public void glyph(BufferedImage page, float gx, float gy, float gw, float gh,
                      int sx, int sy, int sw, int sh, boolean italic, int color) {
        GlTexture tex = Textures.pixel(page);
        if (tex == null) return;
        float u1 = sx / (float) tex.width();
        float v1 = sy / (float) tex.height();
        float u2 = (sx + sw) / (float) tex.width();
        float v2 = (sy + sh) / (float) tex.height();

        float x1 = dx(gx);
        float y1 = dy(gy);
        float x2 = dx(gx + gw);
        float y2 = dy(gy + gh);

        batch.texture(tex);
        if (!italic) {
            batch.sprite(x1, y1, x2 - x1, y2 - y1, u1, v1, u2, v2, color);
            return;
        }
        // курсив в FontRenderer — это сдвиг вершин: верх вправо, низ влево
        float shift = scaleX;
        batch.quadUv(x1 + shift, y1, u1, v1,
                x2 + shift, y1, u2, v1,
                x2 - shift, y2, u2, v2,
                x1 - shift, y2, u1, v2,
                color, Batch.MODE_TEXTURE);
    }

    // ------------------------------------------------------------------ ресурсы

    /** Пути к ресурсам сменились — загруженные текстуры больше не годятся. */
    public static void clearCaches() {
        Textures.clear();
    }
}
