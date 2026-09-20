package ru.dw.qeditor.ui.theme;

import javax.swing.border.AbstractBorder;
import java.awt.Color;
import java.awt.Component;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.RenderingHints;

/** Скруглённая рамка в один пиксель — вместо «объёмных» бордюров Swing. */
public class RoundedBorder extends AbstractBorder {

    private final int radius;
    private final Color color;
    private final int padV;
    private final int padH;

    public RoundedBorder(int radius, Color color, int padV, int padH) {
        this.radius = radius;
        this.color = color;
        this.padV = padV;
        this.padH = padH;
    }

    @Override
    public void paintBorder(Component c, Graphics g0, int x, int y, int w, int h) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(color);
        g.drawRoundRect(x, y, w - 1, h - 1, radius, radius);
        g.dispose();
    }

    @Override
    public Insets getBorderInsets(Component c) {
        return new Insets(padV, padH, padV, padH);
    }

    @Override
    public Insets getBorderInsets(Component c, Insets insets) {
        insets.set(padV, padH, padV, padH);
        return insets;
    }

    @Override
    public boolean isBorderOpaque() { return false; }
}
