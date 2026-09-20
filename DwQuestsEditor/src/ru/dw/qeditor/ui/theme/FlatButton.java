package ru.dw.qeditor.ui.theme;

import javax.swing.JButton;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/** Плоская кнопка: скруглённая, без бевелей, с мягкой подсветкой под курсором. */
public class FlatButton extends JButton {

    public enum Kind {
        /** Основное действие — залитая акцентом. */
        PRIMARY,
        /** Обычная кнопка — фон поверхности, тонкая рамка. */
        NORMAL,
        /** Только текст, без фона. */
        GHOST,
        /** Опасное действие. */
        DANGER
    }

    private final Kind kind;
    private boolean hover;
    private boolean pressed;
    private Color overrideFg;

    public FlatButton(String text, Kind kind, Runnable action) {
        super(text);
        this.kind = kind;
        setContentAreaFilled(false);
        setBorderPainted(false);
        setFocusPainted(false);
        setOpaque(false);
        setForeground(foreground());
        setFont(Theme.font(Theme.FONT_SIZE));
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setMargin(new java.awt.Insets(0, 0, 0, 0));
        if (action != null) addActionListener(e -> action.run());

        addMouseListener(new MouseAdapter() {
            @Override public void mouseEntered(MouseEvent e) { hover = true; repaint(); }

            @Override public void mouseExited(MouseEvent e) { hover = false; pressed = false; repaint(); }

            @Override public void mousePressed(MouseEvent e) { pressed = true; repaint(); }

            @Override public void mouseReleased(MouseEvent e) { pressed = false; repaint(); }
        });
    }

    public static FlatButton primary(String text, Runnable action) {
        return new FlatButton(text, Kind.PRIMARY, action);
    }

    public static FlatButton normal(String text, Runnable action) {
        return new FlatButton(text, Kind.NORMAL, action);
    }

    public static FlatButton ghost(String text, Runnable action) {
        return new FlatButton(text, Kind.GHOST, action);
    }

    public static FlatButton danger(String text, Runnable action) {
        return new FlatButton(text, Kind.DANGER, action);
    }

    /** Компактный вариант для плотных панелей инструментов. */
    public FlatButton small() {
        setFont(Theme.font(Theme.FONT_SIZE_SMALL + 1));
        setPreferredSize(null);
        putClientProperty("small", Boolean.TRUE);
        return this;
    }

    /** Явный цвет текста — например, чтобы показать включённое состояние. */
    public FlatButton fg(Color color) {
        overrideFg = color;
        repaint();
        return this;
    }

    public FlatButton tip(String tooltip) {
        setToolTipText(tooltip);
        return this;
    }

    private boolean isSmall() { return Boolean.TRUE.equals(getClientProperty("small")); }

    private Color foreground() {
        if (overrideFg != null) return overrideFg;
        switch (kind) {
            case PRIMARY: return Color.WHITE;
            case DANGER: return Theme.DANGER;
            case GHOST: return Theme.TEXT_MUTED;
            default: return Theme.TEXT;
        }
    }

    private Color background() {
        switch (kind) {
            case PRIMARY:
                return pressed ? Theme.ACCENT_PRESSED : (hover ? Theme.ACCENT_HOVER : Theme.ACCENT);
            case GHOST:
                return hover ? Theme.HOVER : null;
            case DANGER:
                return hover ? new Color(0x3A2226) : Theme.SURFACE_2;
            default:
                return pressed ? Theme.SURFACE : (hover ? Theme.HOVER : Theme.SURFACE_2);
        }
    }

    @Override
    public Dimension getPreferredSize() {
        Dimension d = super.getPreferredSize();
        int padH = isSmall() ? 10 : 16;
        int h = isSmall() ? 26 : 32;
        return new Dimension(d.width + padH * 2, h);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

        int w = getWidth(), h = getHeight();
        int r = isSmall() ? Theme.RADIUS_SMALL : Theme.RADIUS;

        Color bg = background();
        if (bg != null) {
            g.setColor(bg);
            g.fillRoundRect(0, 0, w, h, r, r);
        }
        if (kind == Kind.NORMAL || kind == Kind.DANGER) {
            g.setColor(hover ? Theme.BORDER_STRONG : Theme.BORDER);
            g.drawRoundRect(0, 0, w - 1, h - 1, r, r);
        }

        boolean enabled = isEnabled();
        g.setColor(enabled ? (hover && kind == Kind.GHOST ? Theme.TEXT : foreground()) : Theme.TEXT_DISABLED);
        g.setFont(getFont());
        java.awt.FontMetrics fm = g.getFontMetrics();
        String text = getText();
        int tx = (w - fm.stringWidth(text)) / 2;
        int ty = (h - fm.getHeight()) / 2 + fm.getAscent();
        if (getIcon() != null) {
            int iw = getIcon().getIconWidth();
            int total = iw + 6 + fm.stringWidth(text);
            int sx = (w - total) / 2;
            getIcon().paintIcon(this, g, sx, (h - getIcon().getIconHeight()) / 2);
            tx = sx + iw + 6;
        }
        g.drawString(text, tx, ty);
        g.dispose();
    }
}
