package ru.dw.qeditor.app;

import ru.dw.qeditor.gl.Batch;
import ru.dw.qeditor.gl.Offscreen;
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
 *
 * <p>Панель узкая, а игровая метка широкая, поэтому метка вписывается в панель
 * по ширине — с любым, в том числе дробным, масштабом. Резкость при этом даёт
 * не целый масштаб, а супер-сэмплинг: метка рисуется в свой кадровый буфер
 * крупно и пиксель в пиксель, а на экран кладётся уменьшенной картинкой
 * с уровнями уменьшения. Так текст читается и не «шакалится» ни при каком
 * размере панели.
 */
public final class McPreview {

    /** Ширина панели квеста в игре: 400 − 20 отступов. */
    private static final int LABEL_WIDTH = 380;

    /** Фон панели предпросмотра — им же заливается кадровый буфер. */
    private static final int BACK = 0xFF1A1D26;

    /** Дальше растить буфер незачем: панель столько пикселей не покажет. */
    private static final int MAX_BUFFER = 2048;

    private final Object id = new Object();
    private String text = "";
    private String labelName = "Описание";
    private int labelColor = -1;
    private int scale = 1;

    private Offscreen buffer;

    public int getScale() { return scale; }

    /** Переключить 1× / 2× — крупнее видно шрифт, но строка уже не влезает. */
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

        float ix = x + 1;
        float iy = y + 1;
        float iw = w - 2;
        float ih = h - 2;

        // логических точек на один пиксель шрифта: на 1× строка в 380 пикселей
        // занимает панель ровно по ширине, на 2× — вдвое больше и листается вбок
        float unit = iw / LABEL_WIDTH * scale;
        // ширина содержимого считается из панели, а не из метки: LABEL_WIDTH * unit
        // округлилось бы на волос больше панели, и на 1× вылезала бы полоса прокрутки
        float contentW = iw * scale;
        float contentH = Math.max(ih, (label.getHeight() + 4) * unit);

        ui.roundRectBorder(x, y, w, h, Theme.RADIUS_SMALL, BACK, Theme.BORDER, 1);
        float[] s = ui.beginScroll(id, ix, iy, iw, ih, contentW, contentH);
        GuiPainter p = ui.painter();

        int deviceW = Math.max(1, Math.round(iw * ui.scale()));
        int deviceH = Math.max(1, Math.round(ih * ui.scale()));
        Offscreen buf = render(p, label, deviceW, deviceH, unit * ui.scale(), s[0] / unit, s[1] / unit);
        // картинка занимает всю панель, поэтому сдвиг прокрутки ей возвращается:
        // внутри области координаты отсчитываются от прокрученного содержимого
        if (buf != null) p.textureSnapped(buf.texture(), s[0], s[1], deviceW, deviceH);

        ui.endScroll(id, ix, iy, iw, ih, contentW, contentH);
    }

    /**
     * Нарисовать видимый кусок метки в свой кадровый буфер.
     *
     * @param device экранных пикселей на пиксель шрифта — каким текст выйдет на панели
     * @param offX   прокрутка в пикселях шрифта
     */
    private Offscreen render(GuiPainter p, McLabel label, int deviceW, int deviceH,
                             float device, float offX, float offY) {
        // В буфере пиксель шрифта занимает чётное число пикселей. Кириллица
        // приходит из unicode_page вдвое крупнее, чем рисуется
        // (McFont.renderUnicodeChar), и на нечётном масштабе GL_NEAREST режет
        // буквы: на чётном она ложится тексель в тексель.
        int inner = Math.max(2, 2 * (int) Math.ceil(device / 2));
        float over = inner / Math.max(0.01f, device);      // во сколько раз буфер крупнее панели
        over = Math.min(over, Math.min(MAX_BUFFER / (float) deviceW, MAX_BUFFER / (float) deviceH));
        if (over < 1) over = 1;
        int bw = Math.max(1, Math.round(deviceW * over));
        int bh = Math.max(1, Math.round(deviceH * over));

        Offscreen buf = ensure(bw, bh);
        Batch batch = p.batch();
        batch.pushTarget(buf.fbo(), bw, bh);
        Offscreen.clear(BACK);

        // внутри этой матрицы единица — пиксель шрифта Minecraft
        GuiPainter g = GuiPainter.of(batch, bw / (float) deviceW * device, bw, bh);
        g.translate(-offX, -offY);
        g.snap();
        label.setY(2);
        label.draw(g);

        batch.popTarget();
        buf.buildMipmaps();
        return buf;
    }

    /** Буфер нужного размера: пока панель не меняется, он один и тот же. */
    private Offscreen ensure(int w, int h) {
        if (buffer != null && buffer.width() == w && buffer.height() == h) return buffer;
        if (buffer != null) buffer.dispose();
        buffer = Offscreen.create(w, h, true);
        return buffer;
    }
}
