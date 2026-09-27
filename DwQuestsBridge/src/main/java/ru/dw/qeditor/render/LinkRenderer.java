package ru.dw.qeditor.render;

import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.model.Defs;

import java.awt.geom.Line2D;

/**
 * Линии зависимостей между квестами — порт UIQuestBrowser.drawLine: дуга
 * (LineType), «волна» у активных связей и переход от цвета родителя к цвету
 * квеста на 75 % длины.
 *
 * <p>В моде это один GL_LINE_STRIP с цветом в каждой вершине. Здесь то же
 * самое: ломаная разворачивается в полоску четырёхугольников с цветом в
 * вершинах, мягкий край считает шейдер. На вкладке в 400 квестов около тысячи
 * связей — все они уходят одним вызовом отрисовки, тогда как версия на Java2D
 * тратила на них до 100 мс на кадр и требовала отдельного пакетного режима,
 * лишь бы не пересобирать конвейер.
 *
 * <p>Дуга по-прежнему считается замкнутой формулой: двух десятков вершин
 * хватает вместо двух сотен.
 */
public final class LinkRenderer {

    private LinkRenderer() { }

    /** Доля длины, на которой градиент успевает дойти до цвета квеста. */
    private static final float GRADIENT_END = 0.75f;

    /** Ниже этого зума градиент не различить — рисуем сплошным цветом. */
    private static final float GRADIENT_MIN_ZOOM = 0.55f;

    /** Сколько вершин хватает, чтобы дуга выглядела гладкой. */
    private static final int MAX_POINTS = 24;
    /** У «волны» смещение своё в каждой вершине, ей нужно чаще. */
    private static final int MAX_POINTS_WIGGLE = 48;

    /** Точки ломаной кадра: массив переиспользуется, чтобы не сорить мусором. */
    private static float[] points = new float[(MAX_POINTS_WIGGLE + 1) * 2];

    /** Нужен ли градиент при таком масштабе. */
    public static boolean gradientWorthIt(float zoom) { return zoom >= GRADIENT_MIN_ZOOM; }

    /**
     * @param x1       конец со стороны родителя
     * @param color1   цвет квеста-потомка
     * @param color2   цвет родителя
     * @param width    толщина в пикселях экрана
     * @param gradient false — сплошной цвет (на мелком масштабе разницы не видно)
     */
    public static void line(GuiPainter p, float x1, float y1, float x2, float y2,
                            int color1, int color2, boolean wiggle, Defs.LineType lineType,
                            float tick, float width, boolean gradient) {
        int count = buildPath(x1, y1, x2, y2, wiggle, lineType, tick);
        if (count < 2) return;

        Batch batch = p.batch();
        int solid = color1 == color2 ? color1 : Fx.lerpRgb(color1, color2, 0.5f);

        float prevX = 0, prevY = 0;
        int prevColor = 0;
        for (int i = 0; i < count; i++) {
            float wx = points[i * 2];
            float wy = points[i * 2 + 1];
            float sx = p.deviceX(wx);
            float sy = p.deviceY(wy);
            int color = 0xFF000000 | (gradient && color1 != color2
                    ? Fx.lerpRgb(color2, color1, Math.min(1f, i / (float) (count - 1) / GRADIENT_END))
                    : solid);
            if (i > 0) batch.segment(prevX, prevY, sx, sy, width, prevColor, color);
            prevX = sx;
            prevY = sy;
            prevColor = color;
        }
    }

    /** Одиночная линия — например, тянущаяся за курсором связь. */
    public static void draw(GuiPainter p, float x1, float y1, float x2, float y2,
                            int color1, int color2, boolean wiggle, Defs.LineType lineType,
                            float tick, float worldWidth) {
        line(p, x1, y1, x2, y2, color1, color2, wiggle, lineType, tick,
                worldWidth * p.deviceScale(), color1 != color2);
    }

