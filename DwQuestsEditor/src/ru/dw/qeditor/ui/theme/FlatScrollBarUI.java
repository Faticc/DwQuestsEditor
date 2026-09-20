package ru.dw.qeditor.ui.theme;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.plaf.basic.BasicScrollBarUI;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;

/** Тонкий скроллбар без кнопок — как в современных редакторах. */
public class FlatScrollBarUI extends BasicScrollBarUI {

    private static final int THICKNESS = 10;
    private static final int THUMB = 6;

    @Override
    protected void configureScrollBarColors() {
        thumbColor = Theme.BORDER_STRONG;
        trackColor = Theme.BG;
    }

    @Override
    protected JButton createDecreaseButton(int orientation) { return zeroButton(); }

    @Override
    protected JButton createIncreaseButton(int orientation) { return zeroButton(); }

    private static JButton zeroButton() {
        JButton b = new JButton();
        b.setPreferredSize(new Dimension(0, 0));
        b.setMinimumSize(new Dimension(0, 0));
        b.setMaximumSize(new Dimension(0, 0));
        b.setFocusable(false);
        return b;
    }

    @Override
    public Dimension getPreferredSize(JComponent c) {
        return new Dimension(THICKNESS, THICKNESS);
    }

    @Override
    protected void paintTrack(Graphics g, JComponent c, Rectangle bounds) {
        // прозрачная дорожка — фон панели виден насквозь
    }

    @Override
    protected void paintThumb(Graphics g0, JComponent c, Rectangle b) {
        if (b.isEmpty() || !scrollbar.isEnabled()) return;
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color color = isDragging ? Theme.ACCENT : (isThumbRollover() ? Theme.ACCENT_SOFT : Theme.BORDER_STRONG);
        g.setColor(color);
        boolean vertical = scrollbar.getOrientation() == javax.swing.JScrollBar.VERTICAL;
        if (vertical) {
            int x = b.x + (b.width - THUMB) / 2;
            g.fillRoundRect(x, b.y + 2, THUMB, Math.max(THUMB, b.height - 4), THUMB, THUMB);
        } else {
            int y = b.y + (b.height - THUMB) / 2;
            g.fillRoundRect(b.x + 2, y, Math.max(THUMB, b.width - 4), THUMB, THUMB, THUMB);
        }
        g.dispose();
    }
}
