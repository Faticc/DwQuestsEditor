package ru.dw.qeditor.ui.theme;

import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JPanel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;

/**
 * Карточка — скруглённый блок с необязательным заголовком.
 * Заменяет TitledBorder, который и выдаёт в Swing «девяностые».
 */
public class Card extends JPanel {

    private final JPanel header;
    private final JPanel body;

    public Card(String title) {
        this(title, null);
    }

    /**
     * @param title   заголовок; null — карточка без шапки
     * @param actions компоненты справа в шапке (кнопки), может быть null
     */
    public Card(String title, Component[] actions) {
        setOpaque(false);
        setLayout(new BorderLayout());
        setBorder(Theme.padding(0));

        body = new JPanel(new BorderLayout());
        body.setOpaque(false);
        body.setBorder(Theme.padding(title == null ? 12 : 4, 12, 12, 12));

        if (title == null) {
            header = null;
        } else {
            header = new JPanel();
            header.setOpaque(false);
            header.setLayout(new BoxLayout(header, BoxLayout.X_AXIS));
            header.setBorder(Theme.padding(10, 12, 2, 10));
            header.add(Theme.sectionTitle(title));
            header.add(Box.createHorizontalGlue());
            if (actions != null) for (Component c : actions) header.add(c);
            add(header, BorderLayout.NORTH);
        }
        add(body, BorderLayout.CENTER);
    }

    /** Содержимое карточки. */
    public Card content(JComponent c) {
        body.removeAll();
        body.add(c, BorderLayout.CENTER);
        return this;
    }

    public JPanel body() { return body; }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(Graphics g0) {
        Graphics2D g = (Graphics2D) g0.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Theme.SURFACE);
        g.fillRoundRect(0, 0, getWidth(), getHeight(), Theme.RADIUS, Theme.RADIUS);
        g.setColor(Theme.BORDER);
        g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, Theme.RADIUS, Theme.RADIUS);
        g.dispose();
    }
}
