package ru.dw.qeditor.render;

import ru.dw.qeditor.model.Defs;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.MultipleGradientPaint;
import java.awt.Paint;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.geom.AffineTransform;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;

/**
 * Линии зависимостей между квестами — порт UIQuestBrowser.drawLine: дуга
 * (LineType), «волна» у активных связей и градиент от цвета родителя к цвету
 * квеста с переходом на 75 % длины.
 *
 * <p>В моде это один GL_LINE_STRIP с цветом в каждой вершине, и стоит он почти
 * ничего. В Java2D так нельзя: на вкладке в 400 квестов около тысячи связей, и
 * наивная отрисовка занимала ~100 мс на кадр. Поэтому здесь {@link Batch}:
 * подсказки рендеринга и обводка ставятся один раз на все линии (их смена
 * заставляет Java2D пересобирать конвейер), пути строятся сразу в экранных
 * координатах при единичной трансформации, а сама дуга считается замкнутой
 * формулой — двух десятков вершин хватает вместо двух сотен.
 */
public final class LinkRenderer {

    private LinkRenderer() { }

    /** Доля длины, на которой градиент успевает дойти до цвета квеста. */
    private static final float GRADIENT_END = 0.75f;

    /** Ниже этого зума градиент не различить — рисуем сплошным цветом, так быстрее. */
    private static final float GRADIENT_MIN_ZOOM = 0.55f;

    /** Сколько вершин хватает, чтобы дуга выглядела гладкой. */
    private static final int MAX_POINTS = 24;
    /** У «волны» смещение своё в каждой вершине, ей нужно чаще. */
    private static final int MAX_POINTS_WIGGLE = 48;

    /** Нужен ли градиент при таком масштабе. */
    public static boolean gradientWorthIt(float zoom) { return zoom >= GRADIENT_MIN_ZOOM; }

    /**
     * Пакет линий: общая настройка на все связи кадра.
     *
     * <pre>
     * try (LinkRenderer.Batch batch = LinkRenderer.batch(p, width, true, true)) {
     *     batch.line(...);
     * }
     * </pre>
     */
    public static Batch batch(GuiPainter p, float deviceWidth, boolean antialias, boolean gradient) {
        return new Batch(p, deviceWidth, antialias, gradient);
    }

    public static final class Batch implements AutoCloseable {

        private final Graphics2D g;
        private final AffineTransform savedTransform;
        private final Object savedAa;
        private final Stroke savedStroke;
        private final Paint savedPaint;

        private final double scaleX;
        private final double scaleY;
        private final double transX;
        private final double transY;
        private final boolean gradient;
        private final Stroke normalStroke;

        private Stroke currentStroke;
        private int currentColor = 1;
        private final Path2D.Float path = new Path2D.Float();

        private Batch(GuiPainter p, float deviceWidth, boolean antialias, boolean gradient) {
            this.g = p.g2();
            this.gradient = gradient;
            this.savedTransform = g.getTransform();
            this.savedAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
            this.savedStroke = g.getStroke();
            this.savedPaint = g.getPaint();

            // поворота в наших трансформациях нет, поэтому хватает масштаба и сдвига
            this.scaleX = savedTransform.getScaleX();
            this.scaleY = savedTransform.getScaleY();
            this.transX = savedTransform.getTranslateX();
            this.transY = savedTransform.getTranslateY();

            g.setTransform(new AffineTransform());
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    antialias ? RenderingHints.VALUE_ANTIALIAS_ON : RenderingHints.VALUE_ANTIALIAS_OFF);
            this.normalStroke = new BasicStroke(Math.max(0.4f, deviceWidth),
                    BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
            this.currentStroke = normalStroke;
            g.setStroke(normalStroke);
        }

        /** Сплошная обводка заданной толщины в экранных пикселях. */
        public Stroke plain(float deviceWidth) {
            return new BasicStroke(Math.max(0.3f, deviceWidth),
                    BasicStroke.CAP_BUTT, BasicStroke.JOIN_ROUND);
        }

