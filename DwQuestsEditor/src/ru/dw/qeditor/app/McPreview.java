package ru.dw.qeditor.app;

import ru.dw.qeditor.gui.Theme;
import ru.dw.qeditor.gui.Ui;
import ru.dw.qeditor.model.Defs;
import ru.dw.qeditor.model.Quest;
import ru.dw.qeditor.render.GuiPainter;
import ru.dw.qeditor.render.McLabel;

/**
 * Живой предпросмотр описания шрифтом Minecraft — ровно тем же {@link McLabel},
 * что рисует игровая панель квеста, поэтому переносы строк и
 * {@code <BR>}/{@code <IMG>} видно сразу.
 *
 * <p>Порт {@code ui/McTextPreview}: прямоугольник в кадре вместо компонента,
 * прокрутка — обычная прокручиваемая область тулкита.
 */
public final class McPreview {

    /** Ширина панели квеста в игре: 400 − 20 отступов. */
    private static final int LABEL_WIDTH = 380;

    private final Object id = new Object();
    private String text = "";
    private String labelName = "Описание";
    private int labelColor = -1;
    private int scale = 1;

    public int getScale() { return scale; }

    /** Переключить 1× / 2× — крупнее видно шрифт, но нужна ширина. */
    public void toggleScale() { scale = scale == 1 ? 2 : 1; }

    public void setText(String text) { this.text = text == null ? "" : text; }

    public void setLabelName(String name, int color) {
        this.labelName = name;
        this.labelColor = color;
    }

    /** Показать описание квеста с цветом заголовка по редкости. */
    public void showQuest(Quest q) {
        if (q == null) {
            setText("");
            return;
        }
        setLabelName("Описание", Defs.Star.of(q.rarity).mainColor);
        setText(q.description);
    }

    private McLabel buildLabel() {
        McLabel label = new McLabel(0, 0, LABEL_WIDTH);
        label.setText(text);
        label.setLabelName(labelName);
        label.setLabelNameColor(labelColor);
        return label;
    }

    public void draw(Ui ui, float x, float y, float w, float h) {
        if (w < 8 || h < 8) return;
        McLabel label = buildLabel();

        // Пиксель шрифта мода должен занимать целое число пикселей экрана.
        // При дробном масштабе GL_NEAREST даёт соседним пикселям глифа разную
        // ширину — буквы разваливаются («шакалятся»). Поэтому масштаб целый, а
        // если метка шире панели, она прокручивается, а не сжимается.
        float deviceW = (w - 2) * ui.scale();
        int device = Math.max(1, Math.min(scale * Math.max(1, Math.round(ui.scale())),
                (int) Math.floor(deviceW / LABEL_WIDTH)));
        // сколько логических точек на один пиксель шрифта
        float unit = device / ui.scale();

        float contentW = LABEL_WIDTH * unit;
        float contentH = Math.max(h - 2, (label.getHeight() + 4) * unit);

        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, 0xFF1A1D26, Theme.BORDER, 1);
        ui.beginScroll(id, x + 1, y + 1, w - 2, h - 2, contentW, contentH);
        GuiPainter p = ui.painter();
        p.push();
        p.snap();
        // внутри этой матрицы единица — пиксель шрифта Minecraft
        p.scale(unit);
        label.setY(2);
        label.draw(p);
        p.pop();
        ui.endScroll(id, x + 1, y + 1, w - 2, h - 2, contentW, contentH);
    }
}
