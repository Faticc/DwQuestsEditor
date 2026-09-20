package ru.dw.qeditor.ui.theme;

import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

/**
 * Переключатель разделов «таблетками» вместо JTabbedPane: плоские вкладки с
 * подсветкой активной, без рамок и объёма.
 */
public class SegmentedTabs extends JPanel {

    private final List<String> titles = new ArrayList<>();
    private final List<Rectangle> bounds = new ArrayList<>();
    private final JPanel pages = new JPanel(new CardLayout());
    private final Bar bar = new Bar();

    private int selected;
    private int hovered = -1;
    private IntConsumer onChange;
    private boolean compact;

    public SegmentedTabs() {
        setOpaque(false);
        setLayout(new java.awt.BorderLayout());
        pages.setOpaque(false);
        add(bar, java.awt.BorderLayout.NORTH);
        add(pages, java.awt.BorderLayout.CENTER);
    }

    /** Более плотный вариант для боковой панели. */
    public SegmentedTabs compact() {
        compact = true;
        bar.setBorder(Theme.padding(6, 0, 6, 0));
        return this;
    }

    public void addTab(String title, JComponent page) {
        titles.add(title);
        pages.add(page, title);
        bar.revalidate();
        bar.repaint();
    }

    public void setOnChange(IntConsumer onChange) { this.onChange = onChange; }

    public int getSelectedIndex() { return selected; }

    public void setSelectedIndex(int index) {
        if (index < 0 || index >= titles.size() || index == selected) return;
        selected = index;
        ((CardLayout) pages.getLayout()).show(pages, titles.get(index));
        bar.repaint();
        if (onChange != null) onChange.accept(index);
    }

    public JComponent getSelectedPage() {
        for (java.awt.Component c : pages.getComponents()) if (c.isVisible()) return (JComponent) c;
        return null;
    }

    private int padH() { return compact ? 12 : 16; }

    private int height() { return compact ? 28 : 34; }

    private final class Bar extends JPanel {

        Bar() {
            setOpaque(false);
            setBorder(Theme.padding(8, 0, 8, 0));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            MouseAdapter ma = new MouseAdapter() {
                @Override
                public void mouseMoved(MouseEvent e) {
                    int h = indexAt(e.getX(), e.getY());
                    if (h != hovered) { hovered = h; repaint(); }
                }

                @Override
                public void mouseExited(MouseEvent e) { hovered = -1; repaint(); }

                @Override
                public void mousePressed(MouseEvent e) {
                    int i = indexAt(e.getX(), e.getY());
                    if (i >= 0) setSelectedIndex(i);
                }
            };
            addMouseListener(ma);
            addMouseMotionListener(ma);
        }

        private int indexAt(int x, int y) {
            for (int i = 0; i < bounds.size(); i++) if (bounds.get(i).contains(x, y)) return i;
            return -1;
        }

        @Override
        public Dimension getPreferredSize() {
            return new Dimension(10, height() + 16);
        }

        @Override
        protected void paintComponent(Graphics g0) {
            Graphics2D g = (Graphics2D) g0.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setFont(Theme.font(compact ? Theme.FONT_SIZE_SMALL + 1 : Theme.FONT_SIZE));
            FontMetrics fm = g.getFontMetrics();

            bounds.clear();
            int x = 0;
            int h = height();
            int y = (getHeight() - h) / 2;

            for (int i = 0; i < titles.size(); i++) {
                String t = titles.get(i);
                int w = fm.stringWidth(t) + padH() * 2;
                Rectangle r = new Rectangle(x, y, w, h);
                bounds.add(r);

                boolean active = i == selected;
                if (active) {
                    g.setColor(Theme.ACCENT_SOFT);
                    g.fillRoundRect(r.x, r.y, r.width, r.height, Theme.RADIUS, Theme.RADIUS);
                } else if (i == hovered) {
                    g.setColor(Theme.HOVER);
                    g.fillRoundRect(r.x, r.y, r.width, r.height, Theme.RADIUS, Theme.RADIUS);
                }
                Color fg = active ? Color.WHITE : (i == hovered ? Theme.TEXT : Theme.TEXT_MUTED);
                g.setColor(fg);
                g.drawString(t, r.x + padH(), r.y + (h - fm.getHeight()) / 2 + fm.getAscent());
                x += w + 4;
            }
            g.dispose();
        }
    }
}