        /** Пунктир — на случай, если понадобится отличать связь ещё и штрихом. */
        public Stroke dashed(float deviceWidth) {
            return new BasicStroke(Math.max(0.4f, deviceWidth), BasicStroke.CAP_BUTT,
                    BasicStroke.JOIN_ROUND, 10f, new float[]{4f, 4f}, 0f);
        }

        /**
         * @param color1 цвет со стороны квеста-потомка
         * @param color2 цвет со стороны родителя
         * @param stroke своя обводка либо null
         */
        public void line(float x1, float y1, float x2, float y2,
                         int color1, int color2, boolean wiggle, Defs.LineType lineType,
                         float tick, Stroke stroke) {
            if (!buildPath(path, x1, y1, x2, y2, wiggle, lineType, tick,
                    scaleX, scaleY, transX, transY)) return;

            Stroke want = stroke == null ? normalStroke : stroke;
            if (want != currentStroke) {
                g.setStroke(want);
                currentStroke = want;
            }

            if (gradient && color1 != color2) {
                float dx1 = (float) (x1 * scaleX + transX);
                float dy1 = (float) (y1 * scaleY + transY);
                float dx2 = (float) (x2 * scaleX + transX);
                float dy2 = (float) (y2 * scaleY + transY);
                if (dx1 != dx2 || dy1 != dy2) {
                    g.setPaint(new LinearGradientPaint(
                            new Point2D.Float(dx1, dy1), new Point2D.Float(dx2, dy2),
                            new float[]{0f, GRADIENT_END, 1f},
                            new Color[]{rgb(color2), rgb(color1), rgb(color1)},
                            MultipleGradientPaint.CycleMethod.NO_CYCLE));
                    currentColor = 1;
                    g.draw(path);
                    return;
                }
            }
            int solid = color1 == color2 ? color1 : Fx.lerpRgb(color1, color2, 0.5f);
            if (solid != currentColor) {
                g.setColor(rgb(solid));
                currentColor = solid;
            }
            g.draw(path);
        }

        @Override
        public void close() {
            g.setTransform(savedTransform);
            g.setPaint(savedPaint);
            g.setStroke(savedStroke);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    savedAa == null ? RenderingHints.VALUE_ANTIALIAS_OFF : savedAa);
        }
    }

    /** Одиночная линия — для редких случаев вроде тянущейся за курсором связи. */
    public static void draw(GuiPainter p, float x1, float y1, float x2, float y2,
                            int color1, int color2, boolean wiggle, Defs.LineType lineType,
                            float tick, float worldWidth) {
        float deviceWidth = (float) (worldWidth * Math.abs(p.g2().getTransform().getScaleX()));
        try (Batch batch = batch(p, deviceWidth, true, color1 != color2)) {
            batch.line(x1, y1, x2, y2, color1, color2, wiggle, lineType, tick, null);
        }
    }

    private static Color rgb(int color) { return GuiPainter.awt(0xFF000000 | color); }

    /**
     * Точки дуги сразу в экранных координатах.
     *
     * <p>В моде это цикл на {@code dist/2} шагов: шаг по большей оси удваивается
     * и на каждом шаге домножается на {@code r = 1 − 1.04/(1.5·inc)}, так что
     * вершина {@code a} стоит в {@code x1 − dx₀·a·rᵃ}. Формула замкнутая —
     * кривую можно посчитать в любой точке и взять меньше вершин: форма и концы
     * те же (на последнем шаге {@code rᶦⁿᶜ ≈ 0.5}, и линия точно приходит в цель).
     *
     * @return false, если рисовать нечего
     */
    private static boolean buildPath(Path2D.Float path, float x1, float y1, float x2, float y2,
                                     boolean wiggle, Defs.LineType lineType, float tick,
                                     double scaleX, double scaleY, double transX, double transY) {
        float deltaX = Math.round(x1 - x2);
        float deltaY = Math.round(y1 - y2);
        float dist = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        int inc = (int) (dist / 2f);
        if (inc <= 0) return false;

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

        path.reset();
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
            float px = (float) (wx * scaleX + transX);
            float py = (float) (wy * scaleY + transY);
            if (i == 0) path.moveTo(px, py);
            else path.lineTo(px, py);
        }
        return true;
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
