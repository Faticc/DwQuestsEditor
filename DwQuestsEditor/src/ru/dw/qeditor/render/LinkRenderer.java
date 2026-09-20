package ru.dw.qeditor.render;

import ru.dw.qeditor.model.Defs;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Line2D;

/**
 * Линия зависимости между квестами — порт UIQuestBrowser.drawLine:
 * ломаная из dist/2 сегментов с градиентом цвета, дугой (LineType) и «волной»
 * у активных связей.
 */
public final class LinkRenderer {

    private LinkRenderer() { }

    /** Доля длины, на которой градиент успевает дойти до второго цвета. */
    private static final float GRADIENT_END = 0.75f;

    public static void draw(GuiPainter p, float x1, float y1, float x2, float y2,
                            int color1, int color2, boolean wiggle, Defs.LineType lineType,
                            float tick, float lineWidth) {
        float deltaX = Math.round(x1 - x2);
        float deltaY = Math.round(y1 - y2);
        float dist = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        int inc = (int) (dist / 2f);
        if (inc <= 0) return;

        float dx = deltaX / inc;
        float dy = deltaY / inc;
        boolean isXGreaterThanY = Math.abs(deltaX) > Math.abs(deltaY);
        if (lineType == Defs.LineType.INVERTED) isXGreaterThanY = !isXGreaterThanY;
        if (lineType != Defs.LineType.STRAIGHT) {
            if (isXGreaterThanY) dx *= 2f;
            else dy *= 2f;
        }
        if (lineWidth < 0.5f) lineWidth = 0.5f;

        Graphics2D g = p.g2();
        Object oldAa = g.getRenderingHint(RenderingHints.KEY_ANTIALIASING);
        java.awt.Stroke oldStroke = g.getStroke();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setStroke(new BasicStroke(lineWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

        float px = 0, py = 0;
        int pc = 0;
        for (int a = 0; a <= inc; a++) {
            float phase = (float) a / inc;
            float adjustedPhase = Math.min(Math.max(phase / GRADIENT_END, 0f), 1f);
            int lerpedColor = Fx.lerpRgb(color1, color2, 1f - adjustedPhase);

            float mx = 0f, my = 0f;
            if (wiggle) {
                mx = (float) Math.sin((tick + a) / 8f + 2f) * (1f - phase);
                my = (float) Math.sin((tick + a) / 8f) * (1f - phase);
            }
            float cx = x1 - dx * a + mx;
            float cy = y1 - dy * a + my;

            if (a > 0) {
                g.setColor(GuiPainter.awt(0xFF000000 | pc));
                g.draw(new Line2D.Float(px, py, cx, cy));
            }
            px = cx;
            py = cy;
            pc = lerpedColor;

            if (lineType != Defs.LineType.STRAIGHT) {
                if (isXGreaterThanY) dx *= 1f - 1.04f / (inc * 3f / 2f);
                else dy *= 1f - 1.04f / (inc * 3f / 2f);
            }
        }

        g.setStroke(oldStroke);
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                oldAa == null ? RenderingHints.VALUE_ANTIALIAS_OFF : oldAa);
    }

    /**
     * Расстояние от точки до нарисованной линии — для выбора связи мышью.
     * Считается по той же ломаной, но без волны.
     */
    public static float distanceTo(float x1, float y1, float x2, float y2,
                                   Defs.LineType lineType, float px, float py) {
        float deltaX = Math.round(x1 - x2);
        float deltaY = Math.round(y1 - y2);
        float dist = (float) Math.sqrt(deltaX * deltaX + deltaY * deltaY);
        int inc = (int) (dist / 2f);
        if (inc <= 0) return Float.MAX_VALUE;

        float dx = deltaX / inc;
        float dy = deltaY / inc;
        boolean isXGreaterThanY = Math.abs(deltaX) > Math.abs(deltaY);
        if (lineType == Defs.LineType.INVERTED) isXGreaterThanY = !isXGreaterThanY;
        if (lineType != Defs.LineType.STRAIGHT) {
            if (isXGreaterThanY) dx *= 2f;
            else dy *= 2f;
        }

        float best = Float.MAX_VALUE;
        float prevX = 0, prevY = 0;
        for (int a = 0; a <= inc; a++) {
            float cx = x1 - dx * a;
            float cy = y1 - dy * a;
            if (a > 0) {
                float d = (float) Line2D.ptSegDist(prevX, prevY, cx, cy, px, py);
                if (d < best) best = d;
            }
            prevX = cx;
            prevY = cy;
            if (lineType != Defs.LineType.STRAIGHT) {
                if (isXGreaterThanY) dx *= 1f - 1.04f / (inc * 3f / 2f);
                else dy *= 1f - 1.04f / (inc * 3f / 2f);
            }
        }
        return best;
    }
}