    /**
     * Точки дуги в координатах мира.
     *
     * <p>В моде это цикл на {@code dist/2} шагов: шаг по большей оси удваивается
     * и на каждом шаге домножается на {@code r = 1 − 1.04/(1.5·inc)}, так что
     * вершина {@code a} стоит в {@code x1 − dx₀·a·rᵃ}. Формула замкнутая —
     * кривую можно посчитать в любой точке и взять меньше вершин: форма и концы
     * те же (на последнем шаге {@code rᶦⁿᶜ ≈ 0.5}, и линия точно приходит в цель).
     *
     * @return сколько точек получилось
     */
    private static int buildPath(float x1, float y1, float x2, float y2,
                                 boolean wiggle, Defs.LineType lineType, float tick) {
        float deltaX = Math.round(x1 - x2);
        float deltaY = Math.round(y1 - y2);
        float dist = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        int inc = (int) (dist / 2f);
        if (inc <= 0) return 0;

        boolean straight = lineType == Defs.LineType.STRAIGHT;
        boolean isXGreaterThanY = Math.abs(deltaX) > Math.abs(deltaY);
        if (lineType == Defs.LineType.INVERTED) isXGreaterThanY = !isXGreaterThanY;

        float dx0 = deltaX / inc;
        float dy0 = deltaY / inc;
        if (!straight) {
            if (isXGreaterThanY) dx0 *= 2f;
            else dy0 *= 2f;
        }
        double r = straight ? 1.0 : 1.0 - 1.04 / (inc * 1.5);
        double lnR = r > 0 ? Math.log(r) : 0;

        int steps = Math.min(inc, wiggle ? MAX_POINTS_WIGGLE : MAX_POINTS);
        if (straight && !wiggle) steps = 1;

        int need = (steps + 1) * 2;
        if (points.length < need) points = new float[need];

        for (int i = 0; i <= steps; i++) {
            float a = inc * (float) i / steps;
            float decay = straight ? 1f : (float) Math.exp(lnR * a);

            float wx = x1 - dx0 * a * (isXGreaterThanY && !straight ? decay : 1f);
            float wy = y1 - dy0 * a * (!isXGreaterThanY && !straight ? decay : 1f);

            if (wiggle) {
                float phase = a / inc;
                wx += (float) Math.sin((tick + a) / 8f + 2f) * (1f - phase);
                wy += (float) Math.sin((tick + a) / 8f) * (1f - phase);
            }
            points[i * 2] = wx;
            points[i * 2 + 1] = wy;
        }
        return steps + 1;
    }

    /**
     * Расстояние от точки до нарисованной линии — для выбора связи мышью.
     * Считается по той же кривой, но без волны.
     */
    public static float distanceTo(float x1, float y1, float x2, float y2,
                                   Defs.LineType lineType, float px, float py) {
        float deltaX = Math.round(x1 - x2);
        float deltaY = Math.round(y1 - y2);
        float dist = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        int inc = (int) (dist / 2f);
        if (inc <= 0) return Float.MAX_VALUE;

        boolean straight = lineType == Defs.LineType.STRAIGHT;
        boolean isXGreaterThanY = Math.abs(deltaX) > Math.abs(deltaY);
        if (lineType == Defs.LineType.INVERTED) isXGreaterThanY = !isXGreaterThanY;

        float dx0 = deltaX / inc;
        float dy0 = deltaY / inc;
        if (!straight) {
            if (isXGreaterThanY) dx0 *= 2f;
            else dy0 *= 2f;
        }
        double r = straight ? 1.0 : 1.0 - 1.04 / (inc * 1.5);
        double lnR = r > 0 ? Math.log(r) : 0;
        int steps = Math.min(inc, MAX_POINTS);

        float best = Float.MAX_VALUE;
        float prevX = 0, prevY = 0;
        for (int i = 0; i <= steps; i++) {
            float a = inc * (float) i / steps;
            float decay = straight ? 1f : (float) Math.exp(lnR * a);
            float cx = x1 - dx0 * a * (isXGreaterThanY && !straight ? decay : 1f);
            float cy = y1 - dy0 * a * (!isXGreaterThanY && !straight ? decay : 1f);
            if (i > 0) {
                float d = (float) Line2D.ptSegDist(prevX, prevY, cx, cy, px, py);
                if (d < best) best = d;
            }
            prevX = cx;
            prevY = cy;
        }
        return best;
    }
}
