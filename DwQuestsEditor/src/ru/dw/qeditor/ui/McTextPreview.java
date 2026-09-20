package ru.dw.qeditor.ui;

import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.render.GuiPainter;
import ru.dw.qeditor.render.McLabel;

import javax.swing.JComponent;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.image.BufferedImage;

/**
 * Живой предпросмотр описания шрифтом Minecraft — ровно тем же
 * {@link McLabel}, что рисует игровая панель квеста, поэтому переносы строк и
 * {@code <BR>}/{@code <IMG>} видно сразу.
 */
public class McTextPreview extends JComponent {

    private String text = "";
    private String labelName = "Описание";
    private int labelColor = -1;
    private int scale = 1;
    /** Ширина панели квеста в игре: 400 − 20 отступов. */
    private int labelWidth = 380;

    private BufferedImage buffer;

    public McTextPreview() {
        setPreferredSize(new Dimension(380, 120));
    }

    public int getScale() { return scale; }

    /** Переключить 1× / 2× — крупнее видно шрифт, но нужна ширина. */
    public void toggleScale() { setScale(scale == 1 ? 2 : 1); }

    public void setText(String text) {
        this.text = text == null ? "" : text;
        revalidate();
        repaint();
    }

    public void setLabelName(String name, int color) {
        this.labelName = name;
        this.labelColor = color;
        repaint();
    }

    public void setScale(int scale) {
        this.scale = Math.max(1, Math.min(3, scale));
        revalidate();
        repaint();
    }

    public void setLabelWidth(int w) {
        this.labelWidth = Math.max(80, w);
        revalidate();
        repaint();
    }

    /** Показать описание квеста с цветом заголовка по редкости. */
    public void showQuest(Quest q) {
        if (q == null) {
            setText("");
            return;
        }
        setLabelName("Описание", ru.dw.qeditor.model.Defs.Star.of(q.rarity).mainColor);
        setText(q.description);
    }

    private McLabel buildLabel() {
        McLabel label = new McLabel(0, 0, labelWidth);
        label.setText(text);
        label.setLabelName(labelName);
        label.setLabelNameColor(labelColor);
        return label;
    }

    @Override
    public Dimension getPreferredSize() {
        McLabel label = buildLabel();
        return new Dimension(labelWidth * scale, Math.max(40, (label.getHeight() + 4) * scale));
    }

    @Override
    protected void paintComponent(Graphics g) {
        int w = getWidth(), h = getHeight();
        if (w <= 0 || h <= 0) return;
        if (buffer == null || buffer.getWidth() != w || buffer.getHeight() != h)
            buffer = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);

        GuiPainter p = GuiPainter.forImage(buffer, scale);
        try {
            p.rectOpaque(0, 0, w / (float) scale, h / (float) scale, 0xFF1A1D26);
            buildLabel().draw(p);
        } finally {
            p.dispose();
        }
        g.drawImage(buffer, 0, 0, null);
    }
}
